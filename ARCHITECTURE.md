# CloudShelf — Architecture

> Companion to [`SPEC.md`](SPEC.md). This doc describes **how** CloudShelf is wired, not **what** it does.

## 1. Layers

```
┌──────────────────────────────────────────────────────────────────┐
│  UI                                                              │
│  Jetpack Compose · Material 3 · Navigation                       │
│  SignInScreen · BucketsScreen · BrowserScreen · PreviewScreen    │
│  TransfersScreen · SettingsScreen                                │
└────────────────────────────┬─────────────────────────────────────┘
                             │   collectAsStateWithLifecycle()
┌────────────────────────────▼─────────────────────────────────────┐
│  ViewModel  (Hilt-injected, StateFlow-exposing)                  │
│  AuthViewModel · BucketsViewModel · BrowserViewModel             │
│  PreviewViewModel · TransfersViewModel · SettingsViewModel       │
│  AuthGateViewModel  (decides start destination)                  │
└────────────────────────────┬─────────────────────────────────────┘
                             │   suspend funs / Flow<T>
┌────────────────────────────▼─────────────────────────────────────┐
│  Repository                                                       │
│  AuthRepository · S3Repository · TransferRepository              │
└────────────────────────────┬─────────────────────────────────────┘
                             │
┌────────────────────────────▼─────────────────────────────────────┐
│  Data sources                                                    │
│  · AWS SDK for Kotlin (S3, STS)  via S3ClientProvider            │
│  · Amplify Auth (Cognito, optional)                              │
│  · WorkManager (UploadWorker, DownloadWorker)                    │
│  · Room (TransferEntity, BucketRegionCache)                      │
│  · DataStore (UserPreferences — theme, non-secret prefs)         │
│  · EncryptedSharedPreferences (IamKeyStore — IAM creds)          │
│  · Android Notification API (TransferNotifications)              │
└──────────────────────────────────────────────────────────────────┘
```

### Layer rules
- **UI never imports** `aws.sdk.kotlin.*`, `com.amplifyframework.*`, Room types, or WorkManager types directly. It binds to a ViewModel's `StateFlow`.
- **ViewModels never** hold network resources, file handles, or WorkManager `OneTimeWorkRequest`s — they delegate via repositories.
- **Repositories** are the lowest layer that knows about AWS. They translate SDK exceptions to domain errors via `S3ErrorMapper` and run every SDK call on `Dispatchers.IO`.
- **Workers** are an exception to "repositories only" — they need direct access to the SDK to stream bytes — but they obtain the `S3Client` only from `S3ClientProvider`.

## 2. Module / package map

Single Gradle module (`app`). Code is organized by feature, with `core/` and `data/` as shared infrastructure.

```
com.mobildroid.cloudshelf.app
├── CloudShelfApplication           # Application — provides WorkerFactory for Hilt + WorkManager
├── MainActivity                    # Compose host
├── auth/
│   ├── AmplifyInitializer          # Best-effort Cognito wiring; no-op if config absent
│   ├── AuthRepository              # Bridges BYOK + Cognito to a single AuthState
│   ├── AuthViewModel               # Sign-in screen state
│   ├── AwsCredentialsProviderFactory  # ⭐ the credential seam
│   ├── CloudShelfCredentials       # Sealed type — IAM or Cognito
│   ├── IamKeyStore                 # EncryptedSharedPreferences wrapper
│   ├── SignInScreen
│   └── StsValidator                # sts:GetCallerIdentity probe before saving keys
├── buckets/
│   ├── BucketsScreen
│   └── BucketsViewModel
├── browser/
│   ├── BrowserScreen               # Breadcrumbs, sort, search-in-prefix, infinite scroll
│   └── BrowserViewModel
├── transfer/
│   ├── Transfer                    # Domain model
│   ├── TransferRepository
│   ├── TransferScheduler           # Builds + enqueues OneTimeWorkRequests
│   ├── TransfersScreen
│   ├── TransfersViewModel
│   ├── UploadWorker                # Multipart + resume
│   ├── DownloadWorker              # Range-resume
│   └── DownloadDestination
├── preview/
│   ├── PreviewMimeDetector
│   ├── PreviewScreen
│   └── PreviewViewModel
├── settings/
│   ├── SettingsScreen
│   └── SettingsViewModel
├── navigation/
│   ├── AuthGateViewModel
│   ├── CloudShelfNavHost
│   └── Routes
├── core/
│   ├── di/                         # AppModule, DatabaseModule
│   ├── notifications/              # TransferNotifications (foreground notif)
│   ├── preferences/                # UserPreferences (DataStore)
│   ├── s3/                         # S3ClientProvider (per-region cache)
│   └── ui/                         # PlaceholderScaffold, EmptyState, SkeletonRow, theme/
└── data/
    ├── db/                         # Room database + DAOs
    │   ├── CloudShelfDatabase
    │   └── transfer/
    │       ├── TransferDao
    │       └── TransferEntity
    └── s3/                         # S3 helpers
        ├── BucketRegionCache       # Region cache in Room
        ├── BucketSummary
        ├── ListPage
        ├── S3ErrorMapper
        ├── S3Item
        └── S3Repository            # ⭐ single point of S3 contact
```

## 3. Threading model

| Where | Dispatcher | Why |
|---|---|---|
| All AWS SDK calls | `Dispatchers.IO` (mandatory) | SDK's HTTP engine cleans up sockets on the calling thread; main-thread invocation throws `NetworkOnMainThreadException` |
| Room DAO calls | `Dispatchers.IO` (Room enforces) | Standard |
| `EncryptedSharedPreferences` reads/writes | `Dispatchers.IO` | Disk + crypto |
| WorkManager workers | Worker dispatcher (background by default) | Already off main |
| ViewModel `viewModelScope` launches | `Dispatchers.Main.immediate` (default) | Switches to IO for SDK calls via `withContext` |
| Compose recomposition | `Dispatchers.Main` | UI |

Anywhere you see an SDK call, you should see `withContext(Dispatchers.IO)` around it. There are no exceptions. `S3Repository` and `StsValidator` model the pattern; copy them.

## 4. Region-aware S3 client cache

```
S3ClientProvider
├── globalClient()                  # us-east-1; used for ListBuckets + GetBucketLocation
├── clientForRegion(region)         # lazily creates and caches a per-region S3Client
└── close()                         # called on sign-out + Application.onTerminate
```

`BucketRegionCache` (Room) persists the bucket → region mapping across launches. The cache normalizes two GetBucketLocation quirks:
- `us-east-1` is reported as `null` / empty string.
- `eu-west-1` historically returned `"EU"`.

`S3Repository.getBucketRegion()` is the only place those quirks are handled.

## 5. Data flow — sign-in (BYOK)

```
SignInScreen
  └─▶ AuthViewModel.signInWithIam(accessKey, secret, region, sessionToken?)
        └─▶ AuthRepository.signInWithIam(...)
              ├─▶ StsValidator.validate(creds)        ── sts:GetCallerIdentity on IO
              ├─▶ IamKeyStore.save(creds)             ── EncryptedSharedPreferences
              └─▶ AuthState.SignedIn(IamKey) emitted on StateFlow
                    │
SignInScreen ◀──────┘  collectAsStateWithLifecycle()
                       ─ navController.navigate(BUCKETS, popUpTo(SIGN_IN))
```

## 6. Data flow — listing a bucket

```
BrowserScreen (bucket="b", prefix="photos/2024/")
  └─▶ BrowserViewModel.loadPage(continuationToken)
        └─▶ S3Repository.listPage(bucket, prefix, ct)         ── on IO
              ├─▶ S3ClientProvider.clientForRegion(getBucketRegion(b))
              │     └─▶ BucketRegionCache.get(b) ?: GetBucketLocation
              └─▶ ListObjectsV2(bucket, prefix, delimiter="/", ct)
                    └─▶ ListPage(items, nextContinuationToken)
        BrowserViewModel emits UiState.Loaded(page)
  collectAsStateWithLifecycle() ─▶ BrowserScreen renders rows + breadcrumbs
```

Sort and search-in-prefix run client-side over the loaded page; pagination is "load more" on scroll.

## 7. Data flow — resumable upload

```
User picks file via SAF (BrowserScreen FAB)
  └─▶ BrowserViewModel.enqueueUpload(uri, bucket, key)
        └─▶ TransferRepository.enqueueUpload(...)
              ├─▶ TransferDao.insert(TransferEntity(state=Queued))
              └─▶ TransferScheduler.scheduleUpload(transferId)
                    └─▶ WorkManager.enqueueUniqueWork(UploadWorker)
                            │
                            ▼
UploadWorker.doWork()                          ── runs on WorkManager dispatcher
  ├─▶ TransferNotifications.show(start)        ── foreground service
  ├─▶ size = SAF resolver.openFileDescriptor(uri).statSize
  ├─▶ if size < 8 MB:
  │     └─▶ S3Repository.put(...)                ── single PUT, SSE-S3
  │
  └─▶ else (multipart):
        ├─▶ existingUploadId = TransferDao.get(id).uploadId
        ├─▶ if existingUploadId != null:
        │     └─▶ ListParts → resume; build completedParts list
        │   else:
        │     └─▶ CreateMultipartUpload         ── SSE header set here
        │
        ├─▶ for each remaining part (4-wide concurrency):
        │     ├─▶ UploadPart(8 MB)
        │     └─▶ emit progress → notif + Flow → TransfersScreen
        │
        └─▶ CompleteMultipartUpload
              └─▶ TransferDao.update(state=Completed)
                    └─▶ TransferNotifications.show(done)

On process death mid-upload:
  - TransferEntity row still has uploadId.
  - WorkManager retries the unique work; resume path above kicks in.

On permanent failure (exhausted retries):
  - AbortMultipartUpload  ──  so S3 doesn't keep billing for orphaned parts.
```

## 8. Data flow — resumable download

```
User taps row's download icon
  └─▶ BrowserViewModel.enqueueDownload(bucket, key, suggestedDestination)
        └─▶ TransferRepository.enqueueDownload(...)
              └─▶ DownloadWorker scheduled (WorkManager)

DownloadWorker.doWork()
  ├─▶ destinationFile = cacheDir/downloads/<key.fileName>
  ├─▶ startOffset = if (destinationFile.exists()) destinationFile.length() else 0L
  └─▶ S3Repository.getObject(
        bucket, key,
        range = if (startOffset > 0) "bytes=$startOffset-" else null
      )
        ── streams body to FileOutputStream(destinationFile, append = startOffset > 0)
        ── emits progress per N KB
        ── on completion: TransferDao.update(state=Completed)
```

## 9. Data flow — pre-signed share

```
PreviewScreen Share icon
  └─▶ PreviewViewModel.share()
        └─▶ S3Repository.presignGet(bucket, key, ttl = 1.hour)
              └─▶ S3Client.presignGetObject(...) on IO
        Activity.startActivity(Intent.createChooser(...))   ── share sheet
```

URL TTL is hard-coded to 1 hour in v1. URLs are never logged.

## 10. Persistence layout

| Store | Class | What's in it | Backed-up to cloud? |
|---|---|---|---|
| `EncryptedSharedPreferences` | `IamKeyStore` | IAM access key, secret, session token, region | **No** (excluded in `backup_rules.xml`) |
| Room DB `cloudshelf.db` | `CloudShelfDatabase` | `TransferEntity` (queue + history), bucket→region map | **No** (excluded) |
| DataStore `user_prefs.preferences_pb` | `UserPreferences` | Theme override (System/Light/Dark) | Yes |
| App-private files dir | — | Downloaded files (`cacheDir/downloads/`), preview cache | No (cacheDir is OS-managed) |

Backup exclusion is configured in `res/xml/backup_rules.xml` and `res/xml/data_extraction_rules.xml`.

## 11. Navigation graph

```
Routes.SIGN_IN  ─────────────┐
                              │  (AuthGateViewModel decides start destination)
Routes.BUCKETS  ◀─────────────┤
       │                      │
       ├─▶ Routes.BROWSER_ROUTE  (bucket, prefix)
       │           │
       │           └─▶ Routes.PREVIEW_ROUTE  (bucket, key)
       │
       ├─▶ Routes.TRANSFERS
       └─▶ Routes.SETTINGS
                  │
                  └─▶ on sign-out: popUpTo(0, inclusive) ─▶ SIGN_IN
```

`AuthGateViewModel.state` is consulted on first composition. While `AuthState.Unknown` is in flight (we're checking the persisted session), `CloudShelfNavHost` shows a centered spinner — the only reason this matters is to avoid flashing the SignIn screen for already-signed-in users.

## 12. Dependency injection (Hilt)

```
@HiltAndroidApp class CloudShelfApplication : Configuration.Provider

Modules:
  AppModule
    @Provides @Singleton S3ClientProvider
    @Provides @Singleton AwsCredentialsProviderFactory
    @Provides @Singleton IamKeyStore           ─ EncryptedSharedPreferences setup
    @Provides @Singleton TransferScheduler
    @Provides @Singleton TransferNotifications
    @Provides @Singleton UserPreferences       ─ DataStore<Preferences>

  DatabaseModule
    @Provides @Singleton CloudShelfDatabase
    @Provides TransferDao
    @Provides BucketRegionCache
```

WorkManager is bootstrapped via `Configuration.Provider` on `CloudShelfApplication` so Hilt can inject dependencies into workers (`UploadWorker`, `DownloadWorker`). The default `WorkManagerInitializer` is removed in `AndroidManifest.xml` to prevent the auto-init race.

## 13. Build variants

| Flavor | Output | When to use |
|---|---|---|
| `playStoreDebug` | AAB | Internal testing of the Play build |
| `playStoreRelease` | AAB (signed if `keystore.properties` is present) | Play Console upload |
| `internalDebug` | APK | Local sideload during development |
| `internalRelease` | APK (signed if `keystore.properties` is present) | GitHub releases / Drive sharing |

The two flavors share **everything** except `versionNameSuffix` (`-play` vs `-internal`) and `applicationIdSuffix` (`internal` gets `.internal`).

## 14. Testing strategy (current state)

- **Unit tests**: `app/src/test/` with JUnit + MockK + Turbine + `kotlinx-coroutines-test`. ViewModels are the primary target — fake the repositories, assert on `StateFlow` emissions via Turbine.
- **Repository tests**: SDK is mocked; we assert request shapes (e.g. `ServerSideEncryption.Aes256` is set on every PUT).
- **No** instrumentation tests in v1 (Compose UI tests are wired in `androidTestImplementation` for future use).
- Lint runs in CI (`./gradlew :app:lintInternalDebug`).

Coverage gaps (acknowledged): WorkManager workers are not covered by unit tests; a manual test plan covers them — kill the process mid-upload and verify resume.

## 15. Open architectural items

- **Migrate off `EncryptedSharedPreferences`** — it is deprecated (still functional). Plan: wrap DataStore + Android Keystore once Google publishes a replacement.
- **Surface SSE-KMS in Settings** — plumbing exists; UI does not.
- **Crash reporting** — choose Sentry vs Firebase Crashlytics for Phase 6 wiring.
- **CI** — GitHub Actions workflow lives in `.github/` (per the existing folder structure) but needs review for current Kotlin/Gradle versions.

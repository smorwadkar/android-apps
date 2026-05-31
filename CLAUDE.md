# CLAUDE.md — Project guide for AI collaborators

This file is the entry point when working on **CloudShelf** with Claude (Claude Code, Cowork, or any Claude-powered IDE assistant). Read this first; it links out to deeper docs as needed.

> Humans onboarding to the repo are also a valid audience for this file. The two paths to onboarding (`CLAUDE.md` for the AI, `ONBOARDING.md` for the human dev) are kept in sync intentionally.

## 1. What this project is (one paragraph)

CloudShelf is a native Android app that connects to **the user's own AWS account** and lets them browse S3 buckets, navigate prefixes folder-style, upload and download files with resumable multipart transfers, and preview images / PDFs / video / text in-app. Primary auth model is **IAM-key (BYOK)**; Cognito is supported as an optional secondary path. The product is a *client* — there is no server-side component.

Authoritative spec: [`SPEC.md`](SPEC.md).

## 2. Repo map

```
CloudShelf/                          (parent — design docs and history live here)
├── CloudShelf-Plan.md               # Original brainstorm + phased plan (historical)
├── TODO/
│   ├── cognito-auth/
│   │   ├── decision.md              # ⚠️ Load-bearing: BYOK-over-Cognito ADR
│   │   ├── summary.md               # Cognito flow reference
│   │   └── amplify-cognito-flow.svg
│   ├── feature-backlog/             # Brainstorm + priorities for v2+
│   └── marketing/                   # Play Store copy, one-pager
└── CloudShelfApp/                   # ⬅ The actual Android project (Gradle root)
    ├── SPEC.md                      # Formal product/technical spec
    ├── ARCHITECTURE.md              # Layers, data flow, threading
    ├── ONBOARDING.md                # New-machine setup for humans
    ├── CONTRIBUTING.md              # Branch / PR / code-style rules
    ├── CLAUDE.md                    # ← you are here
    ├── README.md                    # Quick start + current phase status
    ├── build.gradle.kts             # Root build script
    ├── settings.gradle.kts
    ├── gradle/                      # Gradle wrapper + libs.versions.toml
    ├── keystore.properties          # ⚠️ Gitignored. Required for release signing.
    ├── local.properties             # ⚠️ Gitignored. Points at the local Android SDK.
    └── app/
        ├── build.gradle.kts         # App module build script (flavors, signing)
        ├── proguard-rules.pro
        └── src/main/
            ├── AndroidManifest.xml
            ├── java/com/mobildroid/cloudshelf/app/
            │   ├── CloudShelfApplication.kt
            │   ├── MainActivity.kt
            │   ├── auth/            # Sign-in: BYOK + optional Cognito
            │   ├── buckets/         # Bucket list screen + VM
            │   ├── browser/         # Prefix browser + VM
            │   ├── transfer/        # WorkManager workers, queue UI
            │   ├── preview/         # Image/PDF/video/text preview
            │   ├── settings/        # Account, theme, cache, about
            │   ├── navigation/      # NavHost + Routes
            │   ├── core/
            │   │   ├── di/          # Hilt modules
            │   │   ├── notifications/
            │   │   ├── preferences/ # DataStore (non-secret prefs)
            │   │   ├── s3/          # S3ClientProvider (per-region cache)
            │   │   └── ui/          # Shared composables + theme
            │   └── data/
            │       ├── db/          # Room (TransferEntity + DAO)
            │       └── s3/          # S3Repository + helpers
            └── res/                 # Compose-light: only icons, strings, themes
```

## 3. Load-bearing decisions you must respect

These are not preferences. They are decisions that have already been made and that the code is shaped around. Changing them is a strategic move that requires explicit go-ahead from the owner.

### 3a. IAM-key (BYOK) is the only auth path
- Cognito was removed from the codebase. It is a future-scope feature if needed later.
- `SignInScreen` shows only the IAM-key form — no tabs.
- **Never commit `app/src/main/res/raw/amplifyconfiguration.json`.** It is gitignored.

### 3b. Every AWS SDK for Kotlin call runs on `Dispatchers.IO`
This is non-negotiable. The Kotlin SDK's HTTP engine does synchronous socket cleanup on the calling thread; running it on the main thread throws `NetworkOnMainThreadException` (also happens on lifecycle paths where the dispatcher defaults to `Dispatchers.Main`). Every public method on `S3Repository`, `StsValidator`, and `S3ClientProvider` wraps its body in `withContext(Dispatchers.IO) { ... }`. When you add a new SDK call, do the same.

(Auto-memory entry `feedback_aws_sdk_dispatcher.md` captures this rule for AI context across sessions.)

### 3c. The credential abstraction is the seam
`AwsCredentialsProviderFactory` + the `CloudShelfCredentials` sealed type centralize credential resolution. Everything below `S3Repository` is auth-agnostic.

### 3d. One `S3Client` per region, cached in `S3ClientProvider`
Bucket region is resolved via `GetBucketLocation`, normalized for the `us-east-1` (null) and `eu-west-1` (`"EU"`) quirks, and cached in Room (`BucketRegionCache`). Don't construct ad-hoc `S3Client` instances elsewhere.

### 3e. Every PUT sets SSE-S3 and Glacier Instant Retrieval storage class
`x-amz-server-side-encryption: AES256` and `StorageClass.GlacierInstantRetrieval` are set on every `PutObject` and `CreateMultipartUpload`. The plumbing for `aws:kms` + a per-bucket KMS key id exists but is not exposed in the UI yet (v2). When implementing the Settings surface for SSE-KMS, do **not** weaken the SSE-S3 default.

### 3f. No legacy storage permissions
The manifest deliberately omits `READ_EXTERNAL_STORAGE` and `WRITE_EXTERNAL_STORAGE`. File picking goes through the Photo Picker and SAF under scoped storage. If you find yourself reaching for the legacy permissions, you're solving the wrong problem.

## 4. How to run / build / test

From `CloudShelfApp/`:

```bash
# First-time setup (one of):
gradle wrapper --gradle-version 8.9          # if you have Gradle ≥ 8.9 locally
# — or — open the project in Android Studio Koala FD+ and let it generate the wrapper.

# Create local.properties (gitignored)
echo "sdk.dir=$HOME/Library/Android/sdk" > local.properties   # macOS
# Linux: ~/Android/Sdk    Windows: C:\\Users\\you\\AppData\\Local\\Android\\Sdk

# Build & install debug (sideload-friendly internal flavor)
./gradlew :app:installInternalDebug

# Other useful targets
./gradlew :app:assemblePlayStoreDebug          # Play debug AAB
./gradlew :app:bundlePlayStoreRelease          # Play Store AAB (requires keystore.properties)
./gradlew :app:assembleInternalRelease         # Sideload-friendly universal APK

# Tests
./gradlew :app:testInternalDebugUnitTest
./gradlew :app:lintInternalDebug
```

See `ONBOARDING.md` for AWS-side prerequisites (IAM user + policy + key generation).

## 5. Style and conventions

- **Kotlin 2.0.21**, JVM target 17.
- **Compose-first**: no XML layouts. The `res/` tree holds icons, strings, themes, and backup rules only.
- **Hilt** for DI everywhere. New singletons get `@Singleton @Inject constructor(...)`.
- **State**: `StateFlow` from the ViewModel, collected via `collectAsStateWithLifecycle()` in the composable.
- **Errors**: `S3Repository` translates SDK exceptions into typed domain errors via `S3ErrorMapper`. Never let raw SDK exceptions bubble into UI code.
- **Logging**: Timber only. Don't log credentials, pre-signed URLs, or full request/response bodies.
- **Threading**: SDK calls on `Dispatchers.IO` (see 3b). Anything CPU-heavy on `Dispatchers.Default`. UI updates on `Dispatchers.Main` (the default for ViewModel coroutines).
- **Naming**: feature packages own their `Screen`, `ViewModel`, and any feature-local helpers. Shared utilities go in `core/`. Persistence goes in `data/`.

## 6. Things that look broken but aren't

- `gradlew` / `gradlew.bat` / `gradle/wrapper/gradle-wrapper.jar` are **not** checked in. Run `gradle wrapper --gradle-version 8.9` once after cloning, or open the project in Android Studio and it'll generate them.
- **Cognito removed** — intentionally stripped; may return as a future-scope feature.
- Release builds without `keystore.properties` produce an unsigned AAB. Debug builds are unaffected.
- WorkManager's auto-initialization is turned off in `AndroidManifest.xml` (a `<provider>` `tools:node="remove"`) — that is intentional: Hilt provides the `WorkerFactory` via `CloudShelfApplication: Configuration.Provider`.

## 7. Common Claude workflows

**"Add a new S3 operation"** → put the method on `S3Repository`, wrap in `withContext(Dispatchers.IO)`, run errors through `S3ErrorMapper`. Expose it through a ViewModel that owns a `StateFlow<UiState>`.

**"Add a new screen"** → create `Foo/FooScreen.kt` + `Foo/FooViewModel.kt`, register a `Routes.FOO` constant + `composable(Routes.FOO) { FooScreen(...) }` in `CloudShelfNavHost.kt`, inject the ViewModel via `hiltViewModel()`.

**"Add a new permission"** → don't, if you can avoid it. The manifest is deliberately minimal. If you must, document the user-facing rationale alongside the manifest entry and update `SPEC.md` §9.

**"Touch the auth flow"** → the only path is IAM-key (BYOK). If re-adding Cognito in the future, read `../TODO/cognito-auth/decision.md` first.

**"Run a release build"** → see `README.md` "Generate a release signing key" section. Never check `keystore.properties` or any `.jks` / `.keystore` file into the repo.

## 8. What NOT to do

- ❌ Don't commit `amplifyconfiguration.json`, `keystore.properties`, `*.jks`, `*.keystore`, or `local.properties`.
- ❌ Don't call AWS SDK methods from `Dispatchers.Main` or directly from a composable.
- ❌ Don't construct ad-hoc `S3Client` instances — go through `S3ClientProvider`.
- ❌ Don't log credentials, signed URLs, or request/response bodies.
- ❌ Don't add `READ/WRITE_EXTERNAL_STORAGE`. Use SAF / Photo Picker.
- ❌ Don't import `com.amplifyframework.*` (Cognito dependency was removed; if re-added, keep it confined to `auth/`).
- ❌ Don't disable `isMinifyEnabled` in `release` to chase a ProGuard error — fix the rules in `proguard-rules.pro` instead.

## 9. Cross-document map

- **What we're building & why** → `SPEC.md`
- **How it's wired up** → `ARCHITECTURE.md`
- **How to get a working build on a new machine** → `ONBOARDING.md`
- **How to contribute changes back** → `CONTRIBUTING.md`
- **Current shipping status / quick commands** → `README.md`
- **Cognito auth (removed, future-scope)** → `../TODO/cognito-auth/decision.md`
- **Original brainstorm + phase plan** → `../CloudShelf-Plan.md`

## 10. When you're stuck

1. Re-read the relevant decision doc.
2. Grep the codebase for an existing similar pattern. The codebase is small and consistent — copying a sibling pattern is usually right.
3. If you have to make a strategic choice (a new external dependency, a new persistence store, a change to the auth seam), open an ADR under `TODO/<topic>/decision.md` first and get explicit go-ahead before writing code.

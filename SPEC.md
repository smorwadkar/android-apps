# CloudShelf — Product & Technical Specification

| Field | Value |
|---|---|
| Product | CloudShelf — an Android client for AWS S3 |
| Package / App ID | `com.mobildroid.cloudshelf.app` |
| Platform | Android only (Min SDK 26 / Target SDK 34) |
| Language / UI | Kotlin 2.0.21, Jetpack Compose, Material 3 |
| Current version | 0.1.0 |
| Phase | 6 — polish & ship |
| Owner | Shivdatta Morwadkar |
| Spec version | 1.0 (2026-05-29) |

Companion documents in this directory:

- `README.md` — quick start, build commands, current shipping status
- `ARCHITECTURE.md` — module layout, layer responsibilities, data flow
- `ONBOARDING.md` — new-machine setup, AWS prerequisites, first run
- `CONTRIBUTING.md` — branch / PR / code-style conventions
- `CLAUDE.md` — guidance for Claude (and humans) collaborating on this repo
- `../CloudShelf-Plan.md` — original brainstorm + phase plan (historical)
- `../TODO/cognito-auth/decision.md` — auth-model decision record (load-bearing)

---

## 1. Problem statement

AWS account owners who keep personal photos, documents, and videos in S3 have no good way to manage that storage from an Android phone. The official AWS Console app is administrative, not consumer-friendly. Third-party "S3 browser" apps either lock users into the developer's AWS account (Dropbox-style SaaS), hard-code US regions, or ship without background transfer support.

CloudShelf is the missing piece: a polished, native Android client that talks directly to **the user's own AWS account**, supports every region, and treats uploads and downloads as first-class background work that survives app kills and flaky networks.

## 2. Target user

- **Primary persona — "AWS-savvy individual."** Owns one or more AWS accounts. Already stores files in S3 (backups, photos, raw video, project archives). Comfortable creating an IAM user and pasting an access key on first launch. Wants S3 access from their phone without paying for a SaaS middleman.
- **Secondary persona — "Small-team operator."** Same skill profile, but the bucket is a shared team archive. Uses CloudShelf to grab a file on the road or upload from the field.

Explicitly **not** the target: non-technical end-consumers who would balk at IAM. They are the right audience for the optional Cognito path (kept in the codebase but not promoted — see §6).

## 3. Goals and non-goals

### Goals (v1)
- Sign in to the user's own AWS account with **IAM access keys (BYOK)**, validated against STS before storage.
- Browse buckets across **any AWS region**, with auto-detection per bucket.
- Navigate S3 prefixes folder-style: breadcrumbs, sort, search-in-prefix, pagination.
- Upload files from the Storage Access Framework or Photo Picker; resumable multipart upload for files ≥ 8 MB.
- Download objects to app-private storage with Range-based resume on retry.
- Preview images, PDFs, video, and text in-app without leaving CloudShelf.
- Run transfers as background work that survives process death and network loss.
- Generate pre-signed share URLs for any object (1-hour TTL).
- Encrypt every PUT at rest server-side (SSE-S3 default; SSE-KMS optional per-bucket).
- Ship through Google Play AND a sideload-friendly universal APK.

### Non-goals (v1)
- iOS, web, or desktop builds.
- Server-side components — CloudShelf is a pure client.
- Multi-account or multi-user awareness within a single install.
- File editing, real-time collaboration, or office-document rendering.
- Client-side encryption (server-side is sufficient for the target user).
- Built-in zip/archive handling beyond what the OS provides.

### Stretch (v2, out of scope here)
Pre-signed share UI overhaul, favorites + offline cache, move/copy across prefixes/buckets, bulk operations, thumbnail grid view, camera-capture upload, storage-class awareness, object versioning, metadata viewer, biometric app-lock, multi-cloud (Wasabi / R2 / MinIO / B2 — already architected for, see §7).

## 4. User stories (v1)

1. **First-run authentication.** As a returning AWS user, I paste an access key + secret + region into CloudShelf, the app validates it via `sts:GetCallerIdentity` and stores it encrypted, and I land on my list of buckets.
2. **Browse a multi-region account.** As a user with buckets in `us-east-1` and `ap-south-1`, I tap any bucket and CloudShelf auto-detects its region (caching the result), so listings are fast on the second visit.
3. **Resumable upload of a large file.** As a user uploading a 1 GB video, I can background CloudShelf or even kill the process; when WorkManager reschedules, the upload picks up exactly where it stopped using `ListParts` on the same `uploadId`.
4. **Resumable download.** As a user pulling a large archive over flaky Wi-Fi, the download retries from the last successfully-written byte using `Range: bytes=N-`.
5. **Preview without leaving the app.** As a user inspecting an unfamiliar file, I tap it and see the image / PDF / video / text inline.
6. **Share via short-lived link.** As a user who needs to send a file to a teammate, I tap Share in the preview, CloudShelf generates a 1-hour pre-signed GET URL, and Android's share sheet opens.
7. **Cost-aware behavior.** Listings cache for 60 s; pre-signed URLs are minted on demand only; no needless polling.
8. **Clean sign-out.** Signing out wipes the encrypted preference file, the preview cache, and the in-memory S3 client map — a second user on the same device sees nothing of the first.

## 5. Feature catalog

| # | Feature | State | Notes |
|---|---|---|---|
| F1 | IAM-key sign-in (BYOK) | ✅ Shipping | `AuthRepository`, `IamKeyStore`, `StsValidator` |
| F2 | Cognito sign-in (optional) | ⚠️ Gated | Only active when `amplifyconfiguration.json` is present locally — never committed |
| F3 | Bucket list with region badges | ✅ Shipping | `BucketsScreen`, `BucketRegionCache` (Room) |
| F4 | Prefix browser, breadcrumbs, sort, search | ✅ Shipping | `BrowserScreen` |
| F5 | Pagination via continuation tokens | ✅ Shipping | `S3Repository.listPage(...)` |
| F6 | Per-region `S3Client` cache | ✅ Shipping | `S3ClientProvider` |
| F7 | Upload via SAF / Photo Picker | ✅ Shipping | `UploadWorker` |
| F8 | Multipart upload (8 MB parts × 4 concurrent) | ✅ Shipping | Files ≥ 8 MB go multipart |
| F9 | Resumable upload via `ListParts` on retry | ✅ Shipping | `uploadId` persisted in Room |
| F10 | Download to app-private cache | ✅ Shipping | `DownloadWorker` |
| F11 | Resumable download via `Range` header | ✅ Shipping |  |
| F12 | Transfer queue UI | ✅ Shipping | `TransfersScreen` |
| F13 | Foreground notification with progress | ✅ Shipping | `TransferNotifications` |
| F14 | Preview — images | ✅ Shipping | Coil 3 |
| F15 | Preview — PDF | ✅ Shipping | AndroidX `PdfRenderer` |
| F16 | Preview — video | ✅ Shipping | Media3 ExoPlayer + pre-signed GET |
| F17 | Preview — text | ✅ Shipping |  |
| F18 | Pre-signed share URL (1 h TTL) | ✅ Shipping | Share icon in `PreviewScreen` |
| F19 | File ops: delete / rename / create folder | ✅ Shipping | `S3Repository` |
| F20 | SSE-S3 on every PUT | ✅ Shipping | `ServerSideEncryption.Aes256` |
| F21 | SSE-KMS opt-in per bucket | ⚠️ Plumbed | Surface in Settings (deferred to v2) |
| F22 | Settings: account, theme, cache, about | ✅ Shipping | `SettingsScreen` |
| F23 | Dual flavors: `playStore` (AAB) + `internal` (APK) | ✅ Shipping | `build.gradle.kts` |
| F24 | Release signing wired via gitignored `keystore.properties` | ✅ Shipping |  |

## 6. Authentication

CloudShelf's primary auth model is **IAM-key (BYOK)** — the user pastes their own AWS access key, secret, and region. Cognito is supported as an optional secondary path but is not promoted. Full rationale: `../TODO/cognito-auth/decision.md`.

### Path A — IAM key (default)
1. User enters access key ID + secret + optional session token + default region.
2. App calls `sts:GetCallerIdentity` to validate.
3. On success, credentials encrypt via `EncryptedSharedPreferences` (Android Keystore backed) in `IamKeyStore`.
4. `AwsCredentialsProviderFactory` wraps them in `StaticCredentialsProvider` for the S3 client.

### Path B — Cognito (secondary, only if `amplifyconfiguration.json` exists)
1. `AmplifyInitializer.tryInitialize()` is best-effort; absent config silently disables the tab.
2. User signs in via Amplify Auth (email/password).
3. Amplify federates STS via the Identity Pool to produce short-lived credentials.
4. Same `AwsCredentialsProviderFactory` bridge serves them to the S3 client.

The rest of the app is auth-agnostic — both paths converge in `CloudShelfCredentials`.

**Rules:**
- `amplifyconfiguration.json` is **never committed** (see `CloudShelfApp/.gitignore`).
- `AuthViewModel.UiState.tab` defaults to `Tab.IamKey`.
- `SignInScreen` leads with the IAM-key form.

## 7. Architecture (summary)

Full version: `ARCHITECTURE.md`.

```
UI (Compose) → ViewModel (Hilt) → Repository → Data sources
                                                   ├── AWS SDK for Kotlin (S3 + STS)
                                                   ├── Amplify Auth (Cognito, optional)
                                                   ├── WorkManager (transfers)
                                                   ├── Room (transfer + region cache)
                                                   ├── DataStore (theme + non-secret prefs)
                                                   └── EncryptedSharedPreferences (IAM keys)
```

Key invariants:

- **Every AWS SDK call runs on `Dispatchers.IO`.** The Kotlin SDK's HTTP engine cleans up sockets on the calling thread; without `withContext(Dispatchers.IO)` you get `NetworkOnMainThreadException`. Honored in `S3Repository`, `StsValidator`, `S3ClientProvider`.
- **One `S3Client` per region**, cached by `S3ClientProvider` and keyed off `BucketRegionCache` (persisted in Room).
- **`GetBucketLocation` quirks are normalized.** `us-east-1` returns null/empty; `eu-west-1` historically returned `"EU"`. Both are mapped to canonical region codes in `S3Repository.getBucketRegion`.
- **The credential abstraction is the only seam.** Anything below `S3Repository` knows nothing about Cognito vs IAM. This is what keeps the BYOK / Cognito choice reversible and makes multi-cloud (R2, Wasabi, B2, MinIO) a host/endpoint change rather than a rewrite.

## 8. Data model

Three persistence stores, deliberately separated:

| Store | Holds | Why |
|---|---|---|
| `EncryptedSharedPreferences` (`IamKeyStore`) | IAM access key, secret, optional session token, region | Hardware-backed at rest |
| Room (`CloudShelfDatabase`) | `TransferEntity` (queue + history), `BucketRegionCache` entries | Relational, observable via Flow |
| DataStore preferences (`UserPreferences`) | Theme override (System/Light/Dark), small non-secret prefs | Type-safe, async, non-secret |

Backup rules in `res/xml/backup_rules.xml` and `data_extraction_rules.xml` **exclude** the encrypted prefs file and the Room DB from cloud backup and device-to-device transfer.

## 9. Permissions (manifest)

`INTERNET`, `ACCESS_NETWORK_STATE`, `POST_NOTIFICATIONS`, `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_DATA_SYNC`. **No** legacy storage permissions — file pickers use the Photo Picker and SAF under scoped storage.

## 10. Required AWS IAM policy (user-facing)

Document this in onboarding. Least-privilege template:

```json
{
  "Version": "2012-10-17",
  "Statement": [
    {
      "Sid": "ListAndDescribeBuckets",
      "Effect": "Allow",
      "Action": ["s3:ListAllMyBuckets", "s3:GetBucketLocation"],
      "Resource": "*"
    },
    {
      "Sid": "ReadWriteObjectsInOwnedBuckets",
      "Effect": "Allow",
      "Action": [
        "s3:ListBucket",
        "s3:GetObject",
        "s3:PutObject",
        "s3:DeleteObject",
        "s3:AbortMultipartUpload",
        "s3:ListMultipartUploadParts"
      ],
      "Resource": [
        "arn:aws:s3:::<bucket>",
        "arn:aws:s3:::<bucket>/*"
      ]
    }
  ]
}
```

`s3:CreateMultipartUpload` and `s3:UploadPart` are subsumed by `s3:PutObject`.

## 11. Security

- IAM keys at rest: `EncryptedSharedPreferences` + Android Keystore.
- TLS only; no plaintext fallback. (Certificate pinning to `*.amazonaws.com` is **not** enabled in v1 — too risky against AWS endpoint rotation.)
- Pre-signed URLs: 1-hour TTL, never logged, minted on demand only.
- Timber disabled in release builds. `S3Repository.S3ErrorMapper` redacts keys before surfacing errors to UI.
- Sign-out wipes the encrypted preference file, the preview cache, and the in-memory S3 client map.
- Backup rules exclude the credentials file and Room DB from `adb backup` and D2D transfer.
- Rooted-device detection is **not** in v1 (it's noisy and easily bypassed); revisit if telemetry shows IAM-key leakage incidents.

## 12. Success metrics

| Metric | Target | Source |
|---|---|---|
| First sign-in to first listing | ≤ 10 s on a 4G connection | Manual timing on Pixel 6a |
| Multipart upload throughput | ≥ 5 MB/s on 50 Mbps Wi-Fi (4 × 8 MB parallel) | Manual; instrumented timer in `UploadWorker` |
| Resume-after-kill success rate | 100% for in-flight multipart uploads | Manual: kill mid-upload, watch for completion |
| Crash-free sessions | ≥ 99.5% over rolling 7-day window | Crash reporting (Sentry / Crashlytics, wired in Phase 6) |
| Cold-start time to Buckets list (signed in) | ≤ 2 s on a Pixel 6a | Manual timing |
| Median listing latency (cached region) | ≤ 500 ms | Manual timing |

## 13. Risks & open questions

| Risk | Status | Mitigation / next step |
|---|---|---|
| IAM keys on a lost/rooted device | Accepted | UI warning at entry; recommend short-rotation policy in docs; biometric lock deferred to v2 |
| Coil OOM on giant images | Mitigated | `maxBitmapSize` configured in Coil ImageLoader |
| Orphan multipart uploads after permanent failure | Mitigated | `AbortMultipartUpload` called when WorkManager exhausts retries |
| Doze mode pausing transfers | Mitigated | Foreground service + `FOREGROUND_SERVICE_DATA_SYNC` |
| `EncryptedSharedPreferences` is deprecated | Open | Plan migration to a Keystore-backed wrapper over DataStore once an official replacement is recommended |
| Cognito tab drift if `amplifyconfiguration.json` gets committed by mistake | Mitigated | Gitignored; tested by `AmplifyInitializer.tryInitialize` returning false when absent |
| No client-side encryption | Accepted | SSE-S3 covers the target user; revisit if a regulated-industry persona appears |

## 14. Out-of-scope decisions (already settled)

- **No iOS / no web** — Kotlin Multiplatform considered for v3; not now.
- **No SaaS backend** — pure client app; user owns the storage.
- **No team mode** — single-account, single-user per install.
- **Cognito stays in the tree** — useful reference implementation and validates the credential abstraction; deletion considered after six months of zero adoption signals.

## 15. Glossary

- **BYOK** — Bring Your Own Keys; the user supplies AWS credentials, the app does not own any AWS infra.
- **Pre-signed URL** — short-lived signed S3 URL anyone can use without their own credentials.
- **SAF** — Storage Access Framework; Android's user-mediated file picker.
- **SSE-S3 / SSE-KMS** — server-side encryption modes for S3 objects.
- **STS** — AWS Security Token Service; used here only to validate keys (`GetCallerIdentity`).

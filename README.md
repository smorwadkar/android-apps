# CloudShelf

An Android app that connects to your AWS account, browses your S3 buckets, and uploads/downloads files (images, docs, videos) with in-app preview and background transfers.

> 📄 [One-pager overview](docs/CloudShelf-one-pager.pdf)

- **Package:** `com.mobildroid.cloudshelf.app`
- **Min SDK:** 26 (Android 8.0)
- **Target SDK:** 34
- **Language:** Kotlin
- **UI:** Jetpack Compose, Material 3
- **Owner:** Shivdatta Morwadkar

See `../CloudShelf-Plan.md` for the full design + roadmap.

## Current status — Phase 6 (polish & ship)

All MVP features are in. The remaining work is launching:

- **Settings** has Account (identity + sign out), Appearance (System / Light / Dark theme override persisted via DataStore), Storage (clear preview cache + clear finished transfers), and About (version + package).
- **Share via pre-signed URL** — the Share icon in the Preview top bar generates a 1-hour pre-signed GET URL and opens the Android share sheet.
- **Sign-out hygiene** — signing out also wipes the preview cache so a different account on the same device won't see stale files.
- **Release signing** is wired but inert until you add `keystore.properties` (see below). Debug builds keep working unchanged.

### Generate a release signing key (one-time)

```bash
keytool -genkey -v \
  -keystore release.keystore \
  -keyalg RSA -keysize 2048 -validity 10000 \
  -alias cloudshelf
```

Move `release.keystore` somewhere outside the repo (e.g. `~/.android/cloudshelf-release.keystore`), then create `keystore.properties` at the project root (gitignored):

```
storeFile=/Users/you/.android/cloudshelf-release.keystore
storePassword=<store password>
keyAlias=cloudshelf
keyPassword=<key password>
```

### Produce signed builds

```bash
# Play Store AAB (recommended for Play Console upload)
./gradlew :app:bundlePlayStoreRelease

# Sideload-friendly universal APK
./gradlew :app:assembleInternalRelease
```

Output lands in `app/build/outputs/bundle/playStoreRelease/` and `app/build/outputs/apk/internal/release/` respectively.

## Earlier phases

## Current status — Phase 3 (transfers)

What works:

- **Sign in** via IAM keys (BYOK).
- **Browse** real S3 buckets across regions with breadcrumbs, sort, search-in-prefix, and infinite scroll (Phase 2).
- **Upload** any file you pick from Storage Access Framework (the FAB on the Browser screen). Files ≥ 8 MB use S3 multipart upload with 4 concurrent 8 MB parts. SSE-S3 (`AES256`) is set on every PUT and CreateMultipartUpload. Killing the app mid-upload preserves the `uploadId` in Room; when WorkManager retries, `ListParts` finds completed parts and resumes.
- **Download** any object via the trailing arrow icon on each file row. The downloaded file lands in app-private storage (`cacheDir/downloads/`). On retry, the worker uses `Range: bytes=N-` to resume from the last successfully-written byte.
- **Transfers screen** lists active and recent transfers with a live progress bar, cancel (active), and remove-from-history (recent). The "Clear" action wipes finished entries.
- **Foreground notification** with progress + cancel runs while a transfer is active, so transfers survive backgrounding and process-kills.

### Required IAM permissions (additions for Phase 3)

The §4b policy in `CloudShelfPlan.md` already includes these. If you're using a stripped-down policy, you need at minimum:

```
s3:PutObject
s3:GetObject
s3:AbortMultipartUpload
s3:ListMultipartUploadParts
```

`s3:CreateMultipartUpload` and `s3:UploadPart` are subsumed by `s3:PutObject`.

### What's NOT in Phase 3 (deferred to later phases)

- Preview (Phase 4): tapping a file still goes to a placeholder Preview screen.
- Pre-signed share URLs (deferred to v2).
- SAF tree URI for download location (Phase 6 polish).
- Pause / resume controls — works automatically on retry but no manual pause yet.

## Current status — Phase 1 (auth)

The app signs in via **IAM access key + secret + optional session token + region**. Keys are validated via `sts:GetCallerIdentity` before being stored, and saved with `EncryptedSharedPreferences` (Android Keystore-backed). Cognito was removed as a future-scope feature.

Sign-out from Settings clears the stored keys and returns to the SignIn screen.

### IAM policy

Same policy. Create an IAM user in the AWS console, attach the policy, generate an access key + secret, paste them into the IAM-key tab in the app.

## Build prerequisites

- Android Studio Koala Feature Drop or newer (AGP 8.5+).
- JDK 17.
- Android SDK Platform 34 + Build-Tools 34.
- Android Gradle Plugin 8.5.x.
- Kotlin 2.0.21 (Compose Compiler plugin is enabled).

## First-time setup

The Gradle wrapper binaries (`gradlew`, `gradlew.bat`, `gradle/wrapper/gradle-wrapper.jar`) aren't checked in. After cloning, run **one** of:

```bash
# If you have Gradle 8.9+ installed locally:
gradle wrapper --gradle-version 8.9

# Otherwise just open the project in Android Studio — it will generate the wrapper.
```

Then create a `local.properties` (also gitignored) pointing at your Android SDK:

```
sdk.dir=/Users/you/Library/Android/sdk
```

## Build

```bash
# Debug install (internal flavor — sideload-friendly)
./gradlew :app:installInternalDebug

# Play Store debug build
./gradlew :app:assemblePlayStoreDebug

# Release AAB for Play
./gradlew :app:bundlePlayStoreRelease

# Universal APK for sideload
./gradlew :app:assembleInternalRelease
```

Add a `keystore.properties` file (gitignored) and a `signingConfigs` block in `app/build.gradle.kts` before producing release builds.

## Run tests

```bash
./gradlew :app:testInternalDebugUnitTest
./gradlew :app:lintInternalDebug
```

## Module / package layout

```
app/src/main/java/com/mobildroid/cloudshelf/app/
    auth/         # IAM-key sign-in (BYOK)
    buckets/      # Phase 2 — list buckets, region badges
    browser/      # Phase 2 — prefix navigation, sort, search
    transfer/     # Phase 3 — UploadWorker / DownloadWorker, queue UI
    preview/      # Phase 4 — image / PDF / video / text preview
    fileops/      # Phase 5 — delete, rename, create folder
    settings/     # Phase 6 — sign out, theme, cache, region override
    core/
      di/         # Hilt modules
      ui/theme/   # Material 3 theme, brand palette
      ui/         # shared composables (PlaceholderScaffold, etc.)
    navigation/   # Compose Navigation routes + NavHost
```

## What's intentionally NOT in this scaffold

- `amplifyconfiguration.json` — Cognito config, removed (future-scope).
- Real `S3Client` wiring — Phase 1 introduces `S3ClientProvider` keyed by region.
- WorkManager workers — Phase 3.
- Signing keystore — set up locally before release builds.

## Security notes

- IAM keys (advanced sign-in path) are stored in `EncryptedSharedPreferences` backed by Android Keystore.
- The encrypted prefs file and the Room database are excluded from cloud backup and D2D transfer (`res/xml/backup_rules.xml`, `data_extraction_rules.xml`).
- All S3 PUTs request server-side encryption (`x-amz-server-side-encryption: AES256` by default; SSE-KMS configurable per bucket) — wired in Phase 3.
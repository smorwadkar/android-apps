# CloudShelf — Onboarding (new machine, zero context)

This guide gets a fresh machine from "git clone" to "the app runs on a phone and lists buckets" in about 30–45 minutes. It assumes you've been pointed at this repo and have a Claude subscription (Claude Code, Cowork, or any Claude-powered IDE assistant) to lean on for code-level questions.

Read [`SPEC.md`](SPEC.md) for what we're building and [`CLAUDE.md`](CLAUDE.md) for the rules that the AI assistant will follow.

---

## 1. Prerequisites

### 1a. Local toolchain

| Tool | Version | How to check |
|---|---|---|
| JDK | 17 | `java -version` |
| Android Studio | Koala Feature Drop or newer (AGP 8.5+) | About → Help |
| Android SDK Platform | 34 | SDK Manager |
| Android SDK Build-Tools | 34.x | SDK Manager |
| Gradle (optional, only for wrapper bootstrap) | 8.9+ | `gradle -v` |
| Git | any recent | `git --version` |

Apple Silicon and Linux users: install JDK 17 via your package manager or `jenv` / `sdkman`. Android Studio bundles its own JDK; if your terminal's `java` is different, set `JAVA_HOME` to Android Studio's bundled JDK to avoid build inconsistencies.

### 1b. AWS-side prerequisites

You need an **AWS account you own** (or one whose IAM you can administer). CloudShelf does not provide hosted storage — it connects to **your** S3.

1. Sign in to the AWS Console.
2. Go to IAM → Users → Create user (e.g. `cloudshelf-mobile`).
3. Attach the policy below as an inline policy. Replace `<bucket>` with the bucket name(s) you want to manage from the phone (you can list multiple).
4. Generate an access key (Security credentials → Access keys → Create) — choose "Application running outside AWS." Copy the access key ID and secret access key once; the secret won't be shown again.

Suggested least-privilege policy:

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

`s3:CreateMultipartUpload` and `s3:UploadPart` are covered by `s3:PutObject`.

### 1c. A test bucket (optional but helpful)

Create a small S3 bucket in your preferred region with a couple of test files (an image, a PDF, a short video) so you can validate the full upload/download/preview flow on first run.

---

## 2. Clone and bootstrap

```bash
git clone <repo-url> CloudShelf
cd CloudShelf/CloudShelfApp     # ⬅ Gradle root lives here, NOT at the parent
```

### 2a. Generate the Gradle wrapper

The wrapper jar is **not** checked in (it's binary and varies by Gradle version). Pick one:

- **You have Gradle ≥ 8.9 locally**:
  ```bash
  gradle wrapper --gradle-version 8.9
  ```
- **You don't have Gradle locally**: open the project in Android Studio and let it generate the wrapper on import.

### 2b. Point Gradle at your Android SDK

Create `local.properties` at the `CloudShelfApp/` root (gitignored — never commit it):

```
# macOS
sdk.dir=/Users/you/Library/Android/sdk

# Linux
sdk.dir=/home/you/Android/Sdk

# Windows (escape backslashes or use forward slashes)
sdk.dir=C\:\\Users\\you\\AppData\\Local\\Android\\Sdk
```

### 2c. (Optional) Set up release signing

Skip this if you only need debug builds. For release builds:

```bash
keytool -genkey -v \
  -keystore release.keystore \
  -keyalg RSA -keysize 2048 -validity 10000 \
  -alias cloudshelf
```

Move `release.keystore` somewhere **outside** the repo (e.g. `~/.android/cloudshelf-release.keystore`), then create `keystore.properties` at `CloudShelfApp/` root:

```
storeFile=/Users/you/.android/cloudshelf-release.keystore
storePassword=<store password>
keyAlias=cloudshelf
keyPassword=<key password>
```

`keystore.properties` is gitignored. Lose this file and you cannot ship updates to the same Play listing — back it up.

---

## 3. First build

```bash
# Sideload-friendly debug build, installed on a connected device or emulator
./gradlew :app:installInternalDebug
```

If you see `Gradle sync failed` in Android Studio, click the elephant icon (or **File → Sync Project with Gradle Files**) once and let it resolve.

### Useful Gradle targets

```bash
./gradlew :app:assemblePlayStoreDebug          # Play debug AAB (uses Play flavor)
./gradlew :app:assembleInternalDebug           # Sideload APK (debug)
./gradlew :app:bundlePlayStoreRelease          # Play Store AAB (needs keystore.properties)
./gradlew :app:assembleInternalRelease         # Universal APK (needs keystore.properties)
./gradlew :app:testInternalDebugUnitTest       # Unit tests
./gradlew :app:lintInternalDebug               # Android Lint
```

---

## 4. First run

1. Launch the app. You land on the **Sign In** screen with the IAM-key form selected by default.
2. Paste:
   - **Access key ID** (`AKIA...`)
   - **Secret access key**
   - **Region** (e.g. `us-east-1`) — this is the default region; per-bucket regions are auto-detected.
   - **Session token** — leave blank unless you generated temporary credentials.
3. Tap **Sign In**. The app calls `sts:GetCallerIdentity` to validate the keys before storing them. Invalid keys are rejected here with an error toast.
4. On success, you land on the **Buckets** screen showing every bucket the IAM user can `ListBuckets`.
5. Tap a bucket → the **Browser** screen opens. First touch of a bucket triggers `GetBucketLocation`; the region is cached so subsequent visits skip the call.
6. Try the round-trip:
   - Tap the FAB → pick a file via SAF → it appears in the **Transfers** screen, then in the bucket listing once complete.
   - Tap a file in the listing → preview opens (image / PDF / video / text).
   - Tap the download arrow on a row → file lands in app-private storage; progress shows in **Transfers**.
   - In Preview, tap the Share icon → a 1-hour pre-signed URL opens in the Android share sheet.

If any of those fail, see §6 below.

---

## 5. (Optional) Enable the Cognito sign-in tab

CloudShelf's primary auth model is BYOK (IAM keys). The Cognito tab is **gated off by default** — read [`../TODO/cognito-auth/decision.md`](../TODO/cognito-auth/decision.md) for why before enabling it.

If you do want it enabled locally:

```bash
# From the CloudShelfApp/ directory
npm install -g @aws-amplify/cli      # one-time, Node 18+
amplify configure                    # one-time, choose a profile with Cognito + IAM perms
amplify init                         # framework: android, res dir: app/src/main/res
amplify add auth                     # email sign-in, default config
amplify push                         # creates the Cognito pools + IAM roles
```

`amplify push` writes `app/src/main/res/raw/amplifyconfiguration.json`. The next app launch enables the Cognito tab automatically.

> ⚠️ **Do not commit `amplifyconfiguration.json`.** It is gitignored. Committing it would silently enable Cognito for everyone who clones the repo.

Attach the §1b policy to the Cognito authenticated role that `amplify push` created.

---

## 6. Common errors and fixes

| Symptom | Likely cause | Fix |
|---|---|---|
| `SDK location not found` on Gradle sync | Missing or wrong `local.properties` | Create `CloudShelfApp/local.properties` per §2b |
| `Could not find tools.jar` | Wrong `JAVA_HOME` | Point `JAVA_HOME` at JDK 17 (e.g. Android Studio's bundled JDK) |
| App crashes immediately with `NetworkOnMainThreadException` | An AWS SDK call was added without `withContext(Dispatchers.IO)` | Wrap the SDK call as in `S3Repository`. See [`CLAUDE.md`](CLAUDE.md) §3b |
| "Failed to load credentials" on sign-in | Access key invalid / region typo / IAM policy missing `sts:GetCallerIdentity` | Verify in AWS Console; STS is allowed by default in most accounts |
| Bucket list is empty but you know you have buckets | IAM user lacks `s3:ListAllMyBuckets` | Add it to the inline policy (§1b) |
| Tapping a bucket throws 301 / "AuthorizationHeaderMalformed" | Region mismatch | Should self-heal via `GetBucketLocation`; if not, sign out and back in — `BucketRegionCache` gets rebuilt |
| Multipart upload fails with `AccessDenied` on the last step | Missing `s3:AbortMultipartUpload` or `s3:ListMultipartUploadParts` | Add them (§1b) |
| Release build fails: `keystore.properties not found` | You haven't set up signing | Either set it up (§2c) or use a debug target instead |
| `bundlePlayStoreRelease` succeeds but the AAB is unsigned | `keystore.properties` is missing or invalid | Confirm the file exists and the paths/passwords inside it are correct |
| Cognito tab is grayed out | `amplifyconfiguration.json` not present | Either ignore (BYOK is the primary path) or run `amplify push` (§5) |
| WorkManager doesn't pick up a queued transfer | Battery optimizer is killing the foreground service | On the test device, exclude CloudShelf from battery optimization (Settings → Battery → App optimization) |

---

## 7. Working with Claude on this repo

When you open the project with Claude Code / Cowork:

1. Claude will read [`CLAUDE.md`](CLAUDE.md) automatically — it captures the load-bearing decisions (BYOK default, `Dispatchers.IO` rule, credential seam, etc.).
2. Point Claude at [`SPEC.md`](SPEC.md) if you want product context.
3. Point Claude at [`ARCHITECTURE.md`](ARCHITECTURE.md) if you want layer / module context.
4. Strategic / cross-cutting choices live in [`../TODO/<topic>/decision.md`](../TODO). Have Claude read the relevant ADR before changing anything in that area.

A useful first prompt on a new machine:

> "Read CLAUDE.md and SPEC.md, then summarize what CloudShelf is and the three or four rules I should know before changing any code."

Claude should come back with the BYOK default, the `Dispatchers.IO` rule, the credential seam, the per-region `S3Client` cache, and the "no legacy storage permissions" rule.

---

## 8. What to read next

- [`SPEC.md`](SPEC.md) — product and technical specification
- [`ARCHITECTURE.md`](ARCHITECTURE.md) — code structure, threading, data flow
- [`CONTRIBUTING.md`](CONTRIBUTING.md) — how to commit changes back
- [`CLAUDE.md`](CLAUDE.md) — AI collaborator guide (also useful for humans)
- [`README.md`](README.md) — current shipping status + quick commands
- [`../TODO/cognito-auth/decision.md`](../TODO/cognito-auth/decision.md) — auth ADR
- [`../CloudShelf-Plan.md`](../CloudShelf-Plan.md) — original brainstorm + phased plan

# Contributing to CloudShelf

Thanks for picking up CloudShelf. This file covers how to land a change cleanly. For setup, see [`ONBOARDING.md`](ONBOARDING.md). For the rules the code is shaped around, see [`CLAUDE.md`](CLAUDE.md).

## 1. Before you write code

1. **Read the relevant decision doc.** Strategic choices live under `../TODO/<topic>/decision.md`. The auth ADR ([`../TODO/cognito-auth/decision.md`](../TODO/cognito-auth/decision.md)) is the most load-bearing one. Don't relitigate without explicit go-ahead from the owner.
2. **Check `SPEC.md` and `ARCHITECTURE.md`.** If your change conflicts with either, update the doc in the same PR.
3. **Open an issue or ADR for strategic changes.** New external dependencies, new persistence stores, changes to the credential seam, or anything that touches `auth/` deserve a one-page ADR in `../TODO/<topic>/decision.md` *before* the code change.

## 2. Branching

- `main` — always shippable. Protected branch.
- Feature branches — `feature/<short-slug>` (e.g. `feature/sse-kms-toggle`).
- Bug fixes — `fix/<short-slug>`.
- Spike / exploration — `spike/<short-slug>` (don't expect to merge).

Rebase on `main` before opening a PR. Squash on merge keeps the history clean.

## 3. Commit messages

Conventional Commits, lower-case:

```
feat(transfer): resumable multipart upload via ListParts
fix(browser): normalize eu-west-1 region constraint
refactor(auth): extract CredentialsProvider seam
docs(spec): record SSE-KMS deferral to v2
chore: bump compose-bom to 2024.10.00
test(s3-repo): verify SSE-S3 header on every PutObject
```

The scope is the package or feature name. Keep the subject under 72 chars; put rationale and links in the body.

## 4. Code style

- **Kotlin official style** (`kotlin.code.style=official` in `gradle.properties`).
- **Compose-first**: no new XML layouts. `res/` is for icons, strings, themes, and backup rules only.
- **State** lives in ViewModels as `StateFlow<UiState>`. Composables collect via `collectAsStateWithLifecycle()`.
- **Errors** from the AWS SDK go through `S3ErrorMapper`. UI code consumes typed domain errors, never SDK exceptions.
- **Logging** through Timber. Never log credentials, pre-signed URLs, or full request/response bodies. Timber is disabled in release builds.
- **Naming**:
  - Feature packages own their `Screen`, `ViewModel`, and feature-local helpers.
  - Shared composables live in `core/ui/`; shared infrastructure in `core/`.
  - Persistence in `data/`.
- **Public API surface**: prefer `internal` over `public` unless the type is intentionally exposed.

## 5. Hard rules (don't break these)

- ✅ Every AWS SDK call wraps its body in `withContext(Dispatchers.IO) { ... }`. (See [`CLAUDE.md`](CLAUDE.md) §3b.)
- ✅ Every `PutObject` and `CreateMultipartUpload` sets server-side encryption.
- ✅ `AuthViewModel.UiState.tab` defaults to `Tab.IamKey`. The Cognito tab is gated.
- ✅ S3 clients come from `S3ClientProvider`. Don't construct ad-hoc instances.
- ❌ No `READ_EXTERNAL_STORAGE` or `WRITE_EXTERNAL_STORAGE`. Use the Photo Picker and SAF.
- ❌ No `com.amplifyframework.*` imports outside the `auth/` package.
- ❌ Don't commit `keystore.properties`, `*.jks`, `*.keystore`, `local.properties`, or `app/src/main/res/raw/amplifyconfiguration.json`.

CI runs lint and unit tests on every PR. Local pre-flight:

```bash
./gradlew :app:lintInternalDebug :app:testInternalDebugUnitTest
```

## 6. Tests

- **ViewModels**: unit-tested with JUnit + MockK + Turbine (`androidx.work.testing` is wired for future worker tests).
- **Repositories**: SDK calls are mocked; assert request shapes — especially that `ServerSideEncryption.Aes256` is set on PUTs.
- **No instrumentation tests in v1.** Compose UI test deps are wired (`androidTestImplementation`) but unused.
- **Manual test plan** lives in the PR description for behavior that's hard to unit-test (e.g. "kill the app mid-upload and verify resume").

When you touch the upload / download workers, include a manual test note in the PR:

> Tested on Pixel 6a, Android 14, 50 Mbps Wi-Fi:
> - 100 MB single PUT: ✅
> - 1 GB multipart, killed at ~40%: ✅ resumed to completion
> - 1 GB download, airplane mode toggled at ~60%: ✅ resumed from byte offset

## 7. PR checklist

Copy this into your PR description:

```
## What
<one paragraph>

## Why
<linked issue or ADR>

## Test plan
- [ ] Unit tests added/updated
- [ ] Lint passes locally
- [ ] Manual smoke on a real device (if UI / transfer change)
- [ ] SPEC.md / ARCHITECTURE.md updated if behavior or structure changed
- [ ] No secrets, keystores, or amplifyconfiguration.json in the diff

## Screenshots / recordings
<if UI change>
```

## 8. Releasing

Release flow lives in [`README.md`](README.md). Short version:

1. Bump `versionCode` and `versionName` in `app/build.gradle.kts`.
2. Tag: `git tag v<version> && git push --tags`.
3. `./gradlew :app:bundlePlayStoreRelease` → upload AAB to Play Console internal track.
4. `./gradlew :app:assembleInternalRelease` → attach the APK to a GitHub release.
5. Promote on Play (internal → closed → open → production) after the AAB has soaked for a day on the internal track.

Same signing key for both. Lose `keystore.properties` and you cannot update either listing — keep a backup off-machine.

## 9. Working with Claude on a PR

When using a Claude assistant in this repo:

- Have Claude read `CLAUDE.md` and the relevant feature package before suggesting changes.
- For changes that touch a load-bearing decision (auth, credential seam, threading), have Claude read the ADR first.
- For non-trivial diffs, ask Claude to write a manual test plan as part of the PR description.
- Trust Claude on syntax, dependency choices, and Compose idioms; double-check Claude on AWS S3 / IAM behavior — the SDK has edge cases (region quirks, multipart abort semantics, presign TTL caps) that are easy to get subtly wrong.

## 10. Code of conduct

Be kind, be specific. Disagree with code, not with people. CloudShelf is a personal-scale project; keep reviews proportionate.

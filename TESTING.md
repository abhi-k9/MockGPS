# Independently reproduced validation

Reviewed code checkpoint: **a6dc01744895095ee6fa1b1361cf77f299cd0382**, branch `harden/android17-automation`, 2026-10-23. `docs/VALIDATION.json` records exact source IDs, actual counts, job URLs, public artifact identity and limitations. A later documentation-only commit is not retroactively represented as the binary source for this checkpoint.

## Actual outcomes

| Check | Independently observed result |
| --- | --- |
| Previously reviewed application source0cfeb55f | 60 JVM tests passed; 0 failures/errors/skips; lint0 errors/25 warnings. Reproduced from a Git archive after backporting **only** the three reviewed dependency metadata entries; original metadata failed cold resolution. |
| Official SDK bootstrap | Passed in empty local SDK directory and both hosted jobs; explicit tools19.0/Java21/SDK36/buildtools36.0.0. |
| Cold Gradle resolution | Reproduced two reported missing entries, then found/reviewed a third BOM during complete build; strict configuration/full builds subsequently passed. No permissive mode or blanket trust. |
| Clean debug + unsigned release + instrumentation packaging | Passed locally and in GitHub. Unsigned release output is not an installable signed release. |
| Current JVM tests | **67 passed, 0 failures, 0 errors, 0 skipped**, local and GitHub. |
| Android lint | **0 errors;16 warnings locally,20 in cold hosted CI**. Extra4 are current online update recommendations; per-issue CI evidence was inspected. |
| API36 emulator instrumentation | **11 passed, 0 failures, 0 errors, 0 skipped** in GitHub, with fresh nonzero XML checked. Not merely compiled. |
| Debug identity/signature | CI apksigner verifies, installed package dev.abhik.mockgps; exact SHA/signer below and in artifact JSON. Local APK separately verifies with its own development signer. |
| Workflow/shell static validation | Official checksum-verified actionlint1.7.7 (without optional ShellCheck) and Bash syntax passed. |
| Dependency review | **Failed/external blocker**, not skipped/passed: GitHub reports unsupported repository feature; API403 and integration administrative restriction prevent changing it here. |
| Samsung/OneUI9/Android17/real Tasker/secure lock/genuine fix | **NOT RUN**. API36 emulator does not establish them. |

GitHub primary jobs actually passed:

- Push: https://github.com/abhi-k9/MockGPS/actions/runs/40026375247
- Draft-PR checks: https://github.com/abhi-k9/MockGPS/actions/runs/40026384491
- Earlier successful repaired checkpoint941e82e: https://github.com/abhi-k9/MockGPS/actions/runs/40024641110
- Dependency review blocker: https://github.com/abhi-k9/MockGPS/actions/runs/40026384411

The first post-review workflow revision failed GitHub expression validation because runner.temp was used at job-env scope. It was corrected to initialize the isolated Gradle home inside a job step; the failed attempt is https://github.com/abhi-k9/MockGPS/actions/runs/40024520723 . This was not counted as a pass. Pinned actions also emit a Node20 deprecation/forced-Node24 notice; actual jobs pass, and that workflow-runtime warning is separate from Android lint.

## Commands and scope

```sh
# Local managed workspace: activate existing trusted JDK/proxy, then use a new SDK/Gradle home.
source /workspace/setup/android-env.sh
scripts/setup-android-sdk.sh /workspace/post-review-sdk
export ANDROID_HOME=/workspace/post-review-sdk
export ANDROID_SDK_ROOT="$ANDROID_HOME"
export ANDROID_USER_HOME="$ANDROID_HOME/.android-user"
export GRADLE_USER_HOME=/workspace/post-clean-gradle-v2
# Local extracted JDK needs the explicit installations path in this Gradle home's properties.
scripts/android-build.sh --project-cache-dir=/workspace/post-project-cache-v2 --no-watch-fs
```

The bootstrap archive checksum, exact reviewed Maven hashes and cold-model diagnosis are in `docs/CI_REPAIR.md`. Local logs are retained outside the checkout under `/workspace/post-review-evidence/`. The original60-test reproduction uses archived0cf application/build files plus metadata-only repairs; it does not claim the original strict metadata passed.

CI uses a fresh runner-temporary SDK and Gradle home, explicit Java21, strict verification and the same developer script. The emulator job provisions official SDK/system image, checks KVM, bounds boot readiness, runs connectedDebugAndroidTest, asserts fresh XML and nonzero tests, and retains diagnostics. It does not manufacture a passing result when no device exists. Local `/dev/kvm` was unavailable, so local device execution is not claimed.

## Quality of tests

- Authorization19: exact raw Android types/schema, API1 rejection, missing/wrong/revoked token, command bounds, freshness/extreme times, independent sequence/replay bounds, same-ms events, rollback and read-only status.
- Policy26: valid modes/idempotency, strict FORCE_REAL until explicit AUTO, monotonic delay, cancellation/reconnection/unknown, stale generations, confirmation, process/reboot recovery, duplicate events, clock rollback and rapid changes.
- Engine16: coordinate/metadata validation, partial setup rollback, injection/removal failure, ownership journal/retry, unowned-name protection, rapid cycles and polar drift. Fakes do not prove Android permission admission.
- Tile6: truthful mode/observation/error projection and unlock policy.
- Android instrumentation11: actual packaged manifest, preferences/migration/recovery, credential rotation/epoch, ordered receiver acceptance/rejection with same-ms events, persistent legacy uncertainty and real SystemUI add/bind/click/reopen behavior. The unlocked emulator does not test Samsung secure-lock policy, a full SystemUI process restart, cross-UID malicious APK exploits or physical Tasker.

No broad lint suppression, baseline, skipped assertion or weakened verification was used. MockLocation retains its narrowly documented annotation for this dedicated developer simulator. Three reviewed metadata entries are repository-rooted integrity pins, not independent publisher provenance or a clean vulnerability scan. Byte-identical debug APK reproduction is not claimed: CI creates development signing keys, and archive metadata can vary.

## Warning disposition

Individual20 hosted warnings are recorded in VALIDATION.json. Nine UseKtx suggestions retain explicit commit-failure checks; three InlinedApi warnings concern compile-time numeric constants on the guarded API29–30 compatibility path; one exported receiver is intentionally capability-protected for separately signed Tasker. OldTargetApi/compileSdk37 and online recommendations for newer Gradle/AGP/core/activity/MapLibre are retained pending a separate reviewed upgrade. Resource density, obsolete/redundant pre-API26 icon fallbacks, unused resources and button style were fixed. See the independent audit table; no online check was disabled merely to match local counts.

## Artifact and signing

The successful build artifact contains debug APK, unsigned release, instrumented APK, reports and `development-candidate/build-identity.json`/signature evidence. CI public notices expose actual source, package, APK SHA and public signer without private key material. Use the exact artifact/source in VALIDATION.json, not an APK copied from another run. Debug certificates can change between CI runs; a matching application ID does not override Android signer checks.

Independent artifact download here was blocked by the observed Azure redirect host. CI's successful apksigner/application-ID check and public provenance were inspected through the GitHub API; that is **not** a claim that the zip/APK was independently downloaded and hashed locally. The local signed APK has a different documented SHA/signer. Review/apply the saved exact log/artifact-host network additions if that additional download verification is required in this environment.

Dependency review remains a merge gate. The owner must check/enable Dependency graph at https://github.com/abhi-k9/MockGPS/settings/security_analysis and rerun it. The repo is public; a private-repo Advanced Security subscription is not the stated requirement here. The integration cannot administer that setting (Resource not accessible by integration403). No known high-severity application-code finding remains from this review; this is not a claim that unavailable advisory review found no vulnerable dependency.

# Validation evidence and gaps

Validation date: 2026-10-23. Linux x86_64 cloud environment; Java21 JDK, Android platform36/build-tools36.0.0, AGP9.2.1, Gradle9.6.0. **No emulator or physical Samsung device was run.** `/dev/kvm` was unavailable. GitHub Actions is configured, not observed running.

## Commands and results

A clean run of the shared developer/CI script completed with `BUILD SUCCESSFUL`:

```sh
source /workspace/setup/android-env.sh
scripts/android-build.sh --project-cache-dir=/workspace/.cache/mockgps-project
```

After final policy, storage fail-safe and UI-review corrections, the affected checks were rerun with strict dependency verification:

```sh
./gradlew :app:assembleDebug :app:assembleRelease :app:testDebugUnitTest \
  :app:lintDebug :app:assembleDebugAndroidTest \
  --dependency-verification=strict \
  --project-cache-dir=/workspace/.cache/mockgps-project --max-workers=2 --console=plain
```

Final results validate source commit `c192a13` and are recorded in `docs/VALIDATION.json`. That file is generated from the current run's JUnit/lint XML and artifact SHA-256 digests; it is not a claimed device report. Logs for this workspace are `/tmp/mockgps-final-validation.log` (clean checkpoint) and `/tmp/mockgps-delivery-validation.log` (final affected checks). The clean checkpoint passed59 tests; one strict FORCE_REAL regression was added afterwards. Use final XML/counts for current evidence.

| Check | Outcome |
| --- | --- |
| Clean debug and unsigned release builds | Passed; final affected builds also passed with strict verification |
| JVM tests | Passed60; 0 failures, 0 errors, 0 skipped (actual JUnit XML) |
| Android lint/static analysis | Passed; 0 errors, 25 warnings retained |
| Instrumentation APK | Compiled; seven tests **not executed** locally |
| APK signature | Debug APK verified with SDK apksigner; development v2 signature, no private release signing key |
| Strict dependency verification | Final check uses committed SHA-256 verification metadata |
| CI workflow/shell configuration | YAML parsed, shell syntax and git diff whitespace checked |
| Android16 emulator CI | Configured; not run in this session |
| Android17 / One UI9 / Tasker | **Not run**; all22 hardware scenarios remain unverified |
| Dependency advisory scan | Public-PR dependency review configured; not run here |

An initial parallel development build passed51 JVM tests but lint crashed inside Kotlin FIR / `UElementAsPsiDetector`. A subsequent clean serialized build with frozen sources completed without disabling that detector. This was a tooling failure, not a hidden pass; its resolved run is superseded by the clean/final validation above. No assertion, warning category, or test was disabled to obtain passing checks.

## What the tests establish

- **Policy23:** safe initial state; valid mode changes/idempotency; strict FORCE_REAL until explicit returnAUTO; default monotonic120-second delay; early/stale deadlines; visible confirmation including notification-denied fallback; duplicate/out-of-order events; reconnection/unknown cancellation; mode precedence; process/reboot recovery; invalid delays and rapid start/stop sequences.
- **Authorization15:** disabled integration, API version, missing/wrong/oversized tokens, command scope, expired/future/overflow timestamps, replay boundaries, exact capability comparison and concurrent independent authorization decisions. Actual watermark serialization is a main-looper controller responsibility; a pure concurrent verifier test does not establish Android scheduling.
- **Engine16:** missing/invalid point/metadata, partial setup rollback, injection failure, cleanup failure/retry and durable journal, unowned-provider protection, rapid cycles, polar/antimeridian drift and invalid restart cleanup.
- **Tile6:** every mode's presentation/safe action, unlock requirement, pending state, injection versus selected policy and uncertainty. These are projection tests, not SystemUI/OneUI execution tests.
- **Instrumentation7 (compiled only):** merged manifest exposure/tile permission, backups/least privilege, real private-preference migration/purge, reload recovery, capability revocation/rotation and actual ordered receiver rejection. They do not execute cross-UID exploits, provider injection or FGS admission.

Source inspection separately confirmed one main-thread controller, private/nonsticky service, no restart loop, generation checks, a conservative provider journal, sanitized status, and preserved Android mock flags. Storage write failures invalidate pending starts and attempt cleanup; Android disk-full/IO fault injection was not executed. Foreground-service rejection handling exists, but actual Android17 admission is not proven by a unit test.

## Warnings and limitations

Lint warnings are retained visibly: target/compile SDK37 migration is deferred pending platform validation, MapLibre13.7.1 is newer than pinned13.6.1, the intentional exported capability receiver has no signature-only permission, API31 constants are inlined on the API29 compatibility branch, and remaining KTX/resource/icon/style suggestions exist. The dedicated developer app's single `MockLocation` manifest suppression is narrowly documented because release simulation intentionally requires the capability; it is not a global lint suppression.

Gradle's wrapper checksum remains pinned. `gradle/verification-metadata.xml` was bootstrapped from artifacts resolved through TLS-protected Google/Maven/Gradle repositories, then enforced in a separate strict run. This detects changes to the reviewed artifact set; it is not independent provenance, a signature verification program, a vulnerability scan or proof a dependency is safe. No existing package-signature/TLS verification was disabled. Future dependency/platform/OS artifact additions require trusted hash review; Linux x86_64 is the validated host. Debug keystores and archive timestamps mean byte-identical APK reproducibility was not established.

No PID, open port, compiled instrumentation APK, pure fake provider or tile projection is presented as hardware behavior. Samsung scheduling, lock-screen tiles, Tasker delivery, force-stop/reboot effects, competing/legacy providers, cleanup and fresh genuine-location reacquisition require the matrix in `docs/SAMSUNG_VALIDATION.md`.

## Reproduce device tests

Use a disposable API36 emulator or test device and run `./gradlew :app:connectedDebugAndroidTest`. Inspect current-run reports in `app/build/reports/androidTests/` and `app/build/outputs/androidTest-results/`; do not reuse old output as evidence. For Samsung Android17, follow the manual matrix and record OS/build, app commit/signing identity, settings, timestamps and sanitized results. Never include automation tokens or exact real coordinates in shared reports.

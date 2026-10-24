# Validation evidence

Current work targets versionCode 4 / 0.3.2-dev on `harden/android17-automation`. Exact inspected checkpoints and binary identity belong in [docs/VALIDATION.json](docs/VALIDATION.json). A documentation commit never retroactively changes a recorded APK's source SHA.

## Inspected results before this continuation

At source `6fd92d28706e453646bd03e32a420e673ad89e64`, [push Android](https://github.com/abhi-k9/MockGPS/actions/runs/40031986917) and [PR Android](https://github.com/abhi-k9/MockGPS/actions/runs/40031991045) both concluded **failure**. The push build job passed; emulator instrumentation failed in `unknownStopsRealManualInjectionAndCancelsAnAsynchronousStart`, with an application frame at `ControlCenter.begin:153`. The earlier diagnostic run was canceled and is not a pass. Diagnostic source5963fd0 reproduced android.app.RemoteServiceException$ForegroundServiceDidNotStartInTimeException. Android16 crashes cancellation of a startForegroundService request before promotion. The focused Activity now uses normal startService admission; the service retains stale-generation rejection and location foreground promotion before providers. Do not infer device success from passing compilation or build jobs.

Previous strict local full validation recorded 79 JVM tests, zero failures/errors/skips and lint zero errors/16 warnings. Hosted build reported 79 JVM tests and 20 lint warnings. The historical 67-JVM/11-emulator checkpoint predates the manual-session safety correction and is not the revised candidate. This continuation independently passed strict full local validation (79 tests, zero failures/errors/skips; lint0 errors/16 warnings) before the lifecycle correction. Full revised strict validation passed on clean source `89e01d191a8a72c4d749f17846899731c7ec514f`, including the sequential isolated-cache rerun:79 JVM tests, zero failures/errors/skips; lint0 errors/16 warnings; debug, unsigned release and instrumentation APKs built. The first isolated-cache attempt overlapped a second clean build and lost generated dex input; it is not a cold-build pass. The subsequent full build ran sequentially and passed all133 tasks with the isolated user/project cache. No dependency verification failure occurred in that initial generated-output collision.

## Commands and acceptance

Use Java21, the checksum-pinned Gradle9.6 wrapper, AGP9.2.1, official command-line tools19.0 (archive13114758), compile/target36, min29 and build-tools36.0.0. `scripts/setup-android-sdk.sh` provisions these SDK packages; add `--with-emulator` only where KVM is available.

```sh
scripts/android-build.sh --project-cache-dir=/absolute/fresh-project-cache --no-watch-fs
./gradlew -I scripts/dependency-inventory.init.gradle exportReviewedDependencyInventory \
  -Dmockgps.inventory=/absolute/inventory.json --dependency-verification=strict
python3 scripts/scan-dependency-advisories.py /absolute/inventory.json /absolute/advisories.json
scripts/run-emulator-tests.sh
```

The build script cleans and builds debug, unsigned release and instrumentation APKs, runs JVM tests and lint, with strict verification. An isolated Gradle user/project cache verifies cold resolution independently. A signed debug APK is installable for development; unsigned release is not an installable signed release.

This workspace has no `/dev/kvm`; no local device pass is claimed. Hosted instrumentation must preserve Gradle's failure status and require fresh XML, at least 15 tests, zero failures/errors/skips, and all four VehicleSafetyInstrumentationTest method names. Failure annotations expose only test/class/exception type and application stack frames, never exception messages, token extras or coordinates.

## Regression coverage

The 79 JVM tests include authorization/schema/freshness/independent replay sequence, reducer policy/generations, engine failure/ownership recovery, tile projection and 12 vehicle-safety regressions. The manual CONNECTED regression failed before the reducer correction with “CONNECTED must stop manual simulation,” then passed after it.

Four real-provider device tests cover authenticated CONNECTED stopping a manual FGS, UNKNOWN during injection and asynchronous start, stale callbacks/start generations, denied mock-AppOp removal and retry, pending confirmation cancellation and FORCE_REAL via an actual SystemUI tile click. They provision mock authorization only on disposable ranchu/goldfish emulators and clean up afterward. Shell setup proves no Tasker background-start exemption. Existing device coverage includes packaged manifest, raw ordered IPC/API2 types, storage/migration, credential epochs, legacy uncertainty and native tile binding/clicks.

## Lint, dependencies and hardware limits

The 16 local warnings retain nine explicit preference-commit checks, three guarded/inlined API constants, target/SDK and dependency update recommendations, and the intentionally exported capability-protected Tasker receiver. Four additional hosted warnings were online version recommendations. No blanket suppression, permissive dependency verification, skipped assertion or weakened security threshold is used.

The recovered post-fix reviewed Maven scan covers 174 coordinates, zero high/critical matches and one medium Kotlin build-cache advisory. Cache/incremental disabling mitigates exposure; it does not patch that version. Raw before/after evidence and reviewed artifact hashes are in [dependency review](docs/DEPENDENCY_REVIEW.md). The GitHub dependency-review check remains failed because repository feature/permission support is unavailable; owner action is required. Bounded Maven scanning does not cover native/shaded/unknown dependencies or firmware.

All 24 Samsung matrix cases remain **NOT RUN**. API36 emulator success does not prove OneUI9/Android17, real Tasker/Bluetooth delivery, secure-lock behavior or fresh genuine-location recovery. Missing delivery can leave active simulation until an event, failure or local stop. Successful API cleanup never substitutes for a fresh nonmock fix in an independent client.

## Successful revised checkpoint

Source `89e01d191a8a72c4d749f17846899731c7ec514f` passed [push Android](https://github.com/abhi-k9/MockGPS/actions/runs/40057430820) and [PR Android](https://github.com/abhi-k9/MockGPS/actions/runs/40057434383). Both device jobs reported15 tests, zero failures/errors/skips. Their success parser requires all four vehicle-safety method names; real-provider and SystemUI coverage therefore executed. Both build jobs passed79 JVM tests; hosted lint0 errors/20 warnings. Public job notices are preserved under docs/evidence/candidate-89e01d1.

The [signed CI development artifact](https://github.com/abhi-k9/MockGPS/actions/runs/40057430820/artifacts/12546211907) contains app-debug.apk and build identity/signature reports. CI verified installed package dev.abhik.mockgps and exact version4/0.3.2-dev. See the CI identity for APK SHA256/public signer; local identity is explicitly separate. Direct additional download verification remains blocked by productionresultssa4.blob.core.windows.net, so no independent downloaded-CI-APK hash is claimed. The local APK was independently signature/package/version/hash verified.

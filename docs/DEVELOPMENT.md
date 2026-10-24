# Fast local feedback and release gates

Use FAST for an edit. CHECK is the next milestone; FULL and RELEASE are deliberate expensive gates. All Gradle paths retain strict dependency verification. Nothing here installs SDKs, launches Actions, or automatically sends credentials.

| Level | Command | Work performed |
| --- | --- | --- |
| FAST, default | `scripts/dev.sh` | Fresh `*PolicyTest*` JVM results plus prerequisite compilation; no clean/lint/release/device |
| FAST, affected tests | `scripts/dev.sh fast '*ObservationOrderingTest*' '*CommandAuthTest*'` | Only named JVM classes/methods |
| Compile | `scripts/dev.sh compile` | Debug Kotlin prerequisites; no tests or APK |
| Debug UI | `scripts/dev.sh build` | Debug APK, retaining existing outputs |
| Source/Tasker/CI | `scripts/dev.sh source`, `scripts/dev.sh tasker`, `scripts/dev.sh ci` | Relevant offline schema/model/script checks; no Gradle |
| CHECK | `scripts/dev.sh check` | All JVM tests, debug assembly, source/model/CI checks; optional JVM selectors narrow tests |
| Targeted device | See below | One actual instrumentation method/class on an already running disposable emulator |
| FULL | `scripts/dev.sh full` | Isolated Gradle home, clean debug/unsigned release, all JVM, lint, instrumentation APK, history secret scan, advisory scan, cold API36 and API37 tests, build identity |
| RELEASE | `VALIDATION_RUN_ID=RUN_ID scripts/dev.sh release` | Read-only exact-commit/GitHub gate preflight; never silently signs or publishes |

For a meaningful Android change, `scripts/check-android.sh` additionally runs lint and builds the device-test APK. FULL requires a committed clean source tree (including no untracked source) and is required before a signed replacement candidate. A blocked FULL is not a successful release check. `scripts/android-build.sh` remains the clean **build portion** of FULL, not the editing command. A full release also needs the physical safety protocol and verified assets.

Selectors are quoted Gradle JVM patterns, e.g. `'*PolicyTest.sameMillisecondReconnectionCancelsPendingStart'`. A nonexistent selector fails. `--rerun` is Gradle9.6's per-task option: only Test reruns, its compilation dependencies remain eligible for up-to-date checks. Reports are deleted before the run and parsed afterward; empty, failing, skipped or unmatched results cannot pass. Never use `--rerun-tasks` for ordinary edits. Device selection uses AndroidJUnitRunner's `class` argument, not Gradle JVM `--tests`.

## One-time Linux / CachyOS setup

Install JDK21 (full JDK), Python3, Node, Git, Android command-line/platform tools and the official emulator. On CachyOS/Arch the host tools normally come from `sudo pacman -S jdk21-openjdk python nodejs git android-tools`; obtain the Android SDK using the pinned project setup script or Android Studio, not an unverified third-party tools archive. Grant your development user access to `/dev/kvm` for emulation. FULL additionally requires checksum-verified gitleaks and GitHub CLI authentication for the public Maven advisory queries.

```bash
export JAVA_HOME=/usr/lib/jvm/java-21-openjdk
export ANDROID_HOME="$HOME/Android/Sdk"
export ANDROID_USER_HOME="$HOME/.android"
export GRADLE_USER_HOME="$HOME/.gradle-mockgps-trusted"
export PATH="$JAVA_HOME/bin:$ANDROID_HOME/platform-tools:$PATH"
# Once, outside the edit loop; installs API37.0/build-tools36.0.0.
scripts/setup-android-sdk.sh "$ANDROID_HOME"
# Only after dependencies are locally available:
scripts/dev.sh fast '*PolicyTest*' --offline
scripts/dev.sh build --offline --workers 2
```

Adjust paths for your distribution. `ANDROID_USER_HOME` matters in containers with a read-only login directory; do not repurpose HOME. Keep the trusted Gradle home private to your user and this reviewed checkout. Never share it with untrusted PR checkouts or restore downloaded task/configuration/Kotlin caches into it. SDK/dependency downloads are reused locally; verification still applies. FAST never clears the Gradle home or stops the daemon. `--workers N` / `MOCKGPS_DEV_WORKERS` are selectable; default2 fits the measured two-CPU quota. Avoid running several builds concurrently. Commands have bounded subprocess timeouts (`--timeout`, default360s).

## Warm device testing

Provision a test image and create a **dedicated** AVD once. Boot that AVD separately and keep it running between cases. FAST/CHECK never boots, recreates or wipes one. Current provider tests deliberately reject physical hardware because they change providers, AppOps, credentials and preferences. Use the physical Samsung for the manual protocol, never these destructive fixtures on a daily phone.

```bash
adb devices
scripts/dev.sh device --disposable --serial emulator-5554 \
  --test 'dev.burixon.mockgps.VehicleSafetyInstrumentationTest#newerSequenceCannotMakeStaleDisconnectionOverrideConnected'
```

The wrapper checks an unlocked emulator before compilation, reuses debug/test build outputs, installs the two APKs, force-stops only the test app and runs one explicit Android class/method. It does not clear unrelated device data. Fixtures provision/reset their own state and stop/revoke afterward. It checks Android's actual summary because adb can return zero on test failure. It rejects zero/ignored cases and labels results targeted smoke. Different debug signers fail install; do not automatically uninstall a working app. No warm emulator/device was attached in this environment, so actual warm-device performance is **NOT MEASURED**.

For a dedicated development phone, build/install debug manually only after safe cleanup and signer comparison. Use `adb -s SERIAL install -r app/build/outputs/apk/debug/app-debug.apk` when signatures match. Daily-phone tests follow [SAMSUNG_VALIDATION.md](SAMSUNG_VALIDATION.md), preserving private tokens/counters. Stop, clean up and verify a fresh independent nonmock fix before changing mock-app selection or replacing a differently signed app.

## Measurements and causes

Measured on 2026-10-24 UTC, Debian13/Linux6.18.55, Xeon Platinum8573C, three visible logical CPUs with a **2 CPU cgroup quota**, 8GiB memory limit, no swap and **no /dev/kvm/device**. JDK21, Gradle9.6.0, AGP9.2.1, API37.0, strict verification, all cache/incremental mitigations unchanged. Each table entry is one execution, except the two named targeted repeats. Dependencies and the existing trusted daemon were warm; `clean` made baseline task outputs cold. This is not a cold machine/network benchmark. Historical initially isolated build log reports6m34s, including setup/resolution; it is not directly comparable to the new offline runs.

| Measured command / phase | Wall time | Meaning |
| --- | ---: | --- |
| Existing clean all-purpose command | 97.213s | 133 tasks executed: debug/release/JVM/lint/test APK |
| Warm Gradle `help` | 2.512s | Configuration invocation; not compilation |
| Warm compile check | 3.140s | Existing outputs up to date |
| Selected PolicyTest, first / repeat | 6.554s / 4.401s | Fresh selected tests, no clean |
| All82 unchanged JVM tests | 4.145s | Fresh test execution, compiled outputs reused |
| Warm lint / debug / test APK | 2.853s / 3.310s / 4.595s | Up-to-date checks, not forced new analysis/packaging |
| Real Kotlin/auth/policy/UI edit, affected core tests | 15.423s | Compilation and69 tests; correctly FAILED3 older contract expectations |
| Corrected test fixtures and69 affected core tests | 3.879s | PASS with test recompilation; not a claim of15.4s successful clean release |

Final import/fixture-thread corrections required only3 ordering tests and changed Android-test packaging, which passed in6.954s. The broader85-JVM CHECK passed in23.248s with lint0errors/13warnings; no unrelated full rerun followed.

The baseline profile attributes **45.530s** to debug lint analysis,14.533s to debug Kotlin compilation,5.285s to release Kotlin compilation and5.869s to JVM tests. Project configuration was0.395s and dependency resolution0.431s. Task durations overlap, so do not add them to wall time. The largest avoidable cost was requesting clean/all-variant/lint work for every edit. Cache-disabled Gradle still supports ordinary local output up-to-date checks. FULL retains cold verification; FAST avoids asking for unrelated work.

The first benchmark attempts exited before compilation: sandbox denied Gradle local sockets, then Android tooling tried a read-only user directory. These failures are excluded from successful timings and resolved by allowed local socket execution / writable ANDROID_USER_HOME. No reasoning-time metric is exposed, and none is claimed. Grouped inspection and relevant tests reduce repeated tool work; avoid retrying an unchanged failure.

Saved CI run40068105244 measured build166s (SDK11s; clean build137s), API36 job350s (SDK43s; compile/boot/test step298s), API37 job245s (SDK55s; compile/guest-failure181s). No isolated boot or individual-device-test timer was recorded, so those phases are **NOT MEASURED** separately. API37 crashed before app tests: input/display Java frames and nativeSIGABRT; missing activity service/unlock failure are symptoms. Exact guest root cause is unconfirmed, and this environment lacks KVM to repair/reproduce it locally.

Two later matching-failure plan runs used12s and10s and launched zero expensive jobs. Relative to repeating the measured three heavy jobs (761s combined), each avoided approximately12.7 runner-minutes of comparable work. That is a comparison with observed historical work, not an invoice or guaranteed future saving. No pricing/CPU billing multiplier is assumed. Current ordinary PRs omit release assembly and retain the unresolved API37 check as failed without booting that unchanged guest. Only a reviewed FULL retry launches API37 again. Disposable artifacts expire in7days; release assets/committed receipts remain.

## Security decision: preserve the mitigations

[GHSA-r937-wjx7-w2jp / CVE-2026-53914](https://github.com/advisories/GHSA-r937-wjx7-w2jp) affects kotlin-gradle-plugin before2.4.20-Beta1; this AGP bundles2.2.10. The [upstream fix](https://github.com/JetBrains/kotlin/commit/bf51df665b458fda7c3eaf436c4d88dc119d7ec6) changes **KAPT incremental cache** `java-cache.bin` / `apt-cache.bin` from unrestricted ObjectInputStream to class-filtered deserialization. Exploitation requires poisoned build metadata to be consumed; source/Gradle plugins themselves already execute code. No KAPT plugin or annotation processors are configured here, but that finding is not a blanket clearance for the entire bundled toolchain or future plugin changes. We retain the conservative existing settings rather than introduce an unreviewed exception or beta compiler upgrade.

| Mechanism | Decision / trust boundary |
| --- | --- |
| Kotlin incremental compilation | Remains false; no local opt-in introduced while bundled toolchain is unpatched |
| Gradle task-output cache | Off locally and in CI; do not restore serialized task metadata |
| Configuration cache | Off; distinct configuration/object graph serialization |
| Dependency download cache | Trusted local reuse permitted with strict checked-in checksums; not task-output reuse |
| Remote/shared writable caches | Not configured; hosted PR homes isolated and setup-gradle cache disabled |
| Trusted local outputs / daemon | Retain existing compiled files and Gradle task up-to-date checks; never import from an untrusted checkout/cache |

FAST explicitly enforces the three disabled settings as well as strict verification, even when user-level properties differ. FULL uses a fresh Gradle home and clean outputs. The acceleration comes from smaller task scope, warm daemon and unchanged local intermediates—not claiming the advisory patched. Revisit with a supported patched AGP/compiler release and repeat the security review before enabling incremental metadata.

# CI repair and cold-resolution evidence

Baseline run: https://github.com/abhi-k9/MockGPS/actions/runs/39994165863 at `0cfeb55f29c594bdef9c01254a0958add1462f60`. GitHub's API confirms both jobs failed. Build job120553636537 failed its SDK step with exit127; instrumentation job120553636790 failed before device tests. The user-provided SDK/verification errors were checked against configuration and a fresh local Gradle resolution, not assumed to describe newer code.

## SDK initialization

The old workflow called bare `sdkmanager` without provisioning command-line tools. It relied on a runner-image PATH/tool that was absent. Both jobs now call `scripts/setup-android-sdk.sh` into an empty runner-temporary SDK root. The script downloads Google's official commandline-tools19.0 archive13114758 with pinned SHA256 `7ec965280a073311c339e571cd5de778b9975026cfcbe79f2b1cdcb1e15317ee`. The archive matched Google's repository2-1.xml SHA1 `5fdcc763663eefb86a5b8879697aa6088b041e70` before its SHA256 was recorded. TLS, archive and SDK package checksum verification remain enabled.

ANDROID_HOME/ANDROID_SDK_ROOT and executable paths are exported explicitly through GitHub's environment/path files. Java21 is installed explicitly. SDK36/build-tools36.0.0/platform-tools are installed, with official emulator/API36 google_apis x86_64 image in the instrumentation job. Platform-tools/emulator/image revisions use Google's stable channel; their actual installed revisions are logged rather than represented as byte-pinned releases. SDK license responses are bounded, not an unbounded yes pipeline. The bootstrap completed in an empty local SDK root, including SDK package installation, and reports command-line tools19.0/platform36 revision2/buildtools36.0.0/platform-tools37.0.1 in that run.

The emulator job uses the official emulator and avdmanager directly. It checks KVM access, bounds boot waiting, tests real connected instrumentation, verifies fresh nonzero result XML, preserves the actual Gradle exit status and traps cleanup of its own emulator. It does not equate boot or compilation with passing tests.

## Strict dependency verification

A newly created GRADLE_USER_HOME and project cache reproduced the **same two** classpath failures before any metadata repair. They were absent entries, not hash mismatches. Gradle9.6.0, AGP9.2.1, Java21, repository declarations and selected dependency versions were unchanged. Thus a dependency/repository upgrade is not the repair.

| Artifact | Reviewed SHA256 | Trust checks |
| --- | --- | --- |
| `com.google.guava:guava-parent:33.3.1-jre` POM | `55441db27e8869dfefe053059bdf478bdc7e95585642bf391f0023345fd56287` | Exact XML coordinates/packaging; Maven Central published SHA1; identical canonical repo.maven.apache.org/repo1.maven.org TLS downloads |
| `org.junit:junit-bom:5.11.0-M2` Gradle module | `86477abcf490d6ca059aa9973cb108d22a506f49d1a5569bb32cc6cbf43c2cce` | Exact module coordinates; Maven Central published SHA256; existing POM was already pinned |
| `org.jetbrains.kotlinx:kotlinx-coroutines-bom:1.8.0` POM | `1239e9dbe1397cd5971342956b2511bc3ace7b641842e4372a088dcfa8b9ad55` | Actual reviewed hash is recorded in verification-metadata.xml; Maven Central published SHA256 checked |

The third parent/BOM artifact was discovered only after fixing classpath verification and continuing the cold complete build. Its exact hash in the table must match the committed metadata (the final evidence generation checks this). Guava33.3.1-jre is already selected by Android tools32.2.1; commons-parent71/72 import the JUnit BOM. Gradle may need parent POMs and published Gradle module metadata while resolving the model even when no corresponding runtime JAR changes. Kotlin build-tools classpath similarly needs the coroutines BOM. The initial metadata bootstrap was incomplete for a cold model-resolution path; a warm-cache pass did not prove cold reproducibility.

Only reviewed artifact entries were added. No global trust pattern, permissive verification mode, unrelated dependency upgrade or altered expected hash was used. A strict configuration rerun and full cold-cache build progressed after the repairs. Subsequent CI run URLs/outcomes are recorded in TESTING.md and VALIDATION.json; local success is not a CI pass. Published repository checksums are integrity evidence rooted in those repositories, not independent publisher-signature verification.

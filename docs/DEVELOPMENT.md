# Development

## Toolchain

- JDK 21 (including `javac`)
- Android SDK platform 36 and build tools 36.0.0
- Bash, Python 3, curl, and unzip for the SDK bootstrap
- Node.js only for the optional Tasker dispatcher model tests

Open the project in Android Studio, or use the pinned Gradle wrapper from the repository root.

```sh
export JAVA_HOME=/path/to/jdk-21
export ANDROID_HOME=/path/to/android-sdk
scripts/setup-android-sdk.sh "$ANDROID_HOME"
scripts/android-build.sh
```

The SDK bootstrap installs build prerequisites. It does not provision or boot an emulator. Installation accepts the Android SDK licenses; review Google's terms before running it.

## Useful commands

```sh
# Required local/CI checks: debug APK, unit tests, Android lint
scripts/check-android.sh

# Focused unit suite during development
./gradlew :app:testDebugUnitTest --tests '*PolicyTest*'

# Unsigned release APK
./gradlew :app:assembleRelease

# Optional Tasker dispatcher checks
node scripts/test-tasker-dispatch.cjs

# Optional device tests, using an already connected device
./gradlew :app:connectedDebugAndroidTest
```

Do not use an emulator as a substitute for testing OEM automation on a physical phone. Device tests are optional and are not part of CI.

JVM results are in `app/build/test-results/testDebugUnitTest/`; lint reports are in `app/build/reports/`. A compiled instrumentation APK does not mean device tests ran. Preserve existing tests for policy, authentication, and provider cleanup when changing those boundaries.

## Dependencies

Versions live in `gradle/libs.versions.toml`; checksums live in `gradle/verification-metadata.xml`. Normal builds use strict dependency verification. For an intentional dependency update, obtain artifacts from the configured Google/Maven Central repositories, inspect Gradle's verification report, independently verify new checksums, and review the metadata diff before committing it. Do not turn verification off or accept an unknown checksum merely to make a build pass.

Keep generated APKs, local SDK paths, keystores, and configured automation exports out of Git. Debug APKs use a development key; release signing is documented in [RELEASING.md](RELEASING.md).

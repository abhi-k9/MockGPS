#!/usr/bin/env bash
# Reproducible developer/CI validation. Install Java21 and SDK packages first.
set -euo pipefail

project_root=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)
cd "$project_root"

if [[ -z "${JAVA_HOME:-}" || ! -x "$JAVA_HOME/bin/javac" ]]; then
  echo 'Set JAVA_HOME to a Java 21 JDK (a JRE is insufficient).' >&2
  exit 2
fi
java_version=$("$JAVA_HOME/bin/javac" -version 2>&1)
if [[ ! "$java_version" =~ javac\ 21([.[:space:]]|$) ]]; then
  echo 'This Gradle daemon requires Java 21. Set JAVA_HOME to a Java 21 JDK.' >&2
  exit 2
fi
android_sdk_path=${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}
if [[ -z "$android_sdk_path" || ! -f "$android_sdk_path/platforms/android-36/android.jar" || ! -d "$android_sdk_path/build-tools/36.0.0" ]]; then
  echo 'Set ANDROID_HOME to an Android SDK containing platforms;android-36 and build-tools;36.0.0.' >&2
  exit 2
fi
export ANDROID_HOME="$android_sdk_path"
export PATH="$JAVA_HOME/bin:$PATH"
# Gradle Wrapper validates its pinned distributionSha256Sum on first download.
# Pass additional standard Gradle options (e.g. --offline or --project-cache-dir=...).
./gradlew clean :app:assembleDebug :app:assembleRelease :app:testDebugUnitTest \
  :app:lintDebug :app:assembleDebugAndroidTest --max-workers=2 --console=plain "$@"

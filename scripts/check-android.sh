#!/usr/bin/env bash
# CHECK on a fresh CI checkout: required JVM/debug/lint checks, no release packaging.
set -euo pipefail
cd "$(dirname -- "${BASH_SOURCE[0]}")/.."
./gradlew :app:assembleDebug :app:assembleDebugAndroidTest :app:testDebugUnitTest \
  :app:lintDebug --dependency-verification=strict --no-build-cache --no-configuration-cache \
  -Pkotlin.incremental=false --max-workers=2 --console=plain "$@"

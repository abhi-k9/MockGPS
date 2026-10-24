#!/usr/bin/env bash
# Release milestone, explicitly expensive. Provision official SDK/images beforehand.
set -euo pipefail
cd "$(dirname -- "${BASH_SOURCE[0]}")/.."
if [[ -n $(git status --porcelain --untracked-files=normal) ]]; then
  echo 'Commit source changes before reproducible FULL; FAST/CHECK accept edits.' >&2
  exit 2
fi
: "${ANDROID_HOME:?Set an already provisioned Android SDK}"
: "${ANDROID_AVD_HOME:?Use a dedicated disposable AVD directory}"
command -v gitleaks >/dev/null || { echo 'Install checksum-verified gitleaks before FULL.' >&2; exit 2; }
test -r /dev/kvm && test -w /dev/kvm || { echo 'FULL requires KVM; no device result claimed.' >&2; exit 2; }
for api in 36 37.0; do
  test -f "$ANDROID_HOME/system-images/android-$api/google_apis/x86_64/package.xml" || { echo "Provision API $api image once before FULL." >&2; exit 2; }
done
# FULL uses isolated metadata; local FAST/CHECK keep the developer's trusted home.
mkdir -p work/full
export GRADLE_USER_HOME
GRADLE_USER_HOME=$(mktemp -d "$PWD/work/full/gradle-XXXXXX")
trap './gradlew --stop >/dev/null 2>&1 || true' EXIT
scripts/check-source.sh
gitleaks git --redact --no-banner
scripts/android-build.sh
mkdir -p work/full
./gradlew -I scripts/dependency-inventory.init.gradle exportReviewedDependencyInventory \
  -Dmockgps.inventory="$PWD/work/full/inventory.json" --dependency-verification=strict --max-workers=2 --console=plain
python3 scripts/scan-dependency-advisories.py work/full/inventory.json work/full/advisories.json
for api in 36 37.0; do
  MOCKGPS_TEST_API="$api" scripts/run-emulator-tests.sh
  mkdir -p "work/full/API$api"
  cp -a app/build/outputs/androidTest-results app/build/emulator-diagnostics "work/full/API$api/"
done
FULL_ANDROID=true scripts/record-build-identity.sh
echo 'Local FULL passed. Exact-SHA hosted validation, physical safety evidence and release preflight remain separate.'

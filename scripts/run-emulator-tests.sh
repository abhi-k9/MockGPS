#!/usr/bin/env bash
# Boot the official SDK emulator, wait for readiness, then run actual device tests.
set -euo pipefail
project_root=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)
cd "$project_root"
: "${ANDROID_HOME:?Provision the SDK first with scripts/setup-android-sdk.sh --with-emulator}"
: "${ANDROID_AVD_HOME:?Set ANDROID_AVD_HOME to a writable test-only AVD directory}"
if [[ ! -r /dev/kvm || ! -w /dev/kvm ]]; then
  echo 'Hardware-accelerated emulator tests require readable/writable /dev/kvm; no device result is claimed.' >&2
  exit 2
fi
export PATH="$ANDROID_HOME/cmdline-tools/19.0/bin:$ANDROID_HOME/platform-tools:$ANDROID_HOME/emulator:$PATH"
mkdir -p "$ANDROID_AVD_HOME" app/build/emulator-diagnostics
avd_name=MockGPS_API36
printf 'no\n' | avdmanager create avd --force --name "$avd_name" \
  --package 'system-images;android-36;google_apis;x86_64' --device pixel_6
emulator -avd "$avd_name" -port 5554 -no-window -no-audio -no-boot-anim \
  -no-snapshot -wipe-data -gpu swiftshader_indirect -accel on \
  > app/build/emulator-diagnostics/emulator.log 2>&1 &
emulator_pid=$!
cleanup() {
  adb -s emulator-5554 emu kill >/dev/null 2>&1 || true
  kill "$emulator_pid" >/dev/null 2>&1 || true
}
trap cleanup EXIT
export ANDROID_SERIAL=emulator-5554
booted=false
for ((attempt=0; attempt<120; attempt++)); do
  if ! kill -0 "$emulator_pid" 2>/dev/null; then
    echo 'Emulator exited before readiness. Inspect emulator.log.' >&2
    exit 1
  fi
  if [[ $(adb -s "$ANDROID_SERIAL" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r') == 1 ]]; then
    booted=true
    break
  fi
  sleep 2
done
if [[ "$booted" != true ]]; then
  echo 'Emulator did not complete boot within 240 seconds.' >&2
  exit 1
fi
adb -s "$ANDROID_SERIAL" shell input keyevent 82
for scale in window_animation_scale transition_animation_scale animator_duration_scale; do
  adb -s "$ANDROID_SERIAL" shell settings put global "$scale" 0
done
# Preserve Gradle/instrumentation's actual exit status. Compilation alone is insufficient.
test_start=$(date +%s)
export MOCKGPS_DEVICE_TEST_START="$test_start"
if ./gradlew :app:connectedDebugAndroidTest --dependency-verification=strict --max-workers=2 --console=plain "$@"; then
  :
else
  device_status=$?
  # Public API diagnostics omit exception messages, stack traces, extras and locations.
  python3 - <<'PYFAIL'
from pathlib import Path
import json,os,re,xml.etree.ElementTree as E
failed=[]
for p in Path('app/build/outputs/androidTest-results/connected').rglob('TEST-*.xml'):
 if p.stat().st_mtime<int(os.environ['MOCKGPS_DEVICE_TEST_START']): continue
 for test in E.parse(p).getroot().iter('testcase'):
  for kind in ('failure','error'):
   for failure in test.findall(kind):
    frames=re.findall(r'\bat (dev\.burixon\.mockgps[\w.$]+\([\w.$]+:\d+\))',failure.text or '')[:3]
    failed.append({'class':test.get('classname'),'test':test.get('name'),'type':failure.get('type'),'app_frames':frames})
payload=json.dumps(failed).replace('%','%25').replace('\r','%0D').replace('\n','%0A')
print(f'::error title=Device test failures (names/types only)::{payload}')
PYFAIL
  exit "$device_status"
fi

python3 - <<'PYTEST'
from pathlib import Path
import os,xml.etree.ElementTree as E
files=list(Path('app/build/outputs/androidTest-results/connected').rglob('TEST-*.xml'))
assert files,'No instrumentation result XML; compilation or a booted emulator is not a test pass'
assert all(p.stat().st_mtime>=int(os.environ['MOCKGPS_DEVICE_TEST_START']) for p in files),'Stale instrumentation reports'
tests=failures=errors=skipped=0
for p in files:
 r=E.parse(p).getroot();tests+=int(r.get('tests',0));failures+=int(r.get('failures',0));errors+=int(r.get('errors',0));skipped+=int(r.get('skipped',0))
assert tests>=15 and failures==errors==skipped==0,(tests,failures,errors,skipped)
print(f'Actual emulator instrumentation: {tests} tests, {failures} failures, {errors} errors, {skipped} skipped')
if os.environ.get('GITHUB_ACTIONS')=='true':
 print(f'::notice title=Actual emulator test results::tests={tests} failures={failures} errors={errors} skipped={skipped}')
PYTEST

"""Canonical APK/runtime-test inputs; metadata, docs and Tasker samples have separate fast checks."""
import hashlib
import subprocess

PATHS = ['app', 'build.gradle.kts', 'settings.gradle.kts', 'gradle.properties', 'gradle',
         'gradlew', 'gradlew.bat', 'scripts/android-build.sh', 'scripts/setup-android-sdk.sh',
         'scripts/run-emulator-tests.sh']

def fingerprint(ref='HEAD'):
    tree = subprocess.check_output(['git','ls-tree','-r',ref,'--',*PATHS])
    return hashlib.sha256(b'mockgps-runtime-inputs-v1\n'+tree).hexdigest()

#!/usr/bin/env bash
# Linux x86_64 SDK bootstrap from Google's official, checksum-pinned archive.
set -euo pipefail

sdk_root=${1:-${ANDROID_HOME:-}}
with_emulator=${2:-}
test_api=${3:-36}
if [[ "$test_api" != 36 && "$test_api" != 37.0 ]]; then echo "Supported emulator APIs:36,37.0" >&2; exit 2; fi
if [[ -z "$sdk_root" || "$sdk_root" != /* || ( -n "$with_emulator" && "$with_emulator" != --with-emulator ) ]]; then
  echo 'Usage: scripts/setup-android-sdk.sh /absolute/sdk/path [--with-emulator] [36|37.0]' >&2
  exit 2
fi
if [[ $(uname -s) != Linux || $(uname -m) != x86_64 ]]; then
  echo 'This pinned SDK bootstrap supports Linux x86_64; install equivalent official tools on other hosts.' >&2
  exit 2
fi
if [[ -z "${JAVA_HOME:-}" || ! -x "$JAVA_HOME/bin/javac" || $("$JAVA_HOME/bin/javac" -version 2>&1) != *'javac 21'* ]]; then
  echo 'Set JAVA_HOME to a Java 21 JDK before provisioning the SDK.' >&2
  exit 2
fi
for executable in curl unzip sha256sum; do command -v "$executable" >/dev/null; done

# Google's repository2-1.xml lists this as cmdline-tools;19.0, SHA-1
# 5fdcc763663eefb86a5b8879697aa6088b041e70. The SHA-256 below was independently
# calculated from that exact official TLS download after matching its listed SHA-1.
# Deliberately avoid mutable cmdline-tools;latest and its newer CLI bootstrap.
tools_version=19.0
tools_archive=commandlinetools-linux-13114758_latest.zip
tools_sha256=7ec965280a073311c339e571cd5de778b9975026cfcbe79f2b1cdcb1e15317ee
tools_dir="$sdk_root/cmdline-tools/$tools_version"
mkdir -p "$sdk_root/cmdline-tools"
bootstrap_tmp=$(mktemp -d)
trap 'rm -rf -- "$bootstrap_tmp"' EXIT
if [[ ! -e "$tools_dir" ]]; then
  curl --fail --show-error --silent --location --retry 3 \
    "https://dl.google.com/android/repository/$tools_archive" -o "$bootstrap_tmp/tools.zip"
  printf '%s  %s\n' "$tools_sha256" "$bootstrap_tmp/tools.zip" | sha256sum --check --status
  unzip -q "$bootstrap_tmp/tools.zip" -d "$bootstrap_tmp/unpacked"
  mv "$bootstrap_tmp/unpacked/cmdline-tools" "$tools_dir"
fi
if [[ ! -x "$tools_dir/bin/sdkmanager" ]] || ! rg_fallback=$(sed -n 's/^Pkg.Revision=//p' "$tools_dir/source.properties") || [[ "$rg_fallback" != "$tools_version" ]]; then
  echo "Unexpected tools installation at $tools_dir; refusing to overwrite it." >&2
  exit 2
fi
export ANDROID_HOME="$sdk_root"
export ANDROID_SDK_ROOT="$sdk_root"
export ANDROID_USER_HOME="$sdk_root/.android-user"
export ANDROID_AVD_HOME="$sdk_root/.android-avd"
export PATH="$tools_dir/bin:$sdk_root/platform-tools:$sdk_root/emulator:$JAVA_HOME/bin:$PATH"
mkdir -p "$ANDROID_USER_HOME" "$ANDROID_AVD_HOME"

# A bounded input file accepts the SDK licenses without a yes/SIGPIPE pipeline.
# Acceptance belongs to the operator running this explicit provisioning command.
for ((answer=0; answer<40; answer++)); do printf 'y\n'; done > "$bootstrap_tmp/license-answers"
"$tools_dir/bin/sdkmanager" --sdk_root="$sdk_root" --licenses < "$bootstrap_tmp/license-answers"
packages=('platforms;android-36' 'build-tools;36.0.0' 'platform-tools')
if [[ "$with_emulator" == --with-emulator ]]; then
  packages+=('emulator' "system-images;android-${test_api};google_apis;x86_64")
fi
"$tools_dir/bin/sdkmanager" --sdk_root="$sdk_root" --channel=0 --install "${packages[@]}" < "$bootstrap_tmp/license-answers"
[[ -f "$sdk_root/platforms/android-36/android.jar" && -x "$sdk_root/build-tools/36.0.0/apksigner" && -x "$sdk_root/platform-tools/adb" ]]

# The same bootstrap works locally and in Actions; expose paths to later steps.
if [[ -n "${GITHUB_ENV:-}" && -n "${GITHUB_PATH:-}" ]]; then
  printf 'ANDROID_HOME=%s\nANDROID_SDK_ROOT=%s\nANDROID_USER_HOME=%s\nANDROID_AVD_HOME=%s\n' \
    "$ANDROID_HOME" "$ANDROID_SDK_ROOT" "$ANDROID_USER_HOME" "$ANDROID_AVD_HOME" >> "$GITHUB_ENV"
  printf '%s\n' "$tools_dir/bin" "$sdk_root/platform-tools" "$sdk_root/emulator" >> "$GITHUB_PATH"
fi
printf 'Provisioned SDK at %s with command-line tools %s\n' "$sdk_root" "$tools_version"
"$tools_dir/bin/sdkmanager" --version
for package_file in "$sdk_root/platforms/android-36/source.properties" "$sdk_root/build-tools/36.0.0/source.properties" "$sdk_root/platform-tools/source.properties"; do
  sed -n '/^Pkg.Desc=/p; /^Pkg.Revision=/p' "$package_file"
done

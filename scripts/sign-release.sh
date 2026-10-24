#!/usr/bin/env bash
# Sign an already-built release APK using an existing private key.
set -euo pipefail
umask 077
if [[ $# != 2 || ! -f "$1" || "$2" != /* || -e "$2" ]]; then
  echo 'Usage: sign-release.sh unsigned.apk /absolute/new/output/directory' >&2
  exit 2
fi
: "${ANDROID_HOME:?Set ANDROID_HOME to the Android SDK}"
: "${MOCKGPS_SIGNING_KEYSTORE_BASE64:?Set release keystore secret}"
: "${MOCKGPS_SIGNING_KEY_ALIAS:?Set release key alias}"
: "${MOCKGPS_SIGNING_KEY_PASSWORD:?Set release key password}"
: "${MOCKGPS_SIGNING_STORE_PASSWORD:?Set release store password}"
build_tools="$ANDROID_HOME/build-tools/36.0.0"
[[ -x "$build_tools/apksigner" && -x "$build_tools/zipalign" ]] || {
  echo 'Android build tools 36.0.0 are required.' >&2; exit 2;
}
version=$(sed -n 's/^[[:space:]]*versionName = "\([^"]*\)".*/\1/p' "$(dirname -- "${BASH_SOURCE[0]}")/../app/build.gradle.kts")
[[ "$version" =~ ^[0-9]+\.[0-9]+\.[0-9]+(-[0-9A-Za-z.-]+)?$ ]] || {
  echo 'Expected one semantic version in app/build.gradle.kts.' >&2; exit 2;
}
signing_tmp=$(mktemp -d)
trap 'rm -rf -- "$signing_tmp"' EXIT
printf '%s' "$MOCKGPS_SIGNING_KEYSTORE_BASE64" | base64 --decode > "$signing_tmp/release.keystore"
unset MOCKGPS_SIGNING_KEYSTORE_BASE64
[[ -s "$signing_tmp/release.keystore" ]] || { echo 'Release keystore is empty.' >&2; exit 2; }
mkdir -- "$signing_tmp/assets"
signed_apk="$signing_tmp/assets/MockGPS-$version.apk"
"$build_tools/zipalign" -P 16 -f 4 "$1" "$signing_tmp/aligned.apk"
"$build_tools/apksigner" sign --ks "$signing_tmp/release.keystore" \
  --ks-key-alias "$MOCKGPS_SIGNING_KEY_ALIAS" \
  --ks-pass env:MOCKGPS_SIGNING_STORE_PASSWORD \
  --key-pass env:MOCKGPS_SIGNING_KEY_PASSWORD \
  --out "$signed_apk" "$signing_tmp/aligned.apk"
"$build_tools/apksigner" verify --verbose --print-certs "$signed_apk"
"$build_tools/zipalign" -c -P 16 4 "$signed_apk"
(cd -- "$signing_tmp/assets" && sha256sum "MockGPS-$version.apk" > SHA256SUMS)
mkdir -p -- "$(dirname -- "$2")"
mkdir -- "$2"
mv -- "$signing_tmp/assets/"* "$2/"

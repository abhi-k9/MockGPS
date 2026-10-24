#!/usr/bin/env bash
# Owner-side only. Uses an already authorized persistent key; never generates one.
set -euo pipefail
if [[ $# != 4 ]]; then
  echo 'Usage: sign-candidate.sh unsigned.apk output.apk /secure/existing.keystore existing-alias' >&2
  exit 2
fi
: "${ANDROID_HOME:?Set an official Android SDK containing build-tools/36.0.0}"
unsigned_apk=$1
signed_apk=$2
keystore_path=$3
key_alias=$4
[[ -f "$unsigned_apk" && -f "$keystore_path" && ! -e "$signed_apk" ]] || {
  echo 'Input APK/key must exist; output must be new.' >&2; exit 2;
}
aligned_apk=$(mktemp "${TMPDIR:-/tmp}/mockgps-aligned-XXXXXX.apk")
trap 'rm -f "$aligned_apk"' EXIT
"$ANDROID_HOME/build-tools/36.0.0/zipalign" -P 16 -f 4 "$unsigned_apk" "$aligned_apk"
# Passwords are requested interactively by apksigner, never passed in arguments/logs.
"$ANDROID_HOME/build-tools/36.0.0/apksigner" sign --ks "$keystore_path" --ks-key-alias "$key_alias" \
  --out "$signed_apk" "$aligned_apk"
"$ANDROID_HOME/build-tools/36.0.0/apksigner" verify --verbose --print-certs "$signed_apk"
sha256sum "$signed_apk"
echo 'Record this new APK hash/public signer; verify it matches your authorized certificate before distribution.'

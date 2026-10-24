# Releases

Create a Git tag to build a signed APK and publish it through GitHub Releases.

## One-time setup

Add these repository **Actions secrets** in GitHub:

| Secret | Contents |
| --- | --- |
| `MOCKGPS_SIGNING_KEYSTORE_BASE64` | Base64-encoded release keystore |
| `MOCKGPS_SIGNING_KEY_ALIAS` | Existing signing key alias |
| `MOCKGPS_SIGNING_KEY_PASSWORD` | Signing key password |
| `MOCKGPS_SIGNING_STORE_PASSWORD` | Keystore password |

Keep the original keystore and passwords in a secure offline backup. Reuse the same signing certificate for in-place APK updates. Do not commit signing material or put secret values in workflow inputs, issues, or command arguments. Allow GitHub Actions to create releases; the workflow requests `contents: write` only for the release job. Restrict who can push version tags.

## Publish

1. Update `versionCode` and `versionName` in `app/build.gradle.kts`. Each update requires a greater version code.
2. Run the local checks, inspect the UI, and test any changed automation behavior on a physical device.
3. Commit and merge to the default branch.
4. Create and push `v<versionName>`, for example `v0.5.0`.

The release workflow checks that the tag matches the app version, runs JVM tests and lint, builds one unsigned release APK, aligns and signs it, verifies its signature and alignment, and publishes the APK plus `SHA256SUMS` using generated release notes. A hyphen in the version marks a prerelease. GitHub provides the tagged source archives alongside those assets.

The workflow fails when signing secrets are missing or the release already exists. It never generates a replacement signing key or silently falls back to debug signing. If a run fails, diagnose it before deleting an existing tag or release; published versions should remain immutable.

## Local signing

Build `:app:assembleRelease`, set the four signing environment variables securely, then run:

```sh
scripts/sign-release.sh app/build/outputs/apk/release/app-release-unsigned.apk /absolute/new/output/directory
```

The script writes a signed APK and checksums to a new output directory. It creates a private temporary keystore and removes it on exit. It never prints passwords or the encoded keystore. Signature verification proves the APK is internally valid; compare its public signing certificate with your established release certificate before distribution.

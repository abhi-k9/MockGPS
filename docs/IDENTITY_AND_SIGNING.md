# Fork identity, signing and installation

Review date: 2026-10-23. Decision: give the independently maintained fork the installed application ID **`dev.abhik.mockgps`**. Retain the existing Kotlin/Android resource namespace **`dev.burixon.mockgps`**. This is an installation/signing boundary decision, not a cosmetic source rename.

## Why a separate installed package is justified

Android identifies an installed application by its package/application ID and checks signing identity when an APK attempts to update that package. Keeping upstream's `dev.burixon.mockgps` would make an independently signed fork compete for the same installed package. An upstream APK and a CI debug APK normally have different signing certificates; `adb install -r` does not override that boundary and may fail with `INSTALL_FAILED_UPDATE_INCOMPATIBLE`. Reusing the upstream ID does not inherit the upstream signing key, its update trust or access to its private data.

A unique ID permits intentional side-by-side installation while keeping each app's preferences, automation credentials, permissions and component identity separate. It also prevents a future upstream update from being mistaken for an update to this fork. The Android Developer Options mock-app selection still chooses one application at a time; side-by-side installation is not permission to run competing mock providers.

## Identity matrix

| Concern | Fork decision |
| --- | --- |
| Installed package / `applicationId` | `dev.abhik.mockgps` |
| Kotlin source and generated resource namespace | `dev.burixon.mockgps` (unchanged) |
| Explicit receiver component | `dev.abhik.mockgps/dev.burixon.mockgps.MockGpsReceiver` |
| Explicit activity component | `dev.abhik.mockgps/dev.burixon.mockgps.MainActivity` |
| Quick Settings tile component | `dev.abhik.mockgps/dev.burixon.mockgps.ModeTileService` |
| External action | Fork-owned `dev.abhik.mockgps.CONTROL`; use the actual checked-in contract in `TASKER.md` |
| Debug and unsigned release variant | Same fork application ID; build/signing identity determines update compatibility |
| Instrumentation package | AGP-generated test package for the fork application; tests target the actual application context |

The package before `/` is the installed application ID; the class after `/` remains fully qualified in the source namespace. **Do not use `dev.abhik.mockgps/.MockGpsReceiver`**: Android expands that shorthand to `dev.abhik.mockgps.MockGpsReceiver`, a class this source layout does not define. Manifest component names and test assertions must resolve to the actual source classes, and external instructions must use the full components above. Internal intents use `Intent(context, Class::class.java)`/`ComponentName(context, Class::class.java)`, avoiding a hard-coded installed package.

Keeping one installed package for debug and release avoids separate automation secrets, mock-app choices and tiles for each build type. It does not make debug and production certificates interchangeable. A future production distribution must use a controlled, stable release signing identity; no release key is introduced by this change.

## Deliberate transition from upstream or the earlier hardening APK

Changing an application ID is a **new installation**, not a same-package upgrade. Android will not grant the new package access to the old package's private preferences, ownership journal, permissions, automation token or camera state. The fork does not request a shared UID, use root, export private storage, or silently copy a capability from another package.

1. Disable old Tasker profiles and revoke the old capability where the old build supports it. Select FORCE_REAL/Stop in the old application.
2. Keep the old app selected as mock-location app while retrying any necessary cleanup. Inspect its result. If provider cleanup remains uncertain, follow the recovery procedure, set mock-location app to None and reboot before enabling the new app. Do not assume uninstalling, stopping a service or switching the selected mock app alone has proven a fresh genuine fix.
3. Verify a newly timestamped non-mock location in an independent client. Preserve the old APK/source if rollback may be needed; do not preserve an exposed credential.
4. Install the new fork APK. Inspect the installation identity rather than its similar launcher label. It is valid to leave the old package installed but inactive until validation is complete.
5. Open the fork, grant its own precise-location/notification permissions, explicitly select it in Developer Options, and select the desired simulation point again.
6. Remove/re-add the Quick Settings tile for the fork. SystemUI stores component identity, so the old tile is not automatically repointed to the new package. Do not infer tile identity from a similar label.
7. Opt in to automation, create/reveal a **new token**, and update every Tasker explicit component/action and replay/ordering fields using `TASKER.md`. Old credentials and components are intentionally not accepted. Reconcile the selected-car state before returning to AUTO.
8. Run the Samsung validation sequence before relying on the fork. Delete the old installation only after stopping its injection and deliberately deciding that its private settings are no longer needed.

The existing `State.migrate` logic can migrate preferences within one installed package when that package is upgraded; it does not reach across the old/new application ID boundary. Do not describe it as an upstream-to-fork data migration. Existing users of the previously delivered `dev.burixon.mockgps` engineering APK must follow the fresh-install steps above.

## Signed development candidate and future updates

A **debug APK** is signed by a development certificate and is installable for hardware testing. An unsigned release APK is a build-validation output; it cannot be called an installable signed release. No private signing key belongs in Git, uploaded reports or CI artifacts.

A fresh CI runner can generate a different debug keystore on each run. Therefore two debug APKs with the same fork application ID may still fail an in-place update because their certificates differ. Check the signer digest and APK SHA-256 in the artifact's build identity record. For repeated phone testing, use the same downloaded artifact, or intentionally uninstall/reinstall after safe cleanup; reinstalling destroys that package's settings and token. A future stable development-signing arrangement needs an explicit protected-secret/release process and must not be improvised by checking a keystore into the repository.

Local release/debug signing can similarly differ. A matching package, matching trusted signer/signing lineage and permitted version code are required for normal updates. A downgrade may be rejected even with a matching signer. Never suggest bypassing Android's signing checks.

## Inspect an APK without exposing private key material

On the build host, with the Android SDK tools on PATH:

```sh
apkanalyzer manifest application-id app-debug.apk
apksigner verify --verbose --print-certs app-debug.apk
sha256sum app-debug.apk
```

Expected installed ID for the new candidate is `dev.abhik.mockgps`. `apksigner` must report verification success. Certificate digests are public identity information; the private keystore/password must not be printed or uploaded. The final candidate's exact commit, checksum and signer digest belong in the successful CI artifact record and `VALIDATION.json`, not copied from a previous run.

To install the same signed candidate again:

```sh
adb install -r app-debug.apk
adb shell pm path dev.abhik.mockgps
adb shell am start -n dev.abhik.mockgps/dev.burixon.mockgps.MainActivity
```

If installation reports a signer mismatch, stop and diagnose which fork APK/key is already installed. `-r` is not a signing override. Do not blindly uninstall an app that may still own mock providers.

## Rollback

Select FORCE_REAL and inspect cleanup, disable/revoke fork automation, set mock app None and verify genuine recovery. Remove the fork tile and its Tasker profiles. Reopen/reinstall the known old application with its correct signer, regrant permissions, select it deliberately and configure a fresh automation credential if supported. Do not restore an old permissive broadcast interface for convenience. If normal location remains uncertain, reboot and verify a fresh non-mock fix before using either simulator.

## Sources and evidence limits

Android's canonical documentation describes the [application ID and namespace distinction](https://developer.android.com/build/configure-app-module#set-application-id), [app-signing/update considerations](https://developer.android.com/studio/publish/app-signing#considerations) and [`apksigner` verification](https://developer.android.com/tools/apksigner). The application-ID documentation was fetched successfully during the post-implementation review; platform signing identity still requires APK/package inspection, not an Android17 hardware claim. Package identity/signature inspection of the prior local debug artifact confirmed upstream ID `dev.burixon.mockgps` and an Android Debug signer; that prior artifact is not the final candidate. Root's final build evidence must verify the changed package and actual signer.

No install/upgrade/rollback on Samsung One UI9 was executed by this review. The identity strategy removes a predictable package/signing conflict; it does not prove OEM runtime compatibility, provider cleanup or successful device installation.

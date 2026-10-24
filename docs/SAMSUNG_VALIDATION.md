# Samsung One UI 9 / Android 17 validation

**Execution status: NOT RUN on physical hardware or an Android 17 emulator.** This document is a test procedure, not a passed compatibility certificate. The cloud environment has no attached test device and no `/dev/kvm`; build/unit/lint evidence belongs in [TESTING.md](../TESTING.md). Record exact device model, Android/API level, One UI version, firmware build, security patch, Tasker version and app commit before running. An SDK 36 build is not Android 17 API 37 validation.

## Prepare a reversible test

Use a device and location-consuming test clients you control. Do not test with a safety-critical navigation session. Select a conspicuous but harmless mock location, record its expected value privately, and keep the app's FORCE_REAL control accessible. Keep simulated-location flags enabled; the validation client should inspect `Location.isMock()`, provider, wall time, elapsed realtime and accuracy.

1. Install the debug APK with ADB or Android's package installer. Confirm its application ID `dev.abhik.mockgps` and recorded source/SHA256/signer. It installs separately from upstream/earlier engineering APKs: stop/recover the old package first, then configure fresh data/token/tile/tasks. A signer mismatch within the new package requires deliberate cleanup before reinstalling; never uninstall a still-injecting app blindly.
2. Open MockGPS, enable Android Developer options, and select it under **Select mock location app**. Grant location permission, turn system Location on, and grant notifications. Select mock coordinates explicitly.
3. Add the native tile from the Quick Settings edit panel. Samsung panel editing may vary. Verify that the app screen remains a usable fallback if the tile cannot be added.
4. Keep integration disabled initially. Check that an unauthenticated legacy broadcast cannot control the app or exported service. Then configure a new token and the **specific paired vehicle** profile using [TASKER.md](TASKER.md).
5. Begin in FORCE_REAL, reconnect/reconcile Tasker, and deliberately return to AUTO only for each automation test. Clear prior test notes and capture the current bounded app status before every run.
6. Test Samsung normal battery optimization first. Repeat relevant cases after deliberately choosing an unrestricted/not-sleeping configuration, recording exact settings. Such configuration does not override Android permission restrictions.

Use two distinct observations: (a) app mode/provider/injection/last-result state; (b) a fresh fix from a separate test client. A tile mode or completed broadcast is not a substitute for either observation.

## Validation matrix

All rows initially have result **NOT RUN**. For each row record timestamp, preconditions, actual result, app state, Android logs relevant to the operation, and PASS/FAIL/INCONCLUSIVE. An unavailable genuine fix is INCONCLUSIVE, not a pass.

| # | Scenario | Procedure and required observation | Initial result |
| --- | --- | --- | --- |
| 1 | Manual mock start | With app visible, valid selection, permissions and mock-app authorization, start explicitly. Running must appear only after initial provider injection succeeds; independent client receives a fresh `isMock=true` fix. | NOT RUN |
| 2 | Manual mock stop | Stop through the app. Pending work and callbacks end; owned providers are removed or cleanup uncertainty appears. No restart follows. | NOT RUN |
| 3 | Genuine GPS recovery | After successful cleanup, use a separate client outdoors and request a fresh fix. Verify a newer elapsed timestamp and `isMock=false`; never accept an old cached genuine fix. Record reacquisition time without imposing an invented guarantee. | NOT RUN |
| 4 | Tile activation | From FORCE_REAL choose AUTO through the tile after unlock. It must not start injection or claim a genuine fix. Open the app separately for any pending confirmation; tile label/subtitle distinguish mode, pending and observed execution. | NOT RUN |
| 5 | Tile override | During injection and during a delayed request, choose FORCE_REAL. Injection stops/cancels, pending confirmations cannot reactivate it, and tile/app agree. A remaining genuine fix remains separately unverified. | NOT RUN |
| 6 | Tasker car connection | In AUTO, connect the selected paired car. Verify authenticated connected event, delay cancellation and shutdown. Connect an unrelated device: it must not alter selected-car truth. | NOT RUN |
| 7 | Tasker car disconnection | Disconnect the selected car after current reconciliation. No request appears before configured delay; at/after due, notification asks for visible confirmation. No silent background location FGS starts. Confirm only if still AUTO/disconnected. | NOT RUN |
| 8 | Rapid reconnect | Disconnect, reconnect before due, then deliver duplicate/out-of-order disconnect and an old confirmation intent. No injection starts; old generation is rejected. Repeat near the due-time boundary. | NOT RUN |
| 9 | Bluetooth radio manually off | Turn radio off while the car is connected. Tasker sends UNKNOWN and pending work cancels. The app must not mistake “radio unavailable” for confirmed car disconnection. Reenable and reconcile. | NOT RUN |
| 10 | Locked device | Lock before connection/disconnection, delay expiry and tile interactions. Authenticated stop acts when delivered. Enabling requires unlock/visible confirmation; no hidden start or coordinate/credential disclosure. Record Samsung delivery/subtitle behavior. | NOT RUN |
| 11 | Tasker force-stopped | Force-stop Tasker, change car state, then reopen it. No promise of missed-event delivery. Reconcile current state and verify old Tasker tasks cannot enable injection. Record any lingering known-state limitation. | NOT RUN |
| 12 | MockGPS process killed | Kill a background process without force-stop where Android permits. Reopen and verify recovery invalidates pending starts and connection truth; attempts owned-provider cleanup and reports uncertainty. A later `onDestroy()` call is not required for safety claims. | NOT RUN |
| 13 | Reboot | Reboot while injecting and while delay is pending. No silent boot injection. Mode policy remains predictable, observed runtime state is reconciled, connection starts UNKNOWN and old requests cannot activate. Open Tasker/app and reconcile. | NOT RUN |
| 14 | Battery optimization enabled | Repeat connect/disconnect/delay/locked cases under Samsung normal optimization and sleeping configuration. Record delayed or missing events accurately; do not compensate with high-frequency polling. | NOT RUN |
| 15 | Battery optimization unrestricted | Repeat #14 with the user-chosen unrestricted/not-sleeping configuration. Document differences. Do not treat this as a blanket background FGS or while-in-use exemption. | NOT RUN |
| 16 | Missing location permission | Revoke permission, attempt visible start, and revoke during execution where the OS permits. No false running result or infinite retry; show actionable failure and cleanup result. Restore only through user action. | NOT RUN |
| 17 | Not selected as mock app | Deselect before start; startup must fail safely. Deselect while running and stop: if AppOp prevents removal, show cleanup uncertainty, then use recovery below. Do not claim real GPS restored. | NOT RUN |
| 18 | Background-start rejection | Send Tasker disconnect/legacy start from the background. It must never use that broadcast to start injection. Exercise a deliberately restricted foreground-start attempt only in an instrumented/dev test; capture rejection without false success or a retry loop. | NOT RUN |
| 19 | Provider cleanup failure | Revoke mock AppOp during injection or use an instrumented provider adapter failure. Verify uncertainty, no callback/restart loop, and recovery after reselecting MockGPS and explicitly stopping. Unit adapter failures supplement, not replace, platform observation. | NOT RUN |
| 20 | Repeated transitions | Repeat start-stop-start-stop rapidly, then mix connection/reconnection/FORCE_REAL, duplicate/out-of-order sequences and stale pending intents. End with no pending work, no active owned providers and matching app/tile state—or an explicit error. | NOT RUN |
| 21 | Other apps' recovery | In at least one Android LocationManager client and any separately chosen fused/OEM client, check fresh nonmock fixes after cleanup. Record cached-mock behavior and provider differences; app does not claim control over every source. | NOT RUN |
| 22 | OS/app update | Record versions and signing identity. Upgrade with retained data when signatures permit; verify safe migration, integration disabled/revoked as defined, old restart/pending intents invalidated and selected mode coherent. Repeat #1–#8 after firmware update. | NOT RUN |
| 23 | Selected car connects during MANUAL_MOCK/startup | Start manually, then deliver fresh typed API2 CONNECTED during injection and separately startup. Verify cleanup outcome, inactive AUTO unless FORCE_REAL, stale callbacks rejected, tile/app agreement; later DISCONNECTED requires a new delay/confirmation. Independently verify a fresh nonmock fix. | NOT RUN |
| 24 | UNKNOWN during MANUAL_MOCK and missed delivery | Deliver fresh typed UNKNOWN during manual injection/startup; verify cleanup outcome, inactive AUTO unless FORCE_REAL, stale-work rejection and tile/app agreement. Compare intentionally missed delivery (no silence-based stop guarantee) with local FORCE_REAL. Later DISCONNECTED needs new delay/confirmation; verify genuine recovery independently. | NOT RUN |

## Additional security and reconciliation probes

* Disabled integration, missing/incorrect token, unsupported action/API/command, string-typed numeric extras, expired/future timestamp, equal/older sequence, legacy `on` and direct external service start must not enable injection.
* Read-only `status` authenticates/freshness-checks but does not consume mutation ordering. Equal timestamps with increasing sequences must work; replayed mutation sequences must fail without leaking coordinates/token.
* Revoke while a confirmation is pending. Clicking an old notification cannot start. Reenable and ensure the prior token no longer works.
* Select FORCE_REAL during a delayed request and while the activity is opening from a notification. Old extras must not override the new mode.
* Start Tasker when the car was already connected; the initial current-state reconciliation must send CONNECTED. A different Bluetooth device must not cause a selected-car disconnect.
* During multiple-device tests, send UNKNOWN on uncertain/radio-off states. If Tasker cannot establish selected-car truth, activation stays gated.
* Deny notifications and repeat the delayed request. No successful-notification-delivery claim; next visible app open shows the limitation.
* Verify that imported/upstream auto-boot settings, broadcast-enabled flags and cached genuine coordinates do not reenable permissive behavior during migration.

## ADB and diagnostic techniques

Read app status first; collect only the minimum needed. These commands do not themselves verify acceptance or GPS recovery:

```bash
adb shell getprop ro.build.version.release
adb shell getprop ro.build.version.sdk
adb shell getprop ro.build.version.security_patch
adb shell dumpsys package dev.abhik.mockgps
adb shell dumpsys activity services dev.abhik.mockgps
adb shell cmd appops get dev.abhik.mockgps android:mock_location
```

Use `adb shell dumpsys alarm` to inspect this package's pending alarms, and `adb shell dumpsys notification` only if notification debugging is necessary. Both can expose unrelated personal data. `adb shell dumpsys location` can expose precise genuine/mock coordinates and attribution; inspect locally and redact coordinates, package identities unrelated to the test and device identifiers before sharing. Provider presence alone does not establish fresh genuine-location recovery.

For a process-death probe, `adb shell am kill dev.abhik.mockgps` only kills processes eligible under Android's rules; it may not kill an active FGS. `adb shell am force-stop dev.abhik.mockgps` is a **different** scenario: it marks the app stopped and suppresses normal receivers/alarms until reopened. Record which command was actually used and whether the process exited. Do not label force-stop as an ordinary recoverable process kill. Avoid `pm clear` unless deliberately testing data reset.

Inspect logcat locally around the exact transition for `ForegroundServiceStartNotAllowedException`, `SecurityException`, permission failures and service lifecycle. Prefer process/package-filtered capture; after process restart its PID changes. Full logcat may include precise locations, Bluetooth addresses, Tasker variables or secrets from unrelated apps. Do not upload unredacted logs or dump Intent extras. Record sanitized error category/time and the relevant app result instead. Never include the control token in a report.

Use a controlled test client to inspect the `Location` mock flag and timestamps. ADB fake-location commands or shell exemptions must not be used as evidence that ordinary Tasker can start a location FGS. The command probe in [TASKER.md](TASKER.md) tests broadcast routing/types only.

## Recovery without root

1. Select **FORCE_REAL** in MockGPS or its native tile. Disable/revoke integration and disable the Tasker profile while diagnosing. Reopen the app and inspect cleanup state; mode selection alone is not proof.
2. If cleanup reports an error because MockGPS is no longer the selected mock-location app, select it again in Developer options, reopen MockGPS and explicitly stop/clean up. Keep permissions available long enough to perform cleanup. Deselect it after cleanup succeeds.
3. Verify a fresh `isMock=false` fix in an independent client. Allow ordinary GNSS/network reacquisition time; a cached coordinate or a map marker is insufficient. Turning Location off/on can trigger reacquisition but does not prove cleanup by itself.
4. If provider state remains uncertain, turn system Location off, ensure injection is not enabled, and reboot. The application must not silently inject on boot. Reenable system Location, keep automation disabled, and verify fresh nonmock locations before returning to normal use.
5. Force-stop or uninstall can stop the app's code but must **not** be treated as proof that system test providers or other apps' caches were removed. Prefer explicit cleanup before uninstall/rollback; if uncertain, reboot and verify.
6. Before returning to AUTO, reselect the intended mock app only if still testing, open Tasker and MockGPS, reconcile the selected vehicle's current state, and create a new integration token if the previous one was exposed or revoked.

A fresh genuine fix requires functioning sensors/network and suitable reception. If none is available, report “injection stopped / genuine fix unverified” and continue diagnosis without pretending that mock removal restored a usable fix immediately.

## Rollback

Disable Tasker profiles, revoke integration, select FORCE_REAL and complete provider cleanup first. Preserve only necessary user settings privately; do not export a token or genuine-location history. Install a trusted prior build only if its signing identity matches, or deliberately uninstall/reinstall if it does not. Uninstalling deletes app configuration. The permissive upstream broadcast interface is unsafe for unattended automation; keep it disabled when using an older build. After rollback/reboot confirm genuine-location behavior independently.

## Result record template

```text
Case ID / date:
App commit, APK signature identity, installation method:
Device model, API, One UI, firmware/security patch:
Tasker version, selected-car matching method (redact address):
Permissions, mock-app selection, notification/battery/lock state:
Precondition / actions:
App mode, desired, observed service/provider/injection state, pending generation:
Separate client provider, fresh timestamp, mock flag (redact coordinates):
Sanitized failure category / cleanup / recovery:
PASS / FAIL / INCONCLUSIVE and reason:
```

Keep results separate by firmware and app version. A passed unit test, compiled APK, or a test on another Android device must not be copied into this physical-device matrix as PASS.

## Efficient physical-device sequence for this candidate

**Every step below requires your Samsung phone and remains NOT RUN here.** An API36 emulator result does not mark these OneUI9/Android17 rows passed. Use a safe location-testing context and stop if normal-location recovery is uncertain.

| Order | Action | Expected result / failure indicator |
| --- | --- | --- |
| 1 | Download the successful Actions artifact for the recorded source SHA; compare build-identity.json checksum/signer with the APK. Stop/recover old upstream/engineering app; install the fork. | Installed package dev.abhik.mockgps; signer mismatch is an installation blocker, not something -r overrides. |
| 2 | Open fork, grant precise foreground location and notifications; select the fork in Developer Options. | Fresh FORCE_REAL state and explicit mock-app selection; missing permissions/selection must produce actionable failure rather than simulated success. |
| 3 | Configure fresh API2 token, full explicit component, exact typed extras and one persisted sequence dispatcher for the selected paired vehicle. | Fresh higher-sequence authenticated mutations accepted; wrong token, string numeric types and replay rejected. Status does not consume sequence. Keep credentials private. |
| 4 | Select a synthetic point, explicitly select AUTO and manually start from the focused app; then connect the selected car. | Actual mock fix first, then CONNECTED stops manual injection, invalidates stale work and leaves inactive AUTO unless FORCE_REAL. Cleanup uncertainty must be shown; other Bluetooth devices do not substitute for the car. |
| 5 | Repeat manual start followed by fresh vehicle_unknown; compare deliberately missed delivery and local FORCE_REAL. | Delivered UNKNOWN stops manual simulation. Silence cannot establish a missing event or guarantee a stop. App/tile agree; no cleanup/genuine-fix certainty is invented. |
| 6 | Deliver CONNECTED and separately UNKNOWN during an admitted manual startup. | No process crash or stale-intent resurrection; desired=false, old generation rejected and providers removed or explicitly uncertain. Later disconnection requires a new delay/confirmation. |
| 7 | Exercise denied/restored mock authorization and explicit cleanup retry, then obtain a fresh nonmock fix independently. | Keep/restore fork selection while retrying. Confirm cleanup separately from a new timestamped mock=false fix; cached position is INCONCLUSIVE. Record recovery time, never genuine coordinates. |
| 8 | Use the real native tile during manual injection; reopen panel and test secure lock, without Tasker. | FORCE_REAL stops and persists; app/tile agree. Returning AUTO requires unlock and never starts injection. Repeat under normal/restricted Samsung battery policy. |
| 9 | In AUTO disconnect the selected car with Bluetooth on; use default120seconds and explicitly confirm in the visible app. | Inexact minimum delay followed by visible request; no unattended background start. After a prior manual safety stop, this must be a new request. DISCONNECTED cannot clear FORCE_REAL. |
| 10 | Reconnect before/near deadline, deliver UNKNOWN, try stale confirmation, same-ms distinct sequences and delayed older disconnect. | Requests cancel; old sequence/generation cannot resurrect injection. Physical Tasker reconciles current car truth after missed delivery/force-stop rather than replaying historical edges. |
| 11 | Exercise process termination and separately force-stop/reopen. | Reopen invalidates pending starts, preserves FORCE_REAL and reports cleanup/uncertainty. A missing onDestroy must not be mistaken for successful cleanup. Force-stop suppresses execution until reopened. |
| 12 | Reboot, unlock and reopen; reconcile Tasker state. | No silent simulation restore; fresh reconciliation/visible confirmation required. Verify genuine recovery again before relying on normal location. |

Host commands (downloaded artifact named app-debug.apk; inspect public identity, not private keys):

```sh
sha256sum app-debug.apk
apksigner verify --verbose --print-certs app-debug.apk
adb install -r app-debug.apk
adb shell pm path dev.abhik.mockgps
adb shell am start -n dev.abhik.mockgps/dev.burixon.mockgps.MainActivity
adb shell getprop ro.build.version.release
adb shell getprop ro.build.version.sdk
adb shell getprop ro.product.manufacturer
adb shell cmd appops get dev.abhik.mockgps android:mock_location
```

`INSTALL_FAILED_UPDATE_INCOMPATIBLE` means the same installed package has another signer. Stop/recover it before any deliberate uninstall; new installation erases settings/token. Developer Options selection and runtime permission granting should be performed visibly, not replaced with shell exemptions as automation evidence.

For process probes, first record app mode/result and PID (no coordinates/token):

```sh
adb shell pidof dev.abhik.mockgps
adb shell am kill dev.abhik.mockgps
# Recheck PID: am kill can leave an active foreground service alive.
adb shell pidof dev.abhik.mockgps
# A controlled crash probe is distinct from ordinary low-memory death:
adb shell am crash dev.abhik.mockgps
# A separate force-stop scenario deliberately suppresses normal receivers:
adb shell am force-stop dev.abhik.mockgps
adb shell am start -n dev.abhik.mockgps/dev.burixon.mockgps.MainActivity
# Reboot only when ready to perform the reboot scenario:
adb reboot
adb wait-for-device
```

Do not call a no-op am kill a passed process-death test. Crash/force-stop/reboot are different outcomes and must be recorded separately. For diagnostics, record source SHA/APK SHA/signer, OS/build, exact scenario, mode, vehicle, sanitized transition code, sequence and times. Never include the capability, Tasker URI/tasks containing it, full intents, dumpsys location, raw coordinates or unreviewed full logcat. App/controller status and ordered authenticated responses omit coordinates/token. If a crash trace is necessary, inspect it locally and redact any token/URI/coordinates before sharing; do not automatically upload device logs. Use a separate client only to record timestamp/mock flag and PASS/FAIL/INCONCLUSIVE, not the real location itself.

Recovery: FORCE_REAL → inspect/retry while fork remains selected → select mock app None → toggle location if appropriate → reboot if uncertain → independently verify a fresh non-mock fix. Never treat uninstall, a stopped service, tile selection or old cached position as proof of genuine recovery. Remove the fork tile/Tasker profiles and reconfigure the correctly signed old app only after safe cleanup if rolling back.

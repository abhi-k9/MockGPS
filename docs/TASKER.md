# Tasker integration: selected-car state, not unrestricted remote control

Protocol version: 2. **Breaking engineering update:** fresh fork package/token and updated Tasker tasks are required; API1 is rejected. This integration uses Android explicit broadcasts locally. It needs neither root nor Shizuku, an HTTP server, background-location permission, or a signature shared with Tasker. **The Tasker UI recipe and Bluetooth delivery have not been executed on a Samsung device.** No importable XML is supplied or claimed tested.

## What the automation does

Tasker owns the connection-state truth for **one intentionally selected paired vehicle**. MockGPS owns policy, delay, cancellation, credentials, and injection. In AUTO:

* Vehicle connected: cancel pending requests and stop injection.
* Vehicle disconnected: wait at least the configured delay (default 120 seconds), then recheck AUTO, latest recorded vehicle state and request generation. If still valid, post a notification asking the user to open the app and confirm. Do not expect a background start.
* Vehicle unknown, Bluetooth off, missed reconciliation or app/process recovery: cancel activation; resynchronize before requesting anything.

FORCE_REAL remains selected until the user explicitly returns to AUTO in MockGPS or its tile. Manual start is blocked until that return to AUTO. An explicitly selected MANUAL_MOCK session is independent of vehicle events; use STOP/FORCE_REAL to override it. Switching AUTO or integration configuration stops a manual session and requires reconciliation. Tasker cannot clear FORCE_REAL, select MANUAL_MOCK, choose coordinates, or start injection directly. Selecting AUTO resets the recorded vehicle state to UNKNOWN so an old disconnection cannot reactivate a request.

The delay is an **inexact elapsed-time alarm**, not a 120-second execution guarantee. Doze and Samsung scheduling can delay it. A received broadcast is not proof of successful Android foreground-service startup. The confirmation notification and visible activity are the supported activation path; see [platform feasibility](PLATFORM_FEASIBILITY.md).

## Prepare MockGPS

1. Install the built APK; open it and grant location permission. On Android versions requiring it, grant notifications so confirmation requests can be seen.
2. Enable Android Developer options and select MockGPS under **Select mock location app**. Select a location explicitly in MockGPS. Missing coordinates must not fall back to `(0, 0)`.
3. Open MockGPS automation settings. Integration defaults to disabled. Enable it deliberately and reveal/copy the generated 64-character hexadecimal capability token only when ready to configure Tasker.
4. Set the delay if needed (the UI accepts 0–3600 seconds). Keep FORCE_REAL during initial configuration. Return to AUTO deliberately and send a current vehicle-state reconciliation.
5. Clear the clipboard after transfer. Do not include the token in screenshots, shared Tasker exports, logs or bug reports. Revoking integration invalidates the token and cancels pending requests. Reenabling requires updating Tasker with the newly generated token.

## Versioned command contract

Send an explicit broadcast with action `dev.abhik.mockgps.CONTROL` and component `dev.abhik.mockgps/dev.burixon.mockgps.MockGpsReceiver`.

| Extra | Required Android type | Value |
| --- | --- | --- |
| `api` | `int` | `2` |
| `token` | `String` | Current 64-character hexadecimal token |
| `command` | `String` | One of the commands below |
| `event_time` | `long` | Sender epoch time in **milliseconds**; Tasker `%TIMEMS` |
| `seq` | `long` | Nonnegative strictly increasing persisted sender counter for mutations; less than Long.MAX_VALUE |

| Command | Effect |
| --- | --- |
| `vehicle_connected` | Record selected-car connection; cancel pending starts and stop injection when governed by AUTO. FORCE_REAL remains dominant. |
| `vehicle_disconnected` | Record selected-car disconnection; schedule a delayed confirmation request only when policy allows. |
| `vehicle_unknown` | Invalidate untrusted connection knowledge and cancel pending activation. Use for radio off and uncertain/recovery states. |
| `stop` | Select FORCE_REAL and request injection shutdown/cancellation; never grants permission to restart or clear the override. |
| `status` | Refresh/report local diagnostic state; does not start injection or broadcast coordinates/credentials. |

Mutating commands consume a persisted `seq`, independent of the wall clock. It must exceed the last accepted mutation sequence. Duplicate/older sequences are rejected, including after process recovery. `status` is authenticated and fresh but read-only: it does not consume or reset ordering. Requests must be 0–60 seconds old and never in the future. A clock rollback does not block a fresh higher-sequence command; queued pre-rollback future-dated messages still fail. Same-millisecond events with distinct increasing sequences are valid. Exactly five typed extras are required; API1, missing/string/coerced numeric extras, unknown keys and legacy commands are rejected. Credential rotation starts a new sequence epoch; merely enabling an already-enabled credential must not reset replay protection.

A capability proves knowledge of the token, not the identity of a package named in an extra. No caller-supplied package name is trusted. Time ordering is bounded replay protection; a stolen token still authorizes fresh supported commands until revoked.

There is no unauthenticated status endpoint or network listener. An authenticated **ordered** broadcast receives ACCEPTED/rejection and sanitized fields for mode, service, providers, injection, transition, vehicle, desired and pending. ACCEPTED means command acceptance only. The ordinary Tasker `sendBroadcast` recipe below is unordered and receives no acknowledgement; inspect the app. A successful Tasker action or `am broadcast` delivery means that Android delivered/sent an intent, **not** that the app accepted it or a service ran. Inspect MockGPS's mode, observed/provider state and last result for outcome. Never display a Tasker success message saying “GPS restored”; at most say “stop requested.”

## Build typed broadcasts in Tasker

Tasker's Send Intent extra coercion varies by input and version. Do not assume that `api:2` or `event_time:%TIMEMS` creates an `int`/`long`; a string extra must be rejected. The following recipe uses the public Android `Intent.parseUri` type grammar so the required types are explicit. Tasker's action names/UI can vary; validate the recipe on the installed Tasker version before relying on it.

Create a reusable Tasker task with a command parameter `%par1`:

1. Store the private token in a Tasker variable you control, such as `%MockGpsToken`. A Tasker variable is not an Android Keystore; see the backup warning below.
2. Maintain a private persisted `%MockGpsSeq` counter per credential, initialized to 0 for a newly issued token. Configure this one dispatcher task's collision handling to abort the existing instance and start the newest instance, not the default ignore-new setting or run-both. Tasker finishes an already-running action before replacing the task; counter gaps are harmless. Set local `%mock_seq` to 0 for read-only status. For each mutating dispatch, increment the counter once and immediately copy it to `%mock_seq`. Do not reset it on Tasker restart. Do not use `%TIMEMS` as the sequence. Keep the Tasker counter in an exactly representable integer range (rotate well before 2,147,483,647 rather than relying on a scripting engine's full Long range). If the counter is lost or exhausted, revoke/reissue the credential and reinitialize deliberately. Reconcile current vehicle state instead of replaying old queued events.
3. Use **Variable Set** to set a local `%mock_event_time` to `%TIMEMS` immediately before dispatch. Do not reuse the timestamp saved when a Bluetooth event originally occurred minutes earlier.
4. Set local `%mock_uri` to this single line, allowing Tasker variable substitution:

   ```text
   intent:#Intent;action=dev.abhik.mockgps.CONTROL;component=dev.abhik.mockgps/dev.burixon.mockgps.MockGpsReceiver;i.api=2;S.token=%MockGpsToken;S.command=%par1;l.event_time=%mock_event_time;l.seq=%mock_seq;end
   ```

5. Add **Java Function**. Class/object: `android.content.Intent`; function: `parseUri(String, int)` (return type `Intent`); parameters: `%mock_uri`, `0`; return object variable: `%mock_intent`.
6. Add **Java Function**. Class/object: Tasker's `CONTEXT`; function: `sendBroadcast(Intent)`; parameter `%mock_intent`; no return value. This calls Android locally as Tasker and uses the explicit receiver, without shell privileges.
7. Test an authenticated `vehicle_unknown` first with a new counter, then inspect recorded vehicle state/accepted time in the app. Unordered `status` has no acknowledgement and cannot prove delivery in the UI. Test a wrong token, string-typed fields, stale time and replayed sequence: they must not change policy. Do not put URI/token contents into Flash actions or Tasker logs.

Android's Intent URI prefixes here are `i.` for integer, `l.` for long and `S.` for string. This grammar was verified in [AOSP Intent.parseUri source](https://github.com/aosp-mirror/platform_frameworks_base/blob/1cdfff555f4a21f71ccc978290e2e212e2f8b168/core/java/android/content/Intent.java); executing the Tasker action sequence on the target phone is still required. The token and allowed command strings contain no URI separators. Do not generalize this URI construction to arbitrary unescaped user text. Alternatively, a Tasker version with reliably typed native extras may use them after validation; the exact contract above is the authority.

Use one serialized sender/counter owner for this credential. The documented Tasker collision modes are ignore-new (default), abort-existing, or simultaneous; there is no separate native queue setting. [Tasker execution/collision documentation](https://tasker.joaoapps.com/userguide/en/tasks.html) was fetched and reviewed. Avoid Wait/dialog actions in the dispatcher, let the latest current-state reconciliation win, and do not replay historical edge parameters as current truth. The sequence orders sender events, not Android delivery: an older disconnect delivered after a newer connection is rejected. Two events may share a timestamp; their counter values distinguish them. Do not give a stale queued disconnection a newly allocated sequence after a later connection. If Tasker cannot maintain a durable counter and current-car truth, send UNKNOWN and use manual controls. Counter gaps are safe; resetting/reusing values is rejected. Reconciliation always describes current state, not historical queued triggers.

## Create the specific-vehicle profile

1. In Tasker create **State → Net → BT Connected** for the paired vehicle's exact Bluetooth address. Select the correct profile/device using Tasker's picker. Avoid a blank device/address or “any Bluetooth device.” If using a name, confirm it cannot match unrelated paired devices; address matching is preferred where Tasker/device permits it.
2. Enter task: dispatch `vehicle_connected` with fresh time. This must run whenever the selected-car state becomes connected, not for headphones or a watch.
3. Exit task: first establish that Bluetooth is on and that the selected vehicle is actually disconnected; dispatch `vehicle_disconnected` only with that knowledge. Do not add a second Tasker 120-second wait; the app owns the delay and generation checks.
4. Bluetooth radio off, state lookup error or unknown state: dispatch `vehicle_unknown` rather than silently treating the car as disconnected.
5. On Tasker monitor start/profile enable, after reboot, and after changing AUTO/integration/token, reconcile the **current** selected-car state. Dispatch connected, disconnected or unknown accordingly. Do not infer current state from whichever old enter/exit task happened to run last.
6. If Tasker was killed, force-stopped, disabled, battery-restricted or upgraded, open it, verify the profile and perform reconciliation. If MockGPS recovered/reopened, do the same. No event source can guarantee missed events are delivered retroactively.

Keep the selected-car profile and radio-off handling coordinated. Multiple connected Bluetooth devices are irrelevant unless they are the selected vehicle. Tasker must not synthesize a disconnection merely because some other device disconnected. Where Tasker cannot establish a current connection state reliably, send UNKNOWN and require manual confirmation/resynchronization.

## Development probe with ADB

On a development computer with ADB, this tests exact types and routing. Use a private shell and avoid recording its process list. The token necessarily appears in the transient `adb` command arguments; do not run it on a shared computer or publish command output.

```bash
read -rs -p 'MockGPS token: ' MOCKGPS_TOKEN
printf '\n'
MOCKGPS_EVENT_TIME=$(python3 -c 'import time; print(time.time_ns() // 1000000)')
adb shell am broadcast --user current \
  -a dev.abhik.mockgps.CONTROL \
  -n dev.abhik.mockgps/dev.burixon.mockgps.MockGpsReceiver \
  --ei api 2 --es token "$MOCKGPS_TOKEN" \
  --es command status --el event_time "$MOCKGPS_EVENT_TIME" --el seq 0
unset MOCKGPS_TOKEN MOCKGPS_EVENT_TIME
```

The computer timestamp must reach the phone no more than 60 seconds old and must not be ahead of the phone clock. Prefer automatic synchronized date/time; any future timestamp is rejected. `Broadcast completed` is not application acceptance. For this read-only status probe inspect the ordered ACCEPTED response; it does not update the last mutation time. Use a fresh-sequence vehicle_unknown for GUI delivery confirmation; do not claim that ADB's shell identity demonstrates ordinary Tasker foreground-start eligibility. Run device probes from [the validation matrix](SAMSUNG_VALIDATION.md) before relying on the profile.

## Samsung settings and recovery

Keep Tasker and MockGPS notifications permitted and review Samsung's sleeping/deep-sleeping app settings. Validate both normal battery optimization and any intentionally chosen unrestricted setting. Menu labels vary by One UI release. Such settings do not override Android foreground-service or while-in-use permission rules.

For an immediate manual override choose **FORCE_REAL** in the native tile or app. Confirm cleanup state in the app; a selected mode alone is not a fresh genuine location fix. If events appear wrong, disable/revoke integration, cancel Tasker profiles, reopen MockGPS and follow [recovery](SAMSUNG_VALIDATION.md#recovery-without-root). Reconciliation must use current vehicle state before returning to AUTO.

Tasker backup/export may include variables, task text and the capability token. Treat backups as sensitive; redact the token before sharing, protect backups, and revoke/reissue after any exposure. Avoid syncing the token to other automation tools. MockGPS does not export it in notifications or status responses.

## Package/signing migration

This candidate installs as `dev.abhik.mockgps`, while Kotlin component classes retain `dev.burixon.mockgps`. Stop/recover the old installation before changing mock-app selection. Configure fresh permissions, selected point, token, Tasker explicit component/action and sequence epoch; remove the old tile and add the fork tile. No cross-package private-data/credential migration occurs. See [IDENTITY_AND_SIGNING.md](IDENTITY_AND_SIGNING.md).

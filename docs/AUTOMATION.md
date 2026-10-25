# Automation API

Automation is optional. Enable each integration in the app and review its behavior before relying on it. Force real always overrides automation. Paused, unknown, or unreconciled source state blocks an automatic simulation request. An explicit local timed simulation can temporarily override readiness; Force real always wins. Stale incoming requests are rejected. When multiple sources are enabled, all must freshly request simulation.

An eligible request waits for the configured minimum delay. By default, it asks for visible confirmation. Optional unattended approval can start simulation without another tap through a foreground session that the user prepared while the app was visible. Android and device battery restrictions can delay requests or stop the session.

## Approval and change alerts

**Unattended automation** is off by default. To use it:

1. Select the simulation coordinates and grant the normal precise-location/mock-location prerequisites.
2. While MockGPS is open, enable **Settings → Unattended automation** and accept the consent prompt. Check that the app reports the session as ready.
3. Select Auto (**Allow simulation**) and send fresh requests from every enabled integration.

| Approval mode | Eligible request behavior |
| --- | --- |
| Manual (default) | Broadcast: delay, then visible confirmation. Shortcut: local request review, delay, then visible confirmation. |
| Unattended, session armed | Delay, then automatic injection if source agreement, generation, permissions, coordinates, and Auto remain valid. No local review or final tap is required. |
| Unattended, session unavailable at the deadline | No automatic start. The transition becomes `CONFIRMATION_REQUIRED_UNATTENDED_UNAVAILABLE`; open the app to prepare the session or confirm the request manually. |

Force real (**Keep stopped**) always blocks starts and cancels pending work. The prepared service can remain idle while Force real is selected; armed does not mean injecting. Changing unattended consent cancels pending requests and invalidates shortcut authority, so send fresh requests afterward.

Preparing the session starts a location foreground service with Android's required ongoing notification. No background-location permission or background-service exemption is requested. After process death or a device reboot, source agreement and pending starts are invalidated. Reopening the app can prepare the session again while visible; fresh requests are still required. A saved preference alone is not proof of foreground admission.

**Settings → Change alerts** offers **Toast** (default for new installations), **Notification**, **Toast and notification**, or **None** for routine starts and stops. Existing choices are retained. Routine notifications expire after 15 seconds; switching to Toast or None clears any posted routine notification. Errors and required actions have an independent switch in **Quiet notification profile** and a separate notification channel. These alerts do not control the required ongoing notification. Notification permission/channel settings and Android's toast restrictions affect delivery; an absent alert is not a state guarantee.

## Explicit broadcast API

Enable **Intent automation** in MockGPS and copy the credential privately. Use one serialized sender per credential, and keep its mutation counter persistent. This contract is independent of the calling app and its triggers. The optional [Tasker recipe](../tasker/README.md) implements the same contract.

### Integration credentials

The **default intent integration** retains its existing credential and counter for compatibility. You can instead create separate credentials in **Integration credentials → Add integration**. Each has its own persistent counter, label, revocation and explicitly selected permissions:

| Permission | Commands |
| --- | --- |
| Read status | `status` |
| Submit readiness | `pause`, `unknown`, `request_simulation` |
| Force real / keep stopped | `stop` |
| Select approved presets | `select_preset` |

Use one serialized dispatcher per credential. Every configured credential with readiness permission is an independent source and must report Ready; credentials without readiness permission do not participate in agreement. The default integration and App shortcuts participate only when enabled. Creating or revoking a credential stops injection, cancels timers/pending requests and clears all source readiness, while other credentials and their counters remain valid. Changing unattended consent also clears readiness. Send fresh observations afterward.

Turning off the default intent integration revokes that credential; separately created credentials remain configured until revoked individually. A token is a capability, not proof of a caller’s package identity. Permissions cannot enable unattended approval, clear Keep stopped, create presets or change their approval.

Send an **explicit broadcast** to:

- Package: `dev.abhik.mockgps`
- Component: `dev.burixon.mockgps.MockGpsReceiver`
- Action: `dev.abhik.mockgps.CONTROL`

The current protocol is **version 3**. Earlier versions, including version 2, are rejected with `UNSUPPORTED_API`. Configure senders for this version and its generic commands.

The five existing commands require exactly these five fields with these primitive types. `select_preset` additionally requires the sixth field `preset_id` described below; other extra fields are rejected:

| Field | Type | Value |
| --- | --- | --- |
| `api` | int | `3` |
| `token` | string | The current 64-character lowercase hexadecimal credential |
| `command` | string | One of the commands below |
| `event_time` | long | Current observation time, Unix milliseconds |
| `seq` | long | Nonnegative increasing counter for mutations; less than `Long.MAX_VALUE` |

### Commands

| Command | Effect |
| --- | --- |
| `request_simulation` | Reports this source as ready. In Auto, source agreement starts the minimum delay, then applies the user's approval setting. |
| `pause` | Reports this source as blocked, stops active simulation, and cancels pending requests. |
| `unknown` | Clears this source's readiness, stops active simulation, and cancels pending requests. |
| `stop` | Stops simulation and selects the persistent Force real override. |
| `status` | Returns current state without changing the mutation counter. Authentication and freshness checks still apply. |
| `select_preset` | Stops the current session and selects a locally saved, individually approved preset. Requires preset-selection permission and the string extra `preset_id` containing its exact lowercase UUID. Clears pending work and source agreement; send fresh requests afterward. |

The API cannot select Auto, supply arbitrary coordinates, change unattended consent, confirm a manual request, create a timed override, or start simulation directly. Preset selection is limited to saved locations approved in the app. The user selects or approves locations, chooses approval settings, and returns to Auto in MockGPS. The default credential needs **Default integration permissions → Allow approved preset selection** before it can use `select_preset`. Copy a preset ID from **Saved locations**; preset selection also disables route playback. Unapproved or missing IDs both return `PRESET_UNAVAILABLE`, without exposing coordinates or listing presets. A request accepted while Force real is selected cannot override it. There is no HTTP endpoint, webhook, or push subscription; use ordered broadcasts to query state.

Events must be no more than 60 seconds old and cannot be future dated. A simulation request must not predate newer pause/unknown information. Re-evaluate the actual trigger after a stale-observation rejection; do not re-stamp an old event. A missing or uncertain result must be sent as `unknown`.

Initialize the persistent counter to `0` only for a newly issued credential. Persist the counter before dispatching a mutation. Never reset it while retaining the same credential. If ordering state is lost, revoke the credential, issue a new one, and deliberately initialize one new sender. Revocation also cancels pending automation. Anyone holding the token can use the interface, so keep configured automation backups private and avoid logging intent URIs or extras.

### Ordered-broadcast response

Ordinary `sendBroadcast` calls provide no acknowledgement. An ordered broadcast with a final result receiver returns:

| Outcome | Result code | Result data | Result extras |
| --- | --- | --- | --- |
| Accepted | `0` | `ACCEPTED` | State fields below |
| Rejected | `1` | Rejection code below | None |
| No acknowledgement | Caller-supplied initial value | No `ACCEPTED` response | No verified state |

Initialize the result code to `-1`, not `0`, and require both code `0` and data `ACCEPTED` before treating a command as accepted. Acceptance confirms validation and persistence, not active injection or a fresh genuine GPS fix. A delayed start may still be blocked by policy or Android restrictions.

| Result extra | Type | Values / meaning |
| --- | --- | --- |
| `mode` | string | `AUTO`, `FORCE_REAL`, `MANUAL_MOCK` |
| `service` | string | `UNKNOWN`, `STOPPED`, `STARTING`, `INJECTING`, `ERROR` |
| `providers` | string | `UNKNOWN`, `NONE`, `READY` |
| `injection` | string | `UNKNOWN`, `NONE`, `CONFIRMED` |
| `source_state` | string | Combined readiness: `UNKNOWN`, `BLOCKED`, `READY` |
| `transition` | string | Diagnostic reason; treat as an opaque value, not a command or stable state enum |
| `desired` | boolean | Simulation is currently requested by policy |
| `pending` | boolean | A delay is pending; `false` can also mean waiting for visible confirmation |
| `unattended_approval` | boolean | The user has enabled unattended approval; this does not establish session readiness |
| `automation_armed` | boolean | A consented location foreground session is admitted and available; Force real or source state can still block injection |
| `pending_remaining_ms` | long | Remaining minimum delay, or `0` |
| `temporary_override` | boolean | A local timed override is active |
| `override_kind` | string / null | `SIMULATE`, `STOP`, or absent/null |
| `override_remaining_ms` | long | Remaining local override time, or `0` |
| `session_remaining_ms` | long | Remaining optional session ceiling; `-1` means none |
| `capabilities` | string array list | Supported features, currently `readiness`, `stop`, `status`, `select_preset`; not a grant of permission |

A `service` value of `STOPPED` can coexist with `automation_armed=true`: injection is stopped while the automation session remains ready.

The response omits coordinates and credentials. These response fields are additive to protocol 3. Existing five-field requests are unchanged; only `select_preset` requires a sixth field. Clients should ignore unknown response fields and handle unknown future state values conservatively.

| Rejection code | Meaning / recovery |
| --- | --- |
| `UNSUPPORTED_ACTION` | Use the documented action. |
| `MALFORMED_REQUEST` | Use the exact typed fields for the command (five normally, six for preset selection). |
| `DISABLED` | Enable the default integration or create a separate credential. |
| `UNSUPPORTED_API` | Use protocol `3`. |
| `UNAUTHORIZED` | Use the current credential; check for revocation. |
| `UNSUPPORTED_COMMAND` | Use a documented command with exact spelling. |
| `EXPIRED_EVENT` | Observation is older than 60 seconds, negative, or future dated; re-evaluate the trigger. |
| `INVALID_SEQUENCE` | Sequence must be nonnegative and less than `Long.MAX_VALUE`. |
| `REPLAYED_EVENT` | Use the next persistently allocated counter; rotate the credential if ordering was lost. |
| `STALE_OBSERVATION` | Request predates newer safety information, or ties a non-ready observation; obtain a fresh observation. |
| `SCOPE_DENIED` | This credential lacks the command’s permission. Configure a suitable credential locally. |
| `PRESET_UNAVAILABLE` | Preset is missing or not approved for automation. |
| `CREDENTIAL_STORAGE_FAILED` | Restore private storage and reopen the app before retrying. |
| `STORAGE_UNAVAILABLE` | MockGPS could not durably accept the command; restore local storage before retrying with fresh input. |

### Android caller example

This Kotlin example uses only standard Android APIs. Supply a privately stored credential, the original time of a freshly evaluated trigger, and a sequence allocated by your serialized dispatcher. Persist a mutation's sequence **before** calling this function. For `status`, read the current counter without advancing it.

```kotlin
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper

fun sendMockGpsCommand(
    context: Context,
    token: String,
    command: String,
    observationTimeMillis: Long,
    sequence: Long,
    onResult: (accepted: Boolean, code: String?, state: Bundle?) -> Unit,
) {
    val request = Intent("dev.abhik.mockgps.CONTROL")
        .setComponent(ComponentName(
            "dev.abhik.mockgps", "dev.burixon.mockgps.MockGpsReceiver"
        ))
        .putExtra("api", 3) // Int; a string "3" is rejected.
        .putExtra("token", token)
        .putExtra("command", command)
        .putExtra("event_time", observationTimeMillis) // Long
        .putExtra("seq", sequence) // Long

    context.sendOrderedBroadcast(
        request,
        null,
        object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val accepted = resultCode == 0 && resultData == "ACCEPTED"
                onResult(accepted, resultData,
                    if (accepted) getResultExtras(false) else null)
            }
        },
        Handler(Looper.getMainLooper()),
        -1, null, null,
    )
}
```

Keep dispatch asynchronous; do not block the main thread waiting for the callback. Android may delay or prevent delivery. Missing acknowledgement does not prove rejection: query `status` and re-evaluate the trigger before issuing a fresh mutation. Never log the token, request intent, or its extras.

Intent-capable hosts can implement this same contract if they preserve the primitive types, ordering, and credential privacy. Host compatibility must be tested on the chosen device.

## Standard Android shortcuts

### Make actions available to an automation host

1. Open MockGPS, open **Settings**, and enable **App shortcuts**. This is off by default and is separate from **Intent automation**; no broadcast credential is needed for shortcuts.
2. Return to the MockGPS main screen so it can publish the shortcuts.
3. Reopen the app/action picker in your automation host. In Samsung Modes and Routines, select MockGPS again to refresh the available actions.

| Action | Purpose |
| --- | --- |
| Request simulation | Request simulation using the configured approval mode and minimum delay. |
| Pause requests | Stop simulation and block requests from this source. |
| Context unknown | Clear this source's readiness and stop simulation. |
| Stop simulation | Stop simulation and select the persistent Force real override. |
| Open status | Open MockGPS to inspect its current state, where the host supports a fifth shortcut. |

These actions do not assume any particular trigger. The automation host supplies the schedule, application state, or other condition.

### If only “Open MockGPS” appears

“Open MockGPS” is the ordinary launcher entry. The additional actions are published only after **App shortcuts** is enabled. Check that this switch remains on, then reopen the host's action picker.

Long-press the MockGPS launcher icon as a second check. If actions appear there but not in the automation host, publication succeeded and the host may be filtering or caching the list. Host support determines which actions it displays. If the switch turns itself off or MockGPS reports a refresh failure, publication did not succeed; enabling broadcast automation does not fix shortcut publication.

### Shortcut contract

Enable **App shortcuts** in MockGPS to publish standard Android shortcuts. Their IDs are `automation_request_simulation`, `automation_pause`, `automation_unknown`, `automation_stop`, and `automation_status` (status is included when the host supports five shortcuts). Request, pause, unknown, and stop require a host supporting at least four shortcuts. Hosts may expose these as app shortcuts; availability in Samsung Modes and Routines depends on device software and host support. This is not a Samsung-specific plugin API.

Choose your own triggers in the automation host, such as a schedule, an app profile, or a testing workflow. Manual mode requires local review before a delayed confirmation request. Unattended approval trusts the configured host to deliver current requests and skips those taps when the session is armed. In this mode, command shortcuts finish without opening the app; only Open status opens the UI. A shortcut cannot use a UI launch to prepare a missing automation session. Shortcuts do not supply timestamped observations, so do not queue old context changes. Returning to Auto requires fresh reconciliation; shortcut publication alone does not establish valid source state.

Shortcuts use a separate private, revocable authority and do not use the external-command token. Disabling the integration invalidates existing authority. A launcher can invoke a shortcut too, so invocation is not proof of the calling app's identity. Consume shortcuts through the host's standard shortcut support; their private activity, embedded one-use nonces, and intent extras are not a public direct-intent API.

## Temporary overrides

A timed override can be created only through the app’s visible controls, including the **MockGPS timer** tile. Choose 1–1440 minutes and either Simulate or Stop. The persistent Keep stopped setting must be cleared locally before temporary simulation is available. The original mode, manual start intention, pending automation delay and live source agreement are retained underneath the timer.

- **Temporary Stop** suspends injection. If the previous policy still wants simulation at expiry, the already admitted foreground session resumes it. A later pause or unknown observation prevents that resumption.
- **Temporary Simulate** is explicit local consent to simulate for the selected time even while readiness is blocked. Automation observations still update the policy that will resume afterward.
- If an automation request arrives during the timer, its configured minimum delay continues beneath the override. At expiry, a remaining delay is scheduled; an elapsed delay uses the selected approval mode. Manual approval remains required for a new automatic request when unattended approval is off.
- Notification **−5 min / +5 min** actions adjust the elapsed deadline. The controls also offer **End override and resume policy**. Old actions cannot alter a different timer. Force real / `stop` cancels the override and keeps simulation stopped.
- A session duration ceiling has priority over the timer and selects Keep stopped. Updating integrations, unattended consent, delay, selected location or route configuration cancels the timer and requires reconciliation.

The native notification countdown is presentation, not the timer’s source of truth. Dismissing a notification does not start, stop or extend simulation. Expiry never launches a missing service in the background. If the admitted session is gone, restoration cannot inject and requires visible recovery. Interrupting a timer with process death, reboot or application replacement leaves the app in Keep stopped.

Timers use monotonic elapsed time, independent of wall-clock changes. **Timer precision / alarms and reminders** grants optional exact-alarm access on Android 12+. Without it, sleep may delay expiry; the UI reports this limitation. Fix publication checks the deadline before each update, so a late alarm cannot authorize further timed injection. Android/OEM scheduling is still outside the app’s control.

For example: active simulation → temporary Stop → automation Pause → expiry stays stopped. If a new Request simulation follows that Pause during the override, expiry uses that latest request and its delay/approval policy.

## Check on your device

Test both approval modes: request, pause, unknown, pause during a pending delay, Force real, credential revocation, consent changes, reboot, and battery-restricted behavior. Manual requests must require their normal review/confirmation. Armed unattended requests must start only after the minimum delay and fresh source agreement. Stop the session or restart the app to check the unavailable-session fallback; no background event may recreate a missing service. Check each alert preference and blocked notification permission. Outside an explicit temporary simulation, pause and unknown must stop injection. Force real must always stop injection and cancel timers. Check timer Stop/resume with a pause and a later fresh request during the override, both ±5-minute actions, stale actions from an older timer, and process restart. Independently verify a fresh non-mock fix after stopping. Test with a throwaway automation credential and rotate it if logs or screenshots could have captured it.

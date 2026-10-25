# Automation API

Automation is optional. Enable each integration in the app and review its behavior before relying on it. Force real always overrides automation. Paused, unknown, or unreconciled source state blocks a simulation request. Stale incoming requests are rejected. When multiple sources are enabled, all must freshly request simulation.

An eligible request waits for the configured minimum delay and asks for visible confirmation. It never silently starts a background location service. Android and device battery restrictions can delay or prevent delivery.

## Explicit broadcast API

Enable **Intent automation** in MockGPS and copy the credential privately. Use one serialized sender per credential, and keep its mutation counter persistent. This contract is independent of the calling app and its triggers. The optional [Tasker recipe](../tasker/README.md) implements the same contract.

All intent callers share one credential, counter, and logical source. Multiple apps must coordinate through a single dispatcher; they are not independent sources. Intent automation and Android shortcuts are the two independently enabled sources.

Send an **explicit broadcast** to:

- Package: `dev.abhik.mockgps`
- Component: `dev.burixon.mockgps.MockGpsReceiver`
- Action: `dev.abhik.mockgps.CONTROL`

The current protocol is **version 3**. Earlier versions, including version 2, are rejected with `UNSUPPORTED_API`. Configure senders for this version and its generic commands.

The extras must contain exactly these five fields with these primitive types:

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
| `request_simulation` | Reports this source as ready. In Auto, source agreement starts the minimum delay, then requests visible confirmation. |
| `pause` | Reports this source as blocked, stops active simulation, and cancels pending requests. |
| `unknown` | Clears this source's readiness, stops active simulation, and cancels pending requests. |
| `stop` | Stops simulation and selects the persistent Force real override. |
| `status` | Returns current state without changing the mutation counter. Authentication and freshness checks still apply. |

The API cannot select Auto, set coordinates, confirm a request, or start simulation directly. The user selects coordinates and returns to Auto in MockGPS. A request accepted while Force real is selected cannot override it. There is no HTTP endpoint, webhook, or push subscription; use ordered broadcasts to query state.

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

The response omits coordinates and credentials. Handle unknown future response values conservatively.

| Rejection code | Meaning / recovery |
| --- | --- |
| `UNSUPPORTED_ACTION` | Use the documented action. |
| `MALFORMED_REQUEST` | Send exactly the five documented extras with their exact primitive types. |
| `DISABLED` | The user must enable Intent automation. |
| `UNSUPPORTED_API` | Use protocol `3`. |
| `UNAUTHORIZED` | Use the current credential; check for revocation. |
| `UNSUPPORTED_COMMAND` | Use a documented command with exact spelling. |
| `EXPIRED_EVENT` | Observation is older than 60 seconds, negative, or future dated; re-evaluate the trigger. |
| `INVALID_SEQUENCE` | Sequence must be nonnegative and less than `Long.MAX_VALUE`. |
| `REPLAYED_EVENT` | Use the next persistently allocated counter; rotate the credential if ordering was lost. |
| `STALE_OBSERVATION` | Request predates newer safety information, or ties a non-ready observation; obtain a fresh observation. |
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
| Request simulation | Request the normal local review, delay, and final confirmation flow. |
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

Choose your own triggers in the automation host, such as a schedule, an app profile, or a testing workflow. A request shortcut requires local review before it becomes eligible for a delayed confirmation request. Returning to Auto requires fresh reconciliation; shortcut publication alone does not establish valid source state.

Shortcuts use a separate private, revocable authority and do not use the external-command token. Disabling the integration invalidates existing authority. A launcher can invoke a shortcut too, so invocation is not proof of the calling app's identity. Consume shortcuts through the host's standard shortcut support; their private activity, embedded one-use nonces, and intent extras are not a public direct-intent API.

## Check on your device

Confirm request, pause, unknown, pause during a pending delay, Force real, credential revocation, reboot, and battery-restricted behavior. Check that pause or unknown stops injection and that every request requires visible confirmation. Independently verify a fresh non-mock fix after stopping. Test with a throwaway automation credential and rotate it if logs or screenshots could have captured it.

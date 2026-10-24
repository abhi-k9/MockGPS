# Automation

Automation is optional. Enable each integration in the app and review its behavior before relying on it. Force real always overrides automation. Paused, unknown, or unreconciled source state blocks a simulation request. Stale incoming requests are rejected. When multiple sources are enabled, all must freshly request simulation.

An eligible request waits for the configured minimum delay and asks for visible confirmation. It never silently starts a background location service. Android and device battery restrictions can delay or prevent delivery.

## Tasker and other intent-capable apps

Enable external commands in MockGPS and copy the credential privately. Use one serialized sender per credential, and keep its mutation counter persistent. See the [Tasker example](../tasker/README.md) and `tasker/dispatch.js` for a sender model.

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

Commands: `request_simulation` asks for simulation after the delay and visible confirmation; `pause` cancels requests and blocks simulation; `unknown` marks the source unreconciled; `stop` and `status` provide override/status control. `stop` selects Force real. `status` is read-only and does not consume the mutation counter. There is no external start or coordinate-setting command.

Events must be no more than 60 seconds old and cannot be future dated. A simulation request must not predate newer pause/unknown information. Re-evaluate the actual trigger after a stale-observation rejection; do not re-stamp an old event. A missing or uncertain result must be sent as `unknown`.

Persist the counter before dispatch. Never reset it while retaining the same credential. If ordering state is lost, revoke the credential, issue a new one, and deliberately initialize one new sender. Revocation also cancels pending automation. Anyone holding the token can use the interface, so keep configured automation backups private and avoid logging intent URIs or extras.

Ordered broadcasts can return acceptance and a coordinate-free state snapshot. Acceptance means the command passed validation, not that simulation or genuine GPS reception was verified. Apps such as Automate or MacroDroid can use this contract if they preserve the required types and ordering; compatibility must be tested in the chosen host.

## Android shortcuts and Samsung Routines

Enable the shortcut integration in MockGPS to publish standard Android shortcuts for requesting simulation, pausing, reporting unknown state, and stopping. Hosts may expose these as app shortcuts; availability in Samsung Modes and Routines depends on device software and host support. This is not a Samsung-specific plugin API.

Choose your own triggers in the automation host, such as a schedule, an app profile, or a testing workflow. A request shortcut requires local review before it becomes eligible for a delayed confirmation request. Returning to Auto requires fresh reconciliation; shortcut publication alone does not establish valid source state.

Shortcuts use a separate private, revocable authority and do not use the external-command token. Disabling the integration invalidates existing authority. A launcher can invoke a shortcut too, so invocation is not proof of the calling app's identity.

## Check on your device

Confirm request, pause, unknown, pause during a pending delay, Force real, credential revocation, reboot, and battery-restricted behavior. Check that pause or unknown stops injection and that every request requires visible confirmation. Independently verify a fresh non-mock fix after stopping. Test with a throwaway automation credential and rotate it if logs or screenshots could have captured it.

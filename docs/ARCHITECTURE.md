# Architecture and runtime state

MockGPS keeps the existing native map/location-selection UI. This fork adds small Kotlin components rather than a DI framework, cloud service or replacement UI toolkit. The foundation and platform decisions are documented in [FOUNDATION_REVIEW.md](FOUNDATION_REVIEW.md) and [PLATFORM_FEASIBILITY.md](PLATFORM_FEASIBILITY.md).

## State and serialization

`core/Policy.kt` is the deterministic policy reducer. `PolicySnapshot` separates:

- selected mode (`AUTO`, `FORCE_REAL`, `MANUAL_MOCK`);
- desired simulation state;
- observed service state (`UNKNOWN`, `STOPPED`, `STARTING`, `INJECTING`, `ERROR`);
- provider readiness (`UNKNOWN`, `NONE`, `READY`);
- injection acceptance (`UNKNOWN`, `NONE`, `CONFIRMED`);
- sanitized last transition result;
- generation and optional elapsed-time deadline;
- last reported vehicle state and accepted event time;
- configured disconnection delay.

`CONFIRMED` means both `LocationManager.setTestProviderLocation` calls returned successfully. It does not mean another application received the simulated location, that fused/vendor sources are controlled, or that a genuine GPS fix exists after stopping. `NONE` means this app's owned test providers were removed through the platform API; it is not a fresh genuine-location observation.

All Android commands/effects run on the main looper in one application process through `ControlCenter`. Activities, receivers, the tile and the service do not independently mutate a shared “running” Boolean. `PolicyStore` atomically persists configuration and the snapshot in private credential-protected preferences. Loading restores mode, delay, ordering and generation, then deliberately invalidates old runtime observations. Live provider/service observations cannot be restored from preferences as proof of current execution.

`MockGpsApplication` initializes recovery before components run. Recovery clears desired simulation and pending activation, increments generation, resets vehicle truth to unknown and attempts cleanup of journaled providers. It never automatically starts simulation. `FORCE_REAL` persists across process restart/reboot. An AUTO user must reconcile the vehicle state again. The boot/package-replacement receiver applies the same inactive recovery policy.

## Command boundary

The exported `MockGpsReceiver` accepts only the documented versioned authenticated contract. `core/CommandAuth.kt` checks opt-in integration, capability token, command schema and time/replay bounds. The capability belongs to whoever possesses it; caller-supplied package names are not treated as identity. `AutomationAccess` owns local token enable/revoke operations. The old unauthenticated set/on/off/drift/boot interface is removed. The location service and delayed-action receiver are internal.

Tasker owns the vehicle connection observation and must match the designated paired vehicle, reconcile current profile state, and send fresh monotonically ordered events. MockGPS owns the delay and cancellation policy. Because broadcasts do not establish FGS eligibility, delayed activation posts a request to review and confirm in the visible Activity. Tasker events cannot directly start simulation or leave FORCE_REAL. No network listener or background-location permission is needed.

## Generations, delayed requests and overrides

Every state transition that invalidates a start increments the generation and cancels the pending alarm/request notification. Service intents and delayed callbacks carry the generation. A start is accepted only while the controller still desires simulation for that same generation and mode. The deadline also checks the current elapsed-time deadline, AUTO mode and disconnected observation. Thus duplicate, stale, early and out-of-order callbacks do not activate providers.

The inexact elapsed-realtime alarm is a request scheduler, not a foreground-service exemption. Samsung/Android may defer delivery; this is a minimum wait, not a real-time guarantee. Process death/reboot discards pending activation. A notification opens the app; it does not execute an automatic start. Visible confirmation rechecks the current generation and vehicle observation. A reconnect or FORCE_REAL selection cancels it. FORCE_REAL stops provider injection immediately on the serialized main thread and blocks subsequent automatic requests. Only an explicit return to AUTO clears FORCE_REAL. After that, explicit manual start is the user's deliberate selection of MANUAL_MOCK; the reducer rejects manual starts and direct MANUAL_MOCK selection while FORCE_REAL remains selected.

## Provider engine and lifecycle

`engine/ProviderEngine.kt` is platform independent and tested through `ProviderPort`. It validates finite/range coordinates and positive wall/monotonic timestamps and accuracy. `Coordinates.offset` uses spherical geometry so drift remains finite at poles and wraps the antimeridian. A missing selected point is an error; the engine never supplies `(0, 0)` as a default.

`engine/AndroidProviderPort.kt` adapts normal `LocationManager` Developer Options APIs for named `gps` and `network` test providers. It persists a conservative ownership-intent journal before registration. Successful removal clears the journal; failure retains it for recovery. The journal is private, credential protected and excluded from backup with other app state. Removal only touches journaled provider names. An already-absent provider is idempotent cleanup, while security and other removal errors remain failures.

Startup first reconciles previous journaled registrations, then registers/enables both providers. Partial setup or injection failure attempts rollback. The service reports INJECTING only after both provider update calls succeed. A partial acceptance followed by failed removal remains cleanup uncertainty, never “normal GPS restored.” A failed startup does not retry automatically.

`MockLocationService` is non-exported and `START_NOT_STICKY`. It has no restart alarms, task-removal resurrection or boot auto-start. Only a visible, user-confirmed Activity can request its start through the controller. It handles foreground-promotion permission/start rejection, then updates providers at a one-second cadence. Drift changes at most every 2.5 seconds and resets when the selected point changes. Notification updates occur at startup/first injection, rather than every tick; coordinates are omitted and notifications are private. Runtime failures remove providers where possible, persist an actionable result, and offer a best-effort failure notification when notification permission allows.

Stopping cancels all callbacks before disabling/removing owned providers, removes foreground state and stops the service. Shutdown caches its cleanup outcome so a later `onDestroy` does not repeat cleanup against a newer run's journal. A stale instance must not clean a newer live instance's providers. Controller generation checks reject obsolete state callbacks. A retry after uncertain cleanup is explicit user action; the durable journal permits recovery after process death even when `onDestroy` never ran.

## Limits of ownership and recovery

Android's named test-provider APIs do not expose a per-process ownership identifier. The journal records this app's attempted registrations, not an OS guarantee that no competing selected mock app replaced the same names. Do not run competing mock-location tools. A process kill can leave provider state active until a later app recovery/removal attempt; force-stop does not promise immediate cleanup.

Legacy upstream versions had no journal. An upgrade cannot prove ownership or absence of their leftover providers, so the fork does not blindly remove unjournaled test providers. Stop simulation in the old build before upgrading. If uncertain, use the recovery procedure and reboot, then verify a fresh genuine fix. Removing this app's test providers also does not invalidate every application's cached location or force a vendor/fused location source to reacquire GPS. Hardware validation is necessary for Samsung cleanup, lock-screen behavior, permissions, Bluetooth delivery and battery policies.

## Storage failure and migration

Provider cleanup runs even when policy migration/persistence fails. Failed persistence disables desired injection in memory, invalidates its generation and cancels pending requests; a new service start never proceeds from an unpersisted transition. External acknowledgement requires the replay watermark to have committed. Stop attempts remain effective even if private storage is full; inability to persist the override is reported and cannot be promised across a future process restart. These paths have source review, not Android disk-fault execution evidence.

Any nonempty legacy preferences conservatively mark prior provider state unverified, because old stopped flags were written before potentially failing cleanup. Old genuine-coordinate erasure is retried if needed. A successful new journaled injection of both named providers, followed by cleanup, or a real reboot can resolve legacy ambiguity; neither proves a consumer obtained a fresh genuine fix.

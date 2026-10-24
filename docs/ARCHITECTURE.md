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
- last reported vehicle state, display-only accepted event time and persisted monotonic mutation sequence;
- configured disconnection delay.

`CONFIRMED` means both `LocationManager.setTestProviderLocation` calls returned successfully. It does not mean another application received the simulated location, that fused/vendor sources are controlled, or that a genuine GPS fix exists after stopping. `NONE` means this app's owned test providers were removed through the platform API; it is not a fresh genuine-location observation.

All Android commands/effects run on the main looper in one application process through `ControlCenter`. Activities, receivers, the tile and the service do not independently mutate a shared “running” Boolean. `PolicyStore` atomically persists configuration and the snapshot in private credential-protected preferences. Loading restores mode, delay, ordering and generation, then deliberately invalidates old runtime observations. Live provider/service observations cannot be restored from preferences as proof of current execution.

`MockGpsApplication` initializes recovery before components run. Recovery clears desired simulation and pending activation, increments generation, resets vehicle truth to unknown and attempts cleanup of journaled providers. It never automatically starts simulation. `FORCE_REAL` persists across process restart/reboot. An AUTO user must reconcile the vehicle state again. The boot/package-replacement receiver applies the same inactive recovery policy.

## Command boundary

The exported `MockGpsReceiver` accepts only the documented versioned authenticated contract. `core/CommandAuth.kt` checks opt-in integration, capability token, command schema and exact API2 types, freshness and sequence/replay bounds. The capability belongs to whoever possesses it; caller-supplied package names are not treated as identity. `AutomationAccess` owns local token enable/revoke operations. The old unauthenticated set/on/off/drift/boot interface is removed. The location service and delayed-action receiver are internal.

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

## Manual-session vehicle safety

Fresh authenticated CONNECTED or UNKNOWN stops active or starting MANUAL_MOCK as well as AUTO, advances generation and cancels pending confirmation. FORCE_REAL stays selected; other modes become inactive AUTO. Known CONNECTED blocks manual start. An explicit visible manual start while UNKNOWN remains possible, but a later delivered UNKNOWN stops it. A later DISCONNECTED creates a new delay and visible-confirmation request; it cannot resurrect the old manual session.

Missing Tasker delivery cannot be inferred from silence: an active session may continue until an accepted event, runtime failure or local stop. Successful provider cleanup does not establish a fresh genuine fix; verify a new nonmock location in an independent client.

## Canceling a start before delivery

The emulator startup-race regression exposed Android16 RemoteServiceException.ForegroundServiceDidNotStartInTimeException. A focused Activity called startForegroundService and a same-looper safety event stopped the queued service before it could promote. Android16 ActiveServices.bringDownServiceLocked deliberately schedules a crash when fgRequired is still set; canceling before notification is not allowed. The API36 framework source confirms this behavior: https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android16-release/services/core/java/com/android/server/am/ActiveServices.java .

The controller now uses Activity.startService only after its existing visible-focus, permission, configuration, cleanup and durable-state checks. This is normal foreground-app service admission, not a background exemption. onStartCommand first rejects stale generations; an admitted live session still calls startForeground with the location type before engine.start or injection. Android still enforces service admission, foreground type and while-in-use permissions, and denial remains an error. A queued ordinary start can be canceled without a foreground-promotion obligation. No broadcast/tile/delayed path starts this service.

## Beta follow-up

Version5/0.4.0-beta.1 adds idempotent manual starts, bounded in-memory allowlisted command diagnostics, scrollable/night controls and an experimental Tasker project. Compile/target37 is enabled. The release APK was signed through owner-provided repository secrets in a manual isolated job; key material never enters source, logs, caches or assets. API36 passed17 device tests, while API37 has a guest-platform crash before app tests and remains a failed check. No Samsung/Tasker-import or production claim follows from signing. See [BETA_AUDIT.md](BETA_AUDIT.md) and the exact current VALIDATION/RELEASE_CANDIDATE receipts.

## Current observation ordering and provider availability

The persisted lastEventTime is now the greatest accepted mutation timestamp, never decreased by a late safety event or clock rollback. A disconnected observation with a higher counter is still rejected if its time is older, or tied while current vehicle is CONNECTED/UNKNOWN. The controller authenticates this before any mutation; the pure reducer repeats the invariant. Safety stops with fresh sequence remain conservative, FORCE_REAL persists, and a genuinely newer disconnect still needs delay/visible confirmation.

Tasker is the only enabled-capable vehicle source. Samsung's separate unavailable switch persists no consent and exposes no endpoint. There is no second enabled source or built-in Bluetooth monitor. A future verified Samsung pathway must use independent credentials/ordering and disconnected consensus across enabled sources through this same reducer/controller.

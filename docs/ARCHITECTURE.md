# Architecture

MockGPS has one Android app module. Pure Kotlin policy and provider interfaces keep the important decisions testable without an emulator; Android adapters own service admission, storage and IPC.

| Boundary | Responsibility |
| --- | --- |
| `MainActivity`, `OverrideActivity`, secondary panels | Visible local consent, map, settings and timer controls. Display intention and observed injection separately. |
| `ControlCenter` | Serialize inputs and effects on the main looper; persist decisions before starting, stopping or scheduling work. Reject stale generations after cleanup. |
| `Policy`, `SourceAgreement` | Determine mode, desired state, source agreement and minimum delay. All configured readiness sources must agree. Safety observations invalidate older Ready evidence. |
| `PendingStart` | Shared persistence/cleanup/deadline orchestration for broadcasts, shortcuts and reviewed requests. Repeated Ready observations do not stop active injection or register another alarm. |
| `OverridePolicy` | Monotonic timed overlay above ordinary readiness. Capture the policy to resume and continue applying source updates underneath. Force real and session limits take priority. |
| `SessionDeadline` | Private alarm and process callback; recheck current elapsed deadlines on delivery. Use optional exact-alarm access when granted, otherwise disclose inexact expiry. |
| `CommandAuth`, `IntegrationClients`, `AutomationShortcuts` | Strict typed requests, revocable capabilities, scopes, durable replay counters and independent one-use shortcut authority. No caller-supplied identity is trusted. |
| `LocationPresets`, `RoutePlayback` | Bounded private data. Presets need individual approval before scoped automation selection. Imported route metadata and document identifiers are discarded. |
| `ProviderEngine`, `AndroidProviderPort` | Journal ownership before provider creation; confirm both providers accepted updates; attempt removal even after partial failure. Preserve uncertain cleanup for recovery. |
| `MockLocationService` | Non-sticky foreground lifetime and serialized fix publication. Check expiry before each update. No idle location polling. |
| `AutomationAlerts`, `EventHistory` | Optional coordinate-free feedback and bounded process-local event codes. Presentation never determines policy or cleanup success. |

A visible local action admits a location foreground service. Opted-in unattended automation can retain it idle between requests; a timed override can also retain its explicitly admitted session until restoration. Receivers and alarms never recreate a missing service. If restoration cannot use an admitted session, no injection starts. Process death/reboot does not restore injection or pending requests; an interrupted timed override additionally selects Keep stopped.

The policy distinguishes selected mode, desired simulation, service admission, provider readiness, accepted injection and cleanup. None proves that other apps have discarded cached locations. While visible and stopped, the app can observe a fresh non-mock location sample timestamped after cleanup and report that observation separately, without retaining its coordinates.

Route playback uses bounded UTF-8 GPX, rejects DTD/entity declarations before parsing, denies external entity resolution, limits XML depth/elements/points and validates every coordinate. Great-circle interpolation uses elapsed progress at a configured constant speed. Playback supplies speed, heading and altitude, disables drift and stops at completion. Each new injection session starts at the beginning; there is no automatic looping.

MapLibre renders HTTPS OpenFreeMap data. Genuine location samples remain in memory and are used while the map is visible. Selected coordinates, presets, route points and map camera settings remain app-private and are excluded from backup and device transfer.

# Platform feasibility gate

Research date: 2026-10-23. Target: non-root Samsung One UI 9 / Android 17. This is a source and documentation review, **not** a claim of Samsung compatibility.

## Decision before implementation

Proceed with MockGPS using supported developer mock-location APIs, a private location foreground service, serialized policy, authenticated opt-in external commands, and a native tile. Start injection only after a visible user action and valid configuration. Tasker's delayed disconnect event may create a **request for user confirmation**, not a promise to start a location foreground service in the background. Connecting the car and FORCE_REAL must stop injection without creating a new foreground service. Do not add background-location permission, exact-alarm privileges, Accessibility, companion-device impersonation, root, or a backend to evade platform restrictions.

There are two separate checks: permission to start a foreground service from the background, and permission for that service to use while-in-use location. Delivery of an explicit broadcast satisfies neither check by itself. A 120-second delay does not preserve a temporary user-interaction allowance. Battery-optimization exclusion is not a general while-in-use location exemption.

## Evidence and limits

* **Source reviewed:** Google's AOSP GitHub mirror `aosp-mirror/platform_frameworks_base`, main HEAD `1cdfff555f4a21f71ccc978290e2e212e2f8b168`; files linked below. This is framework implementation evidence, not a Samsung implementation or an Android 17 release certification.
* **Local build evidence at gate:** the baseline is compile/target SDK 36, minimum SDK 29, Gradle 9.6.0, AGP 9.2.1; its debug APK builds. Compilation against API 36 cannot establish API 37 or Samsung behavior.
* **Current web limitation:** direct retrieval of Android developer foreground-service/type and TileService reference pages returned HTTP 403 through the managed environment policy. The canonical links below are supplied for verification; do not treat their current Android 17 text as fetched and reviewed. The permitted Google AOSP mirror was read instead. Samsung and Tasker current vendor documentation were not fetched.
* **Automated evidence:** policy/provider adapter tests can establish application decisions and failure handling; they cannot establish Android service admission or provider restoration on a device. Record actual executed tests separately in `TESTING.md`.
* **Emulator evidence:** none at this gate.
* **Physical hardware evidence:** none. Every One UI 9 transition remains a hardware validation item.

## Expected transitions

| Trigger | Expected supported application behavior | Platform conditions and unresolved evidence |
| --- | --- | --- |
| App visible: start | Validate coordinates and permissions; request location FGS; report running only after provider setup and initial injection succeed | Mock-app AppOp must be allowed. Location runtime permission/location FGS declaration and permission must be valid. Catch startup and promotion failures. Samsung/API 37 untested. |
| App visible: stop | Cancel pending requests, stop callbacks, remove owned test providers, report cleanup result | Successful removal means injection ended; it does not prove a fresh genuine fix. |
| Tasker car connected | Authenticate event, persist connection, cancel delay, request stop within AUTO; preserve FORCE_REAL | An ordinary explicit broadcast can perform bounded local work. No new FGS is needed to stop. Delivery is subject to force-stop/OEM restrictions. |
| Tasker car disconnected | Authenticate fresh event, schedule inexact elapsed-time delay, recheck mode/connection/generation, show an actionable notification | No assumed background FGS exemption. User opens visible app and confirms start. Inexact alarm can be delayed by Doze/OEM scheduling; 120 seconds is a minimum policy delay, not an exact deadline. |
| Native tile: stop | FORCE_REAL immediately through centralized policy; cancel all pending starts | Do not start an FGS merely to send STOP. Display cleanup uncertainty rather than “real GPS restored.” Lock-screen stop is safe, subject to System UI allowing the tile interaction. |
| Native tile: return AUTO | Update mode truthfully after unlock; user opens the activity separately for any start confirmation | The implemented tile only toggles modes and does not launch an activity. If adding activity launch later, API 34+ requires the activity `PendingIntent` overload of `startActivityAndCollapse`. Require unlock before any operation that can enable injection or expose settings. Do not assume a tile temporary allowlist satisfies location while-in-use checks. |
| Device locked | Authenticated stops and cancellation can run when delivered; enabling waits for unlock and visible confirmation | No claim that Samsung delivers every Tasker event or displays every notification. Notification denial must produce a visible diagnostic on next app open. |
| Process killed | Recover persistent mode/configuration; invalidate stale starts; reconcile persisted “running” to unknown/stopped only after cleanup | `onDestroy()` is not guaranteed. No infinite restart alarm, sticky auto-start, or assumption that process death removes framework test providers. App reopen should attempt safe owned-provider cleanup and disclose uncertainty. |
| Device reboot | Invalidate automation freshness, pending requests and observed running status; default to no injection | Boot receipt does not generally authorize location FGS admission. Do not silently restore injection. Reconcile Tasker's current specific-car connection before a new disconnect event can request activation. |
| Explicit user force-stop | No autonomous recovery promise | Android force-stop suppresses ordinary app execution/receivers/alarms until the user reopens the app; Tasker can be force-stopped independently. Document reopening and resynchronization. |

## Framework findings

### Foreground services and while-in-use permission

Android 12 introduced restrictions on background FGS starts for apps targeting API 31+. Android 14 tightens foreground-service types and checks while-in-use prerequisites at service creation/promotion. A visible activity is the conservative admission path for this application. The AOSP `ActiveServices` code independently calculates FGS admission and `shouldAllowFgsWhileInUsePermissionLocked`, with special system, visible-UID, privileged, temporary-allowlist and device-owner paths. These privileges are not granted by accepting a Tasker broadcast. The selected design intentionally does not rely on a particular caller's exemption transferring to MockGPS.

Android 14–17 behavior must be evaluated by both device OS and app target SDK. Android 15/16 tighten additional boot/background execution cases; none supplies a general delayed Bluetooth broadcast exemption for this app. Current Android 17-specific changes were not independently verified against a released API 37 SDK or Samsung image in this environment. Keep target/compile migration separate from claiming runtime compatibility. Catch `ForegroundServiceStartNotAllowedException` / `SecurityException` and service-promotion failure without reporting success.

For a location FGS declare `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_LOCATION` and `foregroundServiceType="location"`, and require the appropriate granted location runtime permission before visible start. Foreground-service notification behavior and POST_NOTIFICATIONS permission are separate: lack of notification permission must not be confused with successful automation notification delivery. Do not collect or persist genuine-location history merely to support the controller.

### Quick Settings

A tile is bound by System UI and protected with `android.permission.BIND_QUICK_SETTINGS_TILE`. Its existence is not general IPC authorization. The AOSP TileService implementation explicitly documents `unlockAndRun`, secure lock-state checks, and the API 34 replacement of direct Intent activity launch with the `PendingIntent` overload of `startActivityAndCollapse`. The conservative tile only switches AUTO/FORCE_REAL; activation takes place separately in the visible activity; FORCE_REAL can be applied without activating injection. The tile label should report selected mode and subtitle observed/pending/error state separately. Samsung panel placement, lock-screen availability, subtitle display and refresh timing require physical testing; the app screen is the fallback.

### Test providers and genuine-location restoration

AOSP `LocationManager` documents that `addTestProvider` replaces a same-name provider, that mock operations require allowed `OPSTR_MOCK_LOCATION`, and that `setTestProviderLocation` remains identifiable via `Location.isMock()`. Timestamp and accuracy fields must be complete; supply wall time and elapsed realtime. `removeTestProvider` is a no-op if none exists, but may throw if the mock AppOp is revoked. Catch and surface that failure; never silently mark cleanup complete.

`MockableLocationProvider.setMockProvider(null)` switches back to the retained real provider. This is source evidence for restoring the provider implementation, not a guarantee of immediate GNSS reacquisition or deletion of every client's cached fix. `clearTestProviderLocation` is documented as a no-op and cannot be used to prove cache clearing. The app controls named Android test providers, not every fused/network/OEM location source. Confirm recovery with a newly timestamped, non-mock location in a separate client; the controller should say “injection stopped; genuine fix not verified.”

### Tasker and Samsung

Tasker owns the truth about the selected paired vehicle and sends authenticated, fresh connection-state snapshots/events. MockGPS owns policy and delayed requests. Tasker must match a specific device address/name, reconcile on Tasker start/profile enable and after reboot, and report unknown/radio-off as unknown rather than infer a trustworthy vehicle disconnect. Avoid listening to arbitrary Bluetooth ACL broadcasts and treating them as a selected-car disconnect. Handle Tasker restart, event duplication and sequence/session invalidation explicitly.

Samsung background usage limits, sleeping/deep-sleeping apps, battery restrictions, notification permission, locked state and Tasker delivery are additional variables. Vendor menus vary; do not promise that changing a setting bypasses Android permission rules. Provide a reproducible matrix for battery restrictions both enabled and disabled, plus recovery by reopening apps and reconciling car state. No speculative Tasker XML should be represented as validated.

## Sources

Canonical developer documentation (links supplied; current pages were policy-blocked during this run):

1. [Restrictions on starting foreground services from the background](https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start), including while-in-use restrictions and exceptions.
2. [Foreground-service types: location](https://developer.android.com/develop/background-work/services/fgs/service-types#location).
3. [Android 14 foreground-service type requirements](https://developer.android.com/about/versions/14/changes/fgs-types-required).
4. [Android 15 behavior changes](https://developer.android.com/about/versions/15/behavior-changes-15), [Android 16 changes](https://developer.android.com/about/versions/16/behavior-changes-16), [Android 17](https://developer.android.com/about/versions/17).
5. [TileService reference](https://developer.android.com/reference/android/service/quicksettings/TileService), [Quick Settings tiles](https://developer.android.com/develop/ui/views/quicksettings-tiles).
6. [LocationManager reference](https://developer.android.com/reference/android/location/LocationManager), [Location mock flag](https://developer.android.com/reference/android/location/Location#isMock()).
7. [Tasker Bluetooth Connected state](https://tasker.joaoapps.com/userguide/en/help/sh_bt_connected.html); current vendor behavior not fetched or tested.
8. [Samsung background usage limits](https://www.samsung.com/uk/support/mobile-devices/how-do-i-stop-my-apps-from-sleeping-on-my-samsung-phone/); menu availability on One UI 9 unverified.

Authoritative source actually fetched from Google's AOSP mirror:

* [`ActiveServices.java`](https://github.com/aosp-mirror/platform_frameworks_base/blob/1cdfff555f4a21f71ccc978290e2e212e2f8b168/services/core/java/com/android/server/am/ActiveServices.java), FGS admission and `shouldAllowFgsWhileInUsePermissionLocked`.
* [`TileService.java`](https://github.com/aosp-mirror/platform_frameworks_base/blob/1cdfff555f4a21f71ccc978290e2e212e2f8b168/core/java/android/service/quicksettings/TileService.java), lock-state handling and activity PendingIntent launch.
* [`LocationManager.java`](https://github.com/aosp-mirror/platform_frameworks_base/blob/1cdfff555f4a21f71ccc978290e2e212e2f8b168/location/java/android/location/LocationManager.java), test-provider and mock-flag API contract.
* [`MockableLocationProvider.java`](https://github.com/aosp-mirror/platform_frameworks_base/blob/1cdfff555f4a21f71ccc978290e2e212e2f8b168/services/core/java/com/android/server/location/provider/MockableLocationProvider.java), switching between real and mock implementations.

No source from these references was copied into the application. This review borrows supported API contracts and design concepts only.

## Post-implementation verification (2026-10-23)

The canonical Android FGS/background restrictions, service types, TileService, application-ID and Android17 target-behavior pages were now successfully fetched over verified HTTPS (HTTP200). The FGS restrictions page reports last updated2026-10-16; the Android17 behavior page reports2026-10-22. This supersedes the initial fetch-blocked limitation above, not the absence of Samsung runtime evidence.

Current documentation still distinguishes two gates: background FGS start eligibility and location while-in-use eligibility. A normal Tasker broadcast establishes neither. Official mechanisms can provide **conditional** eligibility: a genuine Companion Device Manager association/presence plus its documented FGS privilege; explicit user battery-optimization exclusion for the start gate; and separately granted background-location permission or a documented while-in-use exception for the location gate. Background location alone does not grant a general FGS-start exemption. None guarantees OEM delivery or justifies pretending this simulator is a companion service. Adding broad background location, a long-lived idle FGS, a fake association or a visible overlay solely to bypass restrictions is not part of this candidate.

Notification/widget interaction or a PendingIntent sent by another visible app can have documented user-interaction exceptions; they are not an unattended Bluetooth-disconnect guarantee. TileService.unlockAndRun is documented to require unlock, and tile lifecycle callbacks may occur in independently bound service instances. A tile callback is not treated as universal location permission. The candidate keeps the tile as a policy override and starts only from a visible explicit Activity confirmation.

The Android17 target-behavior page includes RFCOMM InputStream closure behavior and other API37 changes; it does not establish a new general delayed Tasker/location-FGS exemption. The app remains compile/target36 and uses Tasker-reported connection state, not an RFCOMM socket. No claim of Android17/OneUI9 certification follows from these documentation fetches or an API36 emulator run. A fully unattended mode would need a separately scoped permissions/association prototype and real-device evidence before changing this privacy-focused contract.

Reviewed links: [FGS restrictions](https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start), [location FGS requirements](https://developer.android.com/develop/background-work/services/fgs/service-types#location), [Android17 target changes](https://developer.android.com/about/versions/17/behavior-changes-17), [TileService](https://developer.android.com/reference/android/service/quicksettings/TileService).

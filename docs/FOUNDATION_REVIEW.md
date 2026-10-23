# Foundation review and implementation gate

Reviewed 2026-10-23. Decision: **continue with MockGPS**, incrementally harden the existing native application, and retain its map/location-selection UI. No alternative repository establishes that a delayed Tasker broadcast may start a location foreground service on Android 17 or Samsung One UI 9. Starting simulation from a visible user-confirmed activity is the dependable design baseline. An automation request can record pending intent and offer a notification requiring explicit user interaction when background startup is not permitted; receiving a broadcast is not proof of permission to start a foreground service.

## Evidence and scope

All seven requested repositories existed and were read successfully through native HTTPS Git on the review date. Shallow checkouts of their default HEADs were inspected; the observations below describe source, not independently executed apps or passing test runs. Research checkouts remain outside the product checkout in `/workspace/research`. No alternative's application code was copied. Android 17 and Samsung runtime testing was unavailable. A target/compile SDK, a README compatibility statement, and test source presence are not hardware compatibility evidence.

| Repository | Examined commit | License at that commit |
| --- | --- | --- |
| [BuriXon-code/MockGPS](https://github.com/BuriXon-code/MockGPS) | `2a2e4322596dcb2b37327999dce3f0d079d35dea` | GPL-3.0 license text; preserve upstream copyright and source obligations |
| [mcastillof/FakeTraveler](https://github.com/mcastillof/FakeTraveler) | `ff7ddfdcbe0063d4f77a73e4817fcc078fd5df38` | GPL-3.0 license text |
| [Sriharan-S/gps-mock](https://github.com/Sriharan-S/gps-mock) | `ac9b8179647d5bcb1bcaf3b7101e72095e35327d` | MIT |
| [AlexMelanFromRingo/mirage](https://github.com/AlexMelanFromRingo/mirage) | `0dce31454cd4487187a0c67586d57df446be9969` | MIT |
| [shortcuts/locationjoystick](https://github.com/shortcuts/locationjoystick) | `e55a20e9200aa033f2fc1b77484e50745ac4633b` | MIT, plus separately licensed third-party material |
| [0xfnzero/gps-locator](https://github.com/0xfnzero/gps-locator) | `7845ff8c94fef7d0afd2bcc4fa34ad3575635f29` | MIT |
| [narumiruna/kestrel](https://github.com/narumiruna/kestrel) | `496fa2ecb7994050a3a3e35db9ecd4a2037f16b3` | AGPL-3.0 license text |

The selected fork `abhi-k9/MockGPS` began at the same full SHA as the examined upstream. No migration is justified by source review: the alternatives add either a UI/framework migration or unrelated routing, overlay, HTTP, account, or cloud subsystems, while none resolves Android's foreground-service restrictions.

## Architectural comparison

“Tests present” counts source files discovered under Android/JVM `test` and `androidTest` directories (and Dart `test` for gps-mock), not executed tests or assertions. Non-Android CLI/backend suites are described separately. Absence below means no relevant implementation was found in the inspected tree, not a proof about all historical versions.

| Project | Lifecycle and provider handling | State/error model | Exposure and external control | Tile / Tasker feasibility | Tests and maintenance cost |
| --- | --- | --- | --- | --- | --- |
| MockGPS | Native Kotlin location FGS; `gps`/`network` providers. Sticky/restart behavior and swallowed failures need replacement; explicit cleanup exists but can conceal errors. | SharedPreferences flags currently mix intent and observation; coordinates and genuine location persisted. | Exported receiver and service, with no authenticated command contract. | No native tile initially. Broadcast transport is easy for Tasker but needs opt-in authentication and cannot confer FGS exemption. | 0 test files. Smallest native change surface; MapLibre and AndroidX dependencies. Existing debug build verified separately during onboarding. |
| FakeTraveler | Java bound `MockedLocationService`; `onUnbind` cancels tasks and shuts providers; a Timer drives updates. This is not a proven location-FGS architecture for delayed background automation. | LiveData exposes bound/mocked/error state. `SecurityException` construction failure is reported. | No inspected authenticated Tasker endpoint or tile. Prefer internal binding model where appropriate. | Would require tile, secure control, delayed policy, and Android background lifecycle work. | 0 test files found. Small app; F-Droid link, fastlane metadata and GitHub release workflow are established distribution artifacts, not Android17 validation. Java-to-Kotlin/UI migration would add work. |
| gps-mock | Flutter UI with native Kotlin `MockingService`, controller/store and native tiles. | Error notification paths and a shared native state store exist; favorites/history/routes broaden persistence requirements. | Native Android internals should be reviewed independently; no inferred security guarantee from README. | **Native `BaseFavoriteTileService` verified**. Unlocks when locked and uses `MockControlActivity` through `startActivityAndCollapse`/immutable PendingIntent for start/toggle. This is the useful pattern; the source explicitly warns background FGS starts are unreliable. | 5 Dart test files found, not Android hardware proof. Flutter, map/search/routing/offline/history features increase migration cost. |
| Mirage | Kotlin Compose FGS delegates provider operations to `MockLocationEngine`; coroutine scope/session cancellation and foreground-start error paths exist. | `MockController` exposes in-memory `StateFlow<MockStatus>` separately from persistent `MirageStore` session/configuration. | Internal controller/service design reduces direct command surface. No native Tasker contract/tile found. | Would require the same authenticated command and tile work. The controller catches FGS rejection; it does not make starts permitted. | 0 test files found. Good separation concepts, but Compose/DataStore/coroutine migration unnecessary for this fork. Some README “convincing fix” goals are outside this project's transparent testing scope. |
| locationjoystick | Modular Kotlin app with service/controller/replay components. Larger overlay/group-sync stack. | Cohesive use cases, typed models, cancellation and motion/state tests. | **Versioned `/api/v1/` routes verified**, bearer authentication via constant-time `MessageDigest.isEqual` in `LeaderSyncServer`, structured status/error responses. HTTP adds port/parser/network risks not needed for Tasker. | No native Quick Settings TileService found; HTTP could be called by Tasker only while endpoint runs, which does not solve waking/staring an Android location FGS. | 163 Android/JVM test files found, including route cancellation, state and control API tests. Strong test patterns; large multi-module/DI/overlay/API scope raises migration effort. Do not import accessibility/overlay subsystems. |
| gps-locator | Native Kotlin service; provider/setup policy and coordinate validation separated from UI. | Status schema includes app/build/readiness/activity; receiver returns result codes. Some responses accept before asynchronous service startup, so do not adopt that as proof of success. | **ADB enterprise receiver verified** and protected by `android.permission.DUMP`; suitable shell boundary, unsuitable for ordinarily signed Tasker. Service unexported. | No tile found. DUMP permission must not be used as the only Tasker control permission. | 21 Android/JVM test files found; additional Node CLI/server tests and JUnit report tooling. Useful status/failure/report concepts; cloud, fleet, licensing and route systems unnecessary. |
| Kestrel | Dedicated `MockProviderManager` owns provider operations and timestamps; service/session layering. `runCatching` around injection/cleanup conceals uncertainty in some paths, so do not copy blindly. | Separate model/state/presentation and route logic; user previews separated from activation. | Optional backend/web remote with expiring commands; README explicitly says polling cannot wake a killed app. | No native tile found. Cloud transport adds no location FGS start exemption. | 61 Android/JVM test files found, plus backend/web tooling and detekt. Useful pure-state tests; backend/accounts/cloud would increase dependencies and operational burden. AGPL reuse needs separate licensing review. |

## Android 17 compatibility evidence

| Project | Source SDK declaration examined | Android 17 / One UI 9 evidence from this review |
| --- | --- | --- |
| MockGPS | compile/target 36, min 29 | README says tested Android16; no Android17/Samsung run performed |
| FakeTraveler | compile/target 36, min 21 | No hardware execution or Android17 evidence established |
| gps-mock | compile 36; target/min delegated to Flutter toolchain | Native tile/activity source exists; no Android17/Samsung run performed |
| Mirage | compile/target 36, min 26 | No Android17/Samsung execution established |
| locationjoystick | application convention compile/target 36, min 28 | Broad test source exists; no Android17/Samsung execution established |
| gps-locator | compile/target 35, min 26 | Instrumentation/CLI source exists; no Android17/Samsung execution established |
| Kestrel | compile 37, target 36, min 29 | Compiling API37 is not target37 behavior or Samsung runtime proof |

The target fork should not claim Android17 compatibility solely by updating `targetSdk`. Target36 on Android17 still has runtime and applicable all-app behavior changes; raising the target is a separate compatibility exercise. Use current authoritative platform documentation and the actual device matrix for transition claims.

## Traceable source anchors

Paths are relative to each examined repository at the pinned commit above. Use `https://github.com/<owner>/<repo>/blob/<full-sha>/<path>` to reproduce the inspection.

- MockGPS: `app/src/main/AndroidManifest.xml`, `MockGpsReceiver.kt`, `BootReceiver.kt`, `MockLocationService.kt`, `State.kt` and `MainActivity.kt` under `app/src/main/java/dev/burixon/mockgps/`.
- FakeTraveler: `app/src/main/java/cl/coders/faketraveler/MockedLocationService.java` (`onUnbind`, `startMockedService`, `MockedTask`), `MockedLocationProvider.java`; `README.md`, `fastlane/`, `.github/workflows/`.
- gps-mock: `android/app/src/main/kotlin/com/sriharan/gps_mock/tiles/FavoriteTileServices.kt` (`onStartListening`, `unlockAndRun`, `toggleViaTrampoline`); `MockControlActivity.kt`, `MockingService.kt`, `MockController.kt`, `MockStateStore.kt` in the parent package; `test/tile_slots_test.dart`, `test/control_deck_test.dart`.
- Mirage: `app/src/main/java/com/melan/mirage/core/MockController.kt`, `Models.kt`, `MirageStore.kt`, `MockLocationEngine.kt`; `service/MockLocationService.kt` (`promoteToForeground`, session job, `onDestroy`).
- locationjoystick: `core/location/src/main/kotlin/com/locationjoystick/core/location/TokenAuthHttpServer.kt`, `LeaderSyncServer.kt` (`handleApiRequest`, bearer check), `ControlApiRoutes.kt`; corresponding `core/location/src/test/` tests, `docs/wiki/control-api.html`.
- gps-locator: `app/src/main/AndroidManifest.xml`; `app/src/main/java/com/gpsupdater/app/enterprise/EnterpriseControlReceiver.kt` (`GET_DEVICE_STATUS`, `reject`, `accept`); `location/MockLocationSetupPolicy.kt`; `app/src/androidTest/java/com/gpsupdater/app/EnterpriseControlSmokeTest.kt`; `desktop-cli/test/`.
- Kestrel: `app/src/main/java/dev/narumi/kestrel/core/location/MockProviderManager.kt`, `LocationService.kt`; `app/src/test/java/dev/narumi/kestrel/`; `README.md` remote-control limitations.

## Concepts to reuse, without copying implementation

1. Keep mode policy, pending action generation, desired state and observed provider state separate. Use a deterministic reducer or serialized coordinator that can be tested without a running Android process.
2. Follow gps-mock's user-visible activity approach for tile starts where needed; use supported PendingIntent APIs. A tile selecting AUTO is a policy change, not proof that injection started.
3. Follow Mirage's separation of persistent configuration from runtime observation. Persist enough uncertainty for recovery, but never restore “running” merely because a preference says so.
4. Give the engine a narrow platform boundary and explicit ownership. Roll back partial setup; report injection or removal failure. Adopt timestamp discipline from the small provider managers; do not copy swallowed errors.
5. Use locationjoystick's versioned commands, bounded parsing and tested authorization concepts without introducing a network listener. For separately signed Tasker, explicit opt-in capability authentication is simpler than signature-only permissions; protect credentials from logs/backups and permit revocation.
6. Use gps-locator's structured status/results concept, but distinguish accepted requests from completed service transitions. Shell-only permissions are not Tasker credentials.
7. Use Kestrel/locationjoystick's pure state/cancellation tests and meaningful regression cases. Avoid cloud accounts, route engines, DI frameworks and overlay/accessibility dependencies for this small policy feature.

## Licensing and attribution

Keep MockGPS's GPL-3.0 license and upstream attribution. This review imports ideas only, not source or assets. MIT code could generally be incorporated into a GPL-3.0 work only while retaining its notices and reviewing every copied file/dependency's terms; no such incorporation is needed here. FakeTraveler's GPL terms require matching source/license obligations. Kestrel's AGPL introduces additional network-source obligations and GPL/AGPL combination considerations; do not import its implementation without a deliberate license review. Repository-level license labels do not override third-party notices, asset licenses or per-file exceptions. Do not adopt mock-flag concealment, detection evasion or “convincing location” behavior from any project.

## Phase 0 acceptance decision

The comparative/source gate is complete before substantial refactoring. The engineering plan may proceed with native MockGPS, a serialized mode controller, authenticated opt-in IPC, safe provider cleanup, explicit startup-result handling, a native tile and meaningful tests. No competing implementation supplies compelling evidence for a migration.

The platform gate is conditional on Android documentation, described separately in `PLATFORM_FEASIBILITY.md` when available: visible activity starts and explicit stops can be engineered confidently; delayed external broadcasts must not be assumed to confer location-FGS or while-in-use-permission exceptions. When a permitted start cannot be established, queue a user-visible request and require an explicit visible-app confirmation. Process death/reboot must cancel delayed activation, preserve FORCE_REAL, and reconcile uncertain prior provider state. Native tile interactions must use documented APIs and may bring a visible activity forward. Background request acceptance, notification delivery and provider removal are distinct outcomes.

**Unresolved hardware assumptions:** Samsung One UI 9 tile behavior and lock-screen interaction; Tasker delivery under Samsung battery/auto-start policies; Bluetooth state reconciliation after force-stop/reboot; location-FGS enforcement on the actual Android17 build; partial provider removal behavior and time until clients receive a fresh genuine fix. No physical device, Android17 emulator, or Samsung image was exercised during this research. These are required validation items, not passed tests. The detailed matrix belongs in `SAMSUNG_VALIDATION.md`.

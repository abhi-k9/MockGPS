# Security and correctness audit

Reviewed baseline `2a2e4322596dcb2b37327999dce3f0d079d35dea` and the hardening changes in this fork. Severity reflects the app's role as a user-authorized developer mock-location application. Findings were established by source inspection; exploitability requiring Android mock-app selection, permission or background-start eligibility is qualified below. Baseline line references remain stable through refactoring. The full baseline inspection notes are in [docs/BASELINE_AUDIT_NOTES.md](docs/BASELINE_AUDIT_NOTES.md).

This is an engineering audit, not certification or a claim of production readiness. See [TESTING.md](TESTING.md) for actual executed checks and [docs/SAMSUNG_VALIDATION.md](docs/SAMSUNG_VALIDATION.md) for unexecuted hardware procedures.

## Confirmed findings and remediation

### A01 — High: unrestricted external commands

- **Affected baseline:** `app/src/main/AndroidManifest.xml:40–42`, `MockGpsReceiver.kt:27–39`, `State.kt:205–211`.
- **Impact/reproduction:** A separately signed installed application can explicitly address the exported receiver without a permission or credential. Broadcast control defaults to enabled. Commands alter coordinates, drift, boot settings, start intention and stop state. Injection still requires the user-selected mock-app AppOp and any platform foreground-service prerequisites; configuration mutation does not.
- **Recommended/implemented:** `MockGpsReceiver` now accepts only the exact versioned CONTROL action and exact schema. `core/CommandAuth.kt` requires enabled integration, a 256-bit random capability, recognized command, current/past timestamp no older than 60 seconds, and strictly increasing event time. `AutomationAccess.kt` defaults disabled, creates the credential on opt-in and removes it on revocation. `ControlCenter.external` serializes validation and replay watermark persistence. Legacy `on`, `set`, `drift`, boot and arbitrary extras are rejected. External commands cannot directly start simulation or switch to AUTO/MANUAL_MOCK.
- **Remaining risk:** Capability possession grants the documented control scope; it does not authenticate a caller package. Tasker exports/backups, clipboard managers or a compromised device can expose the credential. Clock rollback may require waiting for the last accepted timestamp; stale commands fail closed. Revoke after suspected exposure. See [docs/SECURITY.md](docs/SECURITY.md).

### A02 — High: exported internal location service

- **Affected baseline:** `AndroidManifest.xml:57–61`, `MockLocationService.kt:132–207`.
- **Impact/reproduction:** An application can address the service directly with legacy start/stop commands and bypass the receiver's enable switch, subject to OS service-start rules.
- **Recommended/implemented:** `MockLocationService` is non-exported. Its only start extra is an internal generation; `ControlCenter.canRun` verifies current desired state, mode and generation before provider work. Null/redelivered/stale intents cannot reactivate it.
- **Remaining risk:** Same-UID/application compromise is outside this IPC boundary. Android owns final service admission and AppOps enforcement.

### A03 — Medium: custom restart on exported boot receiver

- **Affected baseline:** `AndroidManifest.xml:44–55`, `BootReceiver.kt:9–11,36–48`.
- **Impact/reproduction:** Ordinary apps cannot forge protected BOOT_COMPLETED, but can explicitly send the receiver's custom RESTART_SERVICE action while desired=true, bypassing broadcast-control settings.
- **Recommended/implemented:** Boot receiver is non-exported, accepts only actual BOOT_COMPLETED/MY_PACKAGE_REPLACED and performs safe inactive recovery. Custom restart action and location-service restart alarms were removed. Returning after boot/update never silently resumes injection.
- **Remaining risk:** Package force-stop can suppress delivery until launch. Process/boot recovery can only clean providers once Android allows this app to run. No claim of immediate cleanup after arbitrary process kill is made.

### A04 — High correctness: startup failures reported as success

- **Affected baseline:** `MockGpsReceiver.kt:138–181`, `BootReceiver.kt:57–95`, service `144–167,210–247`, activity `934–964`.
- **Impact/reproduction:** Missing mock-app selection, permissions or a disallowed background start can leave desired=true while toast says “Mocking started.” The service also shows success after provider setup failed.
- **Recommended/implemented:** Typed `PolicySnapshot` separates desired state, service state, provider readiness, confirmed injection and transition result. Service transitions to INJECTING only after both provider update calls return successfully. Visible start validates configuration and permissions; restricted starts and provider failures record sanitized codes. External ACCEPTED means authenticated request admission, not injection success. Delayed automation requests visible confirmation rather than assuming Bluetooth broadcasts supply an exemption.
- **Remaining risk:** Android accepting provider updates proves neither every consumer's observed location nor a fresh genuine fix. Samsung runtime eligibility remains unverified.

### A05 — High correctness: injection/cleanup failures hidden

- **Affected baseline:** service `268–357,491–521,695–748,788–807`.
- **Impact/reproduction:** Revoke mock-app selection or otherwise fail provider operations: exceptions disappear, “running” may remain true, and stop reports false certainty despite failed removal.
- **Recommended/implemented:** `engine/ProviderEngine.kt` returns explicit creation, injection and cleanup results, rolls back partial creation/injection failure, attempts removal even if disabling fails and retains uncertain ownership for retry. `AndroidProviderPort.kt` journals ownership before the create binder call and clears it only after successful removal. Recovery attempts journaled cleanup after process death. Service cancels callbacks and reports unknown provider/injection state when cleanup is uncertain. Errors omit raw exceptions/coordinates.
- **Remaining risk:** Loss of AppOp can block cleanup. App uninstall/data-clear/OS/provider replacement can destroy ownership evidence. Successful API removal restores the underlying provider path, not an instantaneous fresh genuine fix or all fused/vendor caches. Recovery procedures remain necessary.

### A06 — High correctness: silent `(0,0)` selection

- **Affected baseline:** `State.kt:73–85`, receiver `140`, boot `57`, service `234`.
- **Impact/reproduction:** Send `on` to a fresh installation without selecting a coordinate; baseline invents `(0,0)`.
- **Recommended/implemented:** Removed `ensureLocation`. Shared configuration validates finite latitude/longitude and requires an explicit selection. Engine rejects invalid coordinates/metadata. Migration discards legacy `(0,0)` because it cannot distinguish a deliberate old selection from a fabricated default; users can explicitly select `(0,0)` again.
- **Remaining risk:** Users must review their selected simulation point before confirmation.

### A07 — Medium: unsafe retries and stale restart policy

- **Affected baseline:** service `207,229,625–661,755–785`, boot `21–33`.
- **Impact/reproduction:** Provider setup failure schedules one-second retries. Sticky service recovery and retained alarms rely on desired=true; boot opt-in starts regardless of previously stopped desired state. A particular stop/alarm race was not reproduced; baseline does clear desired before ordinary explicit stop. Queued legacy ON commands have no generation protection.
- **Recommended/implemented:** Non-sticky service, no automatic location-service retry/restart alarm, generation validation, safe process recovery, distinct delayed automation request alarm. FORCE_REAL cancels pending work and remains selected until an explicit user return to AUTO; even visible manual start cannot bypass FORCE_REAL. Connection changes cancel delayed activation. Deadline delivery itself never starts injection.
- **Remaining risk:** Inexact request alarms can be delayed by Doze/OEM policy. Tasker supplies connection truth; missed or dishonest events cannot be independently verified by this app. Unknown/recovered state requires fresh reconciliation and visible confirmation.

### A08 — Medium: persisted Boolean mistaken for observed state

- **Affected baseline:** `State.kt:14–16,122–168`, activity `789–792,857–869,969–990`, service `238–247`.
- **Impact/reproduction:** Kill process without onDestroy; old service-running=true survives. UI uses it as actual service/provider truth. Stop writes false before removal is known.
- **Recommended/implemented:** Pure policy reducer plus main-looper `ControlCenter` serialize commands and effects. Atomic `PolicyStore` transactions retain mode/settings/watermark/generation; new-process load invalidates live observations and pending work. Native tile listens to the same controller, describes errors/pending/unknown status and represents selected FORCE_REAL versus AUTO rather than claiming genuine GPS recovery.
- **Remaining risk:** No app-level state snapshot can independently guarantee LocationManager or consumer cache state after process death. Hardware tests must compare both app status and platform observations.

### A09 — Medium privacy: exact genuine location retained/mislabeled

- **Affected baseline:** `State.kt:29,88–120`, activity `1168–1172,1240–1286,1682`.
- **Impact/reproduction:** Location updates persist precise genuine coordinates indefinitely in device-protected private preferences. Cached last-known mock coordinates can be captured/displayed as “real” because mock flag and age were not checked.
- **Recommended/implemented:** Genuine fixes remain memory-only; migration clears old device-protected preferences including exact genuine coordinates and permissive control flags. Visible activity filters mock/stale fixes. Backup is disabled and explicit cloud/device-transfer exclusions protect app data. Map privacy disclosure explains external tile requests; no history database or telemetry was added.
- **Remaining risk:** Selected simulation coordinates and map camera persist locally; map areas/IP reach the map provider. A prior camera view can approximate an area of interest, so clearing app data is the definitive local-data removal. Third-party screenshots/backups taken outside this app remain outside its control.

### A10 — Low/Medium: redundant work and coordinate notification disclosure

- **Affected baseline:** service `91–104,561–595`, activity `104–115,1103–1122`.
- **Impact/reproduction:** Notification with exact coordinates is rebuilt every second and has no private visibility setting. UI polls/rebuilds marker every second.
- **Recommended/implemented:** Event-driven controller listeners update UI and tile. Service notification updates for transitions rather than every injection, has private visibility, and omits coordinates. The injection cadence remains one second for provider freshness; drift cadence is separate.
- **Remaining risk:** Android still displays the required foreground-service disclosure and users can observe selected coordinates in the visible map; that is intentional transparency.

### A11 — Medium correctness: invalid drift at geographic boundaries

- **Affected baseline:** service `403–427,466–479`.
- **Impact/reproduction:** Pick a pole or antimeridian with drift enabled. Longitude division approaches zero, longitude can leave ±180 and latitude can leave ±90 without revalidation.
- **Recommended/implemented:** `engine/Coordinates.offset` uses bounded spherical offsets, clamps inverse-trigonometric input and wraps longitude. Location metadata must be finite/positive; timestamps use wall clock and monotonic elapsed realtime. Drift resets when the selected base changes.
- **Remaining risk:** Provider/consumer accuracy remains a simulation parameter, not measured genuine-world accuracy.

### A12 — Medium assurance: absent tests/CI; failing lint

- **Affected baseline:** No test sources or GitHub Actions; baseline lint errors in debug manifest:7, activity:496 and service:329.
- **Impact/reproduction:** Baseline debug build passed, but unit task was NO-SOURCE and lint had three errors/76 warnings. No automatic protection for authorization, policy, partial provider failure or stale work.
- **Recommended/implemented:** Meaningful pure Kotlin policy/auth/engine tests; Android component/storage/lifecycle instrumentation; pinned CI actions for clean debug/unsigned-release builds, unit tests, lint, instrumentation packaging and Android 16 emulator instrumentation; dependency-review workflow for repositories where supported. Version catalog is the active dependency source. Existing wrong-constant code was replaced. MockLocation has one narrow documented manifest suppression because a dedicated simulation app intentionally needs mock-location capability in release; no broad lint baseline or abort disabling is introduced.
- **Remaining risk:** Added workflows are configuration until actually executed by GitHub; emulator tests do not establish Samsung Android 17 behavior. Actual local results are recorded in TESTING.md; unexecuted checks must not be counted as passed.

## Final hardening review corrections

- **Storage failure, medium correctness:** A first hardening draft persisted policy before attempting a safety stop. A full/unavailable preference store could throw before provider removal. Final `ControlCenter.save` fails closed in memory, cancels pending work and still attempts provider cleanup; a failed persisted start cannot launch the service. Recovery attempts cleanup even if migration/policy persistence fails. Accepted external requests require a durable replay watermark. Credential-update failure is surfaced instead of claiming revocation succeeded. Real device/full-disk fault injection remains unexecuted.
- **Legacy provider uncertainty, medium correctness:** Old stopped/desired booleans were not trustworthy because baseline cleanup suppressed failures. Migration now treats any nonempty legacy installation as unverified, retries clearing legacy privacy data after a partial migration, and avoids claiming journal-based cleanup proves old unjournaled providers absent. Recovery documentation covers selecting the mock app and rebooting before normal use.
- **FORCE_REAL precedence:** Final reducer guards both manual start and direct manual-mode selection while FORCE_REAL is active. Only explicit return to AUTO clears the override. The visible manual button cannot bypass it; regression tests exercise both event paths.

## Validation checkpoint

A clean serialized Gradle validation run recorded `BUILD SUCCESSFUL`. Current unit result XML records **60 tests, 0 failures, 0 errors, 0 skipped**: 15 command authorization, 23 policy, 6 tile presentation and 16 provider engine. Android lint completed with **0 errors and 25 warnings**. Debug, unsigned release and instrumentation APKs were compiled. Instrumentation was **not executed** locally, and GitHub Actions/Samsung hardware validation remain unexecuted. Final affected builds/tests/lint/instrumentation compilation also passed with strict Gradle dependency verification after the storage/migration/override corrections. `TESTING.md` and `docs/VALIDATION.json` are the final run record.

## Permissions and exposure review

- Launcher activity is exported because it is the normal user entry point; it does not process external commands or automatically start simulation from incoming extras.
- The capability receiver is the only ordinary external automation entry point. Delay/boot receivers and location service are non-exported. Tile service is guarded by system BIND_QUICK_SETTINGS_TILE permission; switching back to AUTO requires unlock, while safety stop is permitted when locked.
- Fine/coarse location support the map and location FGS prerequisites; FOREGROUND_SERVICE/FOREGROUND_SERVICE_LOCATION describe the service; notification permission supports status/request notices; RECEIVE_BOOT_COMPLETED supports safe inactive recovery. INTERNET and merged ACCESS_NETWORK_STATE support map requests. Dependency WIFI_STATE permission is removed. No background location, root, Shizuku, Accessibility, exact-alarm or Bluetooth permission was added. Tasker owns vehicle detection.
- HTTPS remains enabled with cleartext disabled. No network control server, application analytics, location-history service or raw exception logging was introduced. Android mock flags/disclosures are preserved.

## Dependencies, licensing and release review

The wrapper distribution uses its pinned SHA-256 and standard TLS trust. Build/plugin/runtime versions are pinned in the version catalog; no dynamic dependency version or new backend subsystem is required. CI actions are pinned to commits and use read-only repository permissions. Dependency review is conditional on feature availability; no clean vulnerability scan is claimed before that check runs. Dependency pinning does not eliminate supply-chain risk, and advisories must continue to be monitored.

The fork retains GPL-3.0 and upstream attribution. No alternative-project implementation code was copied. Existing drawer attribution credits OpenStreetMap, OpenFreeMap and MapLibre; the baseline disabling the SDK widget was not treated as proof of a license violation because that separate attribution already exists. Binary distribution must retain notices and make corresponding source available under GPL-3.0. Release APK is unsigned unless a distributor supplies private signing securely; debug signing does not establish production trust.

## Unresolved external validation

Android 17/One UI 9 background behavior, real-device provider removal, genuine GPS reacquisition, Tasker delivery while locked/optimized and Samsung tile presentation require actual target hardware. The conservative implementation deliberately turns an AUTO delayed request into visible confirmation. A successful broadcast is never considered evidence of foreground-service startup, and successful test-provider removal is never called a fresh genuine GPS fix.

## Independent post-implementation review

The previously delivered source is not the final development candidate. A second audit confirmed and repaired timestamp-order collisions/clock rollback, callback loss of persisted legacy cleanup uncertainty, CI SDK provisioning and cold-cache verification omissions. The fork now uses a separate installed package and API2 sequence contract. See [POST_IMPLEMENTATION_AUDIT.md](docs/POST_IMPLEMENTATION_AUDIT.md), [CI_REPAIR.md](docs/CI_REPAIR.md) and [IDENTITY_AND_SIGNING.md](docs/IDENTITY_AND_SIGNING.md). Current exact results/commit are in TESTING.md and VALIDATION.json; the earlier60-test/25-warning paragraph is historical evidence, not a claim about newer code.

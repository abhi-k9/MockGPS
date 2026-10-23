# Security and privacy model

MockGPS is a developer location-testing application. It preserves Android's mock-location flag and normal disclosures. It does not hide simulation, impersonate genuine fixes, bypass third-party detection, require root/Shizuku/Accessibility, or provide a network-control server.

## Trust boundaries

* The user and Android's selected mock-location AppOp authorize provider operations. Ordinary app installation alone is insufficient.
* The activity is the human control surface. Enabling injection requires a visible user action, a valid selected location and applicable permissions.
* The foreground location service is internal. External automation cannot address it as an exported service.
* A narrowly exported explicit command receiver accepts a versioned capability-authenticated contract only when the user enabled integration. Tasker is separately signed; a signature-only permission would prevent this integration and is therefore not the authorization mechanism.
* The tile is a real Android `TileService` bound by System UI with `BIND_QUICK_SETTINGS_TILE`. It toggles AUTO/FORCE_REAL and never starts injection. Returning to AUTO requires unlock; actual activation requires a separate visible-activity confirmation.
* Android scheduling, actual service admission, provider behavior and Samsung background restrictions remain platform trust boundaries. A successful app decision does not prove Android execution.
* Tasker owns the selected-car connection truth. A compromised/misconfigured Tasker installation or a stolen token can submit fresh false events; this app does not authenticate Bluetooth hardware independently.

The application does not trust package names supplied in extras, broadcast delivery as proof of authorization, a persistent “running” Boolean as proof of injection, or an old delayed intent as proof that policy is still valid.

## Capability design and revocation

External integration defaults to disabled, including migration from permissive upstream configuration. Enabling generates 32 cryptographically random bytes represented as 64 hexadecimal characters. The secret is shown/copied only following explicit local interaction. Keep it out of logs, screenshots, notifications and shared diagnostics.

The authenticated `stop` command selects FORCE_REAL. The exact command types, freshness and ordering requirements are in [TASKER.md](TASKER.md). API version, action, enabled setting, supported command, credential and typed timestamp must all validate before dispatch. Tokens are compared without an early-exit string comparison. Unknown/malformed/unsupported requests and any extra keys outside the exact four-key contract are denied. A timestamp must be 0–60 seconds old, never in the future, and strictly greater than the last accepted command timestamp; accepted `status` also consumes time. Ordering and policy transitions must be serialized so concurrent events cannot bypass FORCE_REAL or replay checks.

The token is a bearer capability. It does **not** prove that the sender is Tasker. Token theft permits supported fresh commands until revocation. Replay checks limit reuse of old requests; they are not a substitute for keeping the token secret. A token holder cannot set coordinates, invoke arbitrary service commands, enable manual simulation, or clear FORCE_REAL through this protocol. Revoke disables integration, invalidates the secret and cancels pending activation. Reenabling creates a new token; update Tasker after deliberate resynchronization.

The token is private app configuration, not a promise of hardware-backed storage. Android's ordinary app sandbox protects it from unrelated nonprivileged apps. Rooted devices, a compromised OS, debugging access, token screenshots/clipboard exposure, and Tasker backup/export are outside that guarantee. Application backup is disabled; Tasker's independently managed backups can still contain its copy. Protect them and rotate after exposure. Device encryption/secure lock and a trustworthy Tasker installation are recommended. Do not include secrets in Git or signing material.

## Safe mode policy

Modes and observed execution are separate. FORCE_REAL is persistent, dominates automatic activation, cancels delayed starts and requests injection shutdown. Changing mode is not proof of successful cleanup or a genuine GNSS fix. Returning to AUTO invalidates old connection knowledge; Tasker must resynchronize. Only an explicit return to AUTO clears FORCE_REAL; even a visible manual-start button cannot bypass it. After returning to AUTO, MANUAL_MOCK is an explicit visible-user operation, not a remote command.

Delayed requests carry a generation. Reconnection, unknown state, manual override, explicit stop, recovery, reboot, revocation and later events invalidate old pending work. Before presenting or confirming a request, recheck policy and current recorded vehicle state. No infinite restart loop, sticky resurrection or background alarm may silently start injection. Process death is not assumed to run `onDestroy()` or to remove framework providers. Recovery attempts cleanup and reports uncertainty if verification is impossible.

## Provider ownership and failure behavior

Use only Android developer test-provider APIs. Validate coordinates for finiteness and range, reject missing selection, and never silently substitute `(0, 0)`. `(0, 0)` remains a legitimate deliberately selected coordinate. Location metadata includes accuracy, wall time and elapsed realtime; mock flags remain intact.

Track providers created by this app. If setup or first injection fails, undo partial setup and report failure; do not report “mocking started.” If shutdown fails, retain an explicit uncertain/cleanup-error state. Revoking the mock-app AppOp before cleanup may prevent removal. A later user-initiated cleanup and a device reboot are recovery options; see [SAMSUNG_VALIDATION.md](SAMSUNG_VALIDATION.md#recovery-without-root).

Removing named test providers restores the framework's real provider implementation where present. It cannot force third-party clients to drop cached mock fixes or establish a fresh genuine fix immediately. The app must distinguish stopped injection from genuine-location reacquisition and avoid claiming control of all fused/OEM providers.

## Permissions and components

| Capability | Purpose and limit |
| --- | --- |
| `ACCESS_COARSE_LOCATION`, `ACCESS_FINE_LOCATION` | Location foreground-service prerequisites and explicitly requested map centering/current-location use. Do not gather or persist a real-location trail. |
| `FOREGROUND_SERVICE` | Long-running, user-visible injection service. Not a general background-start exemption. |
| `FOREGROUND_SERVICE_LOCATION` | Declared location FGS type on modern Android. Runtime requirements still apply. |
| `POST_NOTIFICATIONS` | Ongoing status and actionable user-confirmation requests where Android requires permission. Denial must not be misreported as a delivered request. |
| `RECEIVE_BOOT_COMPLETED` | Invalidate stale work and recover safe state, never silently resume injection. |
| Mock-location AppOp / Developer options selection | Android's developer authorization for `addTestProvider`, injection and removal. It must remain selected until cleanup completes. |
| `BIND_QUICK_SETTINGS_TILE` on tile service | Restricts tile binding to Android System UI. Not requested as a general application privilege. |
| Network access through the map component | Map style/tile requests as described below. No automation endpoint or telemetry is introduced. |

Review the merged manifest, because library manifests can contribute permissions. Internal services and scheduling receivers are nonexported; boot handling accepts required system boot semantics only. The launcher remains exported as normal. The command receiver remains exported for separately signed automation and therefore must enforce authentication for every supported command. No custom unprotected restart entry point should remain.

## Map privacy and stored data

The retained MapLibre/OpenFreeMap map downloads styles, tiles, sprites, fonts and associated resources as required by the selected style. Providers can observe the client's network address and requested tile region. This is not an offline map or an anonymity guarantee. The selected location and map viewport may disclose a region through those requests. Do not add app analytics, advertisements, telemetry, an account or cloud history.

Persist only necessary selected mock coordinates, user configuration, control credential, policy and bounded sanitized transition diagnostics. Do not persist exact genuine-location history; purge the upstream cached-real-location keys during migration. A user-requested map-centering operation should stay in memory and must not be presented as evidence of genuine recovery unless it is a fresh, explicitly nonmock fix. Notifications/status should avoid token and exact-coordinate disclosure. Android private-app storage and disabled backup do not protect data from a compromised device or a user deliberately sharing it.

## Supply chain and release controls

Keep Gradle's distribution checksum and HTTPS verification enabled. Pin direct dependencies and centralize versions; review transitive dependencies and merged permissions. CI should build, test, run lint and check dependencies where practical without broad warning suppressions. Record actual results in `TESTING.md`; passing a build is not a security audit of MapLibre or proof of Samsung compatibility.

Do not commit signing keys. A debug APK is for development and is signed with a development key, not a stable private production identity. The release build must be signed through a separately secured release process. An APK signed with a different certificate cannot update an installed app in place; uninstalling removes local configuration, so record only the necessary mock settings and recreate integration deliberately. Preserve GPL-3.0 and upstream attribution; no code from comparison projects is imported without licensing review.

## Reporting and limitations

Report reproducible security issues privately to the fork maintainer using the repository's configured security/contact mechanism when available. Do not publish a capability token, private coordinates, Tasker backup, signing key, full environment dump or unsanitized logcat. Useful reports include version/commit, OS/One UI build, sanitized state, steps and whether cleanup was attempted.

Physical Samsung Android 17 tests have not run in this environment. Background scheduling/delivery, lock-screen tile behavior, service admission and genuine-location recovery remain hardware validation requirements, not security guarantees derived from unit tests. See [the platform gate](PLATFORM_FEASIBILITY.md) and [validation matrix](SAMSUNG_VALIDATION.md).

## Dependency integrity and storage failure

SHA-256 Gradle verification metadata pins the resolved build/runtime/test artifact set. It was bootstrapped through verified HTTPS and tested in a separate strict run; initial trust is the package repositories, not independent artifact provenance. No advisory scan or third-party SDK packet capture completed locally. Review future metadata updates rather than blindly regenerating them.

A policy persistence failure invalidates the in-memory generation, cancels pending requests, attempts provider cleanup and prevents the start path from proceeding. ACCEPTED external responses require a successfully committed replay watermark; credential-write failure is shown rather than toasted as successful revocation. Hardware storage-fault injection is still untested. Legacy migration retries erasing old genuine-coordinate preferences and treats any legacy installation as uncertain because old false running flags did not prove cleanup.

# MockGPS — transparent developer location simulation

A hardened native Kotlin fork of [BuriXon-code/MockGPS](https://github.com/BuriXon-code/MockGPS), preserving the MapLibre map, selected coordinates, camera position, clipboard control and optional bounded drift. Android's mock-location flags and foreground-service disclosures remain intact. No root, Shizuku, Accessibility service, network control server, cloud backend or detection bypass is used.

**Android 17 / Samsung One UI 9 behavior is not hardware validated.** This is an engineering build, not a production-readiness claim. A Tasker broadcast does not grant permission to launch a location foreground service in the background. Vehicle disconnection therefore requests visible user confirmation; it never silently starts injection.

## Modes and controls

| Mode | Behavior |
| --- | --- |
| FORCE_REAL | Persistent manual override; stops injection and cancels delayed starts. Only an explicit return to AUTO clears it; manual start is blocked while it is selected. “Stopped” does not mean a fresh genuine GPS fix was obtained. |
| AUTO | Tasker reports the designated vehicle's state. Connected/unknown stops injection; disconnected waits at least the configured delay (default 120 seconds), rechecks current policy and requests confirmation. Reconnection cancels pending requests. |
| MANUAL_MOCK | Explicit visible-app start after returning to AUTO. Independent of vehicle events; Stop or FORCE_REAL ends it. Never selected by an external start command. |

The native **MockGPS mode** Quick Settings tile switches FORCE_REAL and AUTO. The label is the selected policy; the subtitle reports observed/pending/error state. Returning to AUTO requires unlock and fresh Tasker reconciliation; selecting AUTO never proves simulation is active. Use the app's confirmation button to start after a request, or explicitly return to AUTO before using its manual-start button. Force Real remains available to retry cleanup after an error.

On process restart, reboot and upgrade, stale starts are invalidated and simulation does not automatically resume. Reopen the app and reconcile Tasker's specific-car state. An inexact alarm implements the minimum delay; Doze/OEM scheduling can postpone notification delivery. Disabling notifications leaves confirmation available in the visible app.

## Build and automated checks

Prerequisites: a **Java 21 JDK** (Gradle daemon), Android SDK platform 36/build tools 36.0.0/platform-tools, Bash, and the usual HTTPS access to Google/Maven/Gradle repositories. App bytecode targets Java 17; minimum Android is 10/API29; compile/target SDK is 36. API36 targeting is not evidence of Android17 compatibility.

```sh
export JAVA_HOME=/path/to/jdk-21
export ANDROID_HOME=/path/to/android-sdk
sdkmanager 'platforms;android-36' 'build-tools;36.0.0' 'platform-tools'
./scripts/android-build.sh
```

This runs a clean debug build, unsigned release build, JVM unit tests, Android lint and instrumentation APK compilation using the checked-in Gradle wrapper and pinned distribution checksum. It does not run device tests. To run those on a disposable connected Android device/emulator:

```sh
./gradlew :app:connectedDebugAndroidTest --max-workers=2
```

In this managed cloud environment, first `source /workspace/setup/android-env.sh`; pass `--project-cache-dir=/workspace/.cache/mockgps-project` to the build script. This activates retained JDK/SDK paths and the required Java proxy configuration. Keep TLS verification enabled.

Outputs: `app/build/outputs/apk/debug/app-debug.apk`, `app/build/outputs/apk/release/app-release-unsigned.apk`, and test/lint reports under `app/build/`. CI retains debug APK/reports and separately runs API36 emulator instrumentation; that CI job is not Samsung validation. See [TESTING.md](TESTING.md) for actual local evidence and gaps.

## Install on Samsung / One UI

1. If upgrading, **stop simulation in the old build first**. Keep the previous APK/source for rollback. Debug and release signing certificates must match for in-place updates; do not uninstall a still-injecting app as a cleanup strategy.
2. Install the engineering debug APK with `adb install -r app/build/outputs/apk/debug/app-debug.apk`, or a package installer. Debug signing is for development only.
3. Enable Android Developer Options and select **MockGPS** under **Select mock location app**.
4. Open MockGPS; grant precise location for its location FGS and map, and notifications for service/failure/automation requests. No background-location permission is requested.
5. Select a coordinate explicitly on the map; missing configuration never falls back to `(0,0)`. That point remains valid if intentionally selected. Legacy `(0,0)` requires reselection because old versions created it implicitly.
6. Select AUTO, then test a manual start/stop in a safe location-testing workflow. Confirm status and a fresh non-mock fix in an independent location client; do not infer recovery from the tile.
7. Edit Quick Settings to add **MockGPS mode**. If Samsung hides its subtitle or delays refresh, use the app screen as the state authority.
8. Optionally enable authenticated automation, reveal/copy its credential and configure **only your specific paired vehicle** in Tasker following [docs/TASKER.md](docs/TASKER.md). Select AUTO and reconcile vehicle state. GUI delay range is 0–3600 seconds; default 120.

Samsung sleeping/deep-sleeping apps, battery restrictions, force-stop and lock-screen behavior may prevent Tasker or notifications from running. Test both restrictive and unrestricted battery settings; changing battery settings does not grant missing Android foreground-service permissions. Full procedures and 22 unrun scenarios are in [docs/SAMSUNG_VALIDATION.md](docs/SAMSUNG_VALIDATION.md).

## Automation and privacy

External control is disabled by default. The old unauthenticated `on`, `set`, `boot` and direct-service interfaces are removed. The new API accepts only authenticated version-1 `vehicle_connected`, `vehicle_disconnected`, `vehicle_unknown`, `stop`, and `status` messages with fresh increasing timestamps. `stop` selects FORCE_REAL. Revoking the capability disables access and cancels automation; reenable to issue a new credential. Anyone holding the token can exercise this narrow API, so protect Tasker backups and clipboard contents.

Only selected mock coordinates/configuration and map camera position persist. Genuine location samples are filtered for mock flags/freshness and held in memory for the visible map; old persisted genuine coordinates are erased. Application backup and device transfer are explicitly excluded. Notifications/status omit coordinates and credentials. The map loads HTTPS OpenFreeMap styles/tiles/fonts; providers can observe your IP address and viewed area. No telemetry or advertisements were added; third-party network behavior has not been packet-captured. Attribution remains visible in the drawer and map controls.

## Recovery and rollback

Select **FORCE_REAL** in the app/tile. Verify that injection stopped; if cleanup is uncertain, keep/select MockGPS as mock-location app and retry Force Real. Then set Developer Options mock-location app to **None**, turn system location off/on, and reboot if needed. Seek a newly timestamped non-mock fix outdoors in a separate client. Android/fused/OEM clients may retain cached mock positions; this app cannot promise immediate reacquisition.

Legacy unjournaled providers and competing mock apps cannot be conclusively identified. An upgrade from a legacy installation displays cleanup uncertainty until a successful journaled session replaces both providers and is stopped, or reboot clears legacy ambiguity. Stop before uninstalling/downgrading; matching signatures/version codes may prevent downgrade, so back up only nonsensitive configuration deliberately. Uninstall clears settings/tokens; never distribute private signing keys. Detailed recovery and rollback procedures are in the hardware validation guide.

## Engineering evidence

- [Foundation comparison and gate](docs/FOUNDATION_REVIEW.md)
- [Android/platform feasibility and source evidence](docs/PLATFORM_FEASIBILITY.md)
- [Security audit and remediations](AUDIT.md)
- [Architecture and state model](docs/ARCHITECTURE.md)
- [Threat model and permissions](docs/SECURITY.md)
- [Tasker integration](docs/TASKER.md)
- [Testing evidence](TESTING.md)

## License and attribution

GPL-3.0; retain [LICENSE](LICENSE) and upstream copyright ©2026 Kamil BuriXon Burek. Fork hardening, tests and automation changes are documented in this repository. No alternative-project source was copied. Distribution of GPL binaries requires corresponding source and license notices; CI debug artifacts include no private release signing key. Map data ©OpenStreetMap contributors, tiles by OpenFreeMap, rendering by MapLibre. See [ATTRIBUTION.md](ATTRIBUTION.md).

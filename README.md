# MockGPS

An Android location simulator with a map, a persistent foreground notification, and optional automation. Pick a point, start simulation, and stop it whenever you need real location again.

Requires Android 10 or newer. Built with Kotlin, Material Components, and MapLibre. MockGPS preserves Android's mock-location indicators; it does not conceal simulated locations from other apps.

## Get started

1. Download the APK from [Releases](https://github.com/abhi-k9/MockGPS/releases).
2. Enable Android **Developer options**, then select **MockGPS** as the mock location app.
3. Open MockGPS and allow precise location. Allow notifications to see ongoing status and confirmation requests.
4. Select a point on the map or enter coordinates, then start simulation from the app.
5. Stop simulation with **Force real**. This removes the mock providers; it does not guarantee that your device has acquired a fresh GPS fix.

Use a separate location client to verify the result. Stop an older simulator before changing which app provides mock locations. Debug and release APKs have different signers and cannot update each other in place.

## Controls

- **Force real** stops simulation, cancels pending requests, and prevents automation from restarting it.
- **Auto** allows enabled integrations to report readiness. A fresh request waits for the configured delay and requests your confirmation; it never starts simulation silently.
- **Manual start** starts at your selected point while the app is visible. A paused integration blocks it; a new pause or unknown event stops it.
- The **Quick Settings tile** switches between Force real and Auto. Its status describes the app's state, not proof of genuine GPS reception.

Automation is optional and disabled by default. Tasker and other intent-capable apps use an authenticated command interface. For launcher and routine actions, enable **Settings → App shortcuts** in MockGPS, then reopen the host's action picker. This publishes Request simulation, Pause requests, Context unknown, Stop simulation, and Open status where supported. See the [setup and API reference](docs/AUTOMATION.md).

## Privacy

No account, analytics, remote control server, or background-location permission is required. MockGPS saves selected coordinates and app settings locally, with backup and device transfer disabled. The online map uses OpenFreeMap; map requests disclose your network address and requested map area to that service. Notifications and automation status omit coordinates and credentials.

## Development

Use JDK 21, Android SDK platform 36, and build tools 36.0.0. The Gradle wrapper pins and verifies its distribution; dependency checksums are enforced.

```sh
export JAVA_HOME=/path/to/jdk-21
export ANDROID_HOME=/path/to/android-sdk
scripts/setup-android-sdk.sh "$ANDROID_HOME"
scripts/android-build.sh
```

See [development](docs/DEVELOPMENT.md), [architecture](docs/ARCHITECTURE.md), [release signing](docs/RELEASING.md), and [security](SECURITY.md). CI runs one debug build, JVM tests, and lint; it does not run emulators.

Licensed under [GPL-3.0](LICENSE). Derived from [MockGPS2](https://github.com/abhi-k9/MockGPS2) and [BuriXon-code/MockGPS](https://github.com/BuriXon-code/MockGPS). See [attribution](ATTRIBUTION.md).

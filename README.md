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
- **Auto** allows enabled integrations to report readiness. A fresh request waits for the configured delay, then asks for confirmation by default. Optional unattended approval can start simulation automatically while the automation session is armed.
- **Manual start** starts at your selected point while the app is visible. A paused integration blocks it; a new pause or unknown event stops it.
- **Temporary override** simulates or stops for a chosen duration, then resumes the previous policy using automation updates received during the timer. Use the separate **MockGPS timer** Quick Settings tile to open its controls; the notification has −5/+5 minute buttons. Force real cancels the timer.
- The **MockGPS mode** Quick Settings tile switches between Force real and Auto. Its status describes the app's state, not proof of genuine GPS reception.

Automation is optional and disabled by default. Tasker and other intent-capable apps use an authenticated command interface. For launcher and routine actions, enable **Settings → App shortcuts** in MockGPS, then reopen the host's action picker. This publishes Request simulation, Pause requests, Context unknown, Stop simulation, and Open status where supported. See the [setup and API reference](docs/AUTOMATION.md).

## Home-screen widgets

Long-press the home screen, choose **Widgets → MockGPS**, then add **MockGPS compact** or **MockGPS detailed**. Both support resizing and light/dark appearance; available sizes and previews depend on your launcher.

- **Compact:** last reported simulation status, report time, Force real and a shortcut to the timer controls. Tap the header to open the app.
- **Detailed:** adds mode, unattended-session readiness, combined source state, and the next reported deadline. During an override, −5/+5 minute buttons are available when that override is the next deadline; if the session limit ends sooner, it takes priority. Timer opens the full duration/start/stop controls.

Force real stops injection and keeps it stopped. Starting or resuming through Timer requires the existing visible controls and prerequisites. Widgets cannot silently select Auto, grant consent or recreate a foreground service. Neither widget displays coordinates, saved-location names, routes, credentials or integration labels.

Widgets update when app state changes, with no periodic polling. The report timestamp matters: Android launchers can retain old views after process death or a force stop. The native countdown is based on the reported elapsed deadline and may appear overdue on a retained view; it is not proof of current injection. Tap the header or Open app to inspect current status. A stopped status does not verify a fresh genuine GPS fix.

## Unattended automation and change alerts

**Settings → Unattended automation** is off by default. Enable it explicitly while MockGPS is open to prepare a foreground automation session. In Auto, a fresh request from every enabled source can then start simulation after the configured minimum delay without another tap. Force real remains the persistent override.

The session keeps Android's required ongoing notification even while waiting; no background-location permission is requested. After process death or a device reboot, reopen MockGPS to prepare the session and send fresh requests. The saved setting alone does not mean automation is armed. If the session is unavailable when a delay finishes, the request falls back to manual confirmation.

Choose **Settings → Change alerts** for routine starts and stops: toast (the default for a new installation), notification, both, or neither. Existing choices are preserved. Routine notifications expire after 15 seconds. Errors and required actions have a separate switch under **Quiet notification profile**.

The quiet profile uses toast changes and one compact ongoing status notification with no app badge. **Ongoing status channel settings** opens Android’s controls; choose **Minimize** if your device offers it. Android’s own mock-location notice is controlled by the system and cannot be merged with the app’s notification. Silencing sound alone may leave the status-bar icon visible.

## Saved locations, timers and routes

Save named locations from **Saved locations**. Automation can select only presets approved individually by you, using a credential with preset-selection permission. Separate integration credentials have independent permissions, counters and revocation. See the [API reference](docs/AUTOMATION.md#integration-credentials).

Set an optional **Maximum simulation duration** to stop and select Keep stopped after a session limit. The app distinguishes provider cleanup from observing a fresh non-mock fix in this app; it cannot verify other apps’ cached locations.

Import a local UTF-8 GPX file under **Route playback**, then enable a constant playback speed. Routes support altitude and heading, run instead of drift, and stop at completion. Only one continuous track segment or route is accepted (2–2000 points, under 1 MiB). Importing or enabling playback stops the current session; it never starts injection. Each new session plays from the beginning.

Timers use elapsed time, so changing the clock does not change their duration. Allow **alarms and reminders** for precise expiry during sleep; otherwise Android may deliver expiry late. Before publishing a fix, the service checks elapsed deadlines regardless of alarm delivery. A stopped override may resume late while Android is asleep. Process death, reboot or application replacement cancels interrupted timers and keeps simulation stopped. See [timer behavior](docs/AUTOMATION.md#temporary-overrides).

## Privacy

No account, analytics, remote control server, or background-location permission is required. MockGPS saves selected coordinates, presets, an optional imported route and app settings locally, with backup and device transfer disabled. The online map uses OpenFreeMap; map requests disclose your network address and requested map area to that service. Notifications and automation status omit coordinates and credentials. Diagnostic reports contain only bounded, process-local event codes and state; preview the report before sharing it. Labels, coordinates, routes, credentials and device identifiers are excluded.

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

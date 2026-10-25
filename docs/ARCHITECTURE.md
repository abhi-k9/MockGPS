# Architecture

MockGPS is a single Android app module. It keeps platform integration small and uses pure Kotlin models for decisions that need deterministic tests.

- **UI** displays the map, settings, policy, and observed service state. Coordinates must be explicitly selected before simulation can start.
- **ControlCenter** serializes policy transitions, persistence, alarms, source readiness, and service requests.
- **Core policy** separates the selected mode, desired behavior, and observed provider state. Paused or unknown sources stop simulation. After an eligible request's delay, manual approval requests confirmation; opted-in unattended approval requests injection through an already armed session. Generation checks reject obsolete deadlines and confirmations.
- **Automation adapters** validate their own authority, translate into shared command types, and pass decisions through the same policy boundary. Tasker broadcasts and standard Android shortcuts cannot bypass it.
- **Provider engine** tracks provider creation, injection, and cleanup through a platform port. Partial failures preserve enough state for cleanup retry.
- **MockLocationService** owns foreground lifetime and bounded location updates. A visible app action prepares the unattended location foreground service; it may then remain idle without creating mock providers. Stopping injection can retain this session. A missing session is never recreated by a receiver or alarm. Reboot and process restart never restore an earlier injection session automatically.
- **AutomationSettings** stores local unattended consent (off by default) and optional change-alert preferences. Consent changes cancel pending work and require fresh source requests. Armed status is ephemeral and becomes true only after foreground admission.
- **AutomationAlerts** presents coordinate-free feedback for confirmed starts, cleanup, new errors, and unavailable unattended sessions. Alert delivery never determines policy or cleanup success; the mandatory foreground notification has a separate lifetime.

Force real is a persistent override. Auto is permission to consider eligible integration state, not a request to start simulation. The app distinguishes an armed automation session, service stopped, providers removed, and injection active; none of those states proves a fresh genuine GPS fix.

MapLibre renders HTTPS OpenFreeMap data. Genuine location samples are filtered for mock flags and freshness, retained in memory, and used only while the map is visible. Selected mock coordinates and map camera settings remain local.

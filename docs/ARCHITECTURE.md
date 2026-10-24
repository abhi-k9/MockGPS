# Architecture

MockGPS is a single Android app module. It keeps platform integration small and uses pure Kotlin models for decisions that need deterministic tests.

- **UI** displays the map, settings, policy, and observed service state. Coordinates must be explicitly selected before simulation can start.
- **ControlCenter** serializes policy transitions, persistence, alarms, source readiness, and service requests.
- **Core policy** separates the selected mode, desired behavior, and observed provider state. Paused or unknown sources stop simulation; an eligible request produces a delayed request for visible user confirmation.
- **Automation adapters** validate their own authority, translate into shared command types, and pass decisions through the same policy boundary. Tasker broadcasts and standard Android shortcuts cannot bypass it.
- **Provider engine** tracks provider creation, injection, and cleanup through a platform port. Partial failures preserve enough state for cleanup retry.
- **MockLocationService** owns foreground lifetime and bounded location updates. Reboot and process restart never restore an earlier injection session automatically.

Force real is a persistent override. Auto is permission to consider eligible integration state, not a request to start simulation. The app distinguishes service stopped, providers removed, and injection active; none of those states proves a fresh genuine GPS fix.

MapLibre renders HTTPS OpenFreeMap data. Genuine location samples are filtered for mock flags and freshness, retained in memory, and used only while the map is visible. Selected mock coordinates and map camera settings remain local.

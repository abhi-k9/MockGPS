# Security

Report a suspected vulnerability through the repository's private security advisory feature when available. Do not include tokens, signing keys, private coordinates, or configured automation exports in public issues. For ordinary defects, open a sanitized issue with app/Android versions and reproduction steps.

## Boundaries

- Android mock-location flags remain intact. This app does not implement detection bypasses.
- Simulation runs in a location foreground service with a persistent notification. No background-location permission is requested.
- Exported automation accepts only authenticated, versioned, narrowly scoped commands. External commands cannot select coordinates or directly start injection.
- Automation is disabled by default. Tokens are cryptographically random, revocable capabilities. Timestamps and increasing mutation counters reject stale/replayed commands. Anyone holding a token can use that interface; other apps' storage and backups are outside this app's control.
- Android shortcuts use private, revocable authority and local review before accepting a simulation request. A shortcut invocation does not prove the identity of its host.
- Force real overrides automation. Startup, reboot, and upgrades invalidate pending starts. Failed cleanup remains an error until retried; a stopped service does not prove a fresh genuine location fix.
- App-private settings are excluded from backup and device transfer. Credentials and coordinates are omitted from notifications and status responses. Online map requests are sent over HTTPS.

## Development and releases

Dependencies, Gradle, and SDK bootstrap downloads are verified; do not bypass TLS or checksum verification to resolve a build failure. Review dependency checksum changes against the authoritative repository. CI actions are pinned to commit IDs, PR jobs receive no signing secrets, and release signing reads passwords from environment variables rather than command arguments.

The release workflow signs only version tags. Restrict tag creation and repository write access, and protect the default branch. Store signing secrets in GitHub Actions secrets, keep an offline recovery copy of the existing keystore, and use the same certificate for updates. A compromised release signer requires a recovery plan; a new key generally cannot update installed APKs.

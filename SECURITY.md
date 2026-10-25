# Security

Report a suspected vulnerability through the repository's private security advisory feature when available. Do not include tokens, signing keys, private coordinates, or configured automation exports in public issues. For ordinary defects, open a sanitized issue with app/Android versions and reproduction steps.

## Boundaries

- Android mock-location flags remain intact. This app does not implement detection bypasses.
- Simulation runs in a location foreground service with a persistent notification. Unattended mode prepares this session while the app is visible and can retain it idle between requests. No background-location permission or other special access is requested; background events never recreate a missing session.
- Exported automation accepts only authenticated, versioned, narrowly scoped commands. External commands cannot select coordinates, change unattended consent, clear Force real, or bypass policy. A request can cause delayed injection only when the user has enabled unattended approval and the session is armed.
- Automation is disabled by default. Tokens are cryptographically random, revocable capabilities. Timestamps and increasing mutation counters reject stale/replayed commands. Anyone holding a token can use that interface; other apps' storage and backups are outside this app's control.
- Android shortcuts use private, revocable, one-use authority. Manual approval is the default. Enabling unattended approval deliberately trusts configured hosts to deliver current requests; shortcuts do not provide the timestamped observations of the broadcast API, and invocation does not prove host identity. Consent changes invalidate pending requests and shortcut authority.
- Force real overrides automation and cancels pending starts. The idle automation session may remain prepared while injection is stopped. Startup, reboot, and upgrades invalidate pending starts and source readiness; a saved unattended preference is not proof of an armed foreground session. Failed cleanup remains an error until retried; a stopped service does not prove a fresh genuine location fix.
- App-private settings are excluded from backup and device transfer. Credentials and coordinates are omitted from notifications and status responses. Online map requests are sent over HTTPS.

Optional change alerts can use a toast, notification, both, or neither. They report confirmed injection/cleanup transitions and new errors without coordinates or credentials. Disabling them does not remove Android's required foreground-service notification. System restrictions can delay or prevent alert delivery.

## Development and releases

Dependencies, Gradle, and SDK bootstrap downloads are verified; do not bypass TLS or checksum verification to resolve a build failure. Review dependency checksum changes against the authoritative repository. CI actions are pinned to commit IDs, PR jobs receive no signing secrets, and release signing reads passwords from environment variables rather than command arguments.

The release workflow signs only version tags. Restrict tag creation and repository write access, and protect the default branch. Store signing secrets in GitHub Actions secrets, keep an offline recovery copy of the existing keystore, and use the same certificate for updates. A compromised release signer requires a recovery plan; a new key generally cannot update installed APKs.

# Development candidate gate

The current version is 4 / 0.3.2-dev, installed package `dev.abhik.mockgps`, source namespace `dev.burixon.mockgps`, on `harden/android17-automation`. Keep PR1 draft/unmerged. Do not publish a production release.

The historical 67-JVM/11-emulator candidate contains the manual-session vehicle-safety defect and is not this candidate. Revised source `89e01d191a8a72c4d749f17846899731c7ec514f` passed79 JVM tests and15 actual emulator tests in both [push](https://github.com/abhi-k9/MockGPS/actions/runs/40057430820) and [PR](https://github.com/abhi-k9/MockGPS/actions/runs/40057434383) Android workflows. Local strict full/isolated-cache builds passed: use [VALIDATION.json](VALIDATION.json) for actual source/results, never substitute a historical hash or signer.

The successful build artifact must include the signed debug APK and `development-candidate/build-identity.json`, `signature.txt`, test/lint reports, exact source SHA, clean tracked-worktree status, APK SHA256 and public signer SHA256. The unsigned release APK is not an installable signed release. Debug signing is development-only and can change between CI runs. Compare the downloaded APK hash and signer against that exact artifact's identity; matching package alone cannot authorize an update.

Before changing the selected mock app or uninstalling, select FORCE_REAL in the previous simulator, inspect/retry cleanup, and independently obtain a fresh nonmock fix. Keep the fork selected until owned-provider cleanup succeeds; use the documented recovery/reboot procedure if uncertain. Fresh installation requires permissions, Developer options mock-app selection, synthetic test point, new capability/sequence state, exact typed API2 Tasker configuration and the fork's native tile.

The manual safety gate requires accepted CONNECTED/UNKNOWN to stop active and starting manual sessions, invalidate stale work and leave inactive AUTO unless FORCE_REAL was selected. Later disconnection must create a new delay/visible confirmation. Missing delivery may leave simulation running; local FORCE_REAL remains necessary. Successful cleanup is not a genuine-fix observation.

All 24 physical Samsung cases are NOT RUN. Prioritize manual CONNECTED, manual UNKNOWN, startup race, cleanup denial/recovery, independent fresh nonmock fix, real native tile, physical typed API2 Tasker delivery, disconnection delay/confirmation, rapid reconnect, process death, force-stop and reboot. Android16/API36 emulator execution is not Samsung OneUI9/Android17 validation.

GitHub dependency review remains a blocking repository feature/permission issue. The owner must check/enable Dependency graph at https://github.com/abhi-k9/MockGPS/settings/security_analysis and rerun the check. The independent Maven scan reports 174 coordinates, one medium Kotlin build-cache advisory and zero high/critical matches in those scopes. Disabled caches/incremental compilation mitigate but do not patch it. Native/shaded code, SDK/firmware and unknown advisories remain outside that coverage; see DEPENDENCY_REVIEW.md.

## Exact development artifact

[Download signed debug APK/reports](https://github.com/abhi-k9/MockGPS/actions/runs/40057430820/artifacts/12546211907); extract app-debug.apk. Source `89e01d191a8a72c4d749f17846899731c7ec514f`; package dev.abhik.mockgps; versionCode4 /0.3.2-dev.

APK SHA256: `c8a1757797da4f0483d0b768eb4d413f3655d9646f8867e258a9392b8656ebce`

Signer certificate SHA256: `f273ee09d405d1421a2466c2c0ba27f9d8863a54b841c02980d6ab66ba7c8c4d`

CI apksigner/package/version assertions and public provenance were inspected. Direct independent archive/APK download is blocked at the observed sa4 Azure host; no extra download/hash verification is claimed. The separately verified local APK has its own identity and is not substituted for this artifact. Later documentation-only commits do not change this immutable code checkpoint.

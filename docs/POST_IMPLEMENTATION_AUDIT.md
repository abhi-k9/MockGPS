# Independent post-implementation audit

Baseline independently fetched: `0cfeb55f29c594bdef9c01254a0958add1462f60`; the remote branch had no newer commit. Reviewed application Kotlin, policy/auth/engine tests, both manifests and merged packaging, SDK/Gradle/CI scripts, existing documentation and actual GitHub job API evidence. This second pass did not treat the first AUDIT conclusions as proof. Device/OEM statements remain distinct from code/model evidence.

## Confirmed issues and targeted remediations

| Finding | Severity / reproduction | Remediation and regression |
| --- | --- | --- |
| Wall clock doubled as replay ordering | High correctness / medium automation reliability. Authenticate disconnect and then reconnect/stop within the same millisecond: the latter was rejected; status could consume the millisecond too. Turning the clock backwards rejected fresh later safety events until it caught up. Affects CommandAuth.authorize, Policy.Vehicle, ControlCenter.external and PolicyStore. | API2 requires independent persisted `seq` for mutations and uses event_time only for freshness. Status is read-only. Duplicate/older sequences stay rejected, rotation resets the epoch, and no-op enabling cannot reset it. Unit regressions cover equal timestamps, clock rollback, old sequences with newer times, extreme counters and exact raw Android types. Instrumentation covers actual ordered IPC and credential epochs. Tasker must serialize one durable counter owner and send current, not stale queued, observations. |
| Service callbacks could erase legacy cleanup uncertainty | Medium correctness. Seed legacy_unverified=true, then report a failed promotion or stopped service with cleanupUncertain=false: prior callbacks reported no providers/injection although legacy ownership was never established. | serviceFailed/serviceStopped combine API cleanup result with persisted legacy uncertainty. Instrumentation calls the actual controller and asserts UNKNOWN/CLEANUP_UNCERTAIN survives both callbacks. A successful journaled injection of both providers or reboot is still required to resolve legacy ambiguity. |
| SDK manager absent in CI | Build/release blocker. Actual baseline build job failed its SDK step with exit127. | Both jobs provision pinned official command-line tools19.0 and explicit SDK/PATH using one tested bootstrap, bounded license input and checked downloads. No runner SDK assumption. |
| Incomplete strict verification set | Supply-chain assurance/build blocker. Empty Gradle user/project cache reproduced missing Guava parent POM and JUnit module; complete cold build exposed a third coroutines BOM. | Only three reviewed hashes added after identity/repository checksum checks. Wrapper9.6/AGP9.2.1/repositories/dependency versions unchanged. Strict mode remains; no blanket trust or permissive resolution. See CI_REPAIR.md. |
| Upstream installed package/signing conflict | Distribution/upgrade risk, not cosmetic. An independently signed APK sharing dev.burixon.mockgps cannot update an upstream installation with another signer. | Installed ID dev.abhik.mockgps, unchanged source namespace, full explicit components, separate launcher/tile label, fresh permissions/token/API2 tasks. No silent cross-package data migration. Signing/upgrades/rollback are documented in IDENTITY_AND_SIGNING.md. |

## Authorization and component review

The receiver now reads raw Bundle values into a pure exact-type parser: exactly api:Int, token:String, command:String, event_time:Long and seq:Long. Malformed parcel/runtime failures reject without logging supplied values. Unsupported actions/API/keys/commands cannot dispatch. Tokens remain private, backup/transfer excluded and compared with MessageDigest.isEqual after public format checks. A stolen token remains a narrow capability, not caller-package identity; status never returns coordinates/token. Rotating it deletes old authority. Freshness rejects future/expired/extreme times; sequences reject replay and reserved exhaustion. A malicious authorized sender can still deny automation or issue safety stop; revoke after exposure.

The merged manifest must show only launcher Activity, authenticated receiver and system-permission-protected tile exported. Location service, boot/update receiver and delayed receiver remain private. The exported Activity ignores start extras and never starts injection on resume. Tile requires BIND_QUICK_SETTINGS_TILE and user unlock to return AUTO; FORCE_REAL stop is immediate. Tests check the packaged manifest and real SystemUI binding/clicks on an unlocked emulator, not Samsung lock-screen enforcement.

## State, providers and recovery review

All controller transitions/effects serialize on the main looper. Generations reject obsolete service callbacks, deadlines and confirmations. FORCE_REAL is cleared only by explicit AUTO, never external events or manual start. Delayed receivers notify; they do not launch an FGS. Process/boot recovery invalidates live/pending state. Credential epochs, sequence watermark and policy updates commit before accepted mutation acknowledgement.

The engine journals known provider names before add, rolls back partial setup and retains journal entries when removal or persistence fails. Disable failure does not prevent removal attempts. It never enumerates/removes arbitrary unjournaled providers. Late destroy callbacks cannot repeat cleanup against a newer live run. Failure results and cleanup uncertainty remain separate from desired mode. Successful removal does not prove fresh genuine GPS or invalidate every fused/OEM/client cache.

Android named test providers do not expose a per-process ownership certificate. A journal is conservative intent evidence, not proof against another selected mock app replacing the same names while this process was absent. Avoid competing simulators, stop the old installation first, and use explicit recovery/reboot when ambiguous. Mock-app revocation may deny cleanup; restore selection and retry, or reboot. Those platform consequences are hardware tests, not asserted by a fake-provider unit test.

Storage failures attempt safety cleanup, invalidate starts and cannot return mutation ACCEPTED without a committed watermark. Actual disk-full fault injection remains unexecuted. Private-preference corruption is not an unauthenticated IPC write path; recovery defaults inactive rather than trusting persisted running flags.

## Lint disposition

Resource density qualifiers, redundant pre-API26 launcher bitmaps (minSdk29), unused launcher vectors/name, and mode-button style were corrected without changing logical controls or suppressing checks. Adaptive launcher foreground/monochrome resources remain. Final lint counts are recorded in TESTING/VALIDATION, not inferred from this table.

| Remaining warning | Count | Reason retained |
| --- | --- | --- |
| UseKtx | 9 | Explicit SharedPreferences.commit return checks are intentional failure handling; replacing with Unit-returning edit convenience would lose this evidence. |
| InlinedApi | 3 | ProviderProperties numeric compile-time constants are inlined on the API29–30 compatibility path; the API31 Builder itself is guarded. Legacy device behavior still needs runtime coverage. |
| OldTargetApi / GradleDependency | 1 each | Compile/target36 deliberately retained pending API37 migration/device validation; no claim of Android17 certification. |
| NewerVersionAvailable | 1 | MapLibre13.6.1 retained; an unrelated upgrade to13.7.1 would not repair CI/security findings and requires its own review. |
| ExportedReceiver | 1 | Required separately signed Tasker transport, protected by opt-in capability/type/freshness/sequence checks. Signature-only permission would break that integration. |

No broad suppression, lint baseline, skipped assertion or weakened dependency verification was introduced. The dedicated developer mock-location permission's existing narrow MockLocation annotation remains explained by the app's purpose. Native SystemUI and IPC tests are real instrumentation only when a completed emulator result proves execution. Samsung OneUI9, secure lock behavior, physical Bluetooth delivery and genuine recovery remain physical-device work.

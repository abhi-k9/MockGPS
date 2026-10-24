# CI cost control and persistent signing

Feature branches use PR validation only; master retains push validation, and explicit dispatch remains available. The previous push+PR duplicate Android runs are removed. Fast source/schema/JavaScriptlet/CI-selector checks always run. An app/build/test-harness fingerprint binds cached evidence to actual inputs. A successful matching full build and both device jobs may be reused for documentation or Tasker-only edits; reuse is labelled and does not claim tests executed again. A known matching failed run stays blocking without spending another full run on unchanged code. Missing/unreadable proof fails closed to full validation. `force_android=true` explicitly requests a retry. Selector regressions cover success, failure, missing/forked proof, forced runs, docs-only and runtime edits. Gradle verification and all assertions remain strict; no shared writable build caches are restored while the medium Kotlin advisory remains.

APK compilation occurs before emulator boot, compiler daemons are stopped, and disposable VMs are bounded to two cores/2GB. Credential-unlock readiness is checked; guest crash diagnostics export only types/frames/signals. API36 runs17 actual tests. API37 currently has a guest platform/input/display/native SIGABRT/transport failure before app tests. It remains a failed check, not a test pass. Do not repeatedly run unchanged full suites; inspect saved diagnostics or retry only that failed job after the image/runtime issue is repaired. This is separate from Samsung hardware evidence, which remains incomplete.

Signing is a manually dispatched, separate job in `android.yml`. No normal PR/build/plan job binds signing secrets. The job has scoped contents:write only to update a matching **draft prerelease**, never publish or merge. It reuses a verified CI-built unsigned APK and archives its exact actual merge-build source, rather than silently substituting a local APK or rebuilding unreviewed code. The validated PR head and actual build commit are both recorded, with an identical runtime fingerprint. Public inventory/advisory receipts bind identical build inputs; CI attestations are self-recorded receipts, not a cryptographic SLSA claim.

The owner supplied:

- MOCKGPS_SIGNING_KEYSTORE_BASE64
- MOCKGPS_SIGNING_KEY_ALIAS
- MOCKGPS_SIGNING_KEY_PASSWORD
- MOCKGPS_SIGNING_STORE_PASSWORD

Preparation runs before those bindings are present. The private keystore is decoded into a mode600 temporary file outside assets, passwords are passed through environment references to apksigner, tool errors suppress sensitive arguments, and the key file is deleted even on failure. No key is generated, exported, cached or uploaded. A debug certificate is rejected. Signature, package, version, target, uploaded digest/download integrity and public certificate SHA256 are verified. All source, APK, Tasker, SBOM, advisory and receipt assets remain reviewable in the owner-only draft.

`allow_api37_blocker=true` authorizes only private **candidate preparation** when baseline build/JVM/API36 checks pass and the reviewed API37 infrastructure failure prevented app tests. It does not waive a named application regression, change failed checks, permit publication or mark Android17/Samsung passed. Dependency-review support and mandatory hardware gates remain explicit blockers. Actual signing success is established only by a fresh signing receipt, not secret-name presence.

Dispatch with the exact approved head/run:

```sh
gh workflow run android.yml --repo abhi-k9/MockGPS --ref harden/android17-automation \
  -f sign_release=true -f source_sha=EXACT_VALIDATED_HEAD -f validation_run_id=FULL_RUN_ID \
  -f allow_api37_blocker=true
```

Use the blocker flag only for the documented infrastructure case. A normal full-pass candidate leaves it false. Workflow dispatch is available on this existing registered branch workflow. Publication remains a separate owner action after gates/evidence permit it. Keep the working Samsung install until safe cleanup and signer-aware migration is ready.

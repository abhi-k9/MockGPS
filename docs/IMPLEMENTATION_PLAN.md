# Implementation plan

Baseline: fork `abhi-k9/MockGPS`, commit `2a2e4322596dcb2b37327999dce3f0d079d35dea`.
Branch: `harden/android17-automation`. Baseline debug build succeeds; lint reports 3 errors/76 warnings; test task has no sources.

1. Gate architecture changes on the comparative review and platform feasibility investigation. Treat Samsung Android 17 behavior as unverified; use visible user interaction for starts rather than assuming a broadcast/alarm grants an exemption.
2. Add a pure Kotlin policy reducer and tests. Serialize Android entry points on the main thread. Persist mode, desired/observed/provider/injection states, result, event ordering and generation separately. Recover to inactive/uncertain; invalidate old pending actions.
3. Replace open commands with a narrow opt-in versioned capability-authenticated vehicle-event/status/stop contract. Reject replay, expired/malformed data and old API extras; revoke credentials locally. Never start a location FGS from external broadcasts.
4. Isolate provider lifecycle behind a tested engine with ownership tracking, explicit failure/cleanup results, valid metadata and no automatic restart loop. Make service internal and non-sticky.
5. Preserve map/manual selection; add mode/status controls, native tile, credential setup/revocation and delay configuration. Stop is immediate; starts require visible Activity confirmation. Remove stored genuine location and unsafe boot restoration.
6. Add CI, unit tests and instrumentation checks; build debug/release, run lint without broad suppressions, review manifest/dependencies. Document exact outcomes and hardware gaps.
7. Review final diff, commit coherent changes and prepare a draft PR if repository access permits. Do not claim production readiness or hardware verification.

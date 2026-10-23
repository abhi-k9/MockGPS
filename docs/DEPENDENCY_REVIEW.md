# Dependency vulnerability review and GitHub feature blocker

Integrity and vulnerability review answer different questions. Strict Gradle SHA256 verification rejects unexpected artifact bytes; a matching hash does not establish that the version has no published vulnerability. Both remain required.

## Repository feature investigation

The repository is public. The pinned dependency-review action uses the normal contents:read permission and fail-on-severity:high; no continue-on-error, skip-to-green or lower threshold was introduced. GitHub's dependency-diff API returns403 Forbidden and the action reports “Dependency review is not supported on this repository. Please ensure that Dependency graph is enabled…”. GraphQL returns zero dependency manifests, which does not alone distinguish a disabled graph from missing indexing. Repository security_and_analysis is null, also insufficient to prove the precise feature state.

The integration's GET vulnerability-alerts and supported PUT enable-vulnerability-alerts requests both return403 “Resource not accessible by integration”; the API advertises administration access requirements. The actor permission object says admin but the scoped integration cannot exercise that authority. A public repository does not require the private-repository Advanced Security subscription mentioned in the generic error. We cannot truthfully conclude which owner-side graph setting is selected or repair it with this integration.

Owner action:

1. Open https://github.com/abhi-k9/MockGPS/settings/security_analysis as the repository owner.
2. Under Dependency graph, choose Enable if disabled. If already enabled, inspect indexing/status and repository/organization policy for dependency review; retain the exact failing run/API error for GitHub support.
3. Enable Dependabot alerts if desired/available; this is not a substitute for confirming Dependency graph availability.
4. Rerun the failed Dependency review job from PR1's Checks tab, or run `gh run rerun RUN_ID --failed` using owner-authorized authentication. Inspect the result before changing the gate's status.

No raw token needs to be supplied in chat. The failed workflow remains visible. Exact inspected final run belongs in VALIDATION.json/PR1; a repeated unsupported-feature error is not a vulnerability finding or a successful advisory scan.

## Independent, reproducible Maven advisory check

The available public GitHub Advisory Database REST endpoint works independently of repository dependency-diff support. `scripts/dependency-inventory.init.gradle` resolves exact Maven coordinates for debug/release runtime, JVM/instrumentation runtime and root/app buildscript classpaths under strict verification, without changing versions/repositories. `scripts/scan-dependency-advisories.py` sends only those public package names/versions to `/advisories?type=reviewed&ecosystem=maven&affects=package@version`, handles all pages and retains complete responses. A known vulnerable Log4j2.14.1 positive control must return GHSA-jfh8-c2jp-5v3q; it is not an application dependency. API failures or an empty inventory fail the operation.

```sh
./gradlew -I scripts/dependency-inventory.init.gradle exportReviewedDependencyInventory \
  -Dmockgps.inventory=/absolute/dependency-inventory.json --dependency-verification=strict
python3 scripts/scan-dependency-advisories.py /absolute/dependency-inventory.json /absolute/advisory-scan.json
```

The initial snapshot covered174 resolved Maven coordinates and found9 nonwithdrawn reviewed advisories, all on AGP's buildscript classpath. No matching advisory was reported for the resolved packaged-app/runtime coordinates at that query time. Raw inventory, advisory responses and reviewed update hashes are retained under docs/evidence/dependencies. Advisory text comes from the [GitHub Advisory Database](https://github.com/github/advisory-database), licensed CC-BY-4.0; it can change after the recorded query date.

| Affected build dependency | Finding | Targeted correction |
| --- | --- | --- |
| commons-lang3 3.16.0 | GHSA-j288-q9x7-2f5v, medium recursive-input DoS | 3.18.0 |
| jose4j 0.9.5 | GHSA-3677-xxcr-wjqv, high compressed-JWE DoS | 0.9.6 |
| bcpkix/bcprov 1.79 | GHSA-wg6q-6289-32hp, GHSA-qp49-qgx5-5m26, GHSA-9pwp-9qqc-pr26, GHSA-c3fc-8qff-9hwx, GHSA-574f-3g2m-x479; medium/high/critical | Aligned bcpkix/bcprov/bcutil1.85 |
| jdom2 2.0.6 | GHSA-2363-cqg2-863c, high XXE | 2.0.6.1 |
| kotlin-gradle-plugin 2.2.10 | GHSA-r937-wjx7-w2jp, medium unsafe build-cache deserialization | Unpatched AGP-bundled toolchain advisory; see mitigation below |

The root buildscript resolution rules target only the affected build-tool libraries; app dependency versions, AGP9.2.1 and Gradle9.6.0 remain pinned. Six module versions change (the three Bouncy Castle modules remain aligned). Sixteen new jar/POM/BOM artifacts were independently fetched through canonical Maven Central TLS, matched to its published SHA256 (or published SHA1 plus byte-identical canonical mirror where SHA256 was unavailable), and checked for XML identity/JAR integrity. Existing verification entries and hashes are preserved exactly. Generating a candidate checksum list was discovery only: unreviewed generated hashes were not accepted as trust. The reviewed receipt lists every new artifact and source.

The Kotlin advisory's first listed patched version is2.4.20-Beta1. Forcing a different bundled Kotlin plugin into AGP without compatibility evidence is not a targeted safe fix. Project task caching, configuration caching and Kotlin incremental compilation are explicitly disabled. CI uses an isolated fresh Gradle home and disables action cache restore. Do not import untrusted build caches or share writable Gradle/project state across untrusted builders. This reduces exposure; it is not a patched-version claim. The medium advisory remains reported and requires a compatible toolchain update. A final scan must confirm the resulting inventory and remaining advisory count; see VALIDATION.json for actual results.

Coverage is limited to published, reviewed Maven advisories for the recorded resolved scopes. It excludes unknown/unreviewed vulnerabilities, embedded/shaded/native libraries, the Gradle distribution, isolated tool-worker dependencies not in those configurations, SDK/emulator/system images, Android/Samsung firmware and application logic. It does not replace PR dependency-diff review, a native SDK audit or hardware validation. The independent script fails on high/critical matches, matching the existing review threshold; medium matches are retained, never suppressed or presented as zero findings.

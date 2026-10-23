# Attribution and distribution

MockGPS originates from [BuriXon-code/MockGPS](https://github.com/BuriXon-code/MockGPS), baseline `2a2e4322596dcb2b37327999dce3f0d079d35dea`, copyright ©2026 Kamil BuriXon Burek, GPL-3.0. The fork preserves the upstream license and artwork. This fork adds mode policy, authenticated automation, native tile, lifecycle hardening, tests, CI and documentation.

The comparison review studied other repositories for architectural ideas only. No application implementation, assets or test code were copied from them. Their licenses and reuse constraints are recorded in `docs/FOUNDATION_REVIEW.md`.

Runtime dependencies include AndroidX (Apache-2.0) and MapLibre Native Android (BSD-2-Clause; review its bundled third-party notices too). Kotlin/Gradle/Android build components retain their respective licenses. Repository-level license names do not supersede third-party asset or transitive dependency notices.

Map data: [© OpenStreetMap contributors](https://www.openstreetmap.org/copyright). Tile service/styles: [OpenFreeMap](https://openfreemap.org/). Rendering: [MapLibre](https://maplibre.org/). Keep the attribution drawer and SDK attribution control when redistributing the map UI; review service terms for your distribution.

When distributing GPL-3.0 APKs, distribute the corresponding source for that exact build and retain GPL/copyright notices. Do not include private release signing keys, credentials or Tasker backups containing automation tokens. Debug signing is a development convenience, not a release-trust guarantee.

buildscript {
    // Targeted build-tool fixes from the reviewed Maven advisory snapshot.
    // These libraries are on AGP's buildscript classpath, not packaged app code.
    configurations.getByName("classpath").resolutionStrategy.eachDependency {
        val patched = when ("${requested.group}:${requested.name}") {
            "org.apache.commons:commons-lang3" -> "3.18.0" // GHSA-j288-q9x7-2f5v
            "org.bitbucket.b_c:jose4j" -> "0.9.6" // GHSA-3677-xxcr-wjqv
            "org.bouncycastle:bcprov-jdk18on",
            "org.bouncycastle:bcpkix-jdk18on",
            "org.bouncycastle:bcutil-jdk18on" -> "1.85" // Aligned BC modules; ASN.1/name-constraint fixes
            "org.jdom:jdom2" -> "2.0.6.1" // GHSA-2363-cqg2-863c
            else -> null
        }
        if (patched != null) {
            useVersion(patched)
            because("Build-tool security fixes")
        }
    }
}

plugins {
    alias(libs.plugins.android.application) apply false
}

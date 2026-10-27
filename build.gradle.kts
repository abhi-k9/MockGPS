buildscript {
    // Keep AGP's classpath patched without changing packaged app dependencies.
    val securityVersions = java.util.Properties().apply {
        rootProject.file("gradle/build-tool-security.properties").inputStream().use { load(it) }
    }
    configurations.getByName("classpath").resolutionStrategy.eachDependency {
        val patched = securityVersions.getProperty("${requested.group}.${requested.name}")
        if (patched != null) {
            useVersion(patched)
            because("Build-tool security fixes")
        }
    }
}

plugins {
    alias(libs.plugins.android.application) apply false
}

subprojects {
    val securityVersions = java.util.Properties().apply {
        rootProject.file("gradle/build-tool-security.properties").inputStream().use { load(it) }
    }
    // Lint resolves its own tools separately from AGP's buildscript classpath.
    configurations.matching { it.name == "androidLintTool" }.configureEach {
        resolutionStrategy.eachDependency {
            securityVersions.getProperty("${requested.group}.${requested.name}")?.let {
                useVersion(it)
                because("Build-tool security fixes")
            }
        }
    }
}

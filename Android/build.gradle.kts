import org.cyclonedx.Version
import org.cyclonedx.model.Component

plugins {
    id("org.cyclonedx.bom") version "3.4.1"
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.dynamic.feature) apply false
    alias(libs.plugins.android.test) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.androidx.baselineprofile) apply false

    // Google Services (Firebase)
    id("com.google.gms.google-services") version "4.4.4" apply false
    // Crashlytics plugin
    id("com.google.firebase.crashlytics") version "3.0.2" apply false
    // Performance plugin
    id("com.google.firebase.firebase-perf") version "2.0.2" apply false
}

val sbomBuildType = providers.gradleProperty("sbomBuildType").orElse("release").get()
require(sbomBuildType in setOf("release", "internal")) { "Unsupported SBOM build type" }

allprojects {
    group = "com.auralis"
    version = providers.gradleProperty("releaseVersion").orElse("development").get()

    tasks.cyclonedxDirectBom {
        // Resolve the runtime graph of the published variant; internal review packages
        // include their own debug dependencies. Test, lint and build tools stay excluded.
        enabled = project.path == ":app" || project.path == ":feature_rescue"
        includeConfigs = listOf("${sbomBuildType}RuntimeClasspath")
        testConfigs = emptyList()
        componentGroup = "com.auralis"
        componentName = when (project.path) {
            ":app" -> "Crisis Connect Android"
            ":feature_rescue" -> "Crisis Connect Rescue Feature"
            else -> project.name
        }
        componentVersion = providers.gradleProperty("releaseVersion").orElse("development").get()
        schemaVersion = Version.VERSION_17
    }
}

tasks.cyclonedxBom {
    componentGroup = "com.auralis"
    componentName = "Crisis Connect Android"
    componentVersion = providers.gradleProperty("releaseVersion").orElse("development").get()
    projectType = Component.Type.APPLICATION
    schemaVersion = Version.VERSION_17
}

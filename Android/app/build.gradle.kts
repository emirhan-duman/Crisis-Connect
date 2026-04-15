import java.util.Properties
import org.gradle.api.GradleException

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    kotlin("kapt")
    id("com.google.gms.google-services")
    id("com.google.firebase.crashlytics")
    id("com.google.firebase.firebase-perf")
}

val keystoreProperties = Properties().apply {
    val keystoreFile = rootProject.file("keystore.properties")
    if (keystoreFile.exists()) {
        keystoreFile.inputStream().use(::load)
    }
}
val localProperties = Properties().apply {
    val localFile = rootProject.file("local.properties")
    if (localFile.exists()) {
        localFile.inputStream().use(::load)
    }
}

fun signingValue(propertyKey: String, envKey: String): String? {
    val fromProperties = keystoreProperties.getProperty(propertyKey)?.trim()
    if (!fromProperties.isNullOrEmpty()) return fromProperties
    return System.getenv(envKey)?.trim()?.takeIf { it.isNotEmpty() }
}

fun secretConfigValue(propertyKey: String, envKey: String): String {
    val fromLocalProperties = localProperties.getProperty(propertyKey)?.trim()
    if (!fromLocalProperties.isNullOrEmpty()) return fromLocalProperties
    return System.getenv(envKey)?.trim().orEmpty()
}

fun buildConfigString(value: String): String {
    val escaped = value.replace("\\", "\\\\").replace("\"", "\\\"")
    return "\"$escaped\""
}

val googleWebClientId = secretConfigValue("GOOGLE_WEB_CLIENT_ID", "GOOGLE_WEB_CLIENT_ID")
val mapLibreApiKey = secretConfigValue("MAPLIBRE_API_KEY", "MAPLIBRE_API_KEY")
val appCheckDebugToken = secretConfigValue("APP_CHECK_DEBUG_TOKEN", "APP_CHECK_DEBUG_TOKEN")
val rescueNodeIdHexLengthRaw = secretConfigValue(
    "RESCUE_NODE_ID_HEX_LENGTH",
    "RESCUE_NODE_ID_HEX_LENGTH"
)
val rescueNodeIdHexLength = rescueNodeIdHexLengthRaw
    .toIntOrNull()
    ?.coerceIn(8, 24)
    ?: 12
if (googleWebClientId.isBlank()) {
    logger.warn("GOOGLE_WEB_CLIENT_ID is not set. Google Sign-In will be disabled.")
}
if (mapLibreApiKey.isBlank()) {
    logger.warn("MAPLIBRE_API_KEY is not set. Map style requests may fail.")
}
if (rescueNodeIdHexLengthRaw.isNotBlank() && rescueNodeIdHexLengthRaw.toIntOrNull() == null) {
    logger.warn("RESCUE_NODE_ID_HEX_LENGTH is invalid. Falling back to default value 12.")
}

val releaseStoreFilePath = signingValue("STORE_FILE", "ANDROID_KEYSTORE_FILE")
val releaseStorePassword = signingValue("STORE_PASSWORD", "ANDROID_KEYSTORE_PASSWORD")
val releaseKeyAlias = signingValue("KEY_ALIAS", "ANDROID_KEY_ALIAS")
val releaseKeyPassword = signingValue("KEY_PASSWORD", "ANDROID_KEY_PASSWORD")
val hasReleaseSigning = listOf(
    releaseStoreFilePath,
    releaseStorePassword,
    releaseKeyAlias,
    releaseKeyPassword
).all { !it.isNullOrEmpty() }
val isReleaseTaskRequested = gradle.startParameter.taskNames.any { taskName ->
    taskName.contains("Release", ignoreCase = true)
}
val isInternalTaskRequested = gradle.startParameter.taskNames.any { taskName ->
    taskName.contains("Internal", ignoreCase = true)
}

android {
    namespace = "com.auralis.crisisconnect"
    compileSdk = 35
    testBuildType = if (hasReleaseSigning) "internal" else "debug"
    dynamicFeatures += setOf(":feature_rescue")

    defaultConfig {
        applicationId = "com.auralis.crisisconnect"
        minSdk = 24
        targetSdk = 35
        versionCode = 35
        versionName = "1.0.0"
        ndk {
            abiFilters += listOf("armeabi-v7a", "arm64-v8a")
        }
        manifestPlaceholders["googleWebClientId"] = googleWebClientId
        buildConfigField(
            "String",
            "GOOGLE_WEB_CLIENT_ID",
            buildConfigString(googleWebClientId)
        )
        buildConfigField(
            "int",
            "RESCUE_NODE_ID_HEX_LENGTH",
            rescueNodeIdHexLength.toString()
        )
        buildConfigField(
            "String",
            "APP_CHECK_DEBUG_TOKEN",
            buildConfigString("")
        )
        buildConfigField(
            "boolean",
            "VOICE_LATENCY_DIAGNOSTICS_ENABLED",
            "false"
        )
        resValue("string", "maplibre_api_key", mapLibreApiKey)

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        testInstrumentationRunnerArguments["firebaseAppCheckDebugSecret"] =
            appCheckDebugToken.ifBlank {
                System.getenv("APP_CHECK_DEBUG_TOKEN_FROM_CI")?.trim().orEmpty()
            }
        vectorDrawables {
            useSupportLibrary = true
        }
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = file(requireNotNull(releaseStoreFilePath))
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    buildTypes {
        getByName("debug") {
            versionNameSuffix = "-debug"
            buildConfigField(
                "String",
                "APP_CHECK_DEBUG_TOKEN",
                buildConfigString(appCheckDebugToken)
            )
            buildConfigField(
                "boolean",
                "VOICE_LATENCY_DIAGNOSTICS_ENABLED",
                "true"
            )
        }

        create("internal") {
            initWith(getByName("debug"))
            applicationIdSuffix = ".internal"
            versionNameSuffix = "-internal"
            resValue("string", "app_name", "Crisis Connect Internal")
            buildConfigField(
                "String",
                "APP_CHECK_DEBUG_TOKEN",
                buildConfigString(appCheckDebugToken)
            )
            buildConfigField(
                "boolean",
                "VOICE_LATENCY_DIAGNOSTICS_ENABLED",
                "true"
            )
            matchingFallbacks += listOf("debug", "release")
            if (hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("release")
            }
        }

        release {
            isMinifyEnabled = true
            isShrinkResources = true
            buildConfigField(
                "boolean",
                "VOICE_LATENCY_DIAGNOSTICS_ENABLED",
                "false"
            )
            ndk {
                debugSymbolLevel = "SYMBOL_TABLE"
            }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            if (hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions {
        jvmTarget = "11"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }

    // Keep all app languages in the base bundle. The app exposes an in-app language picker, so
    // Play/App Bundle language splits can leave the non-system language unavailable at runtime.
    bundle {
        abi {
            enableSplit = true
        }
        density {
            enableSplit = true
        }
        language {
            enableSplit = false
        }
    }
}

if (!hasReleaseSigning && isReleaseTaskRequested) {
    throw GradleException(
        "Release signing is not configured. Set keystore.properties or ANDROID_KEYSTORE_* env vars."
    )
}

if (!hasReleaseSigning && isInternalTaskRequested) {
    throw GradleException(
        "Internal signing is not configured. Set keystore.properties or ANDROID_KEYSTORE_* env vars."
    )
}

dependencies {

    implementation(libs.androidx.core.ktx)
    implementation("androidx.core:core-telecom:1.0.1")
    implementation("androidx.core:core-splashscreen:1.0.1")
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.compose.animation)
    implementation(libs.androidx.compose.foundation.layout)
    testImplementation(libs.junit)
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.7.3")
    testImplementation("androidx.arch.core:core-testing:2.2.0")
    testImplementation("androidx.test:core:1.6.1")
    testImplementation("org.robolectric:robolectric:4.12.2")
    testImplementation("io.mockk:mockk:1.13.12")
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.ui.test.junit4)
    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)

    // Data Store
    implementation("androidx.datastore:datastore-preferences:1.0.0")

    // JetPack Compose Ek Kütüphaneler
    implementation("androidx.compose.material:material-icons-extended:1.5.4")
    implementation("androidx.navigation:navigation-compose:2.8.0")
    implementation(platform("androidx.compose:compose-bom:2025.05.01"))
    implementation("androidx.compose.animation:animation")
    implementation("androidx.compose.foundation:foundation")
    implementation("com.airbnb.android:lottie-compose:6.4.0")  // Lottie animasyonları
    implementation("androidx.compose.material:material-ripple")

    // Media & imaging
    implementation("io.coil-kt:coil-compose:2.6.0")
    implementation("androidx.exifinterface:exifinterface:1.3.7")

    // MapLibre GL
    implementation("org.maplibre.gl:android-sdk:11.9.0")

    // Media3 - ExoPlayer
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.common)
    implementation(libs.media3.extractor)
    implementation(libs.media3.datasource)

    // QR
    implementation("com.google.zxing:core:3.5.1")
    implementation("androidx.compose.ui:ui-graphics:1.5.4")

    // QR Tarama
    implementation("androidx.camera:camera-core:1.4.0")
    implementation("androidx.camera:camera-camera2:1.4.0")
    implementation("androidx.camera:camera-lifecycle:1.4.0")
    implementation("androidx.camera:camera-view:1.4.0") // PreviewView
    implementation("androidx.camera:camera-mlkit-vision:1.4.0")// ML Kit için köprü
    // ML Kit Barcode Scanning (16 KB page‑size compatible)
    implementation("com.google.mlkit:barcode-scanning:17.3.0")

    // AES Şifreleme
    implementation("com.google.crypto.tink:tink-android:1.18.0")
    implementation("androidx.security:security-crypto-ktx:1.1.0") // EncryptedSharedPreferences
    implementation(libs.google.play.feature.delivery.ktx)

    // Room
    implementation("androidx.room:room-runtime:2.8.4")
    kapt("androidx.room:room-compiler:2.8.4")
    implementation("androidx.room:room-ktx:2.8.4")
    implementation("net.zetetic:sqlcipher-android:4.13.0")

    // Auth System + Firebase
    implementation(platform("com.google.firebase:firebase-bom:34.5.0"))
    implementation("com.google.firebase:firebase-analytics")
    implementation("com.google.android.gms:play-services-auth:21.1.0")
    implementation("com.google.firebase:firebase-auth-ktx:23.1.0")
    implementation("com.google.firebase:firebase-firestore")
    implementation("com.google.firebase:firebase-functions")
    implementation("com.google.firebase:firebase-appcheck-playintegrity")
    debugImplementation("com.google.firebase:firebase-appcheck-debug")
    add("internalImplementation", "com.google.firebase:firebase-appcheck-debug")

    implementation("com.squareup.okhttp3:okhttp:4.11.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")

    // Firebase
    implementation("com.google.firebase:firebase-crashlytics")
    implementation("com.google.firebase:firebase-analytics")
    implementation("com.google.firebase:firebase-perf")

    // Opus Codec
    implementation(files("libs/opus.aar"))

    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.compose.material3:material3:1.3.1")
}

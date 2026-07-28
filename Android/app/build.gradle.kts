import java.util.Properties
import org.gradle.api.GradleException

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.androidx.baselineprofile)
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
val mobileSyncBaseUrl = secretConfigValue("MOBILE_SYNC_BASE_URL", "MOBILE_SYNC_BASE_URL")
val mobileSyncPanelId = secretConfigValue("MOBILE_SYNC_PANEL_ID", "MOBILE_SYNC_PANEL_ID")
// The dashboard's Cloud Run origin, not the hosting domain: Firebase Hosting buffers SSE, so the
// chat stream must hit Cloud Run directly (same reason the web client hard-codes this origin).
val crisisSentinelOnlineBaseUrl = secretConfigValue(
    "CRISIS_SENTINEL_ONLINE_BASE_URL",
    "CRISIS_SENTINEL_ONLINE_BASE_URL"
).ifBlank { "https://ssrcrisisconnect1-jxxgznalnq-uc.a.run.app" }
val enterpriseSsoProviderId = secretConfigValue(
    "ENTERPRISE_SSO_PROVIDER_ID",
    "ENTERPRISE_SSO_PROVIDER_ID"
).ifBlank { "oidc.crisisconnect-sso" }
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
    compileSdk = 36
    ndkVersion = "27.0.12077973"
    testBuildType = if (hasReleaseSigning) "internal" else "debug"
    dynamicFeatures += setOf(":feature_rescue")

    // Native MLS FrameEncryptor/FrameDecryptor bridge (libmls_frame_crypto.so) — the per-frame E2EE
    // hook org.webrtc only exposes via native pointers. See app/src/main/cpp/mls_frame_crypto.cpp.
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    defaultConfig {
        applicationId = "com.auralis.crisisconnect"
        minSdk = 24
        targetSdk = 36
        versionCode = 59
        versionName = "1.1.8"
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
            "String",
            "ENTERPRISE_SSO_PROVIDER_ID",
            buildConfigString(enterpriseSsoProviderId)
        )
        buildConfigField(
            "String",
            "MOBILE_SYNC_BASE_URL",
            buildConfigString(mobileSyncBaseUrl)
        )
        buildConfigField(
            "String",
            "MOBILE_SYNC_PANEL_ID",
            buildConfigString(mobileSyncPanelId)
        )
        buildConfigField(
            "String",
            "CRISIS_SENTINEL_ONLINE_BASE_URL",
            buildConfigString(crisisSentinelOnlineBaseUrl)
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
                buildConfigString("")
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
        // libsignal-android requires java.time & friends on minSdk 24.
        isCoreLibraryDesugaringEnabled = true
    }
    kotlinOptions {
        jvmTarget = "11"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    packaging {
        jniLibs {
            // libsignal's AAR bundles a second, test-only JNI lib (~75 MB/ABI). Never ship it.
            excludes += "**/libsignal_jni_testing.so"
        }
        resources {
            // libsignal-client (pulled in for the Java API) carries DESKTOP natives as jar
            // resources (~86 MB of .dylib/.so for macOS/Linux JVMs). Android never loads these —
            // the AAR's lib/<abi>/libsignal_jni.so is the real one. Excluding them keeps the APK
            // at ~+11 MB for both ABIs instead of ~+97 MB.
            excludes += "libsignal_jni*.dylib"
            excludes += "libsignal_jni*.so"
            excludes += "signal_jni*.dll"
        }
    }

    lint {
        // The app deliberately ships with partial translations and falls back to the default English
        // resources while localization is completed. Keep runtime/security lint fatal, but do not
        // block APK smoke builds on incomplete locale coverage.
        disable += "MissingTranslation"
    }

    // The test phone's Secure Folder (user 150) holds a stale, differently-signed package record
    // that adb can't touch; all-users installs hit INSTALL_FAILED_UPDATE_INCOMPATIBLE. Installing
    // for the main user only sidesteps it.
    installation {
        installOptions += listOf("--user", "0")
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
    // AOT-compiles the baseline profiles shipped in our APK + library AARs (Compose ships its own)
    // right at install time, instead of waiting for the JIT to warm up on first launches.
    implementation(libs.androidx.profileinstaller)
    "baselineProfile"(project(":baselineprofile"))
    // WebRTC (maintained community build of Google's libwebrtc) for real-time internet voice calls.
    // Signaling (SDP/ICE) rides our existing E2E relay; media flows P2P via STUN (TURN added later).
    implementation("io.getstream:stream-webrtc-android:1.3.8")
    // Signal Protocol (X3DH/PQXDH + Double Ratchet) for forward-secret internet messaging (FS milestone).
    // AGPL-3.0 — the app is open source, license accepted for the spike; revisit before release.
    // The -android artifact carries the JNI .so per ABI; the -client jar (test scope) carries desktop
    // natives so the ratchet can run in plain JVM unit tests.
    implementation("org.signal:libsignal-android:0.86.5")
    testImplementation("org.signal:libsignal-client:0.86.5")
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.5")
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
    testImplementation("org.json:json:20240303")
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
    // icons-extended's last release is 1.7.8 (data-only ImageVectors, works with any newer Compose)
    implementation("androidx.compose.material:material-icons-extended:1.7.8")
    implementation("androidx.navigation:navigation-compose:2.9.4")
    implementation("androidx.compose.foundation:foundation")
    implementation("com.airbnb.android:lottie-compose:6.4.0")  // Lottie animasyonları
    implementation("androidx.compose.material:material-ripple")

    // Glance — home-screen widgets with a Compose-style API (SOS quick-access widget)
    implementation("androidx.glance:glance-appwidget:1.2.0-rc01")

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

    // QR Tarama
    implementation("androidx.camera:camera-core:1.4.0")
    implementation("androidx.camera:camera-camera2:1.4.0")
    implementation("androidx.camera:camera-lifecycle:1.4.0")
    implementation("androidx.camera:camera-view:1.4.0") // PreviewView
    implementation("androidx.camera:camera-mlkit-vision:1.4.0")// ML Kit için köprü
    // ML Kit Barcode Scanning (16 KB page‑size compatible)
    implementation("com.google.mlkit:barcode-scanning:17.3.0")
    implementation("com.google.android.gms:play-services-mlkit-text-recognition:19.0.1")

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
    implementation("com.google.firebase:firebase-storage")
    implementation("com.google.firebase:firebase-messaging")

    // Dial codes, example numbers, parsing/validation for the phone-auth country picker
    implementation("com.googlecode.libphonenumber:libphonenumber:8.13.55")
    // Flat rectangular (vector) country flags, looked up by ISO code
    implementation("com.github.murgupluoglu:flagkit-android:1.2.0")
    // SMS User Consent API — one-tap auto-fill of the phone verification code
    implementation("com.google.android.gms:play-services-auth-api-phone:18.2.0")
    // reCAPTCHA Enterprise client — invisible in-app assessment for phone auth
    // (SMS bot score, AUDIT mode), replacing the browser reCAPTCHA fallback that
    // breaks on old devices (e.g. Android 8 Go with Samsung Internet Lite).
    implementation("com.google.android.recaptcha:recaptcha:18.9.1")

    // Offline profile photo upload queue
    implementation("androidx.work:work-runtime-ktx:2.9.1")
    implementation("com.google.firebase:firebase-appcheck-playintegrity")
    debugImplementation("com.google.firebase:firebase-appcheck-debug")

    // Standalone Play Integrity for raw integrity tokens used by attestation flow
    implementation("com.google.android.play:integrity:1.4.0")

    // Google Play In-App Review (rating card shown at positive moments)
    implementation("com.google.android.play:review:2.0.2")
    implementation("com.google.android.play:review-ktx:2.0.2")

    implementation("com.squareup.okhttp3:okhttp:4.11.0")
    implementation("com.google.ai.edge.litertlm:litertlm-android:0.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")

    // Firebase
    implementation("com.google.firebase:firebase-crashlytics")
    implementation("com.google.firebase:firebase-analytics")
    implementation("com.google.firebase:firebase-perf")

    // Opus Codec
    implementation(files("libs/opus.aar"))

    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
}
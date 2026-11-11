import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}
val watchSigning = Properties().apply {
    rootProject.file("keystore.properties").takeIf { it.exists() }?.inputStream()?.use(::load)
}
fun watchSigningValue(key: String, env: String) = watchSigning.getProperty(key)?.takeIf { it.isNotBlank() } ?: System.getenv(env)
val watchStore = watchSigningValue("STORE_FILE", "ANDROID_KEYSTORE_FILE")
val watchPassword = watchSigningValue("STORE_PASSWORD", "ANDROID_KEYSTORE_PASSWORD")
val watchAlias = watchSigningValue("KEY_ALIAS", "ANDROID_KEY_ALIAS")
val watchKeyPassword = watchSigningValue("KEY_PASSWORD", "ANDROID_KEY_PASSWORD")
val watchHasSigning = listOf(watchStore, watchPassword, watchAlias, watchKeyPassword).all { !it.isNullOrBlank() }

android {
    namespace = "com.auralis.crisisconnect.wear"
    compileSdk = 37
    defaultConfig {
        // Data Layer requires identical application IDs and signing certificates on both devices.
        applicationId = "com.auralis.crisisconnect"
        minSdk = 30
        targetSdk = 37
        versionCode = 70
        versionName = "1.2.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    sourceSets.getByName("main").java.srcDir("../watch-protocol/src/main/java")
    sourceSets.maybeCreate("internal").java.srcDir("src/debug/java")
    testBuildType = if (providers.gradleProperty("watchDevTests").isPresent) "watchTest" else "debug"
    buildFeatures { compose = true }
    signingConfigs {
        if (watchHasSigning) create("release") {
            // Resolve relative paths the same way as the phone module.
            storeFile = project(":app").file(requireNotNull(watchStore))
            storePassword = watchPassword
            keyAlias = watchAlias
            keyPassword = watchKeyPassword
        }
    }
    buildTypes {
        getByName("debug") { versionNameSuffix = "-debug" }
        create("watchTest") {
            initWith(getByName("debug"))
            applicationIdSuffix = ".watchtest"
            versionNameSuffix = "-watch-test"
            matchingFallbacks += listOf("debug")
        }
        create("internal") {
            initWith(getByName("debug"))
            applicationIdSuffix = ".internal"
            versionNameSuffix = "-internal"
            if (watchHasSigning) signingConfig = signingConfigs.getByName("release")
        }
        getByName("release") {
            if (watchHasSigning) signingConfig = signingConfigs.getByName("release")
        }
    }
    compileOptions {
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions { jvmTarget = "11" }
    packaging {
        jniLibs.excludes += "**/libsignal_jni_testing.so"
        resources.excludes += listOf("libsignal_jni*.dylib", "libsignal_jni*.so", "libsignal_jni*.dll")
    }
}
dependencies {
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.5")
    implementation("org.signal:libsignal-android:0.86.5")
    implementation("com.google.android.gms:play-services-wearable:20.0.1")
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.4")
    implementation(platform(libs.androidx.compose.bom))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.wear.compose:compose-material3:1.5.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
    testImplementation(libs.junit)
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test:runner:1.7.0")
    // Android 16 removed the hidden InputManager API used by older Espresso versions.
    androidTestImplementation("androidx.test.espresso:espresso-core:3.7.0")
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.ui.test.junit4)
    androidTestImplementation(libs.androidx.uiautomator)
    debugImplementation(libs.androidx.ui.test.manifest)
}

dependencies { add("watchTestImplementation", libs.androidx.ui.test.manifest) }

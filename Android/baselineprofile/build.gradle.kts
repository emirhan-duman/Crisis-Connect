plugins {
    alias(libs.plugins.android.test)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.androidx.baselineprofile)
}

android {
    namespace = "com.auralis.crisisconnect.baselineprofile"
    compileSdk = 36

    defaultConfig {
        // Baseline profile generation needs API 28+ (rooted) or API 33+ (unrooted, e.g. the S21).
        minSdk = 28
        targetSdk = 36
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    kotlinOptions {
        jvmTarget = "11"
    }

    targetProjectPath = ":app"

    // The test phone's Secure Folder (user 150) holds a stale, differently-signed package record
    // that adb can't touch; all-users installs hit INSTALL_FAILED_UPDATE_INCOMPATIBLE. Installing
    // for the main user only sidesteps it.
    installation {
        installOptions += listOf("--user", "0")
    }
}

baselineProfile {
    // Generate on whatever device/emulator is plugged in (API 33+ if unrooted).
    useConnectedDevices = true
}

dependencies {
    implementation(libs.androidx.junit)
    implementation(libs.androidx.uiautomator)
    implementation(libs.androidx.benchmark.macro.junit4)
}

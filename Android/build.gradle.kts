plugins {
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

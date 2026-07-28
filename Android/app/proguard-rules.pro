# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# If your project uses WebView with JS, uncomment the following
# and specify the fully qualified class name to the JavaScript interface
# class:
#-keepclassmembers class fqcn.of.javascript.interface.for.webview {
#   public *;
#}

# Uncomment this to preserve the line number information for
# debugging stack traces.
#-keepattributes SourceFile,LineNumberTable

# If you keep the line number information, uncomment this to
# hide the original source file name.
#-renamesourcefileattribute SourceFile

# Keep source lines for clearer Crashlytics stack traces.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# JNI method names must stay stable.
-keepclasseswithmembernames class * {
    native <methods>;
}

# Room uses generated implementations and annotation metadata.
-keep class * extends androidx.room.RoomDatabase { *; }
-keep @androidx.room.Entity class *

# Room resolves generated "_Impl" databases via reflection at runtime.
# Keep explicit implementations to avoid ClassNotFoundException on release builds.
-keep class com.auralis.crisisconnect.data.AppDatabase_Impl { *; }
-keep class com.auralis.crisisconnect.data.offline.OfflineDatabase_Impl { *; }

# Strip verbose/debug log calls in release builds so they cannot leak PII,
# key sizes, or document identifiers. Log.i/w/e are retained for Crashlytics
# breadcrumbs and real-error visibility.
-assumenosideeffects class android.util.Log {
    public static *** d(...);
    public static *** v(...);
}

# MLS E2EE native bridges: JNI resolves Java_com_auralis_..._MlsWorker_nativeXxx symbols BY NAME —
# the class names and external-fun names must survive minification or every release-build authority
# call loses E2EE (the natives throw UnsatisfiedLinkError and MlsWorker.available turns false).
-keepclasseswithmembernames class com.auralis.crisisconnect.messaging.call.sfu.MlsWorker {
    native <methods>;
}
-keepclasseswithmembernames class com.auralis.crisisconnect.messaging.call.sfu.MlsFrameCrypto {
    native <methods>;
}

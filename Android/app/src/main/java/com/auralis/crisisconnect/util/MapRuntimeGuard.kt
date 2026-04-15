package com.auralis.crisisconnect.util

import android.app.ActivityManager
import android.content.Context
import android.util.Log
import com.auralis.crisisconnect.R
import org.maplibre.android.MapLibre
import org.maplibre.android.WellKnownTileServer
import org.maplibre.android.maps.MapView

private const val REQUIRED_OPENGL_ES_3 = 0x00030000

fun isMapLibreRuntimeSupported(context: Context): Boolean {
    val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        ?: return false
    val reqGlEsVersion = activityManager.deviceConfigurationInfo?.reqGlEsVersion ?: 0
    return reqGlEsVersion >= REQUIRED_OPENGL_ES_3
}

fun initializeMapLibreSafely(
    context: Context,
    logTag: String = "MapRuntimeGuard",
): Boolean {
    if (!isMapLibreRuntimeSupported(context)) {
        return false
    }
    return runCatching {
        MapLibre.getInstance(
            context.applicationContext,
            context.getString(R.string.maplibre_api_key),
            WellKnownTileServer.MapLibre
        )
    }.onFailure { throwable ->
        Log.w(logTag, "MapLibre initialization failed", throwable)
    }.isSuccess
}

fun createMapViewSafely(
    context: Context,
    logTag: String = "MapRuntimeGuard",
): MapView? {
    if (!initializeMapLibreSafely(context, logTag)) {
        return null
    }
    return runCatching {
        MapView(context).apply {
            onCreate(null)
        }
    }.onFailure { throwable ->
        Log.w(logTag, "MapView creation failed", throwable)
    }.getOrNull()
}

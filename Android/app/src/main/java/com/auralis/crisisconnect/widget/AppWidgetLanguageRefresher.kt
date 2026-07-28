package com.auralis.crisisconnect.widget

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import androidx.glance.appwidget.GlanceAppWidgetReceiver

/**
 * Re-renders every pinned home-screen widget of ours. Widget text is resolved from app
 * resources at compose time, so after an in-app language change the pinned widgets keep
 * their old-language text until the next system-initiated update — which, with
 * updatePeriodMillis=0, never comes. LocaleHelper.setLocale calls this to close that gap.
 */
object AppWidgetLanguageRefresher {

    fun refreshAll(context: Context) {
        val appContext = context.applicationContext
        val manager = AppWidgetManager.getInstance(appContext) ?: return
        refresh(appContext, manager, SosWidgetReceiver::class.java)
        refresh(appContext, manager, DisastersWidgetReceiver::class.java)
    }

    private fun refresh(
        context: Context,
        manager: AppWidgetManager,
        receiver: Class<out GlanceAppWidgetReceiver>,
    ) {
        val ids = manager.getAppWidgetIds(ComponentName(context, receiver))
        if (ids == null || ids.isEmpty()) return
        context.sendBroadcast(
            Intent(AppWidgetManager.ACTION_APPWIDGET_UPDATE)
                .setComponent(ComponentName(context, receiver))
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, ids)
        )
    }
}

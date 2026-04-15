package com.auralis.crisisconnect.service

import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.os.LocaleList
import com.auralis.crisisconnect.getSavedLanguageSync
import java.util.Locale

object NotificationLocalization {
    fun localizedContext(baseContext: Context): Context {
        val appContext = baseContext.applicationContext
        val languageCode = runCatching {
            getSavedLanguageSync(appContext)
        }.getOrElse {
            Locale.getDefault().language
        }
        val locale = when {
            languageCode.trim().replace('_', '-').lowercase(Locale.US).startsWith("tr") -> {
                Locale("tr", "TR")
            }

            else -> Locale("en", "US")
        }
        val config = Configuration(baseContext.resources.configuration)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            config.setLocales(LocaleList(locale))
        } else {
            @Suppress("DEPRECATION")
            config.setLocale(locale)
        }
        config.setLayoutDirection(locale)
        return baseContext.createConfigurationContext(config)
    }
}

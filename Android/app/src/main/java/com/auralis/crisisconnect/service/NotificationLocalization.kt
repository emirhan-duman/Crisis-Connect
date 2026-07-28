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
        val normalizedLanguage = languageCode.trim().replace('_', '-').lowercase(Locale.US)
        val locale = when {
            normalizedLanguage.startsWith("tr") -> {
                Locale("tr", "TR")
            }

            normalizedLanguage.startsWith("ja") -> {
                Locale("ja", "JP")
            }

            normalizedLanguage.startsWith("es") -> {
                Locale("es", "ES")
            }

            normalizedLanguage.startsWith("hi") -> {
                Locale("hi", "IN")
            }

            normalizedLanguage.startsWith("fr") -> {
                Locale("fr", "FR")
            }

            normalizedLanguage.startsWith("ar") -> {
                Locale("ar")
            }

            normalizedLanguage.startsWith("ku") -> {
                Locale("ku")
            }

            normalizedLanguage.startsWith("fa") -> {
                Locale("fa")
            }

            normalizedLanguage.startsWith("id") -> {
                Locale("id")
            }

            normalizedLanguage.startsWith("bn") -> {
                Locale("bn")
            }

            normalizedLanguage.startsWith("ru") -> {
                Locale("ru")
            }

            normalizedLanguage.startsWith("de") -> {
                Locale("de", "DE")
            }

            normalizedLanguage.startsWith("ur") -> {
                Locale("ur")
            }

            normalizedLanguage.startsWith("zh") -> {
                Locale("zh", "CN")
            }

            normalizedLanguage.startsWith("uk") -> {
                Locale("uk")
            }

            normalizedLanguage.startsWith("pt") -> {
                Locale("pt")
            }

            normalizedLanguage.startsWith("fil") -> {
                Locale("fil")
            }

            normalizedLanguage.startsWith("vi") -> {
                Locale("vi")
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

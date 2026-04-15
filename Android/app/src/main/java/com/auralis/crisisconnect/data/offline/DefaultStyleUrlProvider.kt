package com.auralis.crisisconnect.data.offline

import android.content.Context
import com.auralis.crisisconnect.R
import com.auralis.crisisconnect.domain.offline.StyleUrlProvider

/**
 * Provides style urls backed by string resources to allow customization.
 */
class DefaultStyleUrlProvider(private val context: Context) : StyleUrlProvider {

    private val apiKey: String
        get() = context.getString(R.string.maplibre_api_key)

    override fun primary(): String =
        context.getString(R.string.offline_map_default_style_url, apiKey)

    override fun fallback(): String =
        context.getString(R.string.offline_map_fallback_style_url, apiKey)
}

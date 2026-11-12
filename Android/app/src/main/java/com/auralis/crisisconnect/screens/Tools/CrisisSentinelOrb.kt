package com.auralis.crisisconnect.screens.Tools

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color as AndroidColor
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.Image
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.auralis.crisisconnect.R

/**
 * Visual variant distinguishing Cloud / Hybrid AI (Crisis Sentinel) from On-Device /
 * Offline AI (Crisis Edge).
 */
enum class CrisisSentinelOrbVariant {
    Sentinel, // Ember preset (Crimson / Neon Orange / Amber / Peach)
    Edge      // Aurora preset (Emerald / Bright Mint / Cyan / Violet)
}

object CrisisSentinelColors {
    // Official React Bits "ember" preset for Crisis Sentinel (matching Web)
    val SentinelStrands = listOf(
        Color(0xFFFF2A1F), // Crimson
        Color(0xFFFF5A2E), // Neon Orange
        Color(0xFFFF7A1A), // Amber
        Color(0xFFFFE2A8), // Warm Light Core
        Color(0xFFFFB37A), // Peach
        Color(0xFFFF3D2E), // Bright Red
        Color(0xFFC42A6A)  // Deep Rose
    )

    // Official React Bits "aurora" preset for Crisis Edge
    val EdgeStrands = listOf(
        Color(0xFF00E08A), // Emerald
        Color(0xFF3DFFB0), // Bright Mint
        Color(0xFF00C2A8), // Teal
        Color(0xFFC8FFF0), // Light Core
        Color(0xFF7FE3FF), // Electric Cyan
        Color(0xFF2F6BFF), // Vibrant Blue
        Color(0xFF6A4DFF)  // Deep Violet
    )
}

/**
 * Modern animated Orb component representing Crisis Sentinel or Crisis Edge.
 *
 * Runs the 100% authentic React component (Strands.jsx by David Haz) bundled directly
 * from developer/crisis-connect-web, executed via WebGL2 in a hardware-accelerated
 * transparent WebView with an instant high-resolution fallback.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun CrisisSentinelOrb(
    modifier: Modifier = Modifier,
    size: Dp = 48.dp,
    variant: CrisisSentinelOrbVariant = CrisisSentinelOrbVariant.Sentinel,
    isListening: Boolean = false,
    isGenerating: Boolean = false,
    energy: Float = 0.65f
) {
    val isDark = isSystemInDarkTheme()
    val variantStr = if (variant == CrisisSentinelOrbVariant.Edge) "edge" else "sentinel"
    val themeStr = if (isDark) "dark" else "light"
    val fallbackDrawable = if (variant == CrisisSentinelOrbVariant.Edge) {
        R.drawable.ic_crisis_edge_orb
    } else {
        R.drawable.ic_crisis_sentinel_orb
    }

    Box(
        modifier = modifier.size(size),
        contentAlignment = Alignment.Center
    ) {
        // Instant crisp raster placeholder while WebGL initializes
        Image(
            painter = painterResource(id = fallbackDrawable),
            contentDescription = null,
            modifier = Modifier.size(size)
        )

        // Live interactive React WebGL component
        AndroidView(
            factory = { context ->
                createStrandsWebView(context, variantStr, isListening, isGenerating, themeStr)
            },
            update = { webView ->
                val script = "if(window.setOrbVariant){window.setOrbVariant('$variantStr');window.setOrbListening($isListening);window.setOrbGenerating($isGenerating);window.setOrbTheme('$themeStr');}"
                webView.evaluateJavascript(script, null)
            },
            modifier = Modifier.size(size)
        )
    }
}

private fun createStrandsWebView(
    context: Context,
    variant: String,
    isListening: Boolean,
    isGenerating: Boolean,
    theme: String
): WebView {
    return WebView(context).apply {
        setBackgroundColor(AndroidColor.TRANSPARENT)
        isVerticalScrollBarEnabled = false
        isHorizontalScrollBarEnabled = false
        isFocusable = false
        isClickable = false

        webChromeClient = WebChromeClient()

        settings.apply {
            javaScriptEnabled = true
            allowFileAccess = true
            domStorageEnabled = true
            cacheMode = WebSettings.LOAD_DEFAULT
            useWideViewPort = true
            loadWithOverviewMode = true
        }

        webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                view?.evaluateJavascript(
                    "if(window.setOrbVariant){window.setOrbVariant('$variant');window.setOrbListening($isListening);window.setOrbGenerating($isGenerating);window.setOrbTheme('$theme');}",
                    null
                )
            }
        }

        // Load asset WITHOUT query params so AssetManager doesn't fail on Android
        loadUrl("file:///android_asset/strands/strands_app.html")
    }
}

/**
 * High-fidelity static icon matching the authentic React Strands Orb 100% pixel-perfect.
 * Used for 16dp–32dp sizes (lists, navigation bars, badges, status cards) with 0 battery consumption.
 */
@Composable
fun CrisisSentinelIcon(
    modifier: Modifier = Modifier,
    size: Dp = 24.dp,
    variant: CrisisSentinelOrbVariant = CrisisSentinelOrbVariant.Sentinel
) {
    val drawableRes = if (variant == CrisisSentinelOrbVariant.Edge) {
        R.drawable.ic_crisis_edge_orb
    } else {
        R.drawable.ic_crisis_sentinel_orb
    }

    Image(
        painter = painterResource(id = drawableRes),
        contentDescription = "Crisis Sentinel",
        modifier = modifier.size(size)
    )
}

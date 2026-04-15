package com.auralis.crisisconnect.feature

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.auralis.crisisconnect.R
import com.auralis.crisisconnect.security.SecurityRepository
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun RescueFeatureRedirectScreen(
    navController: NavController,
    startDestination: String
) {
    val context = LocalContext.current
    val rescueFeatureManager = remember(context) {
        RescueFeatureManager(context.applicationContext)
    }
    var installProgress by rememberSaveable(startDestination) { mutableStateOf<Int?>(null) }
    var launchStarted by rememberSaveable(startDestination) { mutableStateOf(false) }
    val rescueInstallConfirmationLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartIntentSenderForResult()
    ) {
        // The split install listener handles success, cancellation, and retry states.
    }

    LaunchedEffect(startDestination) {
        if (launchStarted) {
            return@LaunchedEffect
        }
        launchStarted = true

        val hasAccess = withContext(Dispatchers.IO) {
            SecurityRepository(context.applicationContext)
                .getUsableStoredCertificateRole(allowExpired = true)
                ?.trim()
                ?.lowercase(Locale.US) in RESCUE_FEATURE_ROLES
        }
        if (!hasAccess) {
            Toast.makeText(
                context,
                context.getString(R.string.rescue_forbidden_toast),
                Toast.LENGTH_LONG
            ).show()
            navController.popBackStack()
            return@LaunchedEffect
        }

        if (rescueFeatureManager.launchInstalled(context, startDestination = startDestination)) {
            navController.popBackStack()
            return@LaunchedEffect
        }

        val hostActivity = context.findHostActivity()
        if (hostActivity == null) {
            Toast.makeText(
                context,
                context.getString(R.string.rescue_module_unavailable),
                Toast.LENGTH_LONG
            ).show()
            navController.popBackStack()
            return@LaunchedEffect
        }

        rescueFeatureManager.installAndLaunch(
            activity = hostActivity,
            confirmationLauncher = rescueInstallConfirmationLauncher,
            startDestination = startDestination,
            onStateChanged = { state ->
                when (state) {
                    is RescueFeatureManager.InstallState.Installing -> {
                        installProgress = state.progressPercent
                    }

                    RescueFeatureManager.InstallState.NotInstalling -> {
                        installProgress = null
                        if (rescueFeatureManager.isInstalled()) {
                            navController.popBackStack()
                        }
                    }
                }
            },
            onError = { message ->
                installProgress = null
                Toast.makeText(context, message, Toast.LENGTH_LONG).show()
                navController.popBackStack()
            }
        )
    }

    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            CircularProgressIndicator()
            Text(
                text = stringResource(R.string.rescue_module_title),
                style = MaterialTheme.typography.titleMedium,
                textAlign = TextAlign.Center
            )
            installProgress?.let { progress ->
                Text(
                    text = "$progress%",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

private fun Context.findHostActivity(): Activity? {
    return when (this) {
        is Activity -> this
        is ContextWrapper -> baseContext.findHostActivity()
        else -> null
    }
}

private val RESCUE_FEATURE_ROLES = setOf("admin", "fieldteam")

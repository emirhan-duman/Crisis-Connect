package com.auralis.crisisconnect.muster

import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.auralis.crisisconnect.R
import com.auralis.crisisconnect.ThemeOption
import com.auralis.crisisconnect.getSavedThemeOption
import com.auralis.crisisconnect.getSavedThemeOptionSync
import com.auralis.crisisconnect.ui.theme.DisasterCommunicationSystemTheme
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

class MusterActivity : ComponentActivity() {
    private val urgentTone by lazy { MusterUrgentTone(this) }
    private var deviceUnlocked by mutableStateOf(false)
    private val unlockResult = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        refreshLockState()
    }

    override fun attachBaseContext(newBase: Context) {
        val config = Configuration(newBase.resources.configuration).apply {
            setLocale(com.auralis.crisisconnect.localeForLanguageCode(com.auralis.crisisconnect.getSavedLanguageSync(newBase)))
        }
        super.attachBaseContext(newBase.createConfigurationContext(config))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // This notification activity does not run the launcher's splash-screen
        // lifecycle. Apply its post-splash theme explicitly so Android does not
        // add a second native title bar above the Compose app bar.
        setTheme(R.style.Theme_CrisisConnect)
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
        }
        refreshLockState()
        val initialTheme = getSavedThemeOptionSync(this)
        configureSystemBars(when (initialTheme) {
            ThemeOption.DARK -> true
            ThemeOption.LIGHT -> false
            ThemeOption.SYSTEM -> (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        })
        setContent {
            val theme by remember { getSavedThemeOption(this@MusterActivity) }.collectAsStateWithLifecycle(initialTheme)
            val darkTheme = when (theme) {
                ThemeOption.DARK -> true
                ThemeOption.LIGHT -> false
                ThemeOption.SYSTEM -> isSystemInDarkTheme()
            }
            SideEffect { configureSystemBars(darkTheme) }
            DisasterCommunicationSystemTheme(darkTheme = darkTheme, dynamicColor = theme == ThemeOption.SYSTEM) {
                if (deviceUnlocked) {
                    MusterScreen(intent.getStringExtra("panelId"), intent.getStringExtra("sessionId")) { finish() }
                } else {
                    MusterLockedScreen(intent.getStringExtra("kind"), { unlock() }, { finish() })
                }
            }
        }
    }

    private fun configureSystemBars(darkTheme: Boolean) {
        val style = if (darkTheme) SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
        else SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT)
        enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
    }

    override fun onResume() {
        super.onResume(); refreshLockState()
        val uid = FirebaseAuth.getInstance().currentUser?.takeUnless { it.isAnonymous }?.uid
        if (uid != null && uid == intent.getStringExtra("uid")) urgentTone.start(uid, intent.getStringExtra("alertProfile") == "urgent" && intent.getLongExtra("expiresAtMillis", 0) > System.currentTimeMillis(), intent.getStringExtra("kind") != "live")
    }
    override fun dispatchTouchEvent(event: android.view.MotionEvent): Boolean {
        if (event.action == android.view.MotionEvent.ACTION_DOWN) urgentTone.stop()
        return super.dispatchTouchEvent(event)
    }
    override fun onDestroy() { urgentTone.stop(); super.onDestroy() }
    override fun onPause() { urgentTone.stop(); deviceUnlocked = false; super.onPause() }

    private fun refreshLockState() {
        deviceUnlocked = getSystemService(KeyguardManager::class.java)?.isDeviceLocked != true
    }

    private fun unlock() {
        val manager = getSystemService(KeyguardManager::class.java) ?: return
        if (Build.VERSION.SDK_INT >= 26) {
            manager.requestDismissKeyguard(this, object : KeyguardManager.KeyguardDismissCallback() {
                override fun onDismissSucceeded() { refreshLockState() }
            })
        } else {
            @Suppress("DEPRECATION")
            val intent: Intent? = manager.createConfirmDeviceCredentialIntent(getString(R.string.muster_title), getString(R.string.muster_unlock))
            if (intent != null) unlockResult.launch(intent) else refreshLockState()
        }
    }
}

@Composable
fun MusterScreen(initialPanel: String? = null, initialSession: String? = null, onBack: () -> Unit) {
    val context = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    val inbox = rememberMusterInbox()
    var refreshing by remember(inbox.uid) { mutableStateOf(false) }
    var saving by remember(inbox.uid) { mutableStateOf(false) }
    var refreshError by remember(inbox.uid) { mutableStateOf(false) }
    var saveError by remember(inbox.uid) { mutableStateOf(false) }
    var pendingReply by remember(inbox.uid) { mutableStateOf<Pair<String, String>?>(null) }
    suspend fun reload() {
        val expected = inbox.uid ?: return
        refreshing = true; refreshError = false
        try { MusterClient.refresh(context, initialPanel, initialSession) }
        catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
        catch (_: Exception) { if (FirebaseAuth.getInstance().currentUser?.uid == expected) refreshError = true }
        finally { if (FirebaseAuth.getInstance().currentUser?.uid == expected) refreshing = false }
        if (FirebaseAuth.getInstance().currentUser?.uid == expected) MusterClient.schedule(context, expected)
    }
    LaunchedEffect(inbox.uid, initialPanel, initialSession) { reload() }
    MusterWorkspace(inbox, refreshing, saving, refreshError, saveError, initialPanel, initialSession,
        onBack = onBack, onRefresh = { scope.launch { reload() } }, onAnswer = { _, _, _ -> }, onFlexibleAnswer = { row, submission ->
            val expected = inbox.uid
            if (expected != null && !saving) scope.launch {
                saving = true; saveError = false
                val fingerprint = musterHash(listOf(row.key, submission.answer, submission.optionId.orEmpty(), submission.note,
                    submission.voice?.sha256.orEmpty(), submission.voice?.durationMillis?.toString().orEmpty()).joinToString("\u0000").toByteArray())
                val operation = pendingReply?.takeIf { it.first == fingerprint }?.second ?: java.util.UUID.randomUUID().toString()
                pendingReply = fingerprint to operation
                try {
                    withContext(Dispatchers.IO) { MusterClient.enqueue(context, expected, row, submission, operation) }
                    if (FirebaseAuth.getInstance().currentUser?.uid == expected) pendingReply = null
                }
                catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                catch (_: Exception) { if (FirebaseAuth.getInstance().currentUser?.uid == expected) saveError = true }
                finally { if (FirebaseAuth.getInstance().currentUser?.uid == expected) saving = false }
            }
        })
}

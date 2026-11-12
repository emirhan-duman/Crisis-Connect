package com.auralis.crisisconnect.watch

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation.compose.*
import com.auralis.crisisconnect.screens.SOSScreen
import com.auralis.crisisconnect.service.GattSOSServerService
import org.json.JSONObject

/** Runs the unmodified production screen/store with emulator-only external-effect suppression. */
class RecoveryPreviewActivity : ComponentActivity() {
    override fun attachBaseContext(base: android.content.Context) {
        val config = android.content.res.Configuration(base.resources.configuration)
        config.setLocale(java.util.Locale.forLanguageTag("tr"))
        super.attachBaseContext(base.createConfigurationContext(config))
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        check(WatchTestHooks.enabled)
        val prefs = getSharedPreferences("watch_dev_link", MODE_PRIVATE)
        val token = intent.getStringExtra("dev_link_token") ?: prefs.getString("token", null)
        check(prefs.edit().putBoolean("recovery", true).apply { if (token != null) putString("token", token) }.commit())
        WatchTestHooks.install(RecoveryPreviewDelegate(this))
        if (token != null) androidx.core.content.ContextCompat.startForegroundService(this, android.content.Intent(this, DevLinkService::class.java))
        val store = WatchRecoveryStore.get(this)
        if (intent.getBooleanExtra("recovery_seed", false)) {
            check(!GattSOSServerService.isDeclared.value)
            store.finish(); store.begin(1791792000123L)
            store.reserveReply("recovery-ui-safe", "synthetic-rescue", "İyiyim")
            store.completeReply("recovery-ui-safe", "accepted")
            store.reserveReply("recovery-ui-injured", "synthetic-rescue", "Yaralıyım")
        }
        setContent { MaterialTheme { Surface(Modifier.fillMaxSize()) {
            val saved by store.state.collectAsState()
            val active by GattSOSServerService.isDeclared.collectAsState()
            val nav = rememberNavController()
            Column(Modifier.fillMaxSize().padding(top = 28.dp, bottom = 24.dp)) {
                Text("KONTROLLÜ TEST · BLE ve dış bildirimler kapalı", modifier = Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.labelSmall)
                Text("Oturum: ${saved.startedAt ?: "yok"} · Aktif: $active", modifier = Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.labelSmall)
                NavHost(nav, startDestination = "home", modifier = Modifier.weight(1f)) {
                    composable("home") {
                        if (saved.startedAt != null || active) LaunchedEffect(Unit) { nav.navigate("sos_status") }
                        else Text("SOS kapalı", modifier = Modifier.padding(24.dp))
                    }
                    composable("sos_status") { SOSScreen(nav) }
                }
            }
        } } }
    }
}

class RecoveryPreviewDelegate(private val context: android.content.Context) : WatchTestHooks.Delegate {
    override fun productionRecovery() = true
    override fun state(actual: PhoneWatchState) = actual
    override fun reply(command: WatchCommand) = "noPeer"
    override fun requestRecoveryApproval(command: WatchCommand): String {
        java.io.File(context.filesDir, "recovery-approval.json").writeText(command.encode().toString(Charsets.UTF_8))
        return "phoneConfirmation"
    }
    override fun record(command: WatchCommand, receipt: JSONObject) {
        java.io.File(context.filesDir, "recovery-last-receipt.json").writeText(receipt.toString())
    }
}

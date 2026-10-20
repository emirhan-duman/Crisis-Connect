package com.auralis.crisisconnect.watch

import android.app.Activity
import android.os.Bundle
import android.widget.Toast
import com.auralis.crisisconnect.R
import kotlinx.coroutines.*

class WatchSosApprovalActivity : Activity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var handled = false
    override fun onPostResume() {
        super.onPostResume()
        if (handled) return
        handled = true
        val command = intent.getByteArrayExtra("command")?.let(WatchCommand::decode)
        if (command == null) { finish(); return }
        scope.launch {
            val result = runCatching { PhoneWatchBridge.approveOnPhone(this@WatchSosApprovalActivity, command) }.getOrDefault("failed")
            if (result != "accepted") Toast.makeText(this@WatchSosApprovalActivity, getString(R.string.watch_sos_not_started), Toast.LENGTH_LONG).show()
            finish()
        }
    }
    override fun onDestroy() { scope.cancel(); super.onDestroy() }
}

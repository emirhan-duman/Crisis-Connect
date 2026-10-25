package com.auralis.crisisconnect.watch

import android.os.Build
import com.auralis.crisisconnect.BuildConfig
import org.json.JSONObject

/** No test transport is packaged in production. The separate watchTest APK is emulator-only. */
object WatchTestHooks {
    val enabled: Boolean get() = BuildConfig.WATCH_TEST && Build.HARDWARE in setOf("ranchu", "goldfish")
    var delegate: Delegate? = null
        private set
    fun install(value: Delegate) { check(enabled); delegate = value }
    interface Delegate {
        fun productionRecovery(): Boolean = false
        fun requestRecoveryApproval(command: WatchCommand): String = "openPhone"
        fun restoredEmergencyTimestamp(): Long? = null
        fun sosStateChanged(startedAt: Long?) {}
        fun state(actual: PhoneWatchState): PhoneWatchState
        fun reply(command: WatchCommand): String
        fun contactReply(command: WatchCommand): String = "noPeer"
        fun record(command: WatchCommand, receipt: JSONObject)
    }
}

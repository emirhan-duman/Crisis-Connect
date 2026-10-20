package com.auralis.crisisconnect.wear

import android.content.Intent
import android.os.Build
import com.auralis.crisisconnect.watch.*
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** Actual socket round trips to the separate phone emulator, never synthetic receipts. */
fun createWatchSimulation(intent: Intent): WatchSimulation {
    check(Build.HARDWARE in setOf("ranchu", "goldfish"))
    val token = requireNotNull(intent.getStringExtra("dev_link_token"))
    require(UUID.fromString(token).toString() == token)
    return DevLinkTransport(token)
}

class DevLinkTransport(private val token: String) : WatchSimulation {
    companion object { @Volatile var commandObserver: ((WatchCommand)->Unit)? = null }
    override val turkish = true
    override val developerLink = true
    suspend fun receipt(command: WatchCommand): JSONObject = withContext(Dispatchers.IO) {
        commandObserver?.invoke(command)
        Socket().use { socket ->
            socket.connect(InetSocketAddress("127.0.0.1", 18765), 2_000)
            socket.soTimeout = 5_000
            val request = JSONObject().put("token", token).put("command", JSONObject(command.encode().toString(Charsets.UTF_8)))
            DataOutputStream(socket.getOutputStream()).writeUTF(request.toString())
            val response = JSONObject(DataInputStream(socket.getInputStream()).readUTF())
            require(response.getString("id") == command.id && response.getString("mode") == "crisis-watch-dev-link-v1")
            val state = PhoneWatchState.decode(response.getJSONObject("state"))
            require(state.peerID in setOf("local-dev-test-recipient", "") && !state.bleBroadcasting)
            response
        }
    }
    override suspend fun exchange(command: WatchCommand): SimulatedWatchReceipt {
        val response = receipt(command)
        return SimulatedWatchReceipt(response.getString("result"), PhoneWatchState.decode(response.getJSONObject("state")))
    }
}

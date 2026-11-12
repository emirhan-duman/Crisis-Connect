package com.auralis.crisisconnect.service

import android.app.Application
import android.bluetooth.BluetoothSocket
import io.mockk.every
import io.mockk.mockk
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.concurrent.ConcurrentHashMap
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class RfcommSocketReplacementTest {
    @Test fun staleBufferedFrameTerminatesReaderAndPreservesReplacementConnection() {
        val service = Robolectric.buildService(RfcommForegroundService::class.java).get()
        val field = RfcommForegroundService::class.java.getDeclaredField("connectedThreads").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        val connections = field.get(service) as ConcurrentHashMap<String, RfcommForegroundService.ConnectedThread>
        val replacementSocket = mockk<BluetoothSocket>(relaxed = true)
        every { replacementSocket.inputStream } returns ByteArrayInputStream(ByteArray(0))
        every { replacementSocket.outputStream } returns ByteArrayOutputStream()
        val replacement = service.ConnectedThread("pair", replacementSocket)
        val oldSocket = mockk<BluetoothSocket>(relaxed = true)
        every { oldSocket.outputStream } returns ByteArrayOutputStream()
        every { oldSocket.inputStream } returns object : ByteArrayInputStream("PEER_IDENTITY|forged|key\n".toByteArray()) {
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                connections["pair"] = replacement
                return super.read(buffer, offset, length)
            }
        }
        val old = service.ConnectedThread("pair", oldSocket).apply { isDaemon = true }
        connections["pair"] = old
        try {
            old.start(); old.join(5000)
            assertFalse("Stale buffered line must not spin", old.isAlive)
            assertSame("Old cleanup must retain the new socket", replacement, connections["pair"])
        } finally { old.cancel(); replacement.cancel() }
    }
}

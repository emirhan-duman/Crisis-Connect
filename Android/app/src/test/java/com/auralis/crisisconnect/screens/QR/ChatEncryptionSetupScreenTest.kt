package com.auralis.crisisconnect.screens.QR

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ChatEncryptionSetupScreenTest {

    private fun invokeParseHandshake(raw: String): Pair<String, String>? {
        val clazz = Class.forName("com.auralis.crisisconnect.screens.QR.QRScanKt")
        val method = clazz.getDeclaredMethod("parseHandshake", String::class.java)
        method.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        return method.invoke(null, raw) as Pair<String, String>?
    }

    @Test
    fun parseJsonFormat() {
        val raw = """dcs://{"v":1,"code":"SESSION","key":"KEY123","name":"Test"}"""
        val result = invokeParseHandshake(raw)
        assertEquals("SESSION" to "KEY123", result)
    }

    @Test
    fun parseEncodedJsonFormat() {
        val raw = "dcs://%7B%22v%22%3A1%2C%22code%22%3A%22ABC%22%2C%22key%22%3A%22XYZ%22%7D"
        val result = invokeParseHandshake(raw)
        assertEquals("ABC" to "XYZ", result)
    }

    @Test
    fun parsePipeFormats() {
        val prefixed = invokeParseHandshake("dcs|REMOTE|KEYVALUE")
        assertEquals("REMOTE" to "KEYVALUE", prefixed)

        val simple = invokeParseHandshake("REMOTE|KEYVALUE")
        assertEquals("REMOTE" to "KEYVALUE", simple)

        val invalid = invokeParseHandshake("onlyonepart")
        assertNull(invalid)
    }
}

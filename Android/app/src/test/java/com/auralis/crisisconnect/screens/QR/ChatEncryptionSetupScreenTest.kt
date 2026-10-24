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

    @Test fun legacyQrCannotBypassVersionChecksInEncryptionSetup() {
        assertNull(invokeParseHandshake("""{"code":"SESSION","key":"RETIRED","platform":"android"}"""))
        assertNull(invokeParseHandshake("""{"code":"SESSION","key":"RETIRED","platform":"android","offlineProtocolVersion":"2"}"""))
        assertNull(invokeParseHandshake("""{"code":"SESSION","key":"RETIRED","platform":"android","offlineProtocolVersion":2.5}"""))
    }

    @Test
    fun parseJsonFormat() {
        val raw = """dcs://{"v":1,"code":"SESSION","key":"KEY123","platform":"android","offlineProtocolVersion":2,"name":"Test"}"""
        val result = invokeParseHandshake(raw)
        assertEquals("SESSION" to "KEY123", result)
    }

    @Test
    fun parseEncodedJsonFormat() {
        val raw = "dcs://" + java.net.URLEncoder.encode("""{"v":1,"code":"ABC","key":"XYZ","platform":"ios","offlineProtocolVersion":2}""", "UTF-8")
        val result = invokeParseHandshake(raw)
        assertEquals("ABC" to "XYZ", result)
    }

    @Test
    fun parsePipeFormats() {
        val prefixed = invokeParseHandshake("dcs|REMOTE|KEYVALUE")
        assertNull(prefixed)

        val simple = invokeParseHandshake("REMOTE|KEYVALUE")
        assertNull(simple)

        val invalid = invokeParseHandshake("onlyonepart")
        assertNull(invalid)
    }
}

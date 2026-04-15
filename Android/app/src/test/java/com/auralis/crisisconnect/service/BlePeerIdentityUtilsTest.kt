package com.auralis.crisisconnect.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.Locale

@RunWith(RobolectricTestRunner::class)
class BlePeerIdentityUtilsTest {
    private val trLocale = Locale("tr", "TR")
    private val enLocale = Locale.US

    @Test
    fun `roleValue maps rescue roles to rescue and others to victim`() {
        assertEquals(BlePeerIdentityUtils.ROLE_RESCUE, BlePeerIdentityUtils.roleValue("admin"))
        assertEquals(BlePeerIdentityUtils.ROLE_RESCUE, BlePeerIdentityUtils.roleValue(" fieldteam "))
        assertEquals(BlePeerIdentityUtils.ROLE_VICTIM, BlePeerIdentityUtils.roleValue("victim"))
        assertEquals(BlePeerIdentityUtils.ROLE_VICTIM, BlePeerIdentityUtils.roleValue(null))
    }

    @Test
    fun `roleLabel returns localized default for unknown values`() {
        assertEquals(BlePeerIdentityUtils.RESCUER_LABEL, BlePeerIdentityUtils.roleLabel("rescue", trLocale))
        assertEquals(BlePeerIdentityUtils.VICTIM_LABEL, BlePeerIdentityUtils.roleLabel("victim", trLocale))
        assertEquals(BlePeerIdentityUtils.VICTIM_LABEL, BlePeerIdentityUtils.roleLabel("unknown", trLocale))
        assertEquals("Field Team", BlePeerIdentityUtils.roleLabel("rescue", enLocale))
        assertEquals("Victim", BlePeerIdentityUtils.roleLabel("victim", enLocale))
        assertEquals("Victim", BlePeerIdentityUtils.roleLabel("unknown", enLocale))
    }

    @Test
    fun `looksLikeBleIdentifier detects ble and mac signatures`() {
        assertTrue(BlePeerIdentityUtils.looksLikeBleIdentifier("ble:foo", null))
        assertTrue(BlePeerIdentityUtils.looksLikeBleIdentifier("AA:BB:CC:DD:EE:FF", null))
        assertTrue(BlePeerIdentityUtils.looksLikeBleIdentifier("peer 1a:2b", null))
        assertTrue(BlePeerIdentityUtils.looksLikeBleIdentifier("Saha Ekibi", null))
        assertTrue(BlePeerIdentityUtils.looksLikeBleIdentifier("Field Team", null))
        assertTrue(BlePeerIdentityUtils.looksLikeBleIdentifier("session-1", "session-1"))
        assertFalse(BlePeerIdentityUtils.looksLikeBleIdentifier("Ahmet Kaya", "session-1"))
    }

    @Test
    fun `sanitizeIncomingName clears identifier-like values`() {
        assertEquals("", BlePeerIdentityUtils.sanitizeIncomingName("  ", null))
        assertEquals("", BlePeerIdentityUtils.sanitizeIncomingName("AA:BB:CC:DD:EE:FF", null))
        assertEquals("Ahmet", BlePeerIdentityUtils.sanitizeIncomingName(" Ahmet ", "session-1"))
    }

    @Test
    fun `sanitizeIncomingName trims dangling bracket artifacts`() {
        assertEquals(
            "Nuri Duman",
            BlePeerIdentityUtils.sanitizeIncomingName("Nuri Duman ()", "session-1")
        )
        assertEquals(
            "Emirhan Duman",
            BlePeerIdentityUtils.sanitizeIncomingName(
                "Emirhan Duman))",
                "session-1"
            )
        )
    }

    @Test
    fun `parsePeerInfoPayload parses valid payload and normalizes fields`() {
        val raw = """
            {
              "kind":"peer_info",
              "name":"  Deniz  ",
              "role":"  RESCUE ",
              "batteryPct":55,
              "avatarB64":"abc123"
            }
        """.trimIndent()

        val parsed = BlePeerIdentityUtils.parsePeerInfoPayload(raw)

        assertNotNull(parsed)
        parsed ?: return
        assertEquals("Deniz", parsed.name)
        assertEquals("rescue", parsed.role)
        assertEquals(55, parsed.batteryPercent)
        assertEquals("abc123", parsed.avatarBase64)
    }

    @Test
    fun `parsePeerInfoPayload rejects non peer info kind`() {
        val parsed = BlePeerIdentityUtils.parsePeerInfoPayload("""{"kind":"other"}""")
        assertNull(parsed)
    }

    @Test
    fun `parsePeerInfoPayload drops invalid battery and oversized avatar`() {
        val longAvatar = "a".repeat(8_193)
        val parsed = BlePeerIdentityUtils.parsePeerInfoPayload(
            """{"kind":"peer_info","name":"x","role":"victim","batteryPct":101,"avatarB64":"$longAvatar"}"""
        )

        assertNotNull(parsed)
        parsed ?: return
        assertNull(parsed.batteryPercent)
        assertNull(parsed.avatarBase64)
    }

    @Test
    fun `parsePeerInfoPayload normalizes unknown role to victim`() {
        val parsed = BlePeerIdentityUtils.parsePeerInfoPayload(
            """{"kind":"peer_info","name":"x","role":"admin"}"""
        )

        assertNotNull(parsed)
        parsed ?: return
        assertEquals(BlePeerIdentityUtils.ROLE_VICTIM, parsed.role)
    }

    @Test
    fun `buildPeerInfoPayload writes only valid optional fields`() {
        val withOptional = BlePeerIdentityUtils.buildPeerInfoPayload(
            name = "Ece",
            role = "victim",
            batteryPercent = 0,
            avatarBase64 = "  aGVsbG8=  "
        )
        val withoutOptional = BlePeerIdentityUtils.buildPeerInfoPayload(
            name = "Ece",
            role = "victim",
            batteryPercent = -1,
            avatarBase64 = "   "
        )

        assertTrue(withOptional.contains("\"batteryPct\":0"))
        assertTrue(withOptional.contains("\"avatarB64\":\"aGVsbG8=\""))
        assertFalse(withoutOptional.contains("batteryPct"))
        assertFalse(withoutOptional.contains("avatarB64"))
    }

    @Test
    fun `buildLabeledName appends role label when missing`() {
        val labeled = BlePeerIdentityUtils.buildLabeledName(
            rawName = "Ahmet",
            roleValue = BlePeerIdentityUtils.ROLE_RESCUE,
            sessionCode = "session-1",
            locale = trLocale
        )
        assertEquals("Ahmet (Saha Ekibi)", labeled)
    }

    @Test
    fun `buildLabeledName uses english labels for english locale`() {
        val labeled = BlePeerIdentityUtils.buildLabeledName(
            rawName = "John",
            roleValue = BlePeerIdentityUtils.ROLE_RESCUE,
            sessionCode = "session-1",
            locale = enLocale
        )
        assertEquals("John (Field Team)", labeled)
    }

    @Test
    fun `buildLabeledName avoids duplicate role suffix`() {
        val labeled = BlePeerIdentityUtils.buildLabeledName(
            rawName = "Ahmet - Saha Ekibi",
            roleValue = BlePeerIdentityUtils.ROLE_RESCUE,
            sessionCode = null,
            locale = trLocale
        )
        assertEquals("Ahmet - (Saha Ekibi)", labeled)
    }

    @Test
    fun `buildLabeledName falls back to role when incoming name invalid`() {
        val labeled = BlePeerIdentityUtils.buildLabeledName(
            rawName = "AA:BB:CC:DD:EE:FF",
            roleValue = BlePeerIdentityUtils.ROLE_VICTIM,
            sessionCode = null,
            locale = trLocale
        )
        assertEquals(BlePeerIdentityUtils.VICTIM_LABEL, labeled)
    }

    @Test
    fun `buildUnverifiedPeerDisplayName ignores claimed rescue role labels`() {
        val displayName = BlePeerIdentityUtils.buildUnverifiedPeerDisplayName(
            rawName = "AFAD Team",
            sessionCode = "session-1",
            locale = enLocale
        )

        assertEquals("AFAD Team", displayName)
    }

    @Test
    fun `buildUnverifiedPeerDisplayName strips reserved role decorations`() {
        val suffixStripped = BlePeerIdentityUtils.buildUnverifiedPeerDisplayName(
            rawName = "AFAD Team (Field Team)",
            sessionCode = "session-1",
            locale = enLocale
        )
        val labelOnly = BlePeerIdentityUtils.buildUnverifiedPeerDisplayName(
            rawName = "Field Team",
            sessionCode = "session-1",
            locale = enLocale
        )

        assertEquals("AFAD Team", suffixStripped)
        assertEquals("Victim", labelOnly)
    }

    @Test
    fun `buildLabeledName removes dangling brackets before role suffix`() {
        val victim = BlePeerIdentityUtils.buildLabeledName(
            rawName = "Nuri Duman () (Kazazede)",
            roleValue = BlePeerIdentityUtils.ROLE_VICTIM,
            sessionCode = null,
            locale = trLocale
        )
        val rescuer = BlePeerIdentityUtils.buildLabeledName(
            rawName = "Emirhan Duman)) (Saha Ekibi)",
            roleValue = BlePeerIdentityUtils.ROLE_RESCUE,
            sessionCode = null,
            locale = trLocale
        )

        assertEquals("Nuri Duman (Kazazede)", victim)
        assertEquals("Emirhan Duman (Saha Ekibi)", rescuer)
    }

    @Test
    fun `buildDisplayNameForContact uses fallback role when preferred unavailable`() {
        val fallback = BlePeerIdentityUtils.buildDisplayNameForContact(
            preferredName = "session-2",
            roleValue = BlePeerIdentityUtils.ROLE_RESCUE,
            addressForFallback = "session-2",
            locale = trLocale
        )
        val preferred = BlePeerIdentityUtils.buildDisplayNameForContact(
            preferredName = "Ayse",
            roleValue = BlePeerIdentityUtils.ROLE_VICTIM,
            addressForFallback = null,
            locale = trLocale
        )

        assertEquals(BlePeerIdentityUtils.RESCUER_LABEL, fallback)
        assertEquals("Ayse (Kazazede)", preferred)
    }

    @Test
    fun `buildBleCounterpartyDisplayName shows victim label for rescue users`() {
        val displayName = BlePeerIdentityUtils.buildBleCounterpartyDisplayName(
            preferredName = "Ayse",
            addressForFallback = "AA:BB:CC:DD:EE:FF",
            isCurrentUserRescue = true,
            locale = trLocale
        )

        assertEquals("Ayse (Kazazede)", displayName)
    }

    @Test
    fun `buildBleCounterpartyDisplayName shows rescue label for victim users`() {
        val displayName = BlePeerIdentityUtils.buildBleCounterpartyDisplayName(
            preferredName = "AFAD 01",
            addressForFallback = "AA:BB:CC:DD:EE:FF",
            isCurrentUserRescue = false,
            locale = trLocale
        )

        assertEquals("AFAD 01 (Saha Ekibi)", displayName)
    }

    @Test
    fun `resolveStableBleContactName prefers stored username over live device label`() {
        val resolved = BlePeerIdentityUtils.resolveStableBleContactName(
            storedName = "Ayse Demir",
            peerName = "Pixel 9 Pro",
            sessionCode = "ble:AA:BB:CC:DD:EE:FF",
            addressForFallback = "AA:BB:CC:DD:EE:FF"
        )

        assertEquals("Ayse Demir", resolved)
    }

    @Test
    fun `resolveStableBleContactName falls back to live name when stored value is only role`() {
        val resolved = BlePeerIdentityUtils.resolveStableBleContactName(
            storedName = BlePeerIdentityUtils.VICTIM_LABEL,
            peerName = "Ayse Demir (Kazazede)",
            sessionCode = "ble:AA:BB:CC:DD:EE:FF",
            addressForFallback = "AA:BB:CC:DD:EE:FF"
        )

        assertEquals("Ayse Demir (Kazazede)", resolved)
    }

    @Test
    fun `resolveStableBleContactName normalizes malformed stored role suffix`() {
        val resolved = BlePeerIdentityUtils.resolveStableBleContactName(
            storedName = "Nuri Duman () (Kazazede)",
            peerName = null,
            sessionCode = "ble:AA:BB:CC:DD:EE:FF",
            addressForFallback = "AA:BB:CC:DD:EE:FF"
        )

        assertEquals("Nuri Duman (Kazazede)", resolved)
    }

    @Test
    fun `isRescuerDisplayName recognizes both localized labels`() {
        assertTrue(BlePeerIdentityUtils.isRescuerDisplayName("Ahmet (Saha Ekibi)"))
        assertTrue(BlePeerIdentityUtils.isRescuerDisplayName("John (Field Team)"))
        assertFalse(BlePeerIdentityUtils.isRescuerDisplayName("Ayse (Kazazede)"))
    }
}

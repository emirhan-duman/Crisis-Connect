package com.auralis.crisisconnect.messaging.call

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [TurnCredentials.parse] — the pure JSON parsing of the dashboard's
 * `/api/turn-credentials` response. Uses the real `org.json` from the `org.json:json` test
 * dependency, so no Robolectric is needed.
 */
class TurnCredentialsParseTest {

    @Test
    fun parsesCloudflareStunAndTurnEntries() {
        val json = """
            {"iceServers":[
              {"urls":["stun:stun.cloudflare.com:3478"]},
              {"urls":["turn:turn.cloudflare.com:3478?transport=udp","turns:turn.cloudflare.com:5349?transport=tcp"],
               "username":"user123","credential":"secret456"}
            ],"ttl":3600}
        """.trimIndent()

        val servers = TurnCredentials.parse(json)

        assertEquals(2, servers.size)
        assertEquals(listOf("stun:stun.cloudflare.com:3478"), servers[0].urls)
        assertNull(servers[0].username)
        assertNull(servers[0].credential)

        val turn = servers[1]
        assertEquals(2, turn.urls.size)
        assertTrue(turn.urls.any { it.startsWith("turn:") })
        assertTrue(turn.urls.any { it.startsWith("turns:") })
        assertEquals("user123", turn.username)
        assertEquals("secret456", turn.credential)
    }

    @Test
    fun toleratesUrlsAsPlainString() {
        val servers = TurnCredentials.parse(
            """{"iceServers":[{"urls":"turn:relay.example:3478","username":"u","credential":"c"}]}"""
        )
        assertEquals(1, servers.size)
        assertEquals(listOf("turn:relay.example:3478"), servers[0].urls)
        assertEquals("u", servers[0].username)
    }

    @Test
    fun toleratesSingleObjectInsteadOfArray() {
        val servers = TurnCredentials.parse(
            """{"iceServers":{"urls":["turn:relay.example:3478"],"username":"u","credential":"c"}}"""
        )
        assertEquals(1, servers.size)
        assertEquals("u", servers[0].username)
    }

    @Test
    fun returnsEmptyForMissingOrEmptyIceServers() {
        assertTrue(TurnCredentials.parse("""{"ttl":3600}""").isEmpty())
        assertTrue(TurnCredentials.parse("""{"iceServers":[]}""").isEmpty())
    }

    @Test
    fun skipsEntriesWithoutUsableUrls() {
        val servers = TurnCredentials.parse(
            """{"iceServers":[{"username":"u"},{"urls":[]},{"urls":["turn:ok.example:3478"]}]}"""
        )
        assertEquals(1, servers.size)
        assertEquals(listOf("turn:ok.example:3478"), servers[0].urls)
    }

    @Test
    fun returnsEmptyForMalformedJson() {
        assertTrue(TurnCredentials.parse("not json at all").isEmpty())
        assertTrue(TurnCredentials.parse("").isEmpty())
    }
}

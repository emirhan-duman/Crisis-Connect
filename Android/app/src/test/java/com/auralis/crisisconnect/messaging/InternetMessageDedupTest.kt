package com.auralis.crisisconnect.messaging

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** FS-5 dedup: the second (FCM/listener) copy of a message id must not re-enter the ratchet. */
class InternetMessageDedupTest {

    @Test
    fun firstClaimWinsSecondIsRejected() {
        val id = "msg-" + System.nanoTime()
        assertTrue("first delivery claims the id", InternetMessageDedup.claim(id))
        assertFalse("duplicate delivery is rejected", InternetMessageDedup.claim(id))
    }

    @Test
    fun releaseAllowsAGenuineRetry() {
        val id = "msg-" + System.nanoTime()
        assertTrue(InternetMessageDedup.claim(id))
        InternetMessageDedup.release(id)
        assertTrue("a released id can be claimed again", InternetMessageDedup.claim(id))
    }

    @Test
    fun blankIdIsNeverDeduped() {
        // A missing id must not collapse unrelated messages into one another.
        assertTrue(InternetMessageDedup.claim(""))
        assertTrue(InternetMessageDedup.claim(""))
    }
}

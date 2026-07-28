package com.auralis.crisisconnect.messaging

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class SafetyNumberTest {

    private val aKey = "AAAApublicKeyForAlice=="
    private val bKey = "BBBBpublicKeyForBob=="
    private val aUid = "uid-alice"
    private val bUid = "uid-bob"

    @Test
    fun bothPeers_computeTheSameNumber() {
        val fromAlice = SafetyNumber.compute(aKey, bKey, aUid, bUid)
        val fromBob = SafetyNumber.compute(bKey, aKey, bUid, aUid)
        assertEquals(fromAlice, fromBob)
    }

    @Test
    fun format_is12GroupsOf5Digits() {
        val number = SafetyNumber.compute(aKey, bKey, aUid, bUid)
        val groups = number.split(" ")
        assertEquals(12, groups.size)
        groups.forEach { assertEquals(5, it.length); assertEquals(true, it.all(Char::isDigit)) }
    }

    @Test
    fun differentKey_producesDifferentNumber() {
        val original = SafetyNumber.compute(aKey, bKey, aUid, bUid)
        val mitm = SafetyNumber.compute(aKey, "EVILswappedKey==", aUid, bUid)
        assertNotEquals(original, mitm)
    }
}

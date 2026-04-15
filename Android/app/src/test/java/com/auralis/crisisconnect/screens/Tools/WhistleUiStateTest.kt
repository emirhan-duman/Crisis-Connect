package com.auralis.crisisconnect.screens.Tools

import org.junit.Assert.assertEquals
import org.junit.Test

class WhistleUiStateTest {

    @Test
    fun defaultsToContinuousMode() {
        assertEquals(WhistleMode.CONTINUOUS, WhistleUiState().mode)
    }
}

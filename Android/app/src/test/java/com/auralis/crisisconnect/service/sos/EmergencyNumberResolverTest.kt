package com.auralis.crisisconnect.service.sos

import android.app.Application
import android.content.Context
import android.telephony.TelephonyManager
import io.mockk.*
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE, application = Application::class)
class EmergencyNumberResolverTest {
    @After fun cleanup() = unmockkAll()

    private fun environment(network: String, sim: String): Pair<Context, TelephonyManager> {
        val context = mockk<Context>()
        val telephony = mockk<TelephonyManager>()
        every { context.getSystemService(Context.TELEPHONY_SERVICE) } returns telephony
        every { telephony.networkCountryIso } returns network
        every { telephony.simCountryIso } returns sim
        return context to telephony
    }

    @Test fun roamedNetworkCountryWinsWithoutReadingProtectedEmergencyList() {
        val (context, telephony) = environment("us", "tr")
        assertEquals("911", EmergencyNumberResolver.resolve(context))
        verify(exactly = 0) { telephony.emergencyNumberList }
    }

    @Test fun simCountryIsUsedWhenNetworkCountryIsUnavailable() {
        val (context, _) = environment("", "tr")
        assertEquals(EmergencyNumberResolver.RegionalEmergencyNumber("112", "TR"),
            EmergencyNumberResolver.resolveWithRegion(context))
    }

    @Test fun absentTelephonyServiceStillUsesLocaleWithoutPhonePermission() {
        val context = mockk<Context>()
        every { context.getSystemService(Context.TELEPHONY_SERVICE) } returns null
        val original = java.util.Locale.getDefault()
        try {
            java.util.Locale.setDefault(java.util.Locale.US)
            assertEquals("911", EmergencyNumberResolver.resolve(context))
        } finally { java.util.Locale.setDefault(original) }
    }
}

package com.auralis.crisisconnect.analytics

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.auralis.crisisconnect.connectivity.DisasterTrafficController
import com.google.firebase.analytics.FirebaseAnalytics
import com.google.firebase.crashlytics.FirebaseCrashlytics
import com.google.firebase.perf.FirebasePerformance
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import io.mockk.verify
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class TelemetryTrafficRecoveryTest {
    @After
    fun tearDown() = unmockkAll()

    @Test
    fun recoveryRespectsRevokedConsentAndNeverEnablesAutomaticCrashUploads() {
        val saving = MutableStateFlow(false)
        val analytics = mockk<FirebaseAnalytics>(relaxed = true)
        val crashlytics = mockk<FirebaseCrashlytics>(relaxed = true)
        val performance = mockk<FirebasePerformance>(relaxed = true)
        mockkObject(DisasterTrafficController)
        every { DisasterTrafficController.saving } returns saving
        mockkStatic(FirebaseAnalytics::class)
        mockkStatic(FirebaseCrashlytics::class)
        mockkStatic(FirebasePerformance::class)
        every { FirebaseAnalytics.getInstance(any()) } returns analytics
        every { FirebaseCrashlytics.getInstance() } returns crashlytics
        every { FirebasePerformance.getInstance() } returns performance

        TelemetryConsent.apply(false)
        Analytics.init(ApplicationProvider.getApplicationContext<Application>())
        verify(timeout = 2_000) { analytics.setAnalyticsCollectionEnabled(false) }

        TelemetryConsent.apply(true)
        saving.value = true
        verify(timeout = 2_000, atLeast = 2) { analytics.setAnalyticsCollectionEnabled(false) }
        saving.value = false
        verify(timeout = 2_000, atLeast = 2) { analytics.setAnalyticsCollectionEnabled(true) }

        TelemetryConsent.apply(false)
        saving.value = true
        verify(timeout = 2_000, atLeast = 4) { analytics.setAnalyticsCollectionEnabled(false) }
        saving.value = false
        verify(timeout = 2_000, atLeast = 5) { analytics.setAnalyticsCollectionEnabled(false) }
        verify(exactly = 2) { analytics.setAnalyticsCollectionEnabled(true) }
        verify(exactly = 0) { crashlytics.setCrashlyticsCollectionEnabled(true) }
        SatelliteCrashReporter.recordFailure("test", "consent_revoked")
        verify(exactly = 0) { crashlytics.recordException(any(), any()) }
        verify(atLeast = 1) { crashlytics.deleteUnsentReports() }
    }
}

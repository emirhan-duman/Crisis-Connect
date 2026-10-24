package com.auralis.crisisconnect.messaging.call

import android.app.Application
import android.app.Notification
import androidx.core.app.NotificationManagerCompat
import io.mockk.*
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE, application = Application::class)
class CallNotificationPermissionTest {
    @After fun cleanup() = unmockkAll()

    private fun post(revoked: Boolean) {
        // Do not call onCreate: this test owns only the notification boundary, not live call engines.
        val service = Robolectric.buildService(InternetCallForegroundService::class.java).get()
        val manager = mockk<NotificationManagerCompat>()
        val notification = Notification()
        mockkStatic(NotificationManagerCompat::class)
        every { NotificationManagerCompat.from(service) } returns manager
        if (revoked) {
            every { manager.notify(7, notification) } throws SecurityException("permission revoked")
        } else {
            every { manager.notify(7, notification) } just Runs
        }
        val method = InternetCallForegroundService::class.java.getDeclaredMethod(
            "postCallNotification", Int::class.javaPrimitiveType, Notification::class.java)
        method.isAccessible = true
        method.invoke(service, 7, notification)
        verify(exactly = 1) { manager.notify(7, notification) }
    }

    @Test fun revokedPermissionDoesNotCrashTheCallStateCollector() = post(revoked = true)
    @Test fun frameworkAllowedCallNotificationIsStillPosted() = post(revoked = false)
}

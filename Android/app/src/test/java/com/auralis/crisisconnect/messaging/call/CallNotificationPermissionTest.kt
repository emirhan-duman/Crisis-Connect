package com.auralis.crisisconnect.messaging.call

import android.app.Application
import android.app.Notification
import android.app.PendingIntent
import android.content.Intent
import androidx.core.app.NotificationManagerCompat
import com.auralis.crisisconnect.MainActivity
import io.mockk.*
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
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

    @Test
    fun openAppNotificationTargetsMainActivity() {
        val service = Robolectric.buildService(InternetCallForegroundService::class.java).get()
        val factory = InternetCallForegroundService::class.java.getDeclaredMethod("openAppIntent")
        factory.isAccessible = true
        val action = factory.invoke(service) as PendingIntent
        val intent = shadowOf(action).savedIntent

        assertEquals(MainActivity::class.java.name, intent.component?.className)
        assertEquals(Intent.ACTION_MAIN, intent.action)
    }

    @Test fun revokedPermissionDoesNotCrashTheCallStateCollector() = post(revoked = true)
    @Test fun frameworkAllowedCallNotificationIsStillPosted() = post(revoked = false)
}

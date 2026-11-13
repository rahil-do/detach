package io.github.detach

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.provider.Settings as SystemSettings
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class StatusNotificationsTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val manager get() = context.getSystemService(NotificationManager::class.java)
    private val notifications get() = shadowOf(manager)

    @Before fun setup() {
        context.getSharedPreferences("pause", Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences("settings", Context.MODE_PRIVATE).edit().clear().commit()
        manager.cancelAll()
        setRunning(false)
    }

    @After fun cleanup() {
        setRunning(false)
    }

    private fun setRunning(running: Boolean) {
        DetachService::class.java.getDeclaredField("instance").apply { isAccessible = true }
            .set(null, if (running) Robolectric.buildService(DetachService::class.java).get() else null)
    }

    private fun pause(until: Long = System.currentTimeMillis() + 60_000L) {
        context.getSharedPreferences("pause", Context.MODE_PRIVATE).edit().putLong("until", until).commit()
    }

    private fun status(): Notification = notifications.getNotification(Pause.NOTIFICATION_ID).also { assertNotNull(it) }

    @Test fun `running status opens Detach with a separate authenticated payment pause action`() {
        setRunning(true)
        Settings(context).pauseNotification = true
        StatusNotifications.refresh(context)

        val notification = status()
        assertEquals("Protection is on", notification.extras.getString(Notification.EXTRA_TITLE))
        val open = shadowOf(notification.contentIntent)
        assertTrue(open.isActivity)
        assertTrue(open.isImmutable)
        assertEquals(MainActivity::class.java.name, open.savedIntent.component?.className)
        assertNull(open.savedIntent.action)
        assertEquals(1, notification.actions.size)
        val action = notification.actions.single()
        assertEquals("Pause for payments", action.title.toString())
        val pause = shadowOf(action.actionIntent)
        assertTrue(pause.isActivity)
        assertTrue(pause.isImmutable)
        assertEquals(PauseActivity::class.java.name, pause.savedIntent.component?.className)
        assertNotEquals(notification.contentIntent, action.actionIntent)
        val activity = context.packageManager.getActivityInfo(pause.savedIntent.component!!, 0)
        assertFalse(activity.exported)
    }

    @Test fun `status channel is low importance silent and does not badge the launcher`() {
        pause()
        StatusNotifications.refresh(context)

        val channel = manager.getNotificationChannel(status().channelId)
        assertEquals(NotificationManager.IMPORTANCE_LOW, channel.importance)
        assertNull(channel.sound)
        assertFalse(channel.shouldVibrate())
        assertFalse(channel.canShowBadge())
        assertEquals(Notification.VISIBILITY_PRIVATE, channel.lockscreenVisibility)
    }

    @Test fun `all status updates remain ongoing local private and noninterrupting`() {
        pause()
        StatusNotifications.refresh(context)

        val notification = status()
        assertTrue(notification.flags and Notification.FLAG_ONGOING_EVENT != 0)
        assertTrue(notification.flags and Notification.FLAG_ONLY_ALERT_ONCE != 0)
        assertTrue(notification.flags and Notification.FLAG_LOCAL_ONLY != 0)
        assertFalse(notification.flags and Notification.FLAG_AUTO_CANCEL != 0)
        assertFalse(notification.extras.getBoolean(Notification.EXTRA_SHOW_WHEN))
        assertEquals(Notification.VISIBILITY_PRIVATE, notification.visibility)
        assertEquals(Notification.CATEGORY_STATUS, notification.category)
        assertNotNull(notification.publicVersion)
        assertEquals("Detach", notification.publicVersion.extras.getString(Notification.EXTRA_TITLE))
        assertEquals("Open Detach to view protection status.", notification.publicVersion.extras.getString(Notification.EXTRA_TEXT))
        assertTrue(notification.publicVersion.actions.isNullOrEmpty())
    }

    @Test fun `a payment pause stays visible even when running status is disabled`() {
        Settings(context).pauseNotification = false
        pause()
        StatusNotifications.refresh(context)

        val notification = status()
        assertEquals("Protection paused", notification.extras.getString(Notification.EXTRA_TITLE))
        val text = notification.extras.getString(Notification.EXTRA_TEXT).orEmpty()
        assertTrue(text.contains("Reminder at ${Pause.untilText(context)}"))
        assertTrue(text.contains("automatic resume needs optional USB setup"))
        assertTrue(notification.actions.isNullOrEmpty())
        assertEquals(MainActivity::class.java.name, shadowOf(notification.contentIntent).savedIntent.component?.className)
        assertTrue(shadowOf(notification.contentIntent).isImmutable)
    }

    @Test fun `paused status explains the Google Pay restart and running status says to pause first`() {
        pause()
        StatusNotifications.refresh(context)
        val paused = status().extras.getCharSequence(Notification.EXTRA_BIG_TEXT).toString()
        assertTrue(paused.contains("restart your phone once"))
        assertTrue(paused.contains("stays paused through the restart"))
        context.getSharedPreferences("pause", Context.MODE_PRIVATE).edit().clear().commit()
        setRunning(true)
        Settings(context).pauseNotification = true
        StatusNotifications.refresh(context)
        val running = status().extras.getCharSequence(Notification.EXTRA_BIG_TEXT).toString()
        assertTrue(running.contains("Pause first"))
        assertTrue(running.contains("app you pay from"))
        assertEquals("On-device protection · you're in control", status().extras.getString(Notification.EXTRA_TEXT))
    }

    @Test fun `a pending payment pause wins over a service that is still disconnecting`() {
        setRunning(true)
        Settings(context).pauseNotification = true
        pause()
        StatusNotifications.refresh(context)

        assertEquals("Protection paused", status().extras.getString(Notification.EXTRA_TITLE))
        assertTrue(status().actions.isNullOrEmpty())
    }

    @Test fun `elapsed reminder updates the same status without resuming or scheduling work`() {
        val until = System.currentTimeMillis() - 1
        pause(until)
        SystemSettings.Secure.putString(context.contentResolver, SystemSettings.Secure.ENABLED_ACCESSIBILITY_SERVICES, "other/service")
        StatusNotifications.refresh(context)
        StatusNotifications.refresh(context)

        assertEquals("Protection is still paused", status().extras.getString(Notification.EXTRA_TITLE))
        assertTrue(status().extras.getString(Notification.EXTRA_TEXT).orEmpty().contains("open Detach"))
        assertTrue(status().actions.isNullOrEmpty())
        assertEquals(until, Pause.until(context))
        assertEquals("other/service", SystemSettings.Secure.getString(context.contentResolver, SystemSettings.Secure.ENABLED_ACCESSIBILITY_SERVICES))
        assertEquals(1, notifications.size())
        assertNull(shadowOf(context).nextStartedService)
        assertTrue(shadowOf(context.getSystemService(AlarmManager::class.java)).scheduledAlarms.isEmpty())
    }

    @Test fun `an implausible future reminder remains paused rather than promising automatic resume`() {
        pause(System.currentTimeMillis() + Pause.MAX_MS + 120_000L)
        StatusNotifications.refresh(context)

        assertEquals("Protection is still paused", status().extras.getString(Notification.EXTRA_TITLE))
        assertTrue(Pause.isPending(context))
    }

    @Test fun `disconnected protection clears status instead of falsely claiming it is on`() {
        setRunning(true)
        Settings(context).pauseNotification = true
        StatusNotifications.refresh(context)
        setRunning(false)
        StatusNotifications.refresh(context)

        assertNull(notifications.getNotification(Pause.NOTIFICATION_ID))
    }

    @Test fun `disabling optional running status clears only that status`() {
        setRunning(true)
        Settings(context).pauseNotification = true
        StatusNotifications.refresh(context)
        manager.notify(7003, Notification.Builder(context, "health").setSmallIcon(R.drawable.ic_notification).build())
        Settings(context).pauseNotification = false
        StatusNotifications.refresh(context)

        assertNull(notifications.getNotification(Pause.NOTIFICATION_ID))
        assertNotNull(notifications.getNotification(7003))
    }

    @Test fun `refresh removes the old reminder notification without creating duplicate statuses`() {
        manager.notify(7002, Notification.Builder(context, "status").setSmallIcon(R.drawable.ic_notification).build())
        pause()
        repeat(3) { StatusNotifications.refresh(context) }

        assertNull(notifications.getNotification(7002))
        assertEquals(1, notifications.size())
    }
}

package io.github.detach

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class HealthNotificationsTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val manager get() = context.getSystemService(NotificationManager::class.java)
    private val notifications get() = shadowOf(manager)
    private val preferences get() = context.getSharedPreferences("health", Context.MODE_PRIVATE)

    @Before fun setup() {
        preferences.edit().clear().commit()
        context.getSharedPreferences("pause", Context.MODE_PRIVATE).edit().clear().commit()
        manager.cancelAll()
        notifications.setNotificationsEnabled(true)
    }

    private fun almostFailing(): Health {
        preferences.edit().putString("day", LocalDate.now().toString())
            .putInt("ig_evals", 299).putInt("yt_evals", 299).commit()
        return Health(context)
    }

    private fun alert() = notifications.allNotifications.single()

    private fun allowFlush(health: Health) {
        Health::class.java.getDeclaredField("lastFlush").apply { isAccessible = true }.setLong(health, 0L)
    }

    @Test fun `health alert is actionable private local and does not expose app details`() {
        val health = almostFailing()
        health.igEval(false)

        val notification = alert()
        assertEquals("Protection needs a check", notification.extras.getString(Notification.EXTRA_TITLE))
        assertTrue(notification.extras.getString(Notification.EXTRA_TEXT).orEmpty().contains("Open diagnostics"))
        assertTrue(notification.flags and Notification.FLAG_ONLY_ALERT_ONCE != 0)
        assertTrue(notification.flags and Notification.FLAG_LOCAL_ONLY != 0)
        assertTrue(notification.flags and Notification.FLAG_AUTO_CANCEL != 0)
        assertEquals(Notification.VISIBILITY_PRIVATE, notification.visibility)
        assertEquals(Notification.CATEGORY_ERROR, notification.category)
        assertFalse(notification.extras.getBoolean(Notification.EXTRA_SHOW_WHEN))
        val open = shadowOf(notification.contentIntent)
        assertTrue(open.isActivity)
        assertTrue(open.isImmutable)
        assertEquals(MainActivity::class.java.name, open.savedIntent.component?.className)
        assertEquals(Health.ACTION_DIAGNOSTICS, open.savedIntent.action)
        assertEquals(notification.contentIntent, notification.actions.single().actionIntent)
        assertNotNull(notification.publicVersion)
        val publicText = notification.publicVersion.extras.toString()
        listOf("Instagram", "YouTube", Targets.INSTAGRAM, Targets.YOUTUBE).forEach { assertFalse(publicText.contains(it)) }
        assertEquals("Open Detach to review protection.", notification.publicVersion.extras.getString(Notification.EXTRA_TEXT))
    }

    @Test fun `health alerts require sufficient failed observations`() {
        val health = Health(context)
        repeat(299) { health.igEval(false) }
        allowFlush(health)
        health.ytEval(false)

        assertEquals(0, notifications.size())
    }

    @Test fun `recognised navigation prevents a false positive alert`() {
        val health = almostFailing()
        health.igEval(true)
        repeat(400) { health.igEval(false) }
        allowFlush(health)
        health.igEval(false)

        assertEquals(0, notifications.size())
    }

    @Test fun `failures in both apps collapse to one alert distinct from status`() {
        val health = almostFailing()
        health.igEval(false)
        allowFlush(health)
        health.ytEval(false)

        assertEquals(1, notifications.size())
        assertNotEquals(Pause.NOTIFICATION_ID, manager.activeNotifications.single().id)
        assertTrue(preferences.getBoolean("alert_active_ig", false))
        assertTrue(preferences.getBoolean("alert_active_yt", false))
    }

    @Test fun `recovery clears a stale alert immediately without waiting for a flush`() {
        val health = almostFailing()
        health.igEval(false)
        assertEquals(1, notifications.size())
        health.igEval(true)

        assertEquals(0, notifications.size())
        assertFalse(preferences.getBoolean("alert_active_ig", true))
    }

    @Test fun `one recovered app does not clear an outstanding failure in the other`() {
        val health = almostFailing()
        health.igEval(false)
        allowFlush(health)
        health.ytEval(false)
        health.igEval(true)
        assertEquals(1, notifications.size())
        health.ytEval(true)

        assertEquals(0, notifications.size())
    }

    @Test fun `a dismissed alert is not reposted by repeated failures or a new health instance`() {
        val health = almostFailing()
        health.igEval(false)
        manager.cancelAll()
        preferences.edit().putLong("alerted_ig", System.currentTimeMillis() - 13 * 3_600_000L).commit()
        allowFlush(health)
        health.igEval(false)
        Health(context).igEval(false)

        assertEquals(0, notifications.size())
    }

    @Test fun `recovering one app does not resurrect a dismissed alert about the other`() {
        val health = almostFailing()
        health.igEval(false)
        allowFlush(health)
        health.ytEval(false)
        manager.cancelAll()
        health.igEval(true)

        assertEquals(0, notifications.size())
        assertTrue(preferences.getBoolean("alert_active_yt", false))
    }

    @Test fun `disabled notifications do not consume the alert cooldown`() {
        val health = almostFailing()
        notifications.setNotificationsEnabled(false)
        health.igEval(false)

        assertEquals(0L, preferences.getLong("alerted_ig", 0))
        assertFalse(preferences.getBoolean("alert_active_ig", false))
        notifications.setNotificationsEnabled(true)
        allowFlush(health)
        health.igEval(false)
        assertEquals(1, notifications.size())
    }

    @Test fun `a recent alert respects the persisted cooldown after recovery`() {
        preferences.edit().putLong("alerted_ig", System.currentTimeMillis() - 3_600_000L).commit()
        val health = almostFailing()
        health.igEval(false)

        assertEquals(0, notifications.size())
        assertFalse(preferences.getBoolean("alert_active_ig", false))
    }

    @Test fun `clock changes do not suppress a new health incident forever`() {
        preferences.edit().putLong("alerted_ig", System.currentTimeMillis() + 24 * 3_600_000L).commit()
        val health = almostFailing()
        health.igEval(false)

        assertEquals(1, notifications.size())
        assertTrue(preferences.getLong("alerted_ig", 0) <= System.currentTimeMillis())
    }

    @Test fun `health recovery leaves the payment status untouched`() {
        val health = almostFailing()
        health.igEval(false)
        context.getSharedPreferences("pause", Context.MODE_PRIVATE).edit()
            .putLong("until", System.currentTimeMillis() + 60_000L).commit()
        StatusNotifications.refresh(context)
        health.igEval(true)

        assertEquals(1, notifications.size())
        assertNotNull(notifications.getNotification(Pause.NOTIFICATION_ID))
    }

    @Test fun `outdated health work cannot post a failure while payment protection is paused`() {
        val health = almostFailing()
        context.getSharedPreferences("pause", Context.MODE_PRIVATE).edit()
            .putLong("until", System.currentTimeMillis() - 1).commit()
        health.igEval(false)

        assertEquals(0, notifications.size())
        assertFalse(preferences.getBoolean("alert_active_ig", false))
    }

    @Test fun `legacy app specific alerts are removed when health is initialised`() {
        manager.notify("ig".hashCode(), Notification.Builder(context, "health").setSmallIcon(R.drawable.ic_notification).build())
        manager.notify("yt".hashCode(), Notification.Builder(context, "health").setSmallIcon(R.drawable.ic_notification).build())
        // Once per process: a fresh process starts with nothing cleared yet.
        Health::class.java.getDeclaredField("legacyCleared").apply { isAccessible = true }.setBoolean(null, false)
        Health(context)

        assertNull(notifications.getNotification("ig".hashCode()))
        assertNull(notifications.getNotification("yt".hashCode()))
    }

    @Test fun `building health again for each status render does not call into the notification service`() {
        Health(context)
        manager.notify("ig".hashCode(), Notification.Builder(context, "health").setSmallIcon(R.drawable.ic_notification).build())
        repeat(3) { Health(context).report() }
        assertNotNull(notifications.getNotification("ig".hashCode()))
    }
}

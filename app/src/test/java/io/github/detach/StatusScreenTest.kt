package io.github.detach

import android.Manifest
import android.app.Activity
import android.app.AppOpsManager
import android.app.KeyguardManager
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings as Sys
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration

/** Detach's screen follows protection and pause changes as they happen, without polling. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], shadows = [ForegroundServiceShadow::class])
class StatusScreenTest {
    private val ctx get() = RuntimeEnvironment.getApplication()
    private val me = "io.github.detach/io.github.detach.DetachService"
    private val others = "com.example.other/.Accessibility"
    private val notifications get() = shadowOf(ctx.getSystemService(NotificationManager::class.java))
    private var service: DetachService? = null

    @Before fun setup() {
        MonitorTestSupport.reset()
        MonitorTestSupport.simulateWallClock()
        ForegroundServiceShadow.reset()
        for (name in listOf("settings", "lock", "pause")) ctx.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear().commit()
        ctx.getSystemService(NotificationManager::class.java).cancelAll()
        Sys.Global.putFloat(ctx.contentResolver, Sys.Global.ANIMATOR_DURATION_SCALE, 0f)
        setList(others)
        Settings(ctx).pauseMinutes = 10
        shadowOf(ctx.getSystemService(PowerManager::class.java)).setIsInteractive(true)
        shadowOf(ctx.getSystemService(KeyguardManager::class.java)).setKeyguardLocked(false)
    }

    @After fun cleanup() {
        service?.onDestroy()
        service = null
        DetachService::class.java.getDeclaredField("instance").apply { isAccessible = true }.set(null, null)
        MonitorTestSupport.reset()
    }

    private fun setList(value: String) = Sys.Secure.putString(ctx.contentResolver, Sys.Secure.ENABLED_ACCESSIBILITY_SERVICES, value)
    private fun list() = Sys.Secure.getString(ctx.contentResolver, Sys.Secure.ENABLED_ACCESSIBILITY_SERVICES)
    private fun grant() = shadowOf(ctx).grantPermissions(Manifest.permission.WRITE_SECURE_SETTINGS)
    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun views(activity: Activity): List<View> {
        fun descend(view: View): List<View> = listOf(view) + if (view is ViewGroup) {
            (0 until view.childCount).flatMap { descend(view.getChildAt(it)) }
        } else emptyList()
        return descend(activity.window.decorView)
    }

    private fun texts(activity: Activity) = views(activity).filterIsInstance<TextView>().map { it.text.toString() }
    private fun statusBlock(activity: Activity) = views(activity).first { it.tag == "status" }
    private fun statusDetail(activity: Activity) = (statusBlock(activity) as ViewGroup).let { block ->
        views(activity).filterIsInstance<TextView>().filter { isInside(it, block) }.map { it.text.toString() }
    }.joinToString("\n")

    private fun isInside(view: View, parent: View): Boolean {
        var current: Any? = view
        while (current is View) {
            if (current === parent) return true
            current = current.parent
        }
        return false
    }

    private fun notificationText(): String = notifications.getNotification(Pause.NOTIFICATION_ID)
        ?.extras?.getCharSequence(Notification.EXTRA_TEXT).toString()

    /** What DetachService.onServiceConnected does when Android binds it. */
    private fun connectService() {
        val created = Robolectric.buildService(DetachService::class.java).create().get()
        service = created
        DetachService::class.java.getDeclaredMethod("onServiceConnected").apply { isAccessible = true }.invoke(created)
    }

    // ------------------------------------------------------------ 2: stale screen

    @Test fun `the open screen shows protection on as soon as the service reconnects`() {
        Pause.begin(ctx)
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        assertTrue(texts(activity).contains("Paused"))
        // Resume without the USB grant: the owner turns Detach on in Accessibility settings.
        views(activity).filterIsInstance<Button>().first { it.text == "Resume" }.performClick()
        setList("$others:$me")
        connectService()
        assertTrue(DetachService.isRunning)
        idle()
        assertTrue(texts(activity).toString(), texts(activity).contains("Protection is on"))
        assertFalse(texts(activity).contains("Paused"))
    }

    @Test fun `the open screen stops claiming protection when the service goes away`() {
        setList("$others:$me")
        connectService()
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        assertTrue(texts(activity).contains("Protection is on"))
        service!!.onUnbind(null)
        idle()
        assertFalse(texts(activity).contains("Protection is on"))
        assertTrue(texts(activity).contains("Waiting for Android"))
    }

    // ------------------------------------------------------------ 5: one truth for screen and notification

    @Test fun `when automatic authority is lost the open screen and the notification say the same`() {
        grant()
        Settings(ctx).resumeOnSocialForeground = true
        shadowOf(ctx.getSystemService(AppOpsManager::class.java)).setMode(
            AppOpsManager.OPSTR_GET_USAGE_STATS, ctx.applicationInfo.uid, ctx.packageName, AppOpsManager.MODE_ALLOWED,
        )
        Pause.begin(ctx)
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        val until = Pause.untilText(ctx)
        assertTrue(statusDetail(activity), statusDetail(activity).contains("when Instagram or YouTube opens"))
        assertTrue(statusDetail(activity), statusDetail(activity).contains("resumes by about $until"))
        assertEquals("Resumes by about $until", notificationText())
        // An update ends the pause's automatic authority while the screen is open.
        PauseReceiver().onReceive(ctx, Intent(Intent.ACTION_MY_PACKAGE_REPLACED))
        idle()
        val detail = statusDetail(activity)
        assertFalse(detail, detail.contains("Instagram"))
        assertFalse(detail, detail.contains("esumes by"))
        assertTrue(detail, detail.contains("Tap Resume"))
        assertEquals(StatusNotifications.TAP_TO_RESUME, notificationText())
    }

    @Test fun `screen and notification agree on whether the pause can resume by itself`() {
        data class Case(val label: String, val arrange: () -> Unit, val automatic: Boolean)
        val cases = listOf(
            Case("timer only", { grant() }, true),
            Case("after an update", { grant(); Pause.begin(ctx); PauseReceiver().onReceive(ctx, Intent(Intent.ACTION_MY_PACKAGE_REPLACED)) }, false),
            Case("no USB grant", { }, false),
        )
        for (case in cases) {
            setup()
            case.arrange()
            if (!Pause.isPending(ctx)) Pause.begin(ctx)
            val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
            val screen = statusDetail(activity)
            val note = notificationText()
            assertEquals("${case.label}: $screen", case.automatic, screen.contains("resumes by about"))
            assertEquals("${case.label}: $note", case.automatic, note.startsWith("Resumes by about"))
            // A time is shown only when Detach can keep it.
            assertEquals("${case.label}: $screen", case.automatic, screen.contains(Pause.untilText(ctx)))
            assertEquals("${case.label}: $note", case.automatic, note.contains(Pause.untilText(ctx)))
            shadowOf(ctx).denyPermissions(Manifest.permission.WRITE_SECURE_SETTINGS)
        }
    }

    // ------------------------------------------------------------ 6: no redraw loop

    @Test fun `waiting for Android neither redraws the screen nor re-posts the notification`() {
        Pause.begin(ctx)
        setList("$others:$me")
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        // Listed, not connected, and no request waiting: nothing will retry, both say so.
        assertTrue(statusDetail(activity), statusDetail(activity).contains("Didn't resume"))
        val block = statusBlock(activity)
        val posted = notifications.getNotification(Pause.NOTIFICATION_ID)
        assertEquals(StatusNotifications.RESUME_STALLED, notificationText())
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(90))
        assertSame("no re-render while nothing changed", block, statusBlock(activity))
        assertSame("no re-post while nothing changed", posted, notifications.getNotification(Pause.NOTIFICATION_ID))
    }

    @Test fun `a paused screen redraws once when the timer is due and then stays still`() {
        Settings(ctx).pauseMinutes = 1
        grant()
        Pause.begin(ctx)
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        val before = statusBlock(activity)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(30))
        assertSame(before, statusBlock(activity))
        // Locked: the due timer waits, so the pause is still pending when the screen updates.
        shadowOf(ctx.getSystemService(KeyguardManager::class.java)).setKeyguardLocked(true)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(32))
        val due = statusBlock(activity)
        assertTrue(before !== due)
        assertTrue(statusDetail(activity), statusDetail(activity).contains("Timer done"))
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMinutes(2))
        assertSame(due, statusBlock(activity))
    }

    // ------------------------------------------------------------ 1: the Resume tap in a stuck reconnect

    @Test fun `tapping Resume while Android never connected the listed service rebinds it`() {
        grant()
        Pause.begin(ctx)
        setList("$others:$me")
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        views(activity).filterIsInstance<Button>().first { it.text == "Resume" }.performClick()
        assertEquals(others, list())
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(Pause.REBIND_GAP_MS))
        assertEquals("$others:$me", list())
        // Android binds it now; the waiting request lets it start.
        connectService()
        idle()
        assertFalse(Pause.isPending(ctx))
        assertTrue(texts(activity).contains("Protection is on"))
    }
}

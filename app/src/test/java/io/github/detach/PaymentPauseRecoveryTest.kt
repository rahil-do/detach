package io.github.detach

import android.Manifest
import android.app.AlarmManager
import android.app.AppOpsManager
import android.app.KeyguardManager
import android.app.Notification
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings as Sys
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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
import org.robolectric.shadows.ShadowAlarmManager
import org.robolectric.shadows.ShadowSystemClock
import java.time.Duration

/**
 * Regressions seen on a Motorola phone (Android 17): a reconnect Android never completed, a
 * force-stop and the BOOT_COMPLETED that Android 15+ re-delivers afterwards, an inexact timer
 * minutes late, and a status notification re-posted every few seconds.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PaymentPauseRecoveryTest {
    private val ctx get() = RuntimeEnvironment.getApplication()
    private val others = "com.android.systemui.accessibility.accessibilitymenu/.AccessibilityMenuService:org.kde.kdeconnect_tp/org.kde.kdeconnect.KdeConnectAccessibilityService"
    private val me = "io.github.detach/io.github.detach.DetachService"
    private val alarms get() = shadowOf(ctx.getSystemService(AlarmManager::class.java))
    private val notifications get() = shadowOf(ctx.getSystemService(NotificationManager::class.java))
    private val stored get() = ctx.getSharedPreferences("pause", Context.MODE_PRIVATE)

    @Before fun setup() {
        MonitorTestSupport.reset()
        MonitorTestSupport.simulateWallClock()
        for (name in listOf("settings", "pause")) ctx.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear().commit()
        ctx.getSystemService(NotificationManager::class.java).cancelAll()
        setList(others)
        Sys.Secure.putInt(ctx.contentResolver, Sys.Secure.ACCESSIBILITY_ENABLED, 1)
        unlocked(true)
        setRunning(false)
    }

    private fun list() = Sys.Secure.getString(ctx.contentResolver, Sys.Secure.ENABLED_ACCESSIBILITY_SERVICES)
    private fun setList(value: String) = Sys.Secure.putString(ctx.contentResolver, Sys.Secure.ENABLED_ACCESSIBILITY_SERVICES, value)
    private fun grant() = shadowOf(ctx).grantPermissions(Manifest.permission.WRITE_SECURE_SETTINGS)
    private fun idle(millis: Long) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(millis))
    private fun accessibilityEnabled() = Sys.Secure.getInt(ctx.contentResolver, Sys.Secure.ACCESSIBILITY_ENABLED, 0)

    private fun unlocked(value: Boolean) {
        shadowOf(ctx.getSystemService(PowerManager::class.java)).setIsInteractive(value)
        shadowOf(ctx.getSystemService(KeyguardManager::class.java)).setKeyguardLocked(!value)
    }

    private fun setRunning(running: Boolean) {
        DetachService::class.java.getDeclaredField("instance").apply { isAccessible = true }
            .set(null, if (running) Robolectric.buildService(DetachService::class.java).get() else null)
    }

    private fun wantDetection() {
        grant()
        Settings(ctx).resumeOnSocialForeground = true
        shadowOf(ctx.getSystemService(AppOpsManager::class.java)).setMode(
            AppOpsManager.OPSTR_GET_USAGE_STATS, ctx.applicationInfo.uid, ctx.packageName, AppOpsManager.MODE_ALLOWED,
        )
    }

    private fun statusText(): String =
        notifications.getNotification(Pause.NOTIFICATION_ID)?.extras?.getCharSequence(Notification.EXTRA_TEXT).toString()

    private fun startedMonitorToken(): String? {
        var token: String? = null
        while (true) {
            val intent = shadowOf(ctx).nextStartedService ?: break
            if (intent.component == ComponentName(ctx, PauseForegroundMonitor::class.java)) token = intent.getStringExtra("pause_token")
        }
        return token
    }

    // ------------------------------------------------------------ 1: stuck reconnect

    @Test fun `a Resume tap while Detach is listed but never connected makes Android bind it again`() {
        grant()
        Pause.begin(ctx)
        // Android lost the bind: Detach is listed, nothing is connected, and listing it again
        // (what Resume used to do) changes nothing.
        setList("$others:$me")
        assertTrue(Pause.resume(ctx))
        assertEquals("only Detach's own entry is taken out", others, list())
        assertEquals(1, accessibilityEnabled())
        idle(Pause.REBIND_GAP_MS)
        assertEquals("the list is put back exactly as it was", "$others:$me", list())
        assertEquals(1, accessibilityEnabled())
        // The reconnect that follows still needs the single-use request.
        assertTrue(Pause.isPending(ctx))
        assertTrue(Pause.consumeResumeAuthorization(ctx))
        assertFalse(Pause.consumeResumeAuthorization(ctx))
    }

    @Test fun `a forced rebind keeps every other service and Detach's position`() {
        grant()
        Pause.begin(ctx)
        val first = "com.example.a/.A"
        val last = "com.example.b/com.example.b.B"
        setList("$first:$me:$last")
        assertTrue(Pause.resume(ctx))
        assertEquals("$first:$last", list())
        idle(Pause.REBIND_GAP_MS)
        assertEquals("$first:$me:$last", list())
    }

    @Test fun `a requested reconnect that never arrives is retried a bounded number of times`() {
        grant()
        Pause.begin(ctx)
        assertTrue(Pause.resume(ctx))
        assertEquals("$others:$me", list())
        repeat(Pause.MAX_REBINDS) { attempt ->
            idle(Pause.RECONNECT_CHECK_MS)
            assertEquals("rebind ${attempt + 1} takes Detach out", others, list())
            idle(Pause.REBIND_GAP_MS)
            assertEquals("and puts it back", "$others:$me", list())
        }
        idle(Pause.RECONNECT_CHECK_MS)
        idle(60_000L)
        assertEquals("no further retries without a new request", "$others:$me", list())
        assertTrue(Pause.consumeResumeAuthorization(ctx))
    }

    @Test fun `no rebind once the service connected, without a waiting request, or without the grant`() {
        grant()
        Pause.begin(ctx)
        assertTrue(Pause.resume(ctx))
        setRunning(true)
        idle(Pause.RECONNECT_CHECK_MS)
        assertEquals("$others:$me", list())
        setRunning(false)
        // The request was used (or expired): a rebind could not resume anything.
        assertTrue(Pause.consumeResumeAuthorization(ctx))
        assertFalse(Pause.recoverStuckReconnect(ctx))
        assertEquals("$others:$me", list())
        shadowOf(ctx).denyPermissions(Manifest.permission.WRITE_SECURE_SETTINGS)
        assertFalse(Pause.resume(ctx))
        assertFalse(Pause.recoverStuckReconnect(ctx))
        assertTrue(list().contains(me))
    }

    @Test fun `a due timer resumes a pause whose earlier reconnect Android lost`() {
        grant()
        Settings(ctx).pauseMinutes = 1
        Pause.begin(ctx)
        // Listed from an earlier request that expired; the service never connected.
        setList("$others:$me")
        ShadowSystemClock.advanceBy(Duration.ofMillis(61_000L))
        Pause.onTimer(ctx)
        assertEquals(others, list())
        idle(Pause.REBIND_GAP_MS)
        assertEquals("$others:$me", list())
        assertTrue(Pause.consumeResumeAuthorization(ctx))
    }

    @Test fun `a pause recorded during the rebind gap keeps Detach out of the list`() {
        grant()
        Pause.begin(ctx)
        setList("$others:$me")
        assertTrue(Pause.resume(ctx))
        assertEquals(others, list())
        // A bind already on its way connects, uses the request, finds a selected payment app in
        // front and pauses again (DetachService.onServiceConnected).
        assertTrue(Pause.consumeResumeAuthorization(ctx))
        assertTrue(Pause.record(ctx))
        idle(Pause.REBIND_GAP_MS)
        assertEquals("not put back while the payment app is in front", others, list())
        idle(Pause.RECONNECT_CHECK_MS * 3)
        assertEquals(others, list())
        assertTrue(Pause.isPending(ctx))
    }

    @Test fun `a reconnect that landed during the rebind gap is put back`() {
        grant()
        Pause.begin(ctx)
        setList("$others:$me")
        assertTrue(Pause.resume(ctx))
        assertTrue(Pause.consumeResumeAuthorization(ctx))
        Pause.onServiceStarted(ctx)
        idle(Pause.REBIND_GAP_MS)
        assertEquals("$others:$me", list())
    }

    @Test fun `a rebind whose process died in the gap is finished when Detach runs again`() {
        grant()
        Pause.begin(ctx)
        setList("$others:$me")
        assertTrue(Pause.resume(ctx))
        assertEquals(others, list())
        MonitorTestSupport.forceStop(ctx)
        idle(Pause.REBIND_GAP_MS)
        assertEquals("nothing of the dead process runs", others, list())
        Pause.resumeIfDue(ctx)
        assertTrue(list(), list().split(':').contains(me))
        assertEquals(1, accessibilityEnabled())
        assertTrue(Pause.consumeResumeAuthorization(ctx))
    }

    @Test fun `an interrupted rebind whose request expired is not finished`() {
        grant()
        Pause.begin(ctx)
        setList("$others:$me")
        assertTrue(Pause.resume(ctx))
        MonitorTestSupport.forceStop(ctx)
        ShadowSystemClock.advanceBy(Duration.ofMillis(Pause.RESUME_AUTHORIZATION_MS + 1_000L))
        Pause.resumeIfDue(ctx)
        assertEquals(others, list())
        assertTrue(Pause.isPending(ctx))
        assertFalse(stored.contains("rebind_elapsed"))
        // A manual request does not spend the timer: opening Detach set it again.
        assertEquals(PauseStatus.AUTOMATIC, PauseStatus.of(ctx))
        assertEquals(Pause.ACTION_REMIND, shadowOf(alarms.scheduledAlarms.single().operation).savedIntent.action)
    }

    @Test fun `the timer's broadcast stays open until a forced rebind has put Detach back`() {
        grant()
        Pause.begin(ctx)
        setList("$others:$me")
        assertTrue(Pause.resume(ctx))
        var finished = false
        Pause.afterRebind { finished = true }
        idle(Pause.REBIND_GAP_MS - 1)
        assertFalse(finished)
        idle(1_000L)
        assertTrue(finished)
        assertEquals("$others:$me", list())
        var immediately = false
        Pause.afterRebind { immediately = true }
        assertTrue(immediately)
    }

    @Test fun `the notification says reconnecting through a forced rebind, not what the gap looks like`() {
        grant()
        Pause.begin(ctx)
        setList("$others:$me")
        StatusNotifications.refresh(ctx)
        assertTrue(statusText(), statusText().startsWith(StatusNotifications.RESUME_STALLED))
        assertTrue(Pause.resume(ctx))
        assertEquals(others, list())
        assertEquals(PauseStatus.RECONNECTING, PauseStatus.of(ctx))
        assertTrue(statusText(), statusText().startsWith("Resume requested · waiting for Android"))
        idle(Pause.REBIND_GAP_MS)
        assertEquals(PauseStatus.RECONNECTING, PauseStatus.of(ctx))
        assertTrue(statusText(), statusText().startsWith("Resume requested · waiting for Android"))
    }

    @Test fun `a reconnect that never completes says to tap Resume once its request expires`() {
        grant()
        Pause.begin(ctx)
        assertTrue(Pause.resume(ctx))
        assertEquals("$others:$me", list())
        assertTrue(statusText(), statusText().contains("If protection doesn't come back, open Detach"))
        // Android never connects it; the bounded rebinds run, then the request expires.
        idle(Pause.RESUME_AUTHORIZATION_MS + 2_000L)
        assertEquals("$others:$me", list())
        assertEquals(PauseStatus.RESUME_STALLED, PauseStatus.of(ctx))
        assertEquals(StatusNotifications.RESUME_STALLED, statusText())
    }

    // ------------------------------------------------------------ 4: BOOT_COMPLETED after a force-stop

    @Test fun `BOOT_COMPLETED re-delivered after a force-stop keeps the pause's automatic resume and detection`() {
        wantDetection()
        setList("")
        Pause.begin(ctx)
        val token = Pause.automaticToken(ctx)
        assertNotNull(token)
        assertEquals(token, startedMonitorToken())
        MonitorTestSupport.forceStop(ctx)
        // Android 15+: PACKAGE_UNSTOPPED, then BOOT_COMPLETED, in the same boot.
        PauseReceiver().onReceive(ctx, Intent(Intent.ACTION_BOOT_COMPLETED))
        assertEquals(token, Pause.automaticToken(ctx))
        assertTrue(Pause.resumesAutomatically(ctx))
        assertEquals("app detection is re-attached to the same pause", token, startedMonitorToken())
        assertEquals(Pause.ACTION_REMIND, shadowOf(alarms.scheduledAlarms.single().operation).savedIntent.action)
        assertTrue(statusText(), statusText().startsWith("Automatic resume after about ${Pause.untilText(ctx)}"))
    }

    @Test fun `a same boot BOOT_COMPLETED keeps a waiting reconnect request`() {
        Pause.begin(ctx)
        assertFalse(Pause.resume(ctx))
        MonitorTestSupport.forceStop(ctx)
        PauseReceiver().onReceive(ctx, Intent(Intent.ACTION_BOOT_COMPLETED))
        assertTrue(Pause.consumeResumeAuthorization(ctx))
    }

    @Test fun `a real reboot still ends automatic resume and detection`() {
        wantDetection()
        setList("")
        Pause.begin(ctx)
        startedMonitorToken()
        MonitorTestSupport.forceStop(ctx)
        MonitorTestSupport.reboot(ctx)
        assertTrue(Pause.isPending(ctx))
        assertNull(Pause.automaticToken(ctx))
        assertNull(startedMonitorToken())
        assertTrue(statusText(), statusText().contains("open Detach and tap Resume"))
    }

    @Test fun `an elapsed clock behind the pause start counts as a reboot even with an unchanged boot count`() {
        grant()
        Pause.begin(ctx)
        stored.edit().putLong("automatic_elapsed", SystemClock.elapsedRealtime() + 60_000L).commit()
        assertTrue(Pause.rebootedSincePause(ctx))
        PauseReceiver().onReceive(ctx, Intent(Intent.ACTION_BOOT_COMPLETED))
        assertFalse(stored.contains("automatic_token"))
        assertTrue(Pause.isPending(ctx))
    }

    @Test fun `an update still ends automatic resume and both texts say to tap Resume`() {
        grant()
        Pause.begin(ctx)
        PauseReceiver().onReceive(ctx, Intent(Intent.ACTION_MY_PACKAGE_REPLACED))
        assertNull(Pause.automaticToken(ctx))
        assertEquals(PauseStatus.MANUAL, PauseStatus.of(ctx))
        assertTrue(statusText(), statusText().contains("open Detach and tap Resume"))
    }

    // ------------------------------------------------------------ 3: force-stop

    @Test fun `opening Detach after a force-stop restores timer status and detection for the same pause`() {
        wantDetection()
        setList("")
        Pause.begin(ctx)
        val token = Pause.automaticToken(ctx)
        startedMonitorToken()
        MonitorTestSupport.forceStop(ctx)
        assertTrue(alarms.scheduledAlarms.isEmpty())
        Robolectric.buildActivity(MainActivity::class.java).setup()
        assertEquals(1, alarms.scheduledAlarms.size)
        assertEquals(token, startedMonitorToken())
        assertTrue(statusText(), statusText().startsWith("Automatic resume after about"))
        assertEquals(token, Pause.automaticToken(ctx))
        assertFalse(Pause.consumeResumeAuthorization(ctx))
    }

    @Test fun `opening Detach after a force-stop past the deadline sets the lost timer again without resuming`() {
        lostTimerIsSetAgain(removePendingIntents = false)
    }

    @Test fun `the lost timer is also set again when the force-stop removed its PendingIntent (Android 15+)`() {
        lostTimerIsSetAgain(removePendingIntents = true)
    }

    private fun lostTimerIsSetAgain(removePendingIntents: Boolean) {
        grant()
        Settings(ctx).pauseMinutes = 1
        Pause.begin(ctx)
        val token = Pause.automaticToken(ctx)
        // Past the deadline and past Android's latest delivery of the alarm that was set.
        ShadowSystemClock.advanceBy(Duration.ofMillis(61_000L + Pause.ALARM_SLACK_MS))
        MonitorTestSupport.forceStop(ctx, removePendingIntents)
        Pause.resumeIfDue(ctx)
        val alarm = alarms.scheduledAlarms.single()
        val now = WallClock.now()
        assertTrue(alarm.triggerAtTime in now + 1..now + Pause.LOCKED_RETRY_MS)
        // Opening Detach authorizes nothing and keeps the pause's (unchanged) authority.
        assertEquals(others, list())
        assertFalse(Pause.consumeResumeAuthorization(ctx))
        assertEquals(token, Pause.automaticToken(ctx))
        // The restored timer then does what the lost one would have done.
        ShadowSystemClock.advanceBy(Duration.ofMillis(alarm.triggerAtTime - now))
        PauseReceiver().onReceive(ctx, Intent(Pause.ACTION_REMIND))
        assertTrue(Pause.consumeResumeAuthorization(ctx))
    }

    @Test fun `opening Detach again does not keep moving an undelivered due timer`() {
        grant()
        Settings(ctx).pauseMinutes = 1
        Pause.begin(ctx)
        ShadowSystemClock.advanceBy(Duration.ofMillis(61_000L + Pause.ALARM_SLACK_MS))
        MonitorTestSupport.forceStop(ctx)
        Pause.resumeIfDue(ctx)
        val first = alarms.scheduledAlarms.single().triggerAtTime
        ShadowSystemClock.advanceBy(Duration.ofMillis(5_000L))
        Pause.resumeIfDue(ctx)
        assertEquals(first, alarms.scheduledAlarms.single().triggerAtTime)
        // A second force-stop (Android 9 to 14 keep the PendingIntent) loses it again: set
        // again for the same time, not later.
        MonitorTestSupport.forceStop(ctx)
        assertTrue(alarms.scheduledAlarms.isEmpty())
        Pause.resumeIfDue(ctx)
        assertEquals(first, alarms.scheduledAlarms.single().triggerAtTime)
    }

    @Test fun `a due alarm still inside Android's delivery window is left alone`() {
        grant()
        Settings(ctx).pauseMinutes = 1
        Pause.begin(ctx)
        unlocked(false)
        ShadowSystemClock.advanceBy(Duration.ofMillis(Pause.until(ctx) - WallClock.now()))
        PauseReceiver().onReceive(ctx, Intent(Pause.ACTION_REMIND))
        val retry = alarms.scheduledAlarms.single().triggerAtTime
        // The retry is due, and Android may still deliver it for a while: opening Detach now
        // neither moves it nor fires it early.
        ShadowSystemClock.advanceBy(Duration.ofMillis(retry - WallClock.now() + 1_000L))
        unlocked(true)
        Pause.resumeIfDue(ctx)
        assertEquals(retry, alarms.scheduledAlarms.single().triggerAtTime)
        assertFalse(Pause.consumeResumeAuthorization(ctx))
    }

    // ------------------------------------------------------------ 7: inexact timer

    /** Android's latest delivery of an inexact alarm set now: 3/4 of its lead time late, none under 10 s. */
    private fun latestDelivery(trigger: Long, now: Long): Long {
        val futurity = trigger - now
        return trigger + if (futurity < Pause.PRECISE_LEAD_MS) 0L else futurity * 3 / 4
    }

    private fun stepsToDeadline(minutes: Int): Int {
        Settings(ctx).pauseMinutes = minutes
        Pause.begin(ctx)
        val until = Pause.until(ctx)
        var steps = 0
        while (true) {
            val alarm = alarms.scheduledAlarms.single()
            assertTrue(alarm.isAllowWhileIdle)
            assertEquals(AlarmManager.RTC_WAKEUP, alarm.type)
            val now = WallClock.now()
            val latest = latestDelivery(alarm.triggerAtTime, now)
            assertTrue("step $steps could fire ${latest - until} ms late", latest <= until)
            if (alarm.triggerAtTime == until) return steps
            steps++
            // Each step was checked for its latest delivery above; delivered on time, the next
            // step is as far away as it can be, so this counts the most steps there can be.
            ShadowSystemClock.advanceBy(Duration.ofMillis(alarm.triggerAtTime - now))
            PauseReceiver().onReceive(ctx, Intent(Pause.ACTION_REMIND))
            assertTrue(Pause.isPending(ctx))
        }
    }

    @Test fun `without exact alarms the timer is never set to fire after its deadline`() {
        val steps = stepsToDeadline(5)
        assertTrue("$steps steps", steps in 1..5)
    }

    @Test fun `even an hour long pause needs only a handful of steps`() {
        val steps = stepsToDeadline(60)
        assertTrue("$steps steps", steps in 1..8)
    }

    @Test fun `a phone locked after paying still steps to the deadline`() {
        // Started unlocked, locked before the first step: every step is still set to fire by
        // the deadline, so unlocking at the deadline does not wait for a late single alarm.
        Settings(ctx).pauseMinutes = 5
        Pause.begin(ctx)
        unlocked(false)
        val until = Pause.until(ctx)
        while (true) {
            val alarm = alarms.scheduledAlarms.single()
            assertTrue(alarm.isAllowWhileIdle)
            val now = WallClock.now()
            assertTrue(latestDelivery(alarm.triggerAtTime, now) <= until)
            ShadowSystemClock.advanceBy(Duration.ofMillis(alarm.triggerAtTime - now))
            PauseReceiver().onReceive(ctx, Intent(Pause.ACTION_REMIND))
            if (alarm.triggerAtTime == until) break
        }
        assertEquals(until, WallClock.now())
    }

    @Test fun `a locked due timer waits for the unlock with one unstepped retry`() {
        grant()
        Settings(ctx).pauseMinutes = 1
        Pause.begin(ctx)
        unlocked(false)
        ShadowSystemClock.advanceBy(Duration.ofMillis(Pause.until(ctx) - WallClock.now()))
        PauseReceiver().onReceive(ctx, Intent(Pause.ACTION_REMIND))
        assertTrue(Pause.isPending(ctx))
        assertEquals(WallClock.now() + Pause.LOCKED_RETRY_MS, alarms.scheduledAlarms.single().triggerAtTime)
    }

    @Test fun `exact delivery is used where Android allows it`() {
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
        Pause.begin(ctx)
        assertEquals(Pause.until(ctx), alarms.scheduledAlarms.single().triggerAtTime)
    }

    @Test fun `step times never end after the deadline whatever the lead`() {
        val now = 1_000_000L
        for (lead in listOf(0L, 1L, 9_999L, 10_000L, 17_499L, 17_500L, 30_000L, 60_000L, 299_999L, 3_600_000L, 7_200_000L)) {
            val trigger = Pause.nextAlarmAt(now, now + lead)
            assertTrue(trigger in now..now + lead)
            assertTrue("lead $lead", latestDelivery(trigger, now) <= now + lead)
            assertEquals(latestDelivery(trigger, now), Pause.latestInexactDelivery(now, trigger))
        }
    }

    // ------------------------------------------------------------ 6: notification churn

    @Test fun `an unchanged status is never cancelled or re-posted`() {
        grant()
        Pause.begin(ctx)
        val posted = notifications.getNotification(Pause.NOTIFICATION_ID)
        assertNotNull(posted)
        repeat(5) {
            StatusNotifications.refresh(ctx)
            Pause.resumeIfDue(ctx)
            Pause.remind(ctx)
        }
        assertSame(posted, notifications.getNotification(Pause.NOTIFICATION_ID))
        // A real change updates the same notification in place.
        PauseReceiver().onReceive(ctx, Intent(Intent.ACTION_MY_PACKAGE_REPLACED))
        val updated = notifications.getNotification(Pause.NOTIFICATION_ID)
        assertTrue(updated !== posted)
        assertTrue(statusText().contains("tap Resume"))
        assertTrue(updated.flags and Notification.FLAG_ONLY_ALERT_ONCE != 0)
        assertEquals(1, notifications.size())
    }

    @Test fun `a status removed from outside is posted again on the next refresh`() {
        Pause.begin(ctx)
        ctx.getSystemService(NotificationManager::class.java).cancel(Pause.NOTIFICATION_ID)
        StatusNotifications.refresh(ctx)
        assertNotNull(notifications.getNotification(Pause.NOTIFICATION_ID))
    }
}

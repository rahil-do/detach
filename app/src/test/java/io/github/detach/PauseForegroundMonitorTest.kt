package io.github.detach

import android.Manifest
import android.app.AppOpsManager
import android.app.AlarmManager
import android.app.KeyguardManager
import android.app.Notification
import android.app.NotificationManager
import android.app.Service
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.HandlerThread
import android.os.Looper
import android.os.PowerManager
import android.os.UserManager
import android.provider.Settings as SystemSettings
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ServiceController
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowUsageStatsManager
import org.robolectric.shadows.ShadowSystemClock
import java.time.Duration
import java.time.LocalDate

@Implements(UsageStatsManager::class)
class CountingUsageStatsManager : ShadowUsageStatsManager() {
    @Implementation
    override fun queryEvents(beginTime: Long, endTime: Long): UsageEvents {
        queryCount++
        queriedOn = Looper.myLooper()
        lastWindow = endTime - beginTime
        return super.queryEvents(beginTime, endTime)
    }

    companion object {
        var queryCount = 0
        var queriedOn: Looper? = null
        var lastWindow = 0L
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], shadows = [CountingUsageStatsManager::class])
@LooperMode(LooperMode.Mode.PAUSED)
class PauseForegroundMonitorTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private var controller: ServiceController<PauseForegroundMonitor>? = null
    private val service get() = controller!!.get()
    private val worker get() = (PauseForegroundMonitor::class.java.getDeclaredField("workerThread").apply { isAccessible = true }
        .get(service) as HandlerThread).looper
    private val bank = Targets.PAYMENT_DEFAULTS.first()

    @Before fun setup() {
        MonitorTestSupport.reset()
        // Wall time follows the simulated clock: these tests never depend on the machine's speed.
        MonitorTestSupport.simulateWallClock()
        CountingUsageStatsManager.queryCount = 0
        CountingUsageStatsManager.queriedOn = null
        shadowOf(context).grantPermissions(Manifest.permission.WRITE_SECURE_SETTINGS)
        SystemSettings.Secure.putString(context.contentResolver, SystemSettings.Secure.ENABLED_ACCESSIBILITY_SERVICES, "")
        Settings(context).resumeOnSocialForeground = true
        usageMode(AppOpsManager.MODE_ALLOWED)
        shadowOf(context.getSystemService(PowerManager::class.java)).setIsInteractive(true)
        shadowOf(context.getSystemService(KeyguardManager::class.java)).setKeyguardLocked(false)
        shadowOf(context.getSystemService(UserManager::class.java)).setUserUnlocked(true)
    }

    @After fun cleanup() {
        controller?.destroy()
        controller = null
        shadowOf(Looper.getMainLooper()).idle()
        MonitorTestSupport.reset()
    }

    private fun usageMode(mode: Int) = shadowOf(context.getSystemService(AppOpsManager::class.java)).setMode(
        AppOpsManager.OPSTR_GET_USAGE_STATS, context.applicationInfo.uid, context.packageName, mode,
    )

    private fun start(inFront: Boolean = false): Int {
        assertTrue(Pause.begin(context, bank, inFront))
        val intent = shadowOf(context).nextStartedService!!
        assertEquals(ComponentName(context, PauseForegroundMonitor::class.java), intent.component)
        controller = Robolectric.buildService(PauseForegroundMonitor::class.java).create()
        shadowOf(worker).pause()
        return service.onStartCommand(intent, 0, 1)
    }

    private fun event(packageName: String, eventType: Int, age: Long = 0L) {
        shadowOf(context.getSystemService(UsageStatsManager::class.java))
            .addEvent(packageName, WallClock.now() - age, eventType)
    }

    /** Moves the simulated clock (and with it wall time) forward in small steps. */
    private fun idleWorker(duration: Duration) {
        // Work already due runs at the current time, before the clock moves.
        shadowOf(worker).idle()
        var remaining = duration.toMillis()
        while (remaining > 0L) {
            val step = minOf(remaining, 250L)
            shadowOf(worker).idleFor(Duration.ofMillis(step))
            remaining -= step
        }
    }

    private fun state(): String = listOf("active", "stopping", "token", "paymentEvidenceAt", "paymentCheckedAt").joinToString { name ->
        "$name=${PauseForegroundMonitor::class.java.getDeclaredField(name).apply { isAccessible = true }.get(service)}"
    } + "; automatic=${Pause.automaticToken(context)}; queries=${CountingUsageStatsManager.queryCount}"

    @Test fun `monitor has no binder is sticky and uses an explicit private immutable notification tap`() {
        assertEquals(Service.START_STICKY, start())
        assertNull(service.onBind(Intent()))
        val notification = shadowOf(service).lastForegroundNotification
        // Shown at once: Android's 10 second deferral raced the stop and left a stale notification.
        assertEquals(Notification.FOREGROUND_SERVICE_IMMEDIATE,
            Notification::class.java.getDeclaredField("mFgsDeferBehavior").apply { isAccessible = true }.getInt(notification))
        assertTrue(notification.extras.getCharSequence(Notification.EXTRA_TEXT).toString().contains("Instagram or YouTube"))
        assertEquals(Notification.VISIBILITY_PRIVATE, notification.visibility)
        assertTrue(notification.flags and Notification.FLAG_ONGOING_EVENT != 0)
        assertTrue(notification.flags and Notification.FLAG_LOCAL_ONLY != 0)
        assertTrue(notification.publicVersion.flags and Notification.FLAG_LOCAL_ONLY != 0)
        val tap = shadowOf(notification.contentIntent)
        assertTrue(tap.isActivity)
        assertTrue(tap.isImmutable)
        assertEquals(ComponentName(context, MainActivity::class.java), tap.savedIntent.component)
        assertNull(tap.savedIntent.action)
        assertFalse(tap.savedIntent.hasExtra("pause_token"))
    }

    @Test fun `monitor health and pause status notifications coexist without replacing each other`() {
        context.getSharedPreferences("health", Context.MODE_PRIVATE).edit()
            .putString("day", LocalDate.now().toString()).putInt("ig_evals", 299).commit()
        Health(context).igEval(false)
        start()
        StatusNotifications.refresh(context)
        val manager = context.getSystemService(NotificationManager::class.java)
        assertEquals(setOf(Pause.NOTIFICATION_ID, 7003, PauseForegroundMonitor.NOTIFICATION_ID),
            manager.activeNotifications.map { it.id }.toSet())
        assertNotEquals(7003, PauseForegroundMonitor.NOTIFICATION_ID)
        assertNotEquals(Pause.NOTIFICATION_ID, PauseForegroundMonitor.NOTIFICATION_ID)
    }

    @Test fun `stale or forged service starts reach startForeground then stop without creating any authority`() {
        assertTrue(Pause.begin(context))
        controller = Robolectric.buildService(PauseForegroundMonitor::class.java).create()
        assertEquals(Service.START_NOT_STICKY, service.onStartCommand(Intent().putExtra("pause_token", "forged"), 0, 1))
        shadowOf(Looper.getMainLooper()).idle()
        // startForegroundService() starts must reach startForeground() before stopping, or
        // Android crashes the process; the notification is then removed with the service.
        assertEquals(PauseForegroundMonitor.NOTIFICATION_ID, shadowOf(service).lastForegroundNotificationId)
        assertEquals(ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE, service.foregroundServiceType)
        assertTrue(shadowOf(service).isForegroundStopped)
        assertTrue(shadowOf(service).notificationShouldRemoved)
        assertTrue(shadowOf(service).isStoppedBySelf)
        // Only up to the start it has seen, so a newer start on its way is not torn down.
        assertEquals(1, shadowOf(service).stopSelfId)
        assertFalse(PauseForegroundMonitor.isActive)
        assertEquals(Service.START_NOT_STICKY, service.onStartCommand(Intent(), 0, 2))
        assertFalse(Pause.consumeResumeAuthorization(context))
        assertEquals(0, CountingUsageStatsManager.queryCount)
        assertFalse(notificationListed())
    }

    private fun notificationListed() = context.getSystemService(NotificationManager::class.java)
        .activeNotifications.any { it.id == PauseForegroundMonitor.NOTIFICATION_ID }

    @Test fun `a stale start cannot stop monitoring of the current pause`() {
        start()
        shadowOf(worker).idle()
        assertEquals(Service.START_STICKY, service.onStartCommand(Intent().putExtra("pause_token", "older pause"), 0, 2))
        shadowOf(Looper.getMainLooper()).idle()
        assertFalse(shadowOf(service).isStoppedBySelf)
        assertTrue(PauseForegroundMonitor.isActive)
        idleWorker(Duration.ofMillis(PauseForegroundMonitor.POLL_MS))
        assertEquals(2, CountingUsageStatsManager.queryCount)
    }

    @Test fun `removing Detach from Recents keeps monitoring and Instagram still resumes`() {
        start()
        shadowOf(worker).idle()
        service.onTaskRemoved(Intent(context, MainActivity::class.java))
        shadowOf(worker).idle()
        shadowOf(Looper.getMainLooper()).idle()
        assertFalse(state(), shadowOf(service).isStoppedBySelf)
        assertTrue(PauseForegroundMonitor.isActive)
        assertTrue(notificationListed())
        eventSinceLastQuery(bank, UsageEvents.Event.MOVE_TO_BACKGROUND)
        eventSinceLastQuery(Targets.INSTAGRAM, UsageEvents.Event.MOVE_TO_FOREGROUND)
        idleWorker(Duration.ofMillis(PauseForegroundMonitor.POLL_MS))
        assertTrue(state(), Pause.consumeResumeAuthorization(context))
    }

    @Test fun `task removal stops a monitor that is no longer eligible`() {
        start()
        shadowOf(worker).idle()
        shadowOf(context).denyPermissions(Manifest.permission.WRITE_SECURE_SETTINGS)
        service.onTaskRemoved(null)
        shadowOf(worker).idle()
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(shadowOf(service).isStoppedBySelf)
        assertFalse(notificationListed())
    }

    /** Android restarting the sticky service after the process was killed: a fresh instance, null intent. */
    private fun restartAfterProcessDeath(): Int {
        controller!!.destroy()
        controller = Robolectric.buildService(PauseForegroundMonitor::class.java).create()
        shadowOf(worker).pause()
        return service.onStartCommand(null, 0, 1)
    }

    @Test fun `a sticky restart re-validates and resumes monitoring of the same pause`() {
        start(inFront = true)
        shadowOf(worker).idle()
        val token = Pause.automaticToken(context)
        val deadline = Pause.until(context)
        assertEquals(Service.START_STICKY, restartAfterProcessDeath())
        shadowOf(worker).idle()
        assertTrue(PauseForegroundMonitor.isActive)
        assertEquals(token, Pause.automaticToken(context))
        assertEquals(deadline, Pause.until(context))
        // The starting payment app is still assumed in front: a restart cannot know otherwise.
        assertTrue(state(), PauseForegroundMonitor.paymentIsKnown(context))
        eventSinceLastQuery(bank, UsageEvents.Event.MOVE_TO_BACKGROUND)
        eventSinceLastQuery(Targets.YOUTUBE, UsageEvents.Event.MOVE_TO_FOREGROUND)
        idleWorker(Duration.ofMillis(PauseForegroundMonitor.POLL_MS))
        assertTrue(state(), Pause.consumeResumeAuthorization(context))
    }

    @Test fun `a sticky restart stops cleanly once the pause opt in or grants are gone`() {
        val endings: List<() -> Unit> = listOf(
            { Pause.onServiceStarted(context) },
            { Settings(context).resumeOnSocialForeground = false },
            { usageMode(AppOpsManager.MODE_IGNORED) },
            { shadowOf(context).denyPermissions(Manifest.permission.WRITE_SECURE_SETTINGS) },
            { PauseReceiver().onReceive(context, Intent(Intent.ACTION_BOOT_COMPLETED)) },
        )
        for ((index, end) in endings.withIndex()) {
            setup()
            CountingUsageStatsManager.queryCount = 0
            start()
            end()
            assertEquals("ending $index", Service.START_NOT_STICKY, restartAfterProcessDeath())
            shadowOf(worker).idle()
            shadowOf(Looper.getMainLooper()).idle()
            assertTrue("ending $index", shadowOf(service).isStoppedBySelf)
            assertFalse("ending $index", PauseForegroundMonitor.isActive)
            assertFalse("ending $index", notificationListed())
            assertEquals("ending $index", 0, CountingUsageStatsManager.queryCount)
            assertFalse("ending $index", Pause.consumeResumeAuthorization(context))
            controller!!.destroy()
            controller = null
            Pause.onServiceStarted(context)
        }
    }

    @Test fun `a monitor notification left without a running monitor is removed`() {
        start()
        controller!!.destroy()
        controller = null
        // A stop racing the notification's deferred first post left it behind on a phone.
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.notify(PauseForegroundMonitor.NOTIFICATION_ID, Notification.Builder(context, "payment_pause_monitor")
            .setSmallIcon(R.drawable.ic_notification).setContentTitle("stale").build())
        assertTrue(notificationListed())
        StatusNotifications.refresh(context)
        assertFalse(notificationListed())
        // The paused status says so instead of implying that detection is still running.
        val text = shadowOf(manager).getNotification(Pause.NOTIFICATION_ID).extras.getCharSequence(Notification.EXTRA_TEXT).toString()
        assertTrue(text, text.startsWith(StatusNotifications.DETECTION_STOPPED))
    }

    @Test fun `a running monitor's notification is never swept and the status does not claim it stopped`() {
        start()
        StatusNotifications.refresh(context)
        assertTrue(notificationListed())
        val text = shadowOf(context.getSystemService(NotificationManager::class.java)).getNotification(Pause.NOTIFICATION_ID)
            .extras.getCharSequence(Notification.EXTRA_TEXT).toString()
        assertFalse(text, text.contains(StatusNotifications.DETECTION_STOPPED))
        assertTrue(text, text.startsWith("Automatic resume"))
    }

    /** The monitor is lost a minute into a pause from the bank's own window; Detach is then opened. */
    private fun reattachFromDetachAMinuteLater(bankLeftAgo: Long?) {
        start(inFront = true)
        controller!!.destroy()
        controller = null
        ShadowSystemClock.advanceBy(Duration.ofMillis(60_000L))
        bankLeftAgo?.let { event(bank, UsageEvents.Event.MOVE_TO_BACKGROUND, it) }
        val token = Pause.automaticToken(context)
        val started = Pause.startedAt(context)
        val until = Pause.until(context)
        Robolectric.buildActivity(MainActivity::class.java).setup()
        val intent = shadowOf(context).nextStartedService!!
        assertEquals(ComponentName(context, PauseForegroundMonitor::class.java), intent.component)
        assertEquals(token, intent.getStringExtra("pause_token"))
        assertEquals(token, Pause.automaticToken(context))
        assertEquals(started, Pause.startedAt(context))
        assertEquals(until, Pause.until(context))
        assertFalse(Pause.consumeResumeAuthorization(context))
        controller = Robolectric.buildService(PauseForegroundMonitor::class.java).create()
        shadowOf(worker).pause()
        assertEquals(Service.START_STICKY, service.onStartCommand(intent, 0, 1))
        shadowOf(worker).idle()
        idleWorker(Duration.ofMillis(PauseForegroundMonitor.POLL_MS))
    }

    @Test fun `opening Detach re-attaches detection to the same pause without new authority`() {
        // Full screen: the bank's own background event (from when Detach came to the front)
        // releases the pre-filled starting app, and Instagram then resumes.
        reattachFromDetachAMinuteLater(bankLeftAgo = 30_000L)
        assertFalse(state(), PauseForegroundMonitor.paymentIsKnown(context))
        eventSinceLastQuery(Targets.INSTAGRAM, UsageEvents.Event.MOVE_TO_FOREGROUND)
        idleWorker(Duration.ofMillis(PauseForegroundMonitor.POLL_MS))
        assertTrue(state(), Pause.consumeResumeAuthorization(context))
    }

    @Test fun `re-attaching from Detach next to the payment app in split screen keeps it held`() {
        // The bank stays resumed in the other pane (no background event, its foreground event is
        // long past); the owner swaps Detach's pane for Instagram. That must not resume.
        reattachFromDetachAMinuteLater(bankLeftAgo = null)
        assertTrue(state(), PauseForegroundMonitor.paymentIsKnown(context))
        eventSinceLastQuery(Targets.INSTAGRAM, UsageEvents.Event.MOVE_TO_FOREGROUND)
        repeat(3) { idleWorker(Duration.ofMillis(PauseForegroundMonitor.POLL_MS)) }
        assertFalse(state(), Pause.consumeResumeAuthorization(context))
        assertTrue(Pause.isPending(context))
        eventSinceLastQuery(bank, UsageEvents.Event.MOVE_TO_BACKGROUND)
        idleWorker(Duration.ofMillis(PauseForegroundMonitor.POLL_MS))
        assertTrue(state(), Pause.consumeResumeAuthorization(context))
    }

    private fun queryAfter() = PauseForegroundMonitor::class.java.getDeclaredField("queryAfter").apply { isAccessible = true }.getLong(service)

    @Test fun `an event Android stored only after the query that its timestamp falls in is still read`() {
        start(inFront = false)
        shadowOf(worker).idle()
        eventSinceLastQuery(bank, UsageEvents.Event.MOVE_TO_FOREGROUND)
        eventSinceLastQuery(Targets.INSTAGRAM, UsageEvents.Event.MOVE_TO_FOREGROUND)
        idleWorker(Duration.ofMillis(PauseForegroundMonitor.POLL_MS))
        assertFalse(state(), Pause.consumeResumeAuthorization(context))
        // The bank's background event is stamped before the last query ran but was stored after it.
        shadowOf(context.getSystemService(UsageStatsManager::class.java))
            .addEvent(bank, queryAfter() - 1L, UsageEvents.Event.MOVE_TO_BACKGROUND)
        idleWorker(Duration.ofMillis(PauseForegroundMonitor.POLL_MS))
        assertTrue(state(), Pause.consumeResumeAuthorization(context))
    }

    @Test fun `overlapping queries never apply an event twice`() {
        start(inFront = true)
        shadowOf(worker).idle()
        eventSinceLastQuery(Targets.INSTAGRAM, UsageEvents.Event.MOVE_TO_FOREGROUND)
        idleWorker(Duration.ofMillis(PauseForegroundMonitor.POLL_MS))
        assertFalse(state(), Pause.consumeResumeAuthorization(context))
        // The starting app's hold ends by timeout (never seen leaving): Instagram's foreground,
        // seen while it was held, no longer counts. Replaying it from the overlap must not revive it.
        val packages = PauseForegroundMonitor::class.java.getDeclaredField("packages").apply { isAccessible = true }.get(service) as ForegroundPackages
        @Suppress("UNCHECKED_CAST")
        val seeds = ForegroundPackages::class.java.getDeclaredField("seeds").apply { isAccessible = true }.get(packages) as MutableMap<String, Long>
        seeds.replaceAll { _, at -> at - ForegroundPackages.PAYMENT_HOLD_MS - 1_000L }
        repeat(2) { idleWorker(Duration.ofMillis(PauseForegroundMonitor.POLL_MS)) }
        assertNull(packages.socialForeground())
        assertFalse(state(), Pause.consumeResumeAuthorization(context))
        // A late-stored event older than one already applied for the same app never overrides it.
        eventSinceLastQuery(Targets.INSTAGRAM, UsageEvents.Event.MOVE_TO_BACKGROUND)
        idleWorker(Duration.ofMillis(PauseForegroundMonitor.POLL_MS))
        shadowOf(context.getSystemService(UsageStatsManager::class.java))
            .addEvent(Targets.INSTAGRAM, queryAfter() - 2_000L, UsageEvents.Event.MOVE_TO_FOREGROUND)
        idleWorker(Duration.ofMillis(PauseForegroundMonitor.POLL_MS))
        assertNull(packages.socialForeground())
        assertFalse(state(), Pause.consumeResumeAuthorization(context))
    }

    @Test fun `a failed enable ends the pause and stops a monitor that is not polling`() {
        start()
        shadowOf(worker).idle()
        shadowOf(context.getSystemService(PowerManager::class.java)).setIsInteractive(false)
        context.sendBroadcast(Intent(Intent.ACTION_SCREEN_OFF))
        shadowOf(Looper.getMainLooper()).idle()
        shadowOf(worker).idle()
        val failing = object : ContextWrapper(context) {
            override fun getApplicationContext(): Context = this
            override fun getContentResolver(): android.content.ContentResolver = throw SecurityException("secure settings write refused")
        }
        assertFalse(Pause.resume(failing))
        shadowOf(Looper.getMainLooper()).idle()
        assertFalse(state(), PauseForegroundMonitor.isActive)
        assertTrue(shadowOf(service).isStoppedBySelf)
        assertFalse(notificationListed())
    }

    @Test fun `re-attaching is refused without a pending pause opt in grants or authority and while resuming`() {
        fun refused(label: String) {
            assertFalse(label, PauseForegroundMonitor.reattach(context))
            assertNull(label, shadowOf(context).nextStartedService)
        }
        refused("no pause")
        start()
        controller!!.destroy()
        controller = null
        Settings(context).resumeOnSocialForeground = false
        refused("opted out")
        Settings(context).resumeOnSocialForeground = true
        usageMode(AppOpsManager.MODE_IGNORED)
        refused("no usage access")
        usageMode(AppOpsManager.MODE_ALLOWED)
        SystemSettings.Secure.putString(context.contentResolver, SystemSettings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            ComponentName(context, DetachService::class.java).flattenToString())
        refused("resume on its way")
        SystemSettings.Secure.putString(context.contentResolver, SystemSettings.Secure.ENABLED_ACCESSIBILITY_SERVICES, "")
        PauseReceiver().onReceive(context, Intent(Intent.ACTION_BOOT_COMPLETED))
        refused("after a restart")
        assertTrue(Pause.isPending(context))
    }

    @Test fun `a timer deferred behind the lock screen tries to bring detection back`() {
        Settings(context).pauseMinutes = 1
        start()
        controller!!.destroy()
        controller = null
        ShadowSystemClock.advanceBy(Duration.ofMillis(60_001L))
        shadowOf(context.getSystemService(PowerManager::class.java)).setIsInteractive(false)
        shadowOf(context.getSystemService(KeyguardManager::class.java)).setKeyguardLocked(true)
        val token = Pause.automaticToken(context)
        Pause.onTimer(context)
        val intent = shadowOf(context).nextStartedService!!
        assertEquals(token, intent.getStringExtra("pause_token"))
        assertTrue(Pause.isPending(context))
        assertFalse(Pause.consumeResumeAuthorization(context))
    }

    @Test fun `a refused re-attach from the timer leaves the timer fallback intact`() {
        Settings(context).pauseMinutes = 1
        start()
        controller!!.destroy()
        controller = null
        val denied = object : ContextWrapper(context) {
            override fun getApplicationContext(): Context = this
            override fun startForegroundService(service: Intent): ComponentName? =
                throw IllegalStateException("ForegroundServiceStartNotAllowedException")
        }
        assertFalse(PauseForegroundMonitor.reattach(denied))
        assertTrue(Pause.isPending(context))
        assertTrue(StatusNotifications.detectionStopped(context))
    }

    @Test fun `an unlock reported while the keyguard still reads locked is picked up without another broadcast`() {
        shadowOf(context.getSystemService(KeyguardManager::class.java)).setKeyguardLocked(true)
        start()
        idleWorker(Duration.ofSeconds(5))
        assertEquals(0, CountingUsageStatsManager.queryCount)
        context.sendBroadcast(Intent(Intent.ACTION_USER_PRESENT))
        shadowOf(Looper.getMainLooper()).idle()
        shadowOf(context.getSystemService(KeyguardManager::class.java)).setKeyguardLocked(false)
        idleWorker(Duration.ofMillis(PauseForegroundMonitor.UNLOCK_RECHECKS_MS.first()))
        assertEquals(1, CountingUsageStatsManager.queryCount)
    }

    @Test fun `overlapping unlock broadcasts never start a second poll chain`() {
        start()
        shadowOf(worker).idle()
        assertEquals(1, CountingUsageStatsManager.queryCount)
        // Two broadcasts reach the main thread before the worker runs either check: the second
        // removeCallbacks(poll) finds nothing, so both checks run and each schedules a poll.
        context.sendBroadcast(Intent(Intent.ACTION_SCREEN_ON))
        shadowOf(Looper.getMainLooper()).idle()
        context.sendBroadcast(Intent(Intent.ACTION_USER_PRESENT))
        shadowOf(Looper.getMainLooper()).idle()
        shadowOf(worker).idle()
        assertEquals(3, CountingUsageStatsManager.queryCount)
        repeat(3) { idleWorker(Duration.ofMillis(PauseForegroundMonitor.POLL_MS)) }
        // One query per poll interval, not two.
        assertEquals(6, CountingUsageStatsManager.queryCount)
    }

    @Test fun `unlock re-checks are bounded and never poll a locked phone`() {
        shadowOf(context.getSystemService(KeyguardManager::class.java)).setKeyguardLocked(true)
        start()
        context.sendBroadcast(Intent(Intent.ACTION_SCREEN_ON))
        shadowOf(Looper.getMainLooper()).idle()
        idleWorker(Duration.ofMillis(PauseForegroundMonitor.UNLOCK_RECHECKS_MS.last() + 1))
        assertEquals(0, CountingUsageStatsManager.queryCount)
        // Unlocked without any broadcast after the bounded re-checks: nothing runs until one arrives.
        shadowOf(context.getSystemService(KeyguardManager::class.java)).setKeyguardLocked(false)
        idleWorker(Duration.ofMinutes(1))
        assertEquals(0, CountingUsageStatsManager.queryCount)
        context.sendBroadcast(Intent(Intent.ACTION_USER_PRESENT))
        shadowOf(Looper.getMainLooper()).idle()
        shadowOf(worker).idle()
        assertEquals(1, CountingUsageStatsManager.queryCount)
    }

    @Test fun `a reconnect request that cannot be recorded leaves app detection running`() {
        start()
        shadowOf(worker).idle()
        assertFalse(Pause.resume(failingAuthorization()))
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(state(), PauseForegroundMonitor.isActive)
        assertFalse(shadowOf(service).isStoppedBySelf)
        assertTrue(Pause.isPending(context))
        assertFalse(Pause.consumeResumeAuthorization(context))
    }

    /** A context whose resume authorization commit fails; everything else is stored normally. */
    private fun failingAuthorization(): Context = object : ContextWrapper(context) {
            override fun getApplicationContext(): Context = this
            override fun getSharedPreferences(name: String, mode: Int): android.content.SharedPreferences {
                val original = super.getSharedPreferences(name, mode)
                if (name != "pause") return original
                return object : android.content.SharedPreferences by original {
                    override fun edit(): android.content.SharedPreferences.Editor {
                        val editor = original.edit()
                        var authorizing = false
                        return object : android.content.SharedPreferences.Editor by editor {
                            override fun putLong(key: String, value: Long): android.content.SharedPreferences.Editor = apply {
                                if (key == "resume_authorized_at") authorizing = true
                                editor.putLong(key, value)
                            }
                            override fun putInt(key: String, value: Int): android.content.SharedPreferences.Editor = apply { editor.putInt(key, value) }
                            override fun remove(key: String): android.content.SharedPreferences.Editor = apply { editor.remove(key) }
                            override fun commit(): Boolean = !authorizing && editor.commit()
                        }
                    }
                }
            }
        }

    @Test fun `an automatic resume whose authorization cannot be recorded stops app detection`() {
        start()
        shadowOf(worker).idle()
        shadowOf(context.getSystemService(PowerManager::class.java)).setIsInteractive(false)
        context.sendBroadcast(Intent(Intent.ACTION_SCREEN_OFF))
        shadowOf(Looper.getMainLooper()).idle()
        shadowOf(worker).idle()
        val token = Pause.automaticToken(context)!!
        Pause.onObservedForeground(failingAuthorization(), token, Targets.INSTAGRAM, paymentForeground = false)
        shadowOf(Looper.getMainLooper()).idle()
        // The attempt spent the automatic authority, so nothing is left for the monitor to do.
        assertNull(Pause.automaticToken(context))
        assertFalse(state(), PauseForegroundMonitor.isActive)
        assertTrue(shadowOf(service).isStoppedBySelf)
        assertFalse(Pause.consumeResumeAuthorization(context))
    }

    @Test fun `usage access granted by permission with the default app op counts`() {
        usageMode(AppOpsManager.MODE_DEFAULT)
        shadowOf(context).denyPermissions(Manifest.permission.PACKAGE_USAGE_STATS)
        assertFalse(PauseForegroundMonitor.hasUsageAccess(context))
        shadowOf(context).grantPermissions(Manifest.permission.PACKAGE_USAGE_STATS)
        assertTrue(PauseForegroundMonitor.hasUsageAccess(context))
        usageMode(AppOpsManager.MODE_IGNORED)
        assertFalse(PauseForegroundMonitor.hasUsageAccess(context))
    }

    @Test fun `closing the UPI app while Instagram is already open resumes as soon as the app is seen leaving`() {
        start(inFront = false)
        shadowOf(worker).idle()
        eventSinceLastQuery(bank, UsageEvents.Event.MOVE_TO_FOREGROUND)
        eventSinceLastQuery(Targets.INSTAGRAM, UsageEvents.Event.MOVE_TO_FOREGROUND)
        idleWorker(Duration.ofMillis(PauseForegroundMonitor.POLL_MS))
        assertFalse(state(), Pause.consumeResumeAuthorization(context))
        // A minute of polling with no new events: Instagram is still in front, the bank still held.
        repeat(30) { idleWorker(Duration.ofMillis(PauseForegroundMonitor.POLL_MS)) }
        assertFalse(state(), Pause.consumeResumeAuthorization(context))
        eventSinceLastQuery(bank, UsageEvents.Event.MOVE_TO_BACKGROUND)
        idleWorker(Duration.ofMillis(PauseForegroundMonitor.POLL_MS))
        assertTrue(state(), Pause.consumeResumeAuthorization(context))
    }

    @Test fun `Instagram stays known in front while a late payment hold clears`() {
        val packages = ForegroundPackages()
        packages.accept(bank, UsageEvents.Event.MOVE_TO_FOREGROUND, setOf(bank), 10_000L)
        packages.accept(Targets.INSTAGRAM, UsageEvents.Event.MOVE_TO_FOREGROUND, setOf(bank), 11_000L)
        assertTrue(packages.paymentForegroundAt(11_000L))
        packages.accept(bank, UsageEvents.Event.MOVE_TO_BACKGROUND, setOf(bank), 71_000L)
        assertFalse(packages.paymentForegroundAt(71_000L))
        assertEquals(Targets.INSTAGRAM, packages.socialForeground())
    }

    @Test fun `query work runs off UI thread at bounded two second intervals with a narrow window`() {
        start()
        shadowOf(worker).idle()
        assertEquals(1, CountingUsageStatsManager.queryCount)
        assertNotEquals(Looper.getMainLooper(), CountingUsageStatsManager.queriedOn)
        assertEquals(worker, CountingUsageStatsManager.queriedOn)
        assertTrue(CountingUsageStatsManager.lastWindow <= PauseForegroundMonitor.LOOKBACK_MS)
        idleWorker(Duration.ofMillis(PauseForegroundMonitor.POLL_MS - 1))
        assertEquals(1, CountingUsageStatsManager.queryCount)
        idleWorker(Duration.ofMillis(1))
        assertEquals(2, CountingUsageStatsManager.queryCount)
        // One poll plus the overlap that catches events Android stored after the last query.
        assertTrue(CountingUsageStatsManager.lastWindow <= PauseForegroundMonitor.POLL_MS + PauseForegroundMonitor.QUERY_OVERLAP_MS)
    }

    @Test fun `no queries are made screen off locked or before user storage unlock`() {
        shadowOf(context.getSystemService(PowerManager::class.java)).setIsInteractive(false)
        start()
        idleWorker(Duration.ofSeconds(20))
        assertEquals(0, CountingUsageStatsManager.queryCount)
        shadowOf(context.getSystemService(PowerManager::class.java)).setIsInteractive(true)
        shadowOf(context.getSystemService(KeyguardManager::class.java)).setKeyguardLocked(true)
        context.sendBroadcast(Intent(Intent.ACTION_SCREEN_ON))
        shadowOf(Looper.getMainLooper()).idle()
        idleWorker(Duration.ofSeconds(20))
        assertEquals(0, CountingUsageStatsManager.queryCount)
        shadowOf(context.getSystemService(KeyguardManager::class.java)).setKeyguardLocked(false)
        shadowOf(context.getSystemService(UserManager::class.java)).setUserUnlocked(false)
        context.sendBroadcast(Intent(Intent.ACTION_USER_PRESENT))
        shadowOf(Looper.getMainLooper()).idle()
        shadowOf(worker).idle()
        assertEquals(0, CountingUsageStatsManager.queryCount)
        shadowOf(context.getSystemService(UserManager::class.java)).setUserUnlocked(true)
        context.sendBroadcast(Intent(Intent.ACTION_USER_PRESENT))
        shadowOf(Looper.getMainLooper()).idle()
        shadowOf(worker).idle()
        assertEquals(1, CountingUsageStatsManager.queryCount)
    }

    @Test fun `screen off interrupts polling and explicit opt out stops the foreground service`() {
        start()
        shadowOf(worker).idle()
        val initialQueries = CountingUsageStatsManager.queryCount
        shadowOf(context.getSystemService(PowerManager::class.java)).setIsInteractive(false)
        context.sendBroadcast(Intent(Intent.ACTION_SCREEN_OFF))
        shadowOf(Looper.getMainLooper()).idle()
        idleWorker(Duration.ofMinutes(1))
        assertEquals(initialQueries, CountingUsageStatsManager.queryCount)
        Settings(context).resumeOnSocialForeground = false
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(state(), shadowOf(service).isStoppedBySelf)
        assertTrue(shadowOf(service).isForegroundStopped)
    }

    @Test fun `usage access revocation stops polling and never authorizes a reconnect`() {
        start()
        shadowOf(worker).idle()
        usageMode(AppOpsManager.MODE_IGNORED)
        shadowOf(Looper.getMainLooper()).idle()
        idleWorker(Duration.ofMillis(PauseForegroundMonitor.POLL_MS))
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(shadowOf(service).isStoppedBySelf)
        assertFalse(Pause.consumeResumeAuthorization(context))
        assertEquals(1, CountingUsageStatsManager.queryCount)
    }

    @Test fun `fresh social events resume but an unresolved recent payment has priority`() {
        start()
        event(Targets.INSTAGRAM, UsageEvents.Event.MOVE_TO_FOREGROUND)
        shadowOf(worker).idle()
        assertTrue(state(), PauseForegroundMonitor.paymentIsKnown(context))
        assertFalse(Pause.consumeResumeAuthorization(context))
        event(bank, UsageEvents.Event.MOVE_TO_BACKGROUND)
        PauseForegroundMonitor::class.java.getDeclaredField("queryAfter").apply { isAccessible = true }.setLong(service, 0L)
        idleWorker(Duration.ofMillis(PauseForegroundMonitor.POLL_MS))
        assertTrue(Pause.consumeResumeAuthorization(context))
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(shadowOf(service).isStoppedBySelf)
        assertFalse(PauseForegroundMonitor.paymentIsKnown(context))
    }

    @Test fun `usage events from before the pending pause never authorize a social reconnect`() {
        event(Targets.INSTAGRAM, UsageEvents.Event.MOVE_TO_FOREGROUND, 5_000L)
        start()
        shadowOf(worker).idle()
        assertFalse(Pause.consumeResumeAuthorization(context))
    }

    @Test fun `fresh bank defers only the alarm and monitor death preserves a timer fallback`() {
        Settings(context).pauseMinutes = 1
        start()
        ShadowSystemClock.advanceBy(Duration.ofMillis(60_001L))
        event(bank, UsageEvents.Event.MOVE_TO_FOREGROUND)
        shadowOf(worker).idle()
        assertTrue(state(), PauseForegroundMonitor.paymentIsKnown(context))
        val deadline = Pause.until(context)
        val token = Pause.automaticToken(context)
        Pause.onTimer(context)
        assertEquals(deadline, Pause.until(context))
        assertEquals(token, Pause.automaticToken(context))
        val alarm = shadowOf(context.getSystemService(AlarmManager::class.java)).scheduledAlarms.single()
        assertTrue(alarm.triggerAtTime > WallClock.now())
        assertFalse(Pause.consumeResumeAuthorization(context))
        controller!!.destroy()
        controller = null
        Pause.onTimer(context)
        assertTrue(Pause.consumeResumeAuthorization(context))
    }

    @Test fun `usage access revoke stops a screen off monitor without a polling wakeup`() {
        shadowOf(context.getSystemService(PowerManager::class.java)).setIsInteractive(false)
        start()
        usageMode(AppOpsManager.MODE_IGNORED)
        shadowOf(Looper.getMainLooper()).idle()
        shadowOf(worker).idle()
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(shadowOf(service).isStoppedBySelf)
        assertEquals(0, CountingUsageStatsManager.queryCount)
    }

    @Test fun `no feature permissions or pending pause means no monitor startup`() {
        assertFalse(PauseForegroundMonitor.startForPause(context))
        Settings(context).resumeOnSocialForeground = false
        assertTrue(Pause.begin(context))
        assertNull(shadowOf(context).nextStartedService)
        Settings(context).resumeOnSocialForeground = true
        usageMode(AppOpsManager.MODE_IGNORED)
        assertFalse(PauseForegroundMonitor.startForPause(context))
        usageMode(AppOpsManager.MODE_ALLOWED)
        shadowOf(context).denyPermissions(Manifest.permission.WRITE_SECURE_SETTINGS)
        assertFalse(PauseForegroundMonitor.startForPause(context))
    }

    @Test fun `foreground start rejection leaves the timer and manual fallback intact`() {
        assertTrue(Pause.begin(context))
        shadowOf(context).nextStartedService
        val denied = object : ContextWrapper(context) {
            override fun getApplicationContext(): Context = this
            override fun startForegroundService(service: Intent): ComponentName? = throw IllegalStateException("background start denied")
        }
        assertFalse(PauseForegroundMonitor.startForPause(denied))
        assertTrue(Pause.isPending(context))
        assertFalse(Pause.consumeResumeAuthorization(context))
    }

    @Test fun `package state ignores non foreground event types and never keeps stale bank hints indefinitely`() {
        val packages = ForegroundPackages()
        packages.accept(bank, UsageEvents.Event.MOVE_TO_FOREGROUND, setOf(bank), 10_000L)
        packages.accept(Targets.YOUTUBE, UsageEvents.Event.MOVE_TO_FOREGROUND, setOf(bank), 10_001L)
        packages.accept("com.example.unrelated", UsageEvents.Event.USER_INTERACTION, setOf(bank), 10_002L)
        assertEquals(Targets.YOUTUBE, packages.socialForeground())
        assertTrue(packages.paymentForegroundAt(10_002L))
        // Scrolling in YouTube produces no events: it stays in front until its own background event.
        assertTrue(packages.paymentForegroundAt(10_001L + ForegroundPackages.PAYMENT_HOLD_MS - 1L))
        assertEquals(Targets.YOUTUBE, packages.socialForeground())
        // A hold that ends by timeout was never seen leaving (split screen): YouTube's older
        // foreground no longer counts, only a fresh one does.
        assertFalse(packages.paymentForegroundAt(10_001L + ForegroundPackages.PAYMENT_HOLD_MS))
        assertNull(packages.socialForeground())
        packages.accept(Targets.YOUTUBE, UsageEvents.Event.MOVE_TO_FOREGROUND, setOf(bank), 10_002L + ForegroundPackages.PAYMENT_HOLD_MS)
        assertFalse(packages.paymentForegroundAt(10_002L + ForegroundPackages.PAYMENT_HOLD_MS))
        assertEquals(Targets.YOUTUBE, packages.socialForeground())
        packages.accept("com.example.unrelated", UsageEvents.Event.MOVE_TO_BACKGROUND, setOf(bank), 50_000L)
        assertEquals(Targets.YOUTUBE, packages.socialForeground())
        packages.accept(Targets.YOUTUBE, UsageEvents.Event.MOVE_TO_BACKGROUND, setOf(bank), 50_001L)
        assertNull(packages.socialForeground())
        packages.accept(Targets.INSTAGRAM, UsageEvents.Event.MOVE_TO_FOREGROUND, setOf(bank), 50_002L)
        packages.clear()
        assertNull(packages.socialForeground())
    }

    @Test fun `a user sitting on one payment screen keeps payment evidence until the app leaves`() {
        val checkout = "com.example.checkout"
        val selected = setOf(bank, checkout)
        val packages = ForegroundPackages()
        packages.accept(checkout, UsageEvents.Event.MOVE_TO_FOREGROUND, selected, 10_000L)
        packages.accept(checkout, UsageEvents.Event.MOVE_TO_BACKGROUND, selected, 20_000L)
        packages.accept(bank, UsageEvents.Event.MOVE_TO_FOREGROUND, selected, 20_001L)
        // A PIN screen held well past the old 30 second hint is still a payment in progress.
        assertTrue(packages.paymentForegroundAt(20_001L + ForegroundPackages.STATE_MAX_MS * 4))
        packages.accept(bank, UsageEvents.Event.MOVE_TO_BACKGROUND, selected, 200_000L)
        assertFalse(packages.paymentForegroundAt(200_001L))
    }

    @Test fun `continuous polling keeps a long payment screen known so the timer defers`() {
        Settings(context).pauseMinutes = 1
        start()
        event(bank, UsageEvents.Event.MOVE_TO_FOREGROUND)
        shadowOf(worker).idle()
        val packages = PauseForegroundMonitor::class.java.getDeclaredField("packages").apply { isAccessible = true }.get(service) as ForegroundPackages
        // Re-date the only foreground event 45 seconds into the past: no newer event arrives.
        val payments = ForegroundPackages::class.java.getDeclaredField("payments").apply { isAccessible = true }
            .get(packages) as MutableMap<String, Long>
        payments[bank] = WallClock.now() - 45_000L
        idleWorker(Duration.ofMillis(PauseForegroundMonitor.POLL_MS))
        assertTrue(state(), PauseForegroundMonitor.paymentIsKnown(context))
        // A minute of uninterrupted polling with no new event: the timer is now due.
        repeat(29) { idleWorker(Duration.ofMillis(PauseForegroundMonitor.POLL_MS)) }
        idleWorker(Duration.ofMillis(1))
        assertTrue(Pause.timerDue(context))
        assertTrue(state(), PauseForegroundMonitor.paymentIsKnown(context))
        Pause.onTimer(context)
        assertFalse(Pause.consumeResumeAuthorization(context))
        assertTrue(Pause.isPending(context))
    }

    @Test fun `a starting app seen only in an event is a short hint while a confirmed one is held`() {
        val packages = ForegroundPackages()
        packages.seed(bank, setOf(bank), 10_000L, inFront = false)
        assertTrue(packages.paymentForegroundAt(10_000L + ForegroundPackages.STATE_MAX_MS))
        assertFalse(packages.paymentForegroundAt(10_001L + ForegroundPackages.STATE_MAX_MS))
        packages.seed(bank, setOf(bank), 10_000L, inFront = true)
        assertTrue(packages.paymentForegroundAt(10_000L + ForegroundPackages.STATE_MAX_MS * 4))
        packages.accept(bank, UsageEvents.Event.MOVE_TO_BACKGROUND, setOf(bank), 20_000L)
        assertFalse(packages.paymentForegroundAt(20_001L))
        // A real foreground event upgrades a short hint to observed evidence.
        packages.seed(bank, setOf(bank), 30_000L, inFront = false)
        packages.accept(bank, UsageEvents.Event.MOVE_TO_FOREGROUND, setOf(bank), 31_000L)
        assertTrue(packages.paymentForegroundAt(31_000L + ForegroundPackages.STATE_MAX_MS * 4))
        packages.seed("com.example.unselected", setOf(bank), 40_000L, inFront = true)
        packages.accept(bank, UsageEvents.Event.MOVE_TO_BACKGROUND, setOf(bank), 40_001L)
        assertFalse(packages.paymentForegroundAt(40_002L))
    }

    /** An event newer than the last query, strictly before the next one (the wall clock is real here). */
    private fun eventSinceLastQuery(packageName: String, eventType: Int) {
        event(packageName, eventType, 0L)
        ShadowSystemClock.advanceBy(Duration.ofMillis(2))
    }

    private fun socialAfterSeedExpiry(): Boolean {
        shadowOf(worker).idle()
        // Re-date the starting app's evidence past the 30 second hint: no event for it arrives.
        val packages = PauseForegroundMonitor::class.java.getDeclaredField("packages").apply { isAccessible = true }.get(service) as ForegroundPackages
        for (name in listOf("payments", "hints")) {
            @Suppress("UNCHECKED_CAST")
            val held = ForegroundPackages::class.java.getDeclaredField(name).apply { isAccessible = true }.get(packages) as MutableMap<String, Long>
            held.replaceAll { _, at -> at - ForegroundPackages.STATE_MAX_MS - 1_000L }
        }
        eventSinceLastQuery(Targets.INSTAGRAM, UsageEvents.Event.MOVE_TO_FOREGROUND)
        idleWorker(Duration.ofMillis(PauseForegroundMonitor.POLL_MS))
        return Pause.consumeResumeAuthorization(context)
    }

    @Test fun `a pause from a selected app event alone does not block the social resume for minutes`() {
        start(inFront = false)
        assertTrue(state(), socialAfterSeedExpiry())
    }

    @Test fun `a pause from the selected app's own window holds the timer until the app leaves`() {
        start(inFront = true)
        shadowOf(worker).idle()
        val packages = PauseForegroundMonitor::class.java.getDeclaredField("packages").apply { isAccessible = true }.get(service) as ForegroundPackages
        @Suppress("UNCHECKED_CAST")
        val seeds = ForegroundPackages::class.java.getDeclaredField("seeds").apply { isAccessible = true }.get(packages) as MutableMap<String, Long>
        seeds.replaceAll { _, at -> at - ForegroundPackages.STATE_MAX_MS - 1_000L }
        idleWorker(Duration.ofMillis(PauseForegroundMonitor.POLL_MS))
        assertTrue(state(), PauseForegroundMonitor.paymentIsKnown(context))
        assertFalse(Pause.consumeResumeAuthorization(context))
    }

    @Test fun `a pause from the selected app's own window keeps it in front until it leaves`() {
        // Split screen: the payment app that started the pause stays resumed next to Instagram,
        // so Instagram opening (with no background event for the payment app) must not resume.
        start(inFront = true)
        assertFalse(state(), socialAfterSeedExpiry())
        assertTrue(state(), PauseForegroundMonitor.paymentIsKnown(context))
        repeat(30) { idleWorker(Duration.ofMillis(PauseForegroundMonitor.POLL_MS)) }
        assertFalse(state(), Pause.consumeResumeAuthorization(context))
        // Only its own background event releases it; Instagram, still in front, then resumes.
        eventSinceLastQuery(bank, UsageEvents.Event.MOVE_TO_BACKGROUND)
        idleWorker(Duration.ofMillis(PauseForegroundMonitor.POLL_MS))
        assertTrue(state(), Pause.consumeResumeAuthorization(context))
    }

    @Test fun `ForegroundPackages keeps a window-read seed through an Instagram foreground`() {
        val packages = ForegroundPackages()
        packages.seed(bank, setOf(bank), 10_000L, inFront = true)
        packages.accept(Targets.INSTAGRAM, UsageEvents.Event.MOVE_TO_FOREGROUND, setOf(bank), 11_000L)
        assertTrue(packages.paymentForegroundAt(11_000L))
        assertTrue(packages.paymentForegroundAt(10_000L + ForegroundPackages.PAYMENT_HOLD_MS))
        // The seed times out without a background event: Instagram's older foreground goes with it.
        assertFalse(packages.paymentForegroundAt(10_001L + ForegroundPackages.PAYMENT_HOLD_MS))
        assertNull(packages.socialForeground())
    }

    @Test fun `a sticky restart long after the starting app left releases its seed from that app's own events`() {
        start(inFront = true)
        shadowOf(worker).idle()
        // The owner leaves the payment app, then Android kills Detach; the restart comes well
        // after that background event, outside the usual ten second window.
        controller!!.destroy()
        controller = null
        ShadowSystemClock.advanceBy(Duration.ofMillis(60_000L))
        // 30 s after the pause began and 30 s before the restart: outside the usual window.
        event(bank, UsageEvents.Event.MOVE_TO_BACKGROUND, 30_000L)
        controller = Robolectric.buildService(PauseForegroundMonitor::class.java).create()
        shadowOf(worker).pause()
        assertEquals(Service.START_STICKY, service.onStartCommand(null, 0, 1))
        shadowOf(worker).idle()
        idleWorker(Duration.ofMillis(PauseForegroundMonitor.POLL_MS * 2))
        assertFalse(state(), PauseForegroundMonitor.paymentIsKnown(context))
        eventSinceLastQuery(Targets.INSTAGRAM, UsageEvents.Event.MOVE_TO_FOREGROUND)
        idleWorker(Duration.ofMillis(PauseForegroundMonitor.POLL_MS))
        assertTrue(state(), Pause.consumeResumeAuthorization(context))
    }

    @Test fun `a sticky restart keeps the seed when the starting app was never seen leaving`() {
        start(inFront = true)
        shadowOf(worker).idle()
        controller!!.destroy()
        controller = null
        ShadowSystemClock.advanceBy(Duration.ofMillis(60_000L))
        // An unrelated app's old background event is not replayed for anything but the seed.
        event("com.example.unrelated", UsageEvents.Event.MOVE_TO_BACKGROUND, 30_000L)
        controller = Robolectric.buildService(PauseForegroundMonitor::class.java).create()
        shadowOf(worker).pause()
        assertEquals(Service.START_STICKY, service.onStartCommand(null, 0, 1))
        shadowOf(worker).idle()
        eventSinceLastQuery(Targets.INSTAGRAM, UsageEvents.Event.MOVE_TO_FOREGROUND)
        idleWorker(Duration.ofMillis(PauseForegroundMonitor.POLL_MS * 2))
        assertTrue(state(), PauseForegroundMonitor.paymentIsKnown(context))
        assertFalse(state(), Pause.consumeResumeAuthorization(context))
    }

    @Test fun `a selected app observed in front still blocks Instagram until it is observed leaving`() {
        start(inFront = false)
        shadowOf(worker).idle()
        eventSinceLastQuery(bank, UsageEvents.Event.MOVE_TO_FOREGROUND)
        eventSinceLastQuery(Targets.INSTAGRAM, UsageEvents.Event.MOVE_TO_FOREGROUND)
        idleWorker(Duration.ofMillis(PauseForegroundMonitor.POLL_MS))
        assertFalse(state(), Pause.consumeResumeAuthorization(context))
        assertTrue(Pause.isPending(context))
    }

    private fun lockThenUnlockWithTimerDue() {
        Settings(context).pauseMinutes = 1
        start()
        shadowOf(worker).idle()
        shadowOf(context.getSystemService(PowerManager::class.java)).setIsInteractive(false)
        shadowOf(context.getSystemService(KeyguardManager::class.java)).setKeyguardLocked(true)
        context.sendBroadcast(Intent(Intent.ACTION_SCREEN_OFF))
        shadowOf(Looper.getMainLooper()).idle()
        idleWorker(Duration.ofMillis(60_001L))
        assertTrue(Pause.timerDue(context))
        shadowOf(context.getSystemService(PowerManager::class.java)).setIsInteractive(true)
        shadowOf(context.getSystemService(KeyguardManager::class.java)).setKeyguardLocked(false)
        context.sendBroadcast(Intent(Intent.ACTION_USER_PRESENT))
        shadowOf(Looper.getMainLooper()).idle()
        shadowOf(worker).idle()
    }

    @Test fun `after an unlock a due timer waits a poll for the payment app to come back to the front`() {
        lockThenUnlockWithTimerDue()
        // The first check after unlock can run before the payment app's resume is reported.
        assertFalse(state(), Pause.consumeResumeAuthorization(context))
        assertTrue(state(), PauseForegroundMonitor.paymentIsKnown(context))
        Pause.onTimer(context)
        assertFalse(Pause.consumeResumeAuthorization(context))
        eventSinceLastQuery(bank, UsageEvents.Event.MOVE_TO_FOREGROUND)
        idleWorker(Duration.ofMillis(PauseForegroundMonitor.POLL_MS))
        assertFalse(state(), Pause.consumeResumeAuthorization(context))
        assertTrue(state(), PauseForegroundMonitor.paymentIsKnown(context))
        assertTrue(Pause.isPending(context))
    }

    @Test fun `after an unlock with no payment app in front a due timer resumes after one poll`() {
        lockThenUnlockWithTimerDue()
        assertFalse(state(), Pause.consumeResumeAuthorization(context))
        idleWorker(Duration.ofMillis(PauseForegroundMonitor.POLL_MS))
        assertTrue(state(), Pause.consumeResumeAuthorization(context))
    }
}

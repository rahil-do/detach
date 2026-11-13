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

    private fun event(packageName: String, eventType: Int, age: Long = 1L) {
        shadowOf(context.getSystemService(UsageStatsManager::class.java))
            .addEvent(packageName, System.currentTimeMillis() - age, eventType)
    }

    private fun idleWorker(duration: Duration) {
        val stored = context.getSharedPreferences("pause", Context.MODE_PRIVATE)
        stored.edit().putLong("started", stored.getLong("started", 0L) - duration.toMillis())
            .putLong("until", stored.getLong("until", 0L) - duration.toMillis()).commit()
        shadowOf(worker).idleFor(duration)
    }

    private fun state(): String = listOf("active", "stopping", "token", "paymentEvidenceAt", "paymentCheckedAt").joinToString { name ->
        "$name=${PauseForegroundMonitor::class.java.getDeclaredField(name).apply { isAccessible = true }.get(service)}"
    } + "; automatic=${Pause.automaticToken(context)}; queries=${CountingUsageStatsManager.queryCount}"

    @Test fun `monitor has no binder is non sticky and uses an explicit private immutable notification tap`() {
        assertEquals(Service.START_NOT_STICKY, start())
        assertNull(service.onBind(Intent()))
        val notification = shadowOf(service).lastForegroundNotification
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

    @Test fun `null stale or forged service starts stop without creating any authority`() {
        assertTrue(Pause.begin(context))
        controller = Robolectric.buildService(PauseForegroundMonitor::class.java).create()
        assertEquals(Service.START_NOT_STICKY, service.onStartCommand(null, 0, 1))
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(shadowOf(service).isStoppedBySelf)
        assertEquals(Service.START_NOT_STICKY, service.onStartCommand(Intent().putExtra("pause_token", "forged"), 0, 2))
        assertFalse(Pause.consumeResumeAuthorization(context))
        assertEquals(0, CountingUsageStatsManager.queryCount)
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
        assertTrue(CountingUsageStatsManager.lastWindow <= PauseForegroundMonitor.POLL_MS)
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
        val stored = context.getSharedPreferences("pause", Context.MODE_PRIVATE)
        stored.edit().putLong("started", stored.getLong("started", 0L) - 60_001L)
            .putLong("until", stored.getLong("until", 0L) - 60_001L).commit()
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
        assertTrue(alarm.triggerAtTime > System.currentTimeMillis())
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
        assertEquals(Targets.YOUTUBE, packages.foregroundAt(10_002L))
        assertTrue(packages.paymentForegroundAt(10_002L))
        assertFalse(packages.paymentForegroundAt(10_001L + ForegroundPackages.PAYMENT_HOLD_MS))
        assertNull(packages.foregroundAt(10_002L + ForegroundPackages.STATE_MAX_MS))
        packages.clear()
        assertNull(packages.foregroundAt(10_002L))
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
        payments[bank] = System.currentTimeMillis() - 45_000L
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
        Thread.sleep(2)
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

    @Test fun `a pause from the selected app's own window keeps it in front until it leaves`() {
        start(inFront = true)
        assertFalse(state(), socialAfterSeedExpiry())
        assertTrue(state(), PauseForegroundMonitor.paymentIsKnown(context))
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

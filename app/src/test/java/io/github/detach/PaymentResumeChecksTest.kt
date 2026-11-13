package io.github.detach

import android.Manifest
import android.app.AlarmManager
import android.app.AppOpsManager
import android.app.KeyguardManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.PowerManager
import android.os.UserManager
import android.provider.Settings as SystemSettings
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
import org.robolectric.android.controller.ServiceController
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowSystemClock
import java.time.Duration

/**
 * Automatic resumes never land on a selected app Android last reported in front (one bounded
 * Usage Events query, also without app detection), a long payment screen under app detection
 * stays held until it really leaves, a locked due timer backs off, and a stale monitor stop
 * cannot take a newer start out of the foreground.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@LooperMode(LooperMode.Mode.PAUSED)
class PaymentResumeChecksTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val bank = Targets.PAYMENT_DEFAULTS.first()
    private var controller: ServiceController<PauseForegroundMonitor>? = null
    private val worker get() = (PauseForegroundMonitor::class.java.getDeclaredField("workerThread").apply { isAccessible = true }
        .get(controller!!.get()) as HandlerThread).looper

    @Before fun setup() {
        MonitorTestSupport.reset()
        // Wall time moves only with the simulated clock, in step with elapsed time.
        MonitorTestSupport.simulateWallClock()
        for (name in listOf("pause", "settings")) context.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear().commit()
        SystemSettings.Global.putInt(context.contentResolver, SystemSettings.Global.BOOT_COUNT, 1)
        SystemSettings.Secure.putString(context.contentResolver, SystemSettings.Secure.ENABLED_ACCESSIBILITY_SERVICES, "")
        shadowOf(context).grantPermissions(Manifest.permission.WRITE_SECURE_SETTINGS)
        Settings(context).pauseMinutes = 1
        phone(locked = false)
        shadowOf(context.getSystemService(UserManager::class.java)).setUserUnlocked(true)
    }

    @After fun cleanup() {
        controller?.destroy()
        controller = null
        MonitorTestSupport.killPausePostedWork()
        shadowOf(Looper.getMainLooper()).idle()
        phone(locked = false)
        MonitorTestSupport.reset()
    }

    private fun phone(locked: Boolean) {
        shadowOf(context.getSystemService(PowerManager::class.java)).setIsInteractive(!locked)
        shadowOf(context.getSystemService(KeyguardManager::class.java)).setKeyguardLocked(locked)
    }

    private fun usageAccess(mode: Int = AppOpsManager.MODE_ALLOWED) = shadowOf(context.getSystemService(AppOpsManager::class.java)).setMode(
        AppOpsManager.OPSTR_GET_USAGE_STATS, context.applicationInfo.uid, context.packageName, mode,
    )

    private fun event(packageName: String, eventType: Int, at: Long = WallClock.now()) {
        shadowOf(context.getSystemService(UsageStatsManager::class.java)).addEvent(packageName, at, eventType)
    }

    private fun advance(millis: Long) = ShadowSystemClock.advanceBy(Duration.ofMillis(millis))

    /** How far ahead of now the single outstanding timer is set. */
    private fun nextAlarmIn(): Long =
        shadowOf(context.getSystemService(AlarmManager::class.java)).scheduledAlarms.single().triggerAtTime - WallClock.now()

    private fun resumed() = Pause.serviceListed(context) && Pause.resumeAuthorized(context)

    // ---- timer without app detection --------------------------------------------------------

    @Test fun `the timer waits while the app that started the pause is still in front`() {
        usageAccess()
        assertTrue(Pause.begin(context, bank, paymentInFront = true))
        // App detection is off: no monitor, only the timer.
        assertNull(shadowOf(context).nextStartedService)
        advance(61_000L)
        assertTrue(Pause.timerDue(context))
        Pause.onTimer(context)
        assertFalse(Pause.serviceListed(context))
        assertNotNull(Pause.automaticToken(context))
        assertEquals(30_000L, nextAlarmIn())
        // Its own background event ends the wait; the next look resumes.
        event(bank, UsageEvents.Event.MOVE_TO_BACKGROUND)
        advance(30_000L)
        Pause.onTimer(context)
        assertTrue(resumed())
    }

    @Test fun `the timer waits for any selected app whose last event put it in front`() {
        usageAccess()
        assertTrue(Pause.begin(context))
        advance(20_000L)
        event(bank, UsageEvents.Event.MOVE_TO_FOREGROUND)
        // Another selected app that came and went does not hold anything.
        event("com.phonepe.app", UsageEvents.Event.MOVE_TO_FOREGROUND)
        event("com.phonepe.app", UsageEvents.Event.MOVE_TO_BACKGROUND)
        advance(41_000L)
        Pause.onTimer(context)
        assertFalse(Pause.serviceListed(context))
        event(bank, UsageEvents.Event.MOVE_TO_BACKGROUND)
        advance(30_000L)
        Pause.onTimer(context)
        assertTrue(resumed())
    }

    @Test fun `without Usage Access the timer still resumes when due, as documented`() {
        usageAccess(AppOpsManager.MODE_IGNORED)
        assertFalse(PauseForegroundMonitor.hasUsageAccess(context))
        assertTrue(Pause.begin(context, bank, paymentInFront = true))
        advance(61_000L)
        Pause.onTimer(context)
        assertTrue(resumed())
    }

    // ---- locked timer -----------------------------------------------------------------------

    @Test fun `a locked due timer backs off and starts over after an unlocked look`() {
        usageAccess()
        assertTrue(Pause.begin(context))
        advance(61_000L)
        phone(locked = true)
        val delays = (1..5).map {
            Pause.onTimer(context)
            nextAlarmIn()
        }
        assertEquals(listOf(30_000L, 60_000L, 120_000L, 300_000L, 300_000L), delays)
        assertFalse(Pause.serviceListed(context))
        // Unlocked with a selected app in front: it waits again, and the backoff starts over.
        phone(locked = false)
        event(bank, UsageEvents.Event.MOVE_TO_FOREGROUND)
        Pause.onTimer(context)
        assertFalse(Pause.serviceListed(context))
        assertEquals(30_000L, nextAlarmIn())
        phone(locked = true)
        Pause.onTimer(context)
        assertEquals(30_000L, nextAlarmIn())
    }

    @Test fun `the locked fallback does not resume over a selected app reported in front`() {
        usageAccess()
        assertTrue(Pause.begin(context))
        // Less than the 20 minute margin of automatic authority (pause + 60 minutes) is left.
        advance(60_000L + 40 * 60_000L + 1_000L)
        event(bank, UsageEvents.Event.MOVE_TO_FOREGROUND)
        phone(locked = true)
        Pause.onTimer(context)
        assertFalse(Pause.serviceListed(context))
        assertNotNull(Pause.automaticToken(context))
        event(bank, UsageEvents.Event.MOVE_TO_BACKGROUND)
        advance(nextAlarmIn())
        Pause.onTimer(context)
        assertTrue(resumed())
    }

    // ---- app detection ----------------------------------------------------------------------

    private fun startMonitor(inFront: Boolean) {
        Settings(context).resumeOnSocialForeground = true
        usageAccess()
        assertTrue(Pause.begin(context, bank, inFront))
        val intent = shadowOf(context).nextStartedService!!
        controller = Robolectric.buildService(PauseForegroundMonitor::class.java).create()
        shadowOf(worker).pause()
        controller!!.get().onStartCommand(intent, 0, 1)
        shadowOf(worker).idle()
    }

    private fun polls(count: Int) = repeat(count) { shadowOf(worker).idleFor(Duration.ofMillis(PauseForegroundMonitor.POLL_MS)) }

    @Test fun `app detection holds a payment screen past ten minutes until it really leaves`() {
        startMonitor(inFront = true)
        // Over ten minutes on one bank screen, observed continuously: no event at all.
        polls(310)
        assertTrue(PauseForegroundMonitor.paymentIsKnown(context))
        assertFalse(Pause.serviceListed(context))
        Pause.onTimer(context)
        assertFalse(Pause.serviceListed(context))
        // The bank's background event is stored too late for the regular overlapping queries.
        event(bank, UsageEvents.Event.MOVE_TO_BACKGROUND, WallClock.now() - 20_000L)
        polls(5)
        assertFalse(Pause.serviceListed(context))
        // The next re-read of the bank's own events finds it, and the due timer resumes.
        polls(300)
        assertTrue(resumed())
    }

    @Test fun `app detection does not resume for Instagram over a selected app reported in front`() {
        // The bank came to the front just before a pause started from its event alone.
        event(bank, UsageEvents.Event.MOVE_TO_FOREGROUND, WallClock.now() - 20_000L)
        Settings(context).pauseMinutes = 10
        startMonitor(inFront = false)
        // The 30 second hint for the starting app has run out.
        polls(20)
        event(Targets.INSTAGRAM, UsageEvents.Event.MOVE_TO_FOREGROUND)
        polls(3)
        assertFalse(Pause.serviceListed(context))
        event(bank, UsageEvents.Event.MOVE_TO_BACKGROUND)
        polls(8)
        assertTrue(resumed())
    }

    @Test fun `a stale monitor stop cannot take a newer valid start out of the foreground`() {
        startMonitor(inFront = true)
        val service = controller!!.get()
        // A worker-thread stop posts its foreground removal to the main thread...
        Handler(worker).post {
            PauseForegroundMonitor::class.java.getDeclaredMethod("stopMonitoring").apply { isAccessible = true }.invoke(service)
        }
        shadowOf(worker).idle()
        // ...and a valid new start for the current pause is delivered before it runs.
        assertTrue(Pause.begin(context, bank, true))
        service.onStartCommand(shadowOf(context).nextStartedService!!, 0, 2)
        shadowOf(Looper.getMainLooper()).idle()
        assertFalse(shadowOf(service).isForegroundStopped)
        assertFalse(shadowOf(service).isStoppedBySelf)
        assertTrue(PauseForegroundMonitor.isActive)
    }
}

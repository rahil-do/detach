package io.github.detach

import android.Manifest
import android.app.AlarmManager
import android.app.AppOpsManager
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.SharedPreferences
import android.os.SystemClock
import android.provider.Settings as SystemSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
import org.robolectric.shadows.ShadowSystemClock
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AutomaticPauseTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val stored get() = context.getSharedPreferences("pause", Context.MODE_PRIVATE)
    private val others = "com.example.other/.Accessibility"
    private val bank = Targets.PAYMENT_DEFAULTS.first()

    @Before fun resetStatics() = MonitorTestSupport.reset()

    private fun grant() {
        shadowOf(context).grantPermissions(Manifest.permission.WRITE_SECURE_SETTINGS)
        SystemSettings.Secure.putString(context.contentResolver, SystemSettings.Secure.ENABLED_ACCESSIBILITY_SERVICES, others)
    }

    private fun begin() {
        Settings(context).pauseMinutes = 1
        assertTrue(Pause.begin(context, bank))
    }

    private fun advance(millis: Long = 60_001L) {
        stored.edit().putLong("started", stored.getLong("started", 0L) - millis)
            .putLong("until", stored.getLong("until", 0L) - millis).commit()
        ShadowSystemClock.advanceBy(Duration.ofMillis(millis))
    }

    @Config(sdk = [28, 31, 34, 35])
    @Test fun `a valid same boot timer requests one authorized reconnect without a monitor`() {
        grant()
        begin()
        assertFalse(Pause.timerDue(context))
        assertNull(shadowOf(context).nextStartedService)
        advance()
        PauseReceiver().onReceive(context, Intent(Pause.ACTION_REMIND))
        val enabled = SystemSettings.Secure.getString(context.contentResolver, SystemSettings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
        assertTrue(enabled.startsWith("$others:"))
        assertTrue(Pause.isPending(context))
        assertNull(Pause.automaticToken(context))
        assertTrue(Pause.consumeResumeAuthorization(context))
        PauseReceiver().onReceive(context, Intent(Pause.ACTION_REMIND))
        assertFalse(Pause.consumeResumeAuthorization(context))
        Pause.onServiceStarted(context)
        assertFalse(Pause.isPending(context))
    }

    @Test fun `opening Detach after deadline preserves the undelivered inexact timer without granting authority`() {
        grant()
        begin()
        val manager = shadowOf(context.getSystemService(AlarmManager::class.java))
        val alarm = manager.scheduledAlarms.single()
        val token = Pause.automaticToken(context)
        advance()
        repeat(3) { Pause.resumeIfDue(context) }
        assertEquals(1, manager.scheduledAlarms.size)
        assertEquals(alarm.operation, manager.scheduledAlarms.single().operation)
        assertFalse(shadowOf(alarm.operation).isCanceled)
        assertEquals(token, Pause.automaticToken(context))
        assertFalse(Pause.consumeResumeAuthorization(context))
        Pause.onTimer(context)
        assertTrue(Pause.consumeResumeAuthorization(context))
    }

    @Test fun `a timer that fires after a small clock correction but before elapsed time is due keeps a follow-up alarm`() {
        grant()
        begin()
        val manager = shadowOf(context.getSystemService(AlarmManager::class.java))
        // The wall clock was corrected 2 s forward: the end time has passed, elapsed time has not.
        stored.edit().putLong("started", stored.getLong("started", 0L) - 60_001L)
            .putLong("until", stored.getLong("until", 0L) - 60_001L).commit()
        ShadowSystemClock.advanceBy(Duration.ofMillis(58_000L))
        assertNotNull(Pause.automaticToken(context))
        assertFalse(Pause.timerDue(context))
        Pause.onTimer(context)
        assertFalse(Pause.consumeResumeAuthorization(context))
        // Without a follow-up nothing would ever resume automatically.
        val retry = manager.scheduledAlarms.single()
        assertTrue(retry.triggerAtTime > System.currentTimeMillis())
        assertTrue(retry.triggerAtTime <= System.currentTimeMillis() + 3_000L)
        ShadowSystemClock.advanceBy(Duration.ofMillis(2_100L))
        stored.edit().putLong("started", stored.getLong("started", 0L) - 2_100L)
            .putLong("until", stored.getLong("until", 0L) - 2_100L).commit()
        assertTrue(Pause.timerDue(context))
        Pause.onTimer(context)
        assertTrue(Pause.consumeResumeAuthorization(context))
    }

    @Test fun `timer cannot authorize before either original deadline`() {
        grant()
        begin()
        Pause.onTimer(context)
        assertFalse(Pause.consumeResumeAuthorization(context))
        assertNotNull(Pause.automaticToken(context))
        assertEquals(others, SystemSettings.Secure.getString(context.contentResolver, SystemSettings.Secure.ENABLED_ACCESSIBILITY_SERVICES))
    }

    @Test fun `timer without secure settings grant remains paused and never authorizes reconnect`() {
        begin()
        advance()
        Pause.onTimer(context)
        assertTrue(Pause.isPending(context))
        assertFalse(Pause.consumeResumeAuthorization(context))
        assertNull(Pause.automaticToken(context))
        grant()
        Pause.onTimer(context)
        assertFalse(Pause.consumeResumeAuthorization(context))
    }

    @Test fun `app opens boot and update cannot renew timer authority`() {
        grant()
        begin()
        advance()
        Pause.resumeIfDue(context)
        assertFalse(Pause.consumeResumeAuthorization(context))
        MonitorTestSupport.reboot(context)
        assertNull(Pause.automaticToken(context))
        Pause.onTimer(context)
        Pause.resumeIfDue(context)
        PauseReceiver().onReceive(context, Intent(Intent.ACTION_MY_PACKAGE_REPLACED))
        assertFalse(Pause.consumeResumeAuthorization(context))
        assertEquals(others, SystemSettings.Secure.getString(context.contentResolver, SystemSettings.Secure.ENABLED_ACCESSIBILITY_SERVICES))
    }

    @Test fun `clock edits boot mismatch and stale automatic windows fail closed`() {
        grant()
        val corruptions: List<(SharedPreferences.Editor) -> Unit> = listOf(
            { it.putLong("automatic_elapsed", SystemClock.elapsedRealtime() + 1) },
            { it.putLong("automatic_duration", Long.MAX_VALUE) },
            { it.putInt("automatic_boot", -1) },
            { it.putString("automatic_token", "") },
            { it.putString("automatic_elapsed", "forged") },
            { it.putLong("started", System.currentTimeMillis() - 10_000) },
        )
        corruptions.forEach { corrupt ->
            begin()
            stored.edit().also(corrupt).commit()
            assertNull(Pause.automaticToken(context))
            Pause.onTimer(context)
            assertFalse(Pause.consumeResumeAuthorization(context))
        }
        begin()
        val boot = SystemSettings.Global.getInt(context.contentResolver, SystemSettings.Global.BOOT_COUNT, 0)
        SystemSettings.Global.putInt(context.contentResolver, SystemSettings.Global.BOOT_COUNT, boot + 1)
        assertNull(Pause.automaticToken(context))
        begin()
        advance(60_000L + Pause.AUTOMATIC_DELAY_MS + 1L)
        Pause.onTimer(context)
        assertFalse(Pause.consumeResumeAuthorization(context))
    }

    @Test fun `observed social needs opt in usage access a current token and no known payment`() {
        grant()
        begin()
        val token = Pause.automaticToken(context)!!
        Pause.onObservedForeground(context, token, Targets.INSTAGRAM, false)
        assertFalse(Pause.consumeResumeAuthorization(context))
        Settings(context).resumeOnSocialForeground = true
        shadowOf(context.getSystemService(AppOpsManager::class.java)).setMode(
            AppOpsManager.OPSTR_GET_USAGE_STATS, context.applicationInfo.uid, context.packageName, AppOpsManager.MODE_IGNORED,
        )
        Pause.onObservedForeground(context, token, Targets.INSTAGRAM, false)
        assertFalse(Pause.consumeResumeAuthorization(context))
        shadowOf(context.getSystemService(AppOpsManager::class.java)).setMode(
            AppOpsManager.OPSTR_GET_USAGE_STATS, context.applicationInfo.uid, context.packageName, AppOpsManager.MODE_ALLOWED,
        )
        Pause.onObservedForeground(context, "old-token", Targets.INSTAGRAM, false)
        Pause.onObservedForeground(context, token, Targets.INSTAGRAM, true)
        assertFalse(Pause.consumeResumeAuthorization(context))
        Pause.onObservedForeground(context, token, Targets.YOUTUBE, false)
        assertTrue(Pause.consumeResumeAuthorization(context))
        Pause.onObservedForeground(context, token, Targets.YOUTUBE, false)
        assertFalse(Pause.consumeResumeAuthorization(context))
    }

    @Test fun `automatic resume waits while a requested reconnect is on its way`() {
        grant()
        begin()
        advance()
        // The owner tapped Resume a moment ago: Detach is listed and the request is unexpired.
        assertTrue(Pause.resume(context))
        val listed = SystemSettings.Secure.getString(context.contentResolver, SystemSettings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
        val token = Pause.automaticToken(context)
        assertNotNull(token)
        Pause.onTimer(context)
        assertEquals(token, Pause.automaticToken(context))
        assertEquals(listed, SystemSettings.Secure.getString(context.contentResolver, SystemSettings.Secure.ENABLED_ACCESSIBILITY_SERVICES))
        assertTrue(Pause.consumeResumeAuthorization(context))
    }

    @Test fun `a timer resume never exempts the selected app it paused for`() {
        grant()
        begin()
        advance()
        Pause.onTimer(context)
        assertTrue(Pause.consumeResumeAuthorization(context))
        // Still in the same app when Detach reconnects: it must pause again, not protect it.
        assertTrue(Pause.shouldAutoPausePayment(context, bank))
        assertFalse(Pause.shouldAutoPausePayment(context, "com.android.systemui"))
        assertFalse(Pause.shouldAutoPausePayment(context, Targets.INSTAGRAM))
        Pause.onServiceStarted(context)
        assertTrue(Pause.shouldAutoPausePayment(context, bank))
    }

    private fun locked(screenOn: Boolean, keyguard: Boolean) {
        shadowOf(context.getSystemService(android.os.PowerManager::class.java)).setIsInteractive(screenOn)
        shadowOf(context.getSystemService(android.app.KeyguardManager::class.java)).setKeyguardLocked(keyguard)
    }

    @Test fun `a due timer waits for an unlock instead of resuming behind the lock screen`() {
        grant()
        begin()
        advance()
        // A payment app left on its PIN screen while the screen timed out.
        for ((screenOn, keyguard) in listOf(false to true, true to true, false to false)) {
            locked(screenOn, keyguard)
            Pause.onTimer(context)
            assertFalse("screen=$screenOn keyguard=$keyguard", Pause.consumeResumeAuthorization(context))
            assertNotNull(Pause.automaticToken(context))
            val retry = shadowOf(context.getSystemService(AlarmManager::class.java)).scheduledAlarms.single()
            assertTrue(retry.triggerAtTime > System.currentTimeMillis())
            assertTrue(retry.triggerAtTime <= System.currentTimeMillis() + Pause.LOCKED_RETRY_MS)
        }
        locked(screenOn = true, keyguard = false)
        Pause.onTimer(context)
        assertTrue(Pause.consumeResumeAuthorization(context))
    }

    @Test fun `a locked phone cannot hold the pause past its automatic authority`() {
        grant()
        begin()
        advance(60_001L + Pause.AUTOMATIC_DELAY_MS - Pause.LOCKED_DEFER_MARGIN_MS)
        locked(screenOn = false, keyguard = true)
        assertNotNull(Pause.automaticToken(context))
        Pause.onTimer(context)
        assertTrue(Pause.consumeResumeAuthorization(context))
        locked(screenOn = true, keyguard = false)
    }

    @Test fun `re-pausing over a selected app cannot become a tight enable and disable loop`() {
        grant()
        begin()
        advance()
        Pause.onTimer(context)
        assertTrue(Pause.consumeResumeAuthorization(context))
        assertTrue(Pause.begin(context, bank))
        // The new pause's timer is a full pause length away and grants nothing earlier.
        assertFalse(Pause.timerDue(context))
        Pause.onTimer(context)
        assertFalse(Pause.consumeResumeAuthorization(context))
    }

    @Test fun `legacy suppression state from older builds is ignored and cleared`() {
        stored.edit().putString("suppressed_payment", bank).putLong("suppressed_elapsed", SystemClock.elapsedRealtime())
            .putInt("suppressed_boot", SystemSettings.Global.getInt(context.contentResolver, SystemSettings.Global.BOOT_COUNT, 0)).commit()
        assertTrue(Pause.shouldAutoPausePayment(context, bank))
        begin()
        assertFalse(stored.contains("suppressed_payment"))
        stored.edit().putString("suppressed_payment", bank).commit()
        Pause.onServiceStarted(context)
        assertFalse(stored.contains("suppressed_payment"))
    }

    @Test fun `after a restart the paused status no longer promises an automatic resume`() {
        grant()
        begin()
        assertTrue(Pause.resumesAutomatically(context))
        MonitorTestSupport.reboot(context)
        assertTrue(Pause.isPending(context))
        assertFalse(Pause.resumesAutomatically(context))
        StatusNotifications.refresh(context)
        val status = shadowOf(context.getSystemService(android.app.NotificationManager::class.java)).getNotification(Pause.NOTIFICATION_ID)
        val text = status.extras.getString(android.app.Notification.EXTRA_TEXT).orEmpty()
        assertFalse(text.contains("Automatic resume"))
        assertTrue(text.contains("tap Resume"))
    }

    @Test fun `failed pause persistence is reported before scheduling or monitor startup`() {
        val failing = object : ContextWrapper(context) {
            override fun getApplicationContext(): Context = this
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
                val original = super.getSharedPreferences(name, mode)
                if (name != "pause") return original
                return object : SharedPreferences by original {
                    override fun edit(): SharedPreferences.Editor {
                        val editor = original.edit()
                        return object : SharedPreferences.Editor by editor {
                            override fun putLong(key: String, value: Long): SharedPreferences.Editor = apply { editor.putLong(key, value) }
                            override fun putInt(key: String, value: Int): SharedPreferences.Editor = apply { editor.putInt(key, value) }
                            override fun putBoolean(key: String, value: Boolean): SharedPreferences.Editor = apply { editor.putBoolean(key, value) }
                            override fun putString(key: String, value: String?): SharedPreferences.Editor = apply { editor.putString(key, value) }
                            override fun remove(key: String): SharedPreferences.Editor = apply { editor.remove(key) }
                            override fun commit(): Boolean { editor.commit(); return false }
                        }
                    }
                }
            }
        }
        assertFalse(Pause.begin(failing, bank))
        assertFalse(Pause.isPending(context))
        assertTrue(shadowOf(context.getSystemService(AlarmManager::class.java)).scheduledAlarms.isEmpty())
        assertNull(shadowOf(context).nextStartedService)
    }
}

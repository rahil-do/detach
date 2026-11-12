package io.github.detach

import android.Manifest
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.SharedPreferences
import android.provider.Settings as Sys
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSystemClock
import java.time.Duration
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
class PauseTest {
    private val ctx get() = RuntimeEnvironment.getApplication()
    private val others = "com.android.systemui.accessibility.accessibilitymenu/.AccessibilityMenuService:org.kde.kdeconnect_tp/org.kde.kdeconnect.KdeConnectAccessibilityService"
    private val me = "io.github.detach/io.github.detach.DetachService"
    private val authorizationKey = "resume_authorized_at"

    private fun enabledList() = Sys.Secure.getString(ctx.contentResolver, Sys.Secure.ENABLED_ACCESSIBILITY_SERVICES)
    private fun setEnabledList(v: String) = Sys.Secure.putString(ctx.contentResolver, Sys.Secure.ENABLED_ACCESSIBILITY_SERVICES, v)
    private fun grant() = shadowOf(ctx).grantPermissions(Manifest.permission.WRITE_SECURE_SETTINGS)
    private fun pausedUntil(t: Long) =
        ctx.getSharedPreferences("pause", Context.MODE_PRIVATE).edit().putLong("until", t).commit()

    @Test fun `resuming adds Detach back and leaves every other service exactly as it was`() {
        grant(); setEnabledList(others); pausedUntil(System.currentTimeMillis() + 60_000)
        assertTrue(Pause.resume(ctx))
        assertEquals("$others:$me", enabledList())
        assertEquals(1, Sys.Secure.getInt(ctx.contentResolver, Sys.Secure.ACCESSIBILITY_ENABLED, 0))
        assertTrue(Pause.isPending(ctx))
        assertTrue(Pause.consumeResumeAuthorization(ctx))
        Pause.onServiceStarted(ctx)
        assertFalse(Pause.isPending(ctx))
    }

    @Test fun `resuming twice doesn't add Detach twice`() {
        grant(); setEnabledList("$others:$me"); pausedUntil(System.currentTimeMillis() + 60_000)
        Pause.resume(ctx)
        Pause.resume(ctx)
        assertEquals("$others:$me", enabledList())
    }

    @Test fun `without the adb grant nothing is written and the pause stays pending`() {
        setEnabledList(others); pausedUntil(System.currentTimeMillis() + 60_000)
        assertFalse(Pause.resume(ctx))
        assertEquals(others, enabledList())
        assertTrue(Pause.isPending(ctx))
    }

    /** If the owner switched Detach off on purpose, nothing may switch it back on behind them. */
    @Test fun `with no pause pending, a boot, an update or a forged resume never switches Detach on`() {
        grant(); setEnabledList(others)
        val r = PauseReceiver()
        r.onReceive(ctx, Intent(Intent.ACTION_BOOT_COMPLETED))
        r.onReceive(ctx, Intent(Intent.ACTION_MY_PACKAGE_REPLACED))
        r.onReceive(ctx, Intent(Pause.ACTION_RESUME).putExtra("until", Long.MAX_VALUE).putExtra("enable", "com.evil/.Service"))
        r.onReceive(ctx, Intent("com.evil.ACTION"))
        assertEquals(others, enabledList())
    }

    @Test fun `a legacy or forged resume broadcast cannot switch Detach back on during a pause`() {
        grant(); setEnabledList(others); pausedUntil(System.currentTimeMillis() + 60_000)
        PauseReceiver().onReceive(ctx, Intent(Pause.ACTION_RESUME).putExtra("enable", "com.evil/.Service"))
        assertEquals(others, enabledList())
        assertTrue(Pause.isPending(ctx))
    }

    @Test fun `a pause counts as over when its time has passed or the clock was moved absurdly`() {
        val now = System.currentTimeMillis()
        pausedUntil(now + 60_000); assertFalse(Pause.isExpired(ctx))
        pausedUntil(now - 1); assertTrue(Pause.isExpired(ctx))
        pausedUntil(now + Pause.MAX_MS + 10 * 60_000); assertTrue(Pause.isExpired(ctx))
    }

    @Test fun `pause length stays between 1 and 60 minutes whatever is stored`() {
        val s = Settings(ctx)
        s.pauseMinutes = 0; assertEquals(1, s.pauseMinutes)
        s.pauseMinutes = 10_000; assertEquals(60, s.pauseMinutes)
        ctx.getSharedPreferences("settings", Context.MODE_PRIVATE).edit().putInt("pause_minutes", -5).commit()
        assertEquals(1, Settings(ctx).pauseMinutes)
    }

    @Test fun `starting a payment pause never starts a background watcher`() {
        grant()
        Pause.begin(ctx)
        assertTrue(Pause.isPending(ctx))
        assertTrue(Pause.until(ctx) > System.currentTimeMillis())
        assertEquals(null, shadowOf(ctx).nextStartedService)
    }

    @Config(sdk = [28, 35])
    @Test fun `a pause has one explicit immutable reminder alarm and no repeat timer`() {
        val alarms = shadowOf(ctx.getSystemService(AlarmManager::class.java))
        Pause.begin(ctx)
        Pause.begin(ctx)
        assertEquals(1, alarms.scheduledAlarms.size)
        val alarm = alarms.scheduledAlarms.single()
        val operation = shadowOf(alarm.operation)
        assertEquals(Pause.until(ctx), alarm.triggerAtTime)
        assertEquals(0L, alarm.interval)
        assertTrue(operation.isBroadcast)
        assertTrue(operation.isImmutable)
        assertEquals(Pause.ACTION_REMIND, operation.savedIntent.action)
        assertEquals(ComponentName(ctx, PauseReceiver::class.java), operation.savedIntent.component)
        assertTrue(Pause.startedAt(ctx) > 0)
    }

    @Test fun `starting a pause revokes both old resume alarm and notification tokens`() {
        val manager = ctx.getSystemService(AlarmManager::class.java)
        val legacy = listOf(1, 2).map { requestCode ->
            PendingIntent.getBroadcast(
                ctx, requestCode, Intent(ctx, PauseReceiver::class.java).setAction(Pause.ACTION_RESUME),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            ).also { manager.set(AlarmManager.RTC_WAKEUP, System.currentTimeMillis() + 60_000, it) }
        }
        Pause.begin(ctx)
        assertTrue(legacy.all { shadowOf(it).isCanceled })
        assertEquals(1, shadowOf(manager).scheduledAlarms.size)
        assertEquals(Pause.ACTION_REMIND, shadowOf(shadowOf(manager).scheduledAlarms.single().operation).savedIntent.action)
    }

    @Test fun `boot update and app opening restore only the original reminder deadline`() {
        grant(); setEnabledList(others)
        Pause.begin(ctx)
        val deadline = Pause.until(ctx)
        val start = Pause.startedAt(ctx)
        val manager = ctx.getSystemService(AlarmManager::class.java)
        shadowOf(manager).scheduledAlarms.toList().forEach { alarm -> alarm.operation?.let { manager.cancel(it) } }
        val receiver = PauseReceiver()
        listOf(Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED, Pause.ACTION_REMIND).forEach { action ->
            receiver.onReceive(ctx, Intent(action).putExtra("until", Long.MAX_VALUE))
            assertEquals(deadline, Pause.until(ctx))
            assertEquals(start, Pause.startedAt(ctx))
            assertEquals(1, shadowOf(manager).scheduledAlarms.size)
            assertEquals(deadline, shadowOf(manager).scheduledAlarms.single().triggerAtTime)
            assertEquals(others, enabledList())
        }
        Pause.resumeIfDue(ctx)
        assertEquals(1, shadowOf(manager).scheduledAlarms.size)
        assertEquals(others, enabledList())
        assertEquals(null, shadowOf(ctx).nextStartedService)
    }

    @Test fun `explicit resume keeps pending authorization until the service confirms reconnect`() {
        grant(); setEnabledList(others)
        Pause.begin(ctx)
        assertTrue(Pause.resume(ctx))
        assertTrue(Pause.isPending(ctx))
        assertTrue(Pause.consumeResumeAuthorization(ctx))
        assertFalse(Pause.consumeResumeAuthorization(ctx))
        Pause.onServiceStarted(ctx)
        assertFalse(Pause.isPending(ctx))
        assertEquals(0L, Pause.startedAt(ctx))
        assertTrue(shadowOf(ctx.getSystemService(AlarmManager::class.java)).scheduledAlarms.isEmpty())
    }

    @Test fun `a manually reconnected service clears both timestamps and the reminder`() {
        Pause.begin(ctx)
        assertFalse(Pause.resume(ctx))
        assertTrue(Pause.consumeResumeAuthorization(ctx))
        Pause.onServiceStarted(ctx)
        assertEquals(0L, Pause.until(ctx))
        assertEquals(0L, Pause.startedAt(ctx))
        assertTrue(shadowOf(ctx.getSystemService(AlarmManager::class.java)).scheduledAlarms.isEmpty())
    }

    @Test fun `explicit resume without a grant authorizes one Settings reconnect without enabling anything`() {
        setEnabledList(others)
        Pause.begin(ctx)
        assertFalse(Pause.consumeResumeAuthorization(ctx))
        assertFalse(Pause.resume(ctx))
        assertEquals(others, enabledList())
        assertTrue(ctx.getSharedPreferences("pause", Context.MODE_PRIVATE).contains(authorizationKey))
        assertTrue(Pause.consumeResumeAuthorization(ctx.createPackageContext(ctx.packageName, 0)))
        assertFalse(Pause.consumeResumeAuthorization(ctx))
        assertTrue(Pause.isPending(ctx))
        assertEquals(others, enabledList())
    }

    @Test fun `resume authorization is consumed atomically across concurrent reconnects`() {
        Pause.begin(ctx)
        assertFalse(Pause.resume(ctx))
        val executor = Executors.newFixedThreadPool(2)
        val ready = CountDownLatch(1)
        try {
            val attempts = List(2) { executor.submit(Callable { ready.await(); Pause.consumeResumeAuthorization(ctx) }) }
            ready.countDown()
            assertEquals(1, attempts.count { it.get(5, TimeUnit.SECONDS) })
        } finally {
            executor.shutdownNow()
        }
    }

    @Test fun `a new payment pause revokes a previous explicit resume authorization`() {
        Pause.begin(ctx)
        assertFalse(Pause.resume(ctx))
        Pause.begin(ctx)
        assertFalse(Pause.consumeResumeAuthorization(ctx))
        assertFalse(ctx.getSharedPreferences("pause", Context.MODE_PRIVATE).contains(authorizationKey))
    }

    @Test fun `resume authorization expires after three minutes without extending itself`() {
        Pause.begin(ctx)
        assertFalse(Pause.resume(ctx))
        ShadowSystemClock.advanceBy(Duration.ofMillis(Pause.RESUME_AUTHORIZATION_MS + 1))
        assertFalse(Pause.consumeResumeAuthorization(ctx))
        assertTrue(Pause.isPending(ctx))
        assertFalse(ctx.getSharedPreferences("pause", Context.MODE_PRIVATE).contains(authorizationKey))
    }

    @Test fun `future malformed stale and orphaned authorization markers are rejected and removed`() {
        ShadowSystemClock.advanceBy(Duration.ofMinutes(10))
        Pause.begin(ctx)
        val prefs = ctx.getSharedPreferences("pause", Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        listOf(0L, -1L, Long.MIN_VALUE, Long.MAX_VALUE, now + 60_000, now - Pause.RESUME_AUTHORIZATION_MS - 1).forEach { issued ->
            assertFalse(Pause.resume(ctx))
            prefs.edit().putLong(authorizationKey, issued).commit()
            assertFalse("Unexpected authorization for timestamp $issued", Pause.consumeResumeAuthorization(ctx))
            assertFalse(prefs.contains(authorizationKey))
        }
        assertFalse(Pause.resume(ctx))
        prefs.edit().putString(authorizationKey, "forged").commit()
        assertFalse(Pause.consumeResumeAuthorization(ctx))
        assertFalse(prefs.contains(authorizationKey))
        assertFalse(Pause.resume(ctx))
        prefs.edit().remove("until").putLong(authorizationKey, now).commit()
        assertFalse(Pause.consumeResumeAuthorization(ctx))
        assertFalse(prefs.contains(authorizationKey))
    }

    @Test fun `elapsed time rejects an expired authorization even if wall clock age looks fresh`() {
        Pause.begin(ctx)
        assertFalse(Pause.resume(ctx))
        ShadowSystemClock.advanceBy(Duration.ofMillis(Pause.RESUME_AUTHORIZATION_MS + 1))
        ctx.getSharedPreferences("pause", Context.MODE_PRIVATE).edit()
            .putLong(authorizationKey, System.currentTimeMillis()).commit()
        assertFalse(Pause.consumeResumeAuthorization(ctx))
    }

    @Test fun `authorization is bound to the current boot before any boot receiver runs`() {
        Pause.begin(ctx)
        assertFalse(Pause.resume(ctx))
        val boot = Sys.Global.getInt(ctx.contentResolver, Sys.Global.BOOT_COUNT, 0)
        Sys.Global.putInt(ctx.contentResolver, Sys.Global.BOOT_COUNT, boot + 1)
        assertFalse(Pause.consumeResumeAuthorization(ctx))
    }

    @Test fun `missing malformed or future elapsed and boot markers cannot authorize reconnects`() {
        Pause.begin(ctx)
        val prefs = ctx.getSharedPreferences("pause", Context.MODE_PRIVATE)
        val corruptions: List<(SharedPreferences.Editor) -> Unit> = listOf(
            { it.remove("resume_authorized_elapsed") },
            { it.putLong("resume_authorized_elapsed", -1L) },
            { it.putLong("resume_authorized_elapsed", Long.MAX_VALUE) },
            { it.putString("resume_authorized_elapsed", "forged") },
            { it.remove("resume_boot_count") },
            { it.putString("resume_boot_count", "forged") },
        )
        corruptions.forEach { corrupt ->
            assertFalse(Pause.resume(ctx))
            prefs.edit().also(corrupt).commit()
            assertFalse(Pause.consumeResumeAuthorization(ctx))
            assertFalse(prefs.all.keys.any { it.startsWith("resume_") })
        }
    }

    @Test fun `reboot and update broadcasts revoke a previously issued resume authorization`() {
        Pause.begin(ctx)
        listOf(Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED).forEach { action ->
            assertFalse(Pause.resume(ctx))
            PauseReceiver().onReceive(ctx, Intent(action))
            assertFalse(Pause.consumeResumeAuthorization(ctx))
            assertTrue(Pause.isPending(ctx))
        }
    }

    @Test fun `reminders and app opening cannot renew an existing resume authorization`() {
        Pause.begin(ctx)
        assertFalse(Pause.resume(ctx))
        val issued = ctx.getSharedPreferences("pause", Context.MODE_PRIVATE).getLong(authorizationKey, 0)
        ShadowSystemClock.advanceBy(Duration.ofMinutes(2))
        Pause.remind(ctx)
        Pause.resumeIfDue(ctx)
        assertEquals(issued, ctx.getSharedPreferences("pause", Context.MODE_PRIVATE).getLong(authorizationKey, 0))
        ShadowSystemClock.advanceBy(Duration.ofMinutes(2))
        assertFalse(Pause.consumeResumeAuthorization(ctx))
    }

    @Test fun `alarms broadcasts boot updates and opening the app never authorize a reconnect`() {
        grant(); setEnabledList(others)
        Pause.begin(ctx)
        val receiver = PauseReceiver()
        listOf(Pause.ACTION_RESUME, Pause.ACTION_REMIND, Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED).forEach { action ->
            receiver.onReceive(ctx, Intent(action).putExtra(authorizationKey, System.currentTimeMillis()))
            assertFalse(Pause.consumeResumeAuthorization(ctx))
            assertTrue(Pause.isPending(ctx))
            assertEquals(others, enabledList())
        }
        Pause.resumeIfDue(ctx)
        assertFalse(Pause.consumeResumeAuthorization(ctx))
        assertEquals(others, enabledList())
    }

    @Test fun `failed authorization persistence never enables the service even with the adb grant`() {
        grant(); setEnabledList(others)
        Pause.begin(ctx)
        val failing = object : ContextWrapper(ctx) {
            override fun getApplicationContext(): Context = this
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
                val stored = super.getSharedPreferences(name, mode)
                if (name != "pause") return stored
                return object : SharedPreferences by stored {
                    override fun edit(): SharedPreferences.Editor {
                        val editor = stored.edit()
                        return object : SharedPreferences.Editor by editor {
                            override fun putLong(key: String, value: Long): SharedPreferences.Editor = apply { editor.putLong(key, value) }
                            override fun putInt(key: String, value: Int): SharedPreferences.Editor = apply { editor.putInt(key, value) }
                            override fun remove(key: String): SharedPreferences.Editor = apply { editor.remove(key) }
                            override fun commit(): Boolean { editor.commit(); return false }
                        }
                    }
                }
            }
        }
        assertFalse(Pause.resume(failing))
        assertEquals(others, enabledList())
        assertTrue(Pause.isPending(ctx))
        assertFalse(Pause.consumeResumeAuthorization(ctx))
    }

    @Test fun `invalid persisted timestamps fail closed without enabling protection`() {
        grant(); setEnabledList(others)
        val prefs = ctx.getSharedPreferences("pause", Context.MODE_PRIVATE)
        prefs.edit().putString("until", "corrupt").putString("started", "corrupt").commit()
        assertTrue(Pause.isPending(ctx))
        assertTrue(Pause.isExpired(ctx))
        assertEquals(0L, Pause.startedAt(ctx))
        Pause.resumeIfDue(ctx)
        assertEquals(others, enabledList())
        assertTrue(shadowOf(ctx.getSystemService(AlarmManager::class.java)).scheduledAlarms.isEmpty())
        assertTrue(Pause.resume(ctx))
        assertTrue(Pause.isPending(ctx))
        assertTrue(Pause.consumeResumeAuthorization(ctx))
        Pause.onServiceStarted(ctx)
        assertFalse(Pause.isPending(ctx))
    }

    @Test fun `reminders boot updates and opening Detach never resume over a bank`() {
        grant()
        setEnabledList(others)
        pausedUntil(System.currentTimeMillis() - 1)
        val receiver = PauseReceiver()
        listOf(Pause.ACTION_REMIND, Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED).forEach {
            receiver.onReceive(ctx, Intent(it))
            assertEquals(others, enabledList())
            assertTrue(Pause.isPending(ctx))
        }
        Pause.resumeIfDue(ctx)
        assertEquals(others, enabledList())
        assertTrue(Pause.isPending(ctx))
        assertTrue(shadowOf(ctx.getSystemService(AlarmManager::class.java)).scheduledAlarms.isEmpty())
    }
}

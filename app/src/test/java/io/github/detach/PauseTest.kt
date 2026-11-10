package io.github.detach

import android.Manifest
import android.content.Context
import android.content.Intent
import android.provider.Settings as Sys
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
class PauseTest {
    private val ctx get() = RuntimeEnvironment.getApplication()
    private val others = "com.android.systemui.accessibility.accessibilitymenu/.AccessibilityMenuService:org.kde.kdeconnect_tp/org.kde.kdeconnect.KdeConnectAccessibilityService"
    private val me = "io.github.detach/io.github.detach.DetachService"

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

    @Test fun `a forged resume during a pause can only switch Detach itself back on`() {
        grant(); setEnabledList(others); pausedUntil(System.currentTimeMillis() + 60_000)
        PauseReceiver().onReceive(ctx, Intent(Pause.ACTION_RESUME).putExtra("enable", "com.evil/.Service"))
        assertEquals("$others:$me", enabledList())
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
}

package io.github.detach

import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class SettingsTest {
    private val context get() = RuntimeEnvironment.getApplication()

    @Test fun `Instagram intentional-use permissions default to enabled and persist changes`() {
        val first = Settings(context)
        assertTrue(first.igStories)
        assertTrue(first.igMessages)
        assertTrue(first.igSharedReels)
        first.igStories = false
        first.igMessages = false
        first.igSharedReels = false
        val restored = Settings(context)
        assertFalse(restored.igStories)
        assertFalse(restored.igMessages)
        assertFalse(restored.igSharedReels)
    }

    @Test fun `default auto pause events are limited to the selected payment packages`() {
        val settings = Settings(context)
        assertTrue(settings.autoPausePayments)
        assertEquals(Targets.ALL, settings.eventPackages())
        settings.autoPausePayments = false
        assertEquals(Targets.READABLE + Targets.SYSTEM_UI, settings.eventPackages())
    }

    @Test fun `owners can select a bank without granting content access`() {
        val settings = Settings(context)
        settings.paymentPackages = setOf("com.example.bank")
        assertEquals(setOf("com.example.bank"), Settings(context).paymentPackages)
        assertTrue("com.example.bank" in settings.eventPackages())
        assertFalse("com.example.bank" in Targets.READABLE)
        assertFalse("com.phonepe.app" in settings.eventPackages())
    }

    @Test fun `invalid and protected package names cannot become payment bypasses`() {
        val settings = Settings(context)
        settings.paymentPackages = Targets.READABLE + Targets.SYSTEM_UI + setOf(
            "io.github.detach", "", "*", "com.bank,com.instagram.android", "com.example.bank",
            " com.bank", "com.bank ", "com.bank\n", "com..bank", "com.bank/app", "com.bank:activity",
            "com.bank.*", "com.1bank", "com.bänk", "com.bank-1", "com." + "b".repeat(256),
        )
        assertEquals(setOf("com.example.bank"), settings.paymentPackages)
    }

    @Test fun `stored payment packages are sanitized even if the setter was bypassed`() {
        val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
        prefs.edit().putStringSet("payment_packages", Targets.READABLE + Targets.SYSTEM_UI + setOf("*", "io.github.detach", "com.example.bank")).commit()
        assertEquals(setOf("com.example.bank"), Settings(context).paymentPackages)
        assertEquals(Targets.READABLE + Targets.SYSTEM_UI + "com.example.bank", Settings(context).eventPackages())
        assertFalse("com.example.bank" in Targets.READABLE)
    }

    @Test fun `changing a returned package set cannot change persisted selection`() {
        val settings = Settings(context)
        settings.paymentPackages = setOf("com.example.bank")
        (settings.paymentPackages as MutableSet<String>).clear()
        assertEquals(setOf("com.example.bank"), Settings(context).paymentPackages)
    }

    @Test fun `an explicitly empty selection remains empty while invalid storage falls back safely`() {
        val settings = Settings(context)
        settings.paymentPackages = emptySet()
        assertTrue(settings.paymentPackages.isEmpty())
        assertEquals(Targets.READABLE + Targets.SYSTEM_UI, settings.eventPackages())
        context.getSharedPreferences("settings", Context.MODE_PRIVATE).edit().putString("payment_packages", "*").commit()
        assertEquals(Targets.PAYMENT_DEFAULTS, Settings(context).paymentPackages)
    }

    @Test fun `invalid duration and auto pause preference types restore safe defaults`() {
        context.getSharedPreferences("settings", Context.MODE_PRIVATE).edit()
            .putString("pause_minutes", "forever").putString("auto_pause_payments", "false").commit()
        val settings = Settings(context)
        assertTrue(settings.autoPausePayments)
        assertEquals(5, settings.pauseMinutes)
        assertEquals(Targets.ALL, settings.eventPackages())
    }
}

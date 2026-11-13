package io.github.detach

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.graphics.Color
import android.hardware.biometrics.BiometricManager
import android.provider.Settings as SystemSettings
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowBiometricManager

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class OtherAccessibilityAppsTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val remote = "com.example.remote/com.example.remote.RemoteInputService"
    private val menu = "com.android.systemui.accessibility.accessibilitymenu/.AccessibilityMenuService"
    private val detach = "io.github.detach/io.github.detach.DetachService"

    @Before fun reset() {
        for (name in listOf("settings", "lock", "pause")) context.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear().commit()
        SystemSettings.Global.putFloat(context.contentResolver, SystemSettings.Global.ANIMATOR_DURATION_SCALE, 0f)
        Shadow.extract<ShadowBiometricManager>(context.getSystemService(BiometricManager::class.java)).setCanAuthenticate(false)
        install("com.example.remote", "Remote Input", system = false)
        install("com.android.systemui.accessibility.accessibilitymenu", "Accessibility Menu", system = true)
    }

    private fun install(packageName: String, label: String, system: Boolean) {
        shadowOf(context.packageManager).installPackage(PackageInfo().apply {
            this.packageName = packageName
            applicationInfo = ApplicationInfo().apply {
                this.packageName = packageName
                nonLocalizedLabel = label
            }
        })
        shadowOf(context.packageManager).getInternalMutablePackageInfo(packageName).applicationInfo!!.flags =
            ApplicationInfo.FLAG_INSTALLED or if (system) ApplicationInfo.FLAG_SYSTEM else 0
    }

    private fun enabled(value: String?) =
        SystemSettings.Secure.putString(context.contentResolver, SystemSettings.Secure.ENABLED_ACCESSIBILITY_SERVICES, value)

    @Test fun `only other installed non system services are named by app label`() {
        enabled("$menu:$remote:$detach:com.example.uninstalled/.Service:not a component::$remote")
        assertEquals(listOf("Remote Input"), OtherAccessibilityApps.labels(context))
    }

    @Test fun `nothing is listed when only Detach and system services are on`() {
        enabled(null)
        assertTrue(OtherAccessibilityApps.labels(context).isEmpty())
        enabled("$menu:$detach")
        assertTrue(OtherAccessibilityApps.labels(context).isEmpty())
    }

    @Test fun `reading other services never changes the enabled list`() {
        enabled("$remote:$detach")
        OtherAccessibilityApps.labels(context)
        Robolectric.buildActivity(MainActivity::class.java).setup()
        assertEquals("$remote:$detach", SystemSettings.Secure.getString(context.contentResolver, SystemSettings.Secure.ENABLED_ACCESSIBILITY_SERVICES))
    }

    @Test @Config(qualifiers = "notnight") fun `light payments section warns about other accessibility apps readably`() {
        assertWarning()
    }

    @Test @Config(qualifiers = "night") fun `dark payments section warns about other accessibility apps readably`() {
        assertWarning()
    }

    @Test fun `no warning appears without other non system services`() {
        enabled("$menu:$detach")
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        assertNull(activity.window.decorView.findViewWithTag<View>("other-accessibility"))
    }

    @Test @Config(qualifiers = "night") fun `payments section recommends pausing before the payment app opens`() {
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        val setup = activity.window.decorView.findViewWithTag<ViewGroup>("payment-setup")
        val text = views(setup).filterIsInstance<TextView>().joinToString(" ") { it.text }
        assertTrue(text.contains("apps you pay from"))
        assertTrue(text.contains("Quick Settings tile"))
        assertTrue(text.contains("restart your phone once"))
        assertTrue(text.contains("stays paused through the restart"))
        val ui = Ui(activity)
        views(setup).filterIsInstance<TextView>().forEach { assertContrast(it.currentTextColor, ui.surface, 4.5) }
    }

    private fun assertWarning() {
        enabled("$menu:$remote:$detach")
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        val ui = Ui(activity)
        val warning = activity.window.decorView.findViewWithTag<ViewGroup>("other-accessibility")
        assertEquals(View.VISIBLE, warning.visibility)
        val texts = views(warning).filterIsInstance<TextView>()
        val note = texts.first { it.text.contains("Remote Input") }
        assertFalse(note.text.contains("Accessibility Menu"))
        assertTrue(note.text.contains("Detach can only switch itself off"))
        assertEquals(ui.sub, note.currentTextColor)
        assertContrast(note.currentTextColor, ui.bg, 4.5)
        val open = views(warning).filterIsInstance<Button>().single { it.text == "Open Accessibility settings" }
        assertContrast(open.currentTextColor, ui.bg, 4.5)
        assertTrue(open.minimumHeight >= ui.dp(48))
        open.performClick()
        assertEquals(SystemSettings.ACTION_ACCESSIBILITY_SETTINGS, shadowOf(activity).nextStartedActivity.action)
        assertEquals("$menu:$remote:$detach", SystemSettings.Secure.getString(context.contentResolver, SystemSettings.Secure.ENABLED_ACCESSIBILITY_SERVICES))
    }

    private fun views(view: View): List<View> = listOf(view) + if (view is ViewGroup) {
        (0 until view.childCount).flatMap { views(view.getChildAt(it)) }
    } else emptyList()

    private fun assertContrast(foreground: Int, background: Int, minimum: Double) {
        val lighter = maxOf(Color.luminance(foreground).toDouble(), Color.luminance(background).toDouble())
        val darker = minOf(Color.luminance(foreground).toDouble(), Color.luminance(background).toDouble())
        assertTrue("Contrast ${(lighter + 0.05) / (darker + 0.05)} must be at least $minimum",
            (lighter + 0.05) / (darker + 0.05) >= minimum)
    }
}

package io.github.detach

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.os.Looper
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.NumberPicker
import android.widget.TextView
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
import java.time.Duration
import android.provider.Settings as SystemSettings

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class BreatherControlsTest {
    private val context get() = RuntimeEnvironment.getApplication()

    @Before fun reset() {
        BreatherTestSupport.reset()
        SystemSettings.Global.putFloat(context.contentResolver, SystemSettings.Global.ANIMATOR_DURATION_SCALE, 1f)
    }

    @Test fun `missing credentials show configuration guidance rather than a start dialog`() {
        val activity = build()
        button(activity, "Start a breather").performClick()
        val dialog = BreatherTestSupport.dialog()
        assertEquals("Authentication required", shadowOf(dialog).title.toString())
        val message = dialog.findViewById<TextView>(android.R.id.message).text.toString()
        assertTrue(message.contains("Set a Detach password"))
        assertTrue(message.contains("enroll biometrics"))
        assertEquals("Enroll biometrics", dialog.getButton(AlertDialog.BUTTON_POSITIVE).text.toString())
        assertEquals(0L, Breather.remainingMs(context))
    }

    @Test @Config(sdk = [28])
    fun `Android 9 guidance offers the supported password path`() {
        val activity = build()
        button(activity, "Start a breather").performClick()
        val dialog = BreatherTestSupport.dialog()
        val message = dialog.findViewById<TextView>(android.R.id.message).text.toString()
        assertTrue(message.contains("Set a Detach password"))
        assertFalse(message.contains("enroll biometrics"))
        assertEquals(android.view.View.GONE, dialog.getButton(AlertDialog.BUTTON_POSITIVE).visibility)
        assertEquals(0L, Breather.remainingMs(context))
    }

    @Test fun `the dialog is general with a bounded native minute wheel and no feature selectors`() {
        Lock(context).set(BreatherTestSupport.PASSWORD)
        val activity = build()
        button(activity, "Start a breather").performClick()
        val dialog = BreatherTestSupport.dialog()
        assertEquals("Take a breather", shadowOf(dialog).title.toString())
        val views = BreatherTestSupport.views(dialog.window!!.decorView)
        assertTrue(views.none { it is CheckBox })
        val picker = views.filterIsInstance<NumberPicker>().single()
        assertEquals(BreatherControls.MIN_MINUTES, picker.minValue)
        assertEquals(BreatherControls.MAX_MINUTES, picker.maxValue)
        assertEquals(BreatherControls.DEFAULT_MINUTES, picker.value)
        assertFalse(picker.wrapSelectorWheel)
        assertTrue(picker.contentDescription.isNotBlank())
        val ends = views.filterIsInstance<TextView>().single { it.tag == "breather-ends" }
        assertTrue(ends.text.startsWith("Ends at "))
    }

    @Test fun `start authenticates first then unblocks everything and plays the intro`() {
        Lock(context).set(BreatherTestSupport.PASSWORD)
        var changes = 0
        var unblocked = emptySet<Breather.Feature>()
        var remaining = 0L
        val activity = BreatherTestSupport.activity()
        // Captured at the change callback: Robolectric's wall clock is real while elapsed time is
        // simulated, so later reads can trip Breather's clock-consistency check on slow machines.
        activity.setContentView(BreatherControls.build(activity) {
            if (changes++ == 0) {
                unblocked = Breather.activeFeatures(context)
                remaining = Breather.remainingMs(context)
            }
        })
        var introShown = false
        (activity.window.decorView as ViewGroup).setOnHierarchyChangeListener(object : ViewGroup.OnHierarchyChangeListener {
            override fun onChildViewAdded(parent: android.view.View, child: android.view.View) {
                if (child.tag == BreatherIntro.TAG) introShown = true
            }
            override fun onChildViewRemoved(parent: android.view.View, child: android.view.View) = Unit
        })
        button(activity, "Start a breather").performClick()
        val chooser = BreatherTestSupport.dialog()
        BreatherTestSupport.views(chooser.window!!.decorView).filterIsInstance<NumberPicker>().single().value = 15
        chooser.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        assertEquals("Enter password", shadowOf(BreatherTestSupport.dialog()).title.toString())
        assertEquals(0, changes)
        assertEquals(0L, Breather.remainingMs(context))
        BreatherTestSupport.enterPassword(BreatherTestSupport.PASSWORD)
        assertEquals(1, changes)
        assertEquals(Breather.Feature.entries.toSet(), unblocked)
        assertTrue(remaining in 14 * 60_000L..15 * 60_000L)

        assertTrue(introShown)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(3))
        assertNull((activity.window.decorView as ViewGroup).findViewWithTag(BreatherIntro.TAG))

        BreatherTestSupport.views(activity.window.decorView).filterIsInstance<Button>()
            .firstOrNull { it.text.toString() == "End breather now" }?.performClick()
        Breather.end(context)
        assertEquals(0L, Breather.remainingMs(context))
    }

    @Test fun `end breather now restores restrictions and reports the change`() {
        Lock(context).set(BreatherTestSupport.PASSWORD)
        var changes = 0
        val activity = BreatherTestSupport.activity()
        assertEquals(true, BreatherTestSupport.start(activity, 5, Breather.Feature.entries.toSet()))
        activity.setContentView(BreatherControls.build(activity) { changes++ })
        button(activity, "End breather now").performClick()
        assertEquals(1, changes)
        assertEquals(0L, Breather.remainingMs(context))
        assertTrue(button(activity, "Start a breather").isEnabled)
    }

    @Test fun `removed animations skip the intro overlay`() {
        SystemSettings.Global.putFloat(context.contentResolver, SystemSettings.Global.ANIMATOR_DURATION_SCALE, 0f)
        val activity = build()
        BreatherIntro.play(activity, 5)
        assertNull((activity.window.decorView as ViewGroup).findViewWithTag(BreatherIntro.TAG))
    }

    @Test fun `tapping the intro skips straight to its fade out`() {
        val activity = build()
        BreatherIntro.play(activity, 5)
        val decor = activity.window.decorView as ViewGroup
        val overlay = decor.findViewWithTag<android.view.View>(BreatherIntro.TAG)
        assertNotNull(overlay)
        assertTrue(overlay.isClickable)
        overlay.performClick()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(600))
        assertNull(decor.findViewWithTag(BreatherIntro.TAG))
    }

    @Test fun `canceling an extension leaves settings and the active breather untouched`() {
        Lock(context).set(BreatherTestSupport.PASSWORD)
        val activity = build()
        assertEquals(true, BreatherTestSupport.start(activity, 5, Breather.Feature.entries.toSet()))
        activity.setContentView(BreatherControls.build(activity) {})
        val before = context.getSharedPreferences("settings", Context.MODE_PRIVATE).all.toMap()
        val remaining = Breather.remainingMs(context)
        button(activity, "Extend breather").performClick()
        val chooser = BreatherTestSupport.dialog()
        assertEquals("Extend your breather", shadowOf(chooser).title.toString())
        val picker = BreatherTestSupport.views(chooser.window!!.decorView).filterIsInstance<NumberPicker>().single()
        assertEquals(5, picker.value)
        picker.value = 30
        chooser.getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
        assertTrue(Breather.remainingMs(context) <= remaining)
        assertEquals(Breather.Feature.entries.toSet(), Breather.activeFeatures(context))
        assertEquals(before, context.getSharedPreferences("settings", Context.MODE_PRIVATE).all.toMap())
    }

    @Test fun `credentials removed while choosing cannot reach Auth silent success`() {
        Lock(context).set(BreatherTestSupport.PASSWORD)
        val activity = build()
        button(activity, "Start a breather").performClick()
        val chooser = BreatherTestSupport.dialog()
        Lock(context).clear()
        chooser.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        assertEquals("Authentication required", shadowOf(BreatherTestSupport.dialog()).title.toString())
        assertEquals(0L, Breather.remainingMs(context))
    }

    @Test fun `controls and dialog offer labeled 48dp touch targets`() {
        assertAccessibleControls()
    }

    @Test @Config(qualifiers = "night")
    fun `dark theme controls use current Ui ink and accessible targets`() {
        assertAccessibleControls()
    }

    @Test @Config(qualifiers = "w320dp-h640dp", fontScale = 1.5f)
    fun `small screen large font keeps the dialog scrollable`() {
        assertAccessibleControls()
    }

    private fun assertAccessibleControls() {
        Lock(context).set(BreatherTestSupport.PASSWORD)
        val activity = build()
        val ui = Ui(activity)
        val minimum = ui.dp(48)
        assertTrue(button(activity, "Start a breather").minimumHeight >= minimum)
        button(activity, "Start a breather").performClick()
        val dialog = BreatherTestSupport.dialog()
        val views = BreatherTestSupport.views(dialog.window!!.decorView)
        val positive = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
        val negative = dialog.getButton(AlertDialog.BUTTON_NEGATIVE)
        assertTrue(positive.minimumHeight >= minimum)
        assertTrue(negative.minimumHeight >= minimum)
        // Dialog actions are drawn with the app's own fills, never the framework/OEM button chrome.
        assertEquals(ui.onAccent, positive.currentTextColor)
        assertEquals(ui.accent, negative.currentTextColor)
        assertNull(positive.backgroundTintList)
        assertTrue(views.filterIsInstance<NumberPicker>().single().contentDescription.isNotBlank())
        assertTrue(views.any { it is android.widget.ScrollView })
    }

    private fun build(): Activity = BreatherTestSupport.activity().also { activity ->
        activity.setContentView(BreatherControls.build(activity) {})
    }

    private fun button(activity: Activity, text: String): Button =
        BreatherTestSupport.views(activity.window.decorView).filterIsInstance<Button>().single { it.text.toString() == text }
}

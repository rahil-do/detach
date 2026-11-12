package io.github.detach

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.TextView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class BreatherControlsTest {
    private val context get() = RuntimeEnvironment.getApplication()

    @Before fun reset() = BreatherTestSupport.reset()

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

    @Test fun `invalid duration and empty feature selection cannot dismiss into authentication`() {
        Lock(context).set(BreatherTestSupport.PASSWORD)
        val activity = build()
        button(activity, "Start a breather").performClick()
        val dialog = BreatherTestSupport.dialog()
        val duration = BreatherTestSupport.views(dialog.window!!.decorView).filterIsInstance<EditText>().single()
        duration.setText("61")
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        assertTrue(dialog.isShowing)
        assertEquals("Choose 1 to 60 minutes", duration.error.toString())
        duration.setText("5")
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        assertTrue(dialog.isShowing)
        assertTrue(BreatherTestSupport.views(dialog.window!!.decorView).filterIsInstance<TextView>()
            .any { it.text.toString() == "Choose at least one feature to unblock." })
        assertEquals(0L, Breather.remainingMs(context))
    }

    @Test fun `selection authenticates before activation and end early reports the change`() {
        Lock(context).set(BreatherTestSupport.PASSWORD)
        var changes = 0
        val activity = BreatherTestSupport.activity()
        activity.setContentView(BreatherControls.build(activity) { changes++ })
        button(activity, "Start a breather").performClick()
        val chooser = BreatherTestSupport.dialog()
        BreatherTestSupport.views(chooser.window!!.decorView).filterIsInstance<CheckBox>()
            .single { it.tag == Breather.Feature.YT_SHORTS }.performClick()
        chooser.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        assertEquals("Enter password", shadowOf(BreatherTestSupport.dialog()).title.toString())
        assertEquals(0, changes)
        assertEquals(0L, Breather.remainingMs(context))
        BreatherTestSupport.enterPassword(BreatherTestSupport.PASSWORD)
        assertEquals(1, changes)
        assertTrue(Breather.allows(context, Breather.Feature.YT_SHORTS))
        assertFalse(Breather.allows(context, Breather.Feature.IG_FEED))
        button(activity, "End breather now").performClick()
        assertEquals(2, changes)
        assertEquals(0L, Breather.remainingMs(context))
        assertTrue(button(activity, "Start a breather").isEnabled)
    }

    @Test fun `canceling feature selection leaves permanent settings and active scope untouched`() {
        Lock(context).set(BreatherTestSupport.PASSWORD)
        val activity = build()
        assertEquals(true, BreatherTestSupport.start(activity, 5, setOf(Breather.Feature.IG_DMS)))
        activity.setContentView(BreatherControls.build(activity) {})
        val before = context.getSharedPreferences("settings", Context.MODE_PRIVATE).all.toMap()
        button(activity, "Change or extend breather").performClick()
        val chooser = BreatherTestSupport.dialog()
        val choices = BreatherTestSupport.views(chooser.window!!.decorView).filterIsInstance<CheckBox>()
        assertTrue(choices.single { it.tag == Breather.Feature.IG_DMS }.isChecked)
        assertTrue(choices.filter { it.tag != Breather.Feature.IG_DMS }.none { it.isChecked })
        choices.single { it.tag == Breather.Feature.IG_FEED }.performClick()
        chooser.getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
        assertEquals(setOf(Breather.Feature.IG_DMS), Breather.activeFeatures(context))
        assertEquals(before, context.getSharedPreferences("settings", Context.MODE_PRIVATE).all.toMap())
    }

    @Test fun `credentials removed while choosing cannot reach Auth silent success`() {
        Lock(context).set(BreatherTestSupport.PASSWORD)
        val activity = build()
        button(activity, "Start a breather").performClick()
        val chooser = BreatherTestSupport.dialog()
        BreatherTestSupport.views(chooser.window!!.decorView).filterIsInstance<CheckBox>().first().performClick()
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
    fun `small screen large font keeps selection labels multiline and scrollable`() {
        assertAccessibleControls()
    }

    private fun assertAccessibleControls() {
        Lock(context).set(BreatherTestSupport.PASSWORD)
        val activity = build()
        val minimum = Ui(activity).dp(48)
        assertTrue(button(activity, "Start a breather").minimumHeight >= minimum)
        button(activity, "Start a breather").performClick()
        val dialog = BreatherTestSupport.dialog()
        val views = BreatherTestSupport.views(dialog.window!!.decorView)
        val choices = views.filterIsInstance<CheckBox>()
        assertEquals(Breather.Feature.entries.size, choices.size)
        for (choice in choices) {
            assertTrue(choice.text.isNotBlank())
            assertTrue(choice.minimumHeight >= minimum)
            assertEquals(Ui(activity).fg, choice.currentTextColor)
            assertFalse(choice.isSingleLine)
        }
        assertTrue(dialog.getButton(AlertDialog.BUTTON_POSITIVE).minimumHeight >= minimum)
        assertTrue(views.filterIsInstance<EditText>().single().contentDescription.isNotBlank())
        assertTrue(views.any { it is android.widget.ScrollView })
        assertTrue(choices.none { it.isChecked })
    }

    private fun build(): Activity = BreatherTestSupport.activity().also { activity ->
        activity.setContentView(BreatherControls.build(activity) {})
    }

    private fun button(activity: Activity, text: String): Button =
        BreatherTestSupport.views(activity.window.decorView).filterIsInstance<Button>().single { it.text.toString() == text }
}

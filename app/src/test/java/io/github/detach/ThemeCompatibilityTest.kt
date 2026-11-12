package io.github.detach

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.RippleDrawable
import android.graphics.drawable.StateListDrawable
import android.os.Build
import android.os.Bundle
import android.provider.Settings as SystemSettings
import android.view.ContextThemeWrapper
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@Suppress("DEPRECATION")
class ThemeCompatibilityTest {
    private val context get() = RuntimeEnvironment.getApplication()

    @Before fun resetMotion() {
        SystemSettings.Global.putFloat(context.contentResolver, SystemSettings.Global.ANIMATOR_DURATION_SCALE, 0f)
    }

    @Test fun `both system palettes provide readable text and visible control boundaries`() {
        for (theme in themes()) {
            val ui = Ui(theme)
            assertEquals(255, Color.alpha(ui.bg))
            assertEquals(255, Color.alpha(ui.surface))
            for (background in listOf(ui.bg, ui.surface)) {
                assertContrast(ui.fg, background, 4.5)
                assertContrast(ui.sub, background, 4.5)
                assertContrast(ui.accent, background, 4.5)
                assertContrast(ui.controlOutline, background, 3.0)
            }
            assertContrast(ui.onAccent, ui.accent, 4.5)
            assertContrast(ui.disabledInk, ui.disabledSurface, 4.5)
        }
    }

    @Test fun `checked unchecked and disabled switch states have distinct visible track and thumb colors`() {
        for (theme in themes()) {
            val ui = Ui(theme)
            val toggle = ui.toggle(false, "YouTube protection")
            val controller = Robolectric.buildActivity(ThemeCompatibilityActivity::class.java).setup()
            controller.get().setContentView(toggle)
            val tracks = mutableListOf<Int>()
            val thumbs = mutableListOf<Int>()
            for (enabled in listOf(true, false)) {
                for (checked in listOf(true, false)) {
                    toggle.isEnabled = enabled
                    toggle.isChecked = checked
                    val track = toggle.trackTintList!!.getColorForState(toggle.drawableState, Color.TRANSPARENT)
                    val thumb = toggle.thumbTintList!!.getColorForState(toggle.drawableState, Color.TRANSPARENT)
                    assertContrast(thumb, track, 3.0)
                    assertEquals(255, Color.alpha(track))
                    assertEquals(255, Color.alpha(thumb))
                    tracks += track
                    thumbs += thumb
                    val info = toggle.createAccessibilityNodeInfo()
                    assertTrue(info.isCheckable)
                    assertEquals(checked, info.isChecked)
                    assertEquals(enabled, toggle.isEnabled)
                    assertEquals(enabled, info.isEnabled)
                    assertEquals("YouTube protection", info.contentDescription)
                }
            }
            assertEquals(4, tracks.toSet().size)
            assertNotEquals(thumbs[0], thumbs[1])
            assertNotEquals(thumbs[2], thumbs[3])
            controller.pause().stop().destroy()
        }
    }

    @Test fun `switch track fill is opaque rather than inheriting the framework translucent track`() {
        for (theme in themes()) {
            val toggle = Ui(theme).toggle(true, "Protection")
            val track = (toggle.trackDrawable as LayerDrawable).getDrawable(0) as GradientDrawable
            assertEquals(255, Color.alpha(track.color!!.defaultColor))
            assertEquals(android.widget.Switch(theme).trackDrawable.intrinsicHeight, toggle.trackDrawable.intrinsicHeight)
        }
    }

    @Test fun `enabled and disabled button labels remain readable without making the whole view translucent`() {
        for (theme in themes()) {
            val ui = Ui(theme)
            val primary = ui.primary("Continue") { }
            assertEquals(ui.onAccent, primary.currentTextColor)
            primary.isEnabled = false
            assertEquals(ui.disabledInk, primary.currentTextColor)
            assertEquals(1f, primary.alpha, 0f)
            val disabledBackground = (primary.background as StateListDrawable).current as GradientDrawable
            assertEquals(ui.disabledSurface, disabledBackground.color!!.defaultColor)
            assertContrast(primary.currentTextColor, disabledBackground.color!!.defaultColor, 4.5)
            for (button in listOf(ui.secondary("Cancel") { }, ui.quiet("Details") { })) {
                assertContrast(button.currentTextColor, ui.bg, 4.5)
                button.isEnabled = false
                assertEquals(ui.disabledInk, button.currentTextColor)
                assertContrast(button.currentTextColor, ui.bg, 4.5)
                assertFalse(button.background.current is RippleDrawable)
                button.isEnabled = true
                assertTrue(button.background.current is RippleDrawable)
                assertTrue(button.minimumHeight >= ui.dp(48))
            }
            primary.isEnabled = true
            assertEquals(ui.onAccent, primary.currentTextColor)
        }
    }

    @Test fun `primary feedback contrasts with its fill and preserves label contrast in both modes`() {
        for (theme in themes()) {
            val ui = Ui(theme)
            val button = ui.primary("Pause protection") { }
            button.isPressed = true
            val ripple = button.background.current as RippleDrawable
            val highlight = ReflectionHelpers.getField<ColorStateList>(ripple.constantState, "mColor").defaultColor
            val pressedFill = composite(highlight, ui.accent)
            assertNotEquals(ui.accent, pressedFill)
            assertTrue(Color.alpha(highlight) > 0)
            assertContrast(ui.onAccent, pressedFill, 4.5)
            button.isPressed = false
            button.isFocusableInTouchMode = true
            button.requestFocus()
            assertTrue(button.isFocused)
            val focused = button.background.current
            button.clearFocus()
            assertNotEquals(focused, button.background.current)
            assertEquals(1f, button.alpha, 0f)
        }
    }

    @Test fun `text icons and password inputs expose legible disabled states without altering masking`() {
        for (theme in themes()) {
            val ui = Ui(theme)
            val label = ui.note("Protection preferences")
            label.isEnabled = false
            assertEquals(ui.disabledInk, label.currentTextColor)
            val icon = ui.icon(R.drawable.ic_lock)
            icon.isEnabled = false
            assertEquals(ui.disabledInk, icon.imageTintList!!.getColorForState(icon.drawableState, 0))
            val input = passwordEditText(theme, "Password")
            input.setText("private")
            input.isEnabled = false
            assertEquals(ui.disabledInk, input.currentTextColor)
            assertEquals(ui.disabledInk, input.currentHintTextColor)
            assertEquals(ui.disabledInk, input.backgroundTintList!!.getColorForState(input.drawableState, 0))
            assertSame(Asterisks, input.transformationMethod)
            assertEquals("*******", input.transformationMethod.getTransformation(input.text, input).toString())
            assertEquals(View.IMPORTANT_FOR_AUTOFILL_NO, input.importantForAutofill)
        }
    }

    @Test fun `disabled group emphasis never compounds state colors with a translucent parent`() {
        val group = LinearLayout(context).apply { alpha = 0.5f }
        Motion.emphasis(group, false)
        assertEquals(1f, group.alpha, 0f)
        assertFalse(group.isEnabled)
        Motion.emphasis(group, true)
        assertEquals(1f, group.alpha, 0f)
        assertTrue(group.isEnabled)
    }

    @Test fun `native alert themes resolve palette attributes for both system modes`() {
        for (theme in themes()) {
            val ui = Ui(theme)
            val dialog = AlertDialog.Builder(theme).setTitle("Confirm").setMessage("A deliberate change").create()
            val attributes = dialog.context.obtainStyledAttributes(intArrayOf(android.R.attr.textColorPrimary,
                android.R.attr.textColorSecondary, android.R.attr.colorAccent, android.R.attr.colorBackground,
                android.R.attr.colorControlActivated, android.R.attr.colorError))
            try {
                assertEquals(ui.fg, attributes.getColorStateList(0)!!.defaultColor)
                assertEquals(ui.sub, attributes.getColorStateList(1)!!.defaultColor)
                assertEquals(ui.accent, attributes.getColor(2, 0))
                assertEquals(ui.bg, attributes.getColor(3, 0))
                assertEquals(ui.accent, attributes.getColor(4, 0))
                assertEquals(theme.getColor(R.color.error), attributes.getColor(5, 0))
            } finally {
                attributes.recycle()
            }
        }
    }

    @Test fun `floating dialog themes follow system palette and retain native dialog behavior`() {
        for (night in listOf(false, true)) {
            val theme = theme(night, R.style.Theme_Detach_Dialog)
            val attributes = theme.obtainStyledAttributes(intArrayOf(android.R.attr.windowIsFloating,
                android.R.attr.windowNoTitle, android.R.attr.colorBackground, android.R.attr.windowLightStatusBar))
            try {
                assertTrue(attributes.getBoolean(0, false))
                assertTrue(attributes.getBoolean(1, false))
                assertEquals(Ui(theme).bg, attributes.getColor(2, 0))
                assertEquals(!night, attributes.getBoolean(3, night))
            } finally {
                attributes.recycle()
            }
        }
    }

    @Test @Config(qualifiers = "w320dp-h360dp") fun `large font native alert titles and actions wrap and stack in a small window`() {
        RuntimeEnvironment.setFontScale(2f)
        val controller = Robolectric.buildActivity(ThemeCompatibilityActivity::class.java).setup()
        val activity = controller.get()
        val ui = Ui(activity)
        val dialog = AlertDialog.Builder(activity)
            .setTitle("Keep protection enabled while adjusting preferences")
            .setMessage("Content remains readable with the system text size.")
            .setPositiveButton("Keep protection enabled", null)
            .setNegativeButton("Return to preferences", null)
            .create()
        dialog.show()
        ui.style(dialog)
        val positive = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
        val negative = dialog.getButton(AlertDialog.BUTTON_NEGATIVE)
        assertEquals(LinearLayout.VERTICAL, (positive.parent as LinearLayout).orientation)
        assertEquals(ViewGroup.LayoutParams.MATCH_PARENT, positive.layoutParams.width)
        assertEquals(ViewGroup.LayoutParams.MATCH_PARENT, negative.layoutParams.width)
        assertTrue(positive.minimumHeight >= ui.dp(48))
        positive.isEnabled = false
        assertEquals(ui.disabledInk, positive.currentTextColor)
        val titleId = activity.resources.getIdentifier("alertTitle", "id", "android")
        assertTrue(dialog.findViewById<android.widget.TextView>(titleId)!!.maxLines > 1)
        assertEquals(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE,
            dialog.window!!.attributes.softInputMode and WindowManager.LayoutParams.SOFT_INPUT_MASK_ADJUST)
        dialog.dismiss()
        controller.pause().stop().destroy()
    }

    @Test @Config(qualifiers = "w240dp-h320dp") fun `dialog pages use the current small window and scroll enlarged content`() {
        RuntimeEnvironment.setFontScale(2f)
        val controller = Robolectric.buildActivity(ThemeCompatibilityActivity::class.java).setup()
        val activity = controller.get()
        val ui = Ui(activity)
        val column = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            repeat(8) { addView(ui.text("A deliberate change stays readable", 23f)) }
            addView(ui.primary("Keep protection enabled") { })
        }
        val scroll = ScrollView(activity).apply { addView(column) }
        ui.dialogPage(activity, scroll)
        assertEquals(ui.dp(208), activity.window.attributes.width)
        val viewport = scroll.parent as ViewGroup
        viewport.measure(View.MeasureSpec.makeMeasureSpec(ui.dp(208), View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        viewport.layout(0, 0, viewport.measuredWidth, viewport.measuredHeight)
        assertTrue(viewport.height <= ui.dp(272))
        assertTrue(column.height > scroll.height)
        assertEquals(1f, scroll.alpha, 0f)
        assertEquals(0f, scroll.translationY, 0f)
        assertEquals(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE,
            activity.window.attributes.softInputMode and WindowManager.LayoutParams.SOFT_INPUT_MASK_ADJUST)
        controller.pause().stop().destroy()
    }

    @Test @Config(sdk = [28, 35], qualifiers = "notnight") fun `day system bars use dark icons`() {
        assertSystemBars(false)
    }

    @Test @Config(sdk = [28, 35], qualifiers = "night") fun `night system bars use light icons`() {
        assertSystemBars(true)
    }

    @Test @Config(qualifiers = "notnight") fun `system theme recreation replaces control colors in both directions`() {
        val controller = Robolectric.buildActivity(ThemeCompatibilityActivity::class.java).setup()
        val original = controller.get()
        val day = original.action.currentTextColor
        RuntimeEnvironment.setQualifiers("+night")
        controller.recreate()
        val night = controller.get()
        assertNotEquals(day, night.action.currentTextColor)
        assertEquals(night.getColor(R.color.on_accent), night.action.currentTextColor)
        assertEquals(night.getColor(R.color.accent), night.toggle.trackTintList!!.getColorForState(night.toggle.drawableState, 0))
        RuntimeEnvironment.setQualifiers("+notnight")
        controller.recreate()
        assertEquals(day, controller.get().action.currentTextColor)
        assertNotEquals(original, controller.get())
        controller.pause().stop().destroy()
    }

    private fun assertSystemBars(night: Boolean) {
        val controller = Robolectric.buildActivity(ThemeCompatibilityActivity::class.java).setup()
        val activity = controller.get()
        val ui = Ui(activity)
        ui.systemBars(activity.window)
        if (Build.VERSION.SDK_INT >= 30) {
            val appearance = activity.window.insetsController!!.systemBarsAppearance
            assertEquals(!night, appearance and WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS != 0)
            assertEquals(!night, appearance and WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS != 0)
        } else {
            assertEquals(!night, activity.window.decorView.systemUiVisibility and View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR != 0)
            assertEquals(!night, activity.window.decorView.systemUiVisibility and View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR != 0)
            assertEquals(ui.bg, activity.window.statusBarColor)
            assertEquals(ui.bg, activity.window.navigationBarColor)
        }
        controller.pause().stop().destroy()
    }

    private fun themes() = listOf(theme(false), theme(true))

    private fun theme(night: Boolean, style: Int = R.style.Theme_Detach): Context {
        val configuration = Configuration(context.resources.configuration).apply {
            uiMode = uiMode and Configuration.UI_MODE_NIGHT_MASK.inv() or
                if (night) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
        }
        return ContextThemeWrapper(context.createConfigurationContext(configuration), style)
    }

    private fun assertContrast(foreground: Int, background: Int, minimum: Double) {
        val lighter = maxOf(Color.luminance(foreground).toDouble(), Color.luminance(background).toDouble())
        val darker = minOf(Color.luminance(foreground).toDouble(), Color.luminance(background).toDouble())
        assertTrue("Contrast ${(lighter + 0.05) / (darker + 0.05)} must be at least $minimum",
            (lighter + 0.05) / (darker + 0.05) >= minimum)
    }

    private fun composite(foreground: Int, background: Int): Int {
        val opacity = Color.alpha(foreground) / 255f
        return Color.rgb((Color.red(foreground) * opacity + Color.red(background) * (1f - opacity)).toInt(),
            (Color.green(foreground) * opacity + Color.green(background) * (1f - opacity)).toInt(),
            (Color.blue(foreground) * opacity + Color.blue(background) * (1f - opacity)).toInt())
    }
}

class ThemeCompatibilityActivity : Activity() {
    lateinit var action: Button
    lateinit var toggle: android.widget.Switch

    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(R.style.Theme_Detach)
        super.onCreate(savedInstanceState)
        val ui = Ui(this)
        action = ui.primary("Continue") { }
        toggle = ui.toggle(true, "Protection")
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(ui.bg)
            addView(action)
            addView(toggle)
        })
        ui.systemBars(window)
    }
}

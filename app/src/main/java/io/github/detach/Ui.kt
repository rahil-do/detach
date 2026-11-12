package io.github.detach

import android.animation.ValueAnimator
import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.RippleDrawable
import android.graphics.drawable.StateListDrawable
import android.os.Build
import android.provider.Settings
import android.transition.ChangeBounds
import android.transition.Fade
import android.transition.TransitionManager
import android.transition.TransitionSet
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowInsetsController
import android.view.WindowManager
import android.view.accessibility.AccessibilityNodeInfo
import android.view.animation.PathInterpolator
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView

@Suppress("DEPRECATION")
class Ui(private val context: Context) {
    val bg = context.getColor(R.color.window)
    val fg = context.getColor(R.color.ink)
    val sub = context.getColor(R.color.secondary_ink)
    val line = context.getColor(R.color.rule)
    val surface = context.getColor(R.color.surface)
    val accent = context.getColor(R.color.accent)
    val onAccent = context.getColor(R.color.on_accent)
    val disabledInk = context.getColor(R.color.disabled_ink)
    val disabledSurface = context.getColor(R.color.disabled_surface)
    val controlOutline = context.getColor(R.color.control_outline)
    val caution = context.getColor(R.color.caution)
    val regular: Typeface = Typeface.create("sans-serif", Typeface.NORMAL)
    val medium: Typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)

    fun dp(value: Int) = (value * context.resources.displayMetrics.density).toInt()

    fun colors(enabled: Int, disabled: Int = disabledInk) = ColorStateList(
        arrayOf(intArrayOf(-android.R.attr.state_enabled), intArrayOf()),
        intArrayOf(disabled, enabled),
    )

    fun text(label: CharSequence, size: Float = 15f, color: Int = fg, strong: Boolean = false) = TextView(context).apply {
        text = label
        textSize = size
        setTextColor(colors(color))
        typeface = if (strong) medium else regular
        includeFontPadding = true
        setLineSpacing(dp(3).toFloat(), 1f)
    }

    fun note(label: CharSequence) = text(label, 13f, sub)

    fun section(label: String) = text(label, 12f, sub, true).apply {
        letterSpacing = 0.04f
        setPadding(0, dp(28), 0, dp(12))
        isAccessibilityHeading = true
    }

    fun shape(fill: Int = surface, stroke: Int? = null, radius: Int = 10) = GradientDrawable().apply {
        setColor(fill)
        cornerRadius = dp(radius).toFloat()
        if (stroke != null) setStroke(dp(1), stroke)
    }

    fun ripple(fill: Int = Color.TRANSPARENT, stroke: Int? = null, highlight: Int = context.getColor(R.color.press)) = RippleDrawable(
        ColorStateList.valueOf(highlight), shape(fill, stroke), shape(Color.WHITE),
    )

    private fun button(label: String, action: () -> Unit) = Button(context, null, android.R.attr.borderlessButtonStyle).apply {
        text = label
        textSize = 14f
        typeface = medium
        isAllCaps = false
        isSingleLine = false
        includeFontPadding = true
        gravity = Gravity.CENTER
        minHeight = dp(48)
        minimumHeight = dp(48)
        minWidth = dp(48)
        minimumWidth = dp(48)
        setPadding(dp(16), dp(8), dp(16), dp(8))
        stateListAnimator = null
        backgroundTintList = null
        setOnClickListener { action() }
    }

    fun primary(label: String, action: () -> Unit) = button(label, action).apply { asPrimary(this) }

    fun secondary(label: String, action: () -> Unit) = button(label, action).apply { asSecondary(this) }

    fun quiet(label: String, action: () -> Unit) = button(label, action).apply { asQuiet(this) }

    /**
     * Button looks are applied as complete fill + label pairs. Never recolor only the label of a
     * framework button: OEM and dynamic-color themes draw their own fills (for example a pale
     * blue pill on Android 16+ Motorola dialogs), which made accent text unreadable.
     */
    private fun asPrimary(button: Button) = button.apply {
        resetChrome(this)
        setTextColor(colors(onAccent))
        background = buttonBackground(accent, disabledFill = disabledSurface, focusStroke = onAccent,
            highlight = context.getColor(R.color.primary_press))
    }

    private fun asSecondary(button: Button) = button.apply {
        resetChrome(this)
        setTextColor(colors(fg))
        background = buttonBackground(stroke = controlOutline)
    }

    private fun asQuiet(button: Button) = button.apply {
        resetChrome(this)
        setTextColor(colors(accent))
        background = buttonBackground()
    }

    private fun resetChrome(button: Button) = button.apply {
        backgroundTintList = null
        stateListAnimator = null
        elevation = 0f
        translationZ = 0f
        typeface = medium
        isAllCaps = false
    }

    private fun buttonBackground(
        fill: Int = Color.TRANSPARENT,
        stroke: Int? = null,
        disabledFill: Int = Color.TRANSPARENT,
        focusStroke: Int = accent,
        highlight: Int = context.getColor(R.color.press),
    ) = StateListDrawable().apply {
        addState(intArrayOf(-android.R.attr.state_enabled), shape(disabledFill, stroke))
        addState(intArrayOf(android.R.attr.state_focused), ripple(fill, focusStroke, highlight))
        addState(intArrayOf(), ripple(fill, stroke, highlight))
    }

    fun toggle(checked: Boolean, label: String) = Switch(context).apply {
        isChecked = checked
        contentDescription = label
        minHeight = dp(48)
        minimumHeight = dp(48)
        minimumWidth = dp(56)
        setPaddingRelative(dp(8), 0, 0, 0)
        splitTrack = false
        trackDrawable = LayerDrawable(arrayOf(shape(Color.WHITE, radius = 7).apply {
            setSize(-1, dp(14))
        })).apply {
            setLayerInsetRelative(0, dp(4), 0, dp(4), 0)
            setLayerGravity(0, Gravity.CENTER_VERTICAL or Gravity.FILL_HORIZONTAL)
        }
        val checkedState = intArrayOf(android.R.attr.state_checked)
        val states = arrayOf(intArrayOf(-android.R.attr.state_enabled, android.R.attr.state_checked),
            intArrayOf(-android.R.attr.state_enabled), checkedState, intArrayOf())
        trackTintList = ColorStateList(states, intArrayOf(context.getColor(R.color.disabled_checked_track), disabledSurface, accent, controlOutline))
        thumbTintList = ColorStateList(states, intArrayOf(context.getColor(R.color.disabled_checked_thumb), disabledInk, onAccent, bg))
    }

    fun icon(resource: Int, size: Int = 24, tint: Int = fg) = ImageView(context).apply {
        setImageResource(resource)
        imageTintList = colors(tint)
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        layoutParams = LinearLayout.LayoutParams(dp(size), dp(size))
    }

    fun hairline() = View(context).apply {
        setBackgroundColor(line)
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1))
    }

    fun space(height: Int) = View(context).apply {
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        layoutParams = LinearLayout.LayoutParams(1, dp(height))
    }

    fun disclosure(label: String, expanded: Boolean, action: () -> Unit) = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = dp(52)
        background = ripple()
        isClickable = true
        isFocusable = true
        addView(text(label, 14f, accent, true), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        addView(icon(R.drawable.ic_chevron, 20, accent).apply { rotation = if (expanded) 180f else 0f })
        isSelected = expanded
        accessibilityDelegate = object : View.AccessibilityDelegate() {
            override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfo) {
                super.onInitializeAccessibilityNodeInfo(host, info)
                info.className = Button::class.java.name
                info.addAction(if (host.isSelected) AccessibilityNodeInfo.AccessibilityAction.ACTION_COLLAPSE else AccessibilityNodeInfo.AccessibilityAction.ACTION_EXPAND)
            }

            override fun performAccessibilityAction(host: View, action: Int, args: android.os.Bundle?): Boolean {
                if (action == AccessibilityNodeInfo.ACTION_EXPAND) return !host.isSelected && host.performClick()
                if (action == AccessibilityNodeInfo.ACTION_COLLAPSE) return host.isSelected && host.performClick()
                return super.performAccessibilityAction(host, action, args)
            }
        }
        if (Build.VERSION.SDK_INT >= 30) stateDescription = if (expanded) "Expanded" else "Collapsed"
        setOnClickListener { action() }
    }

    fun updateDisclosure(header: ViewGroup, expanded: Boolean) {
        header.isSelected = expanded
        if (Build.VERSION.SDK_INT >= 30) header.stateDescription = if (expanded) "Expanded" else "Collapsed"
        val chevron = header.getChildAt(1)
        chevron.animate().cancel()
        if (Motion.enabled(context)) chevron.animate().rotation(if (expanded) 180f else 0f).setDuration(180).setStartDelay(0).start()
        else chevron.rotation = if (expanded) 180f else 0f
    }

    fun panel() = shape(bg, line, 18)

    fun style(field: EditText) = field.apply {
        setTextColor(colors(fg))
        setHintTextColor(colors(sub))
        textSize = 16f
        typeface = regular
        minimumHeight = dp(56)
        backgroundTintList = ColorStateList(
            arrayOf(intArrayOf(-android.R.attr.state_enabled), intArrayOf(android.R.attr.state_focused), intArrayOf()),
            intArrayOf(disabledInk, accent, controlOutline),
        )
        setPaddingRelative(dp(4), dp(14), dp(4), dp(14))
    }

    fun style(dialog: AlertDialog) {
        dialog.window?.setBackgroundDrawable(panel())
        for (which in intArrayOf(AlertDialog.BUTTON_POSITIVE, AlertDialog.BUTTON_NEGATIVE, AlertDialog.BUTTON_NEUTRAL)) {
            dialog.getButton(which)?.apply {
                if (which == AlertDialog.BUTTON_POSITIVE) asPrimary(this) else asQuiet(this)
                isSingleLine = false
                includeFontPadding = true
                minimumHeight = dp(48)
                setPaddingRelative(dp(16), dp(8), dp(16), dp(8))
            }
        }
        // Separate the filled confirm action from its neighbour and give the row breathing room
        // from the rounded dialog edge.
        dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.let { positive ->
            (positive.layoutParams as? ViewGroup.MarginLayoutParams)?.marginStart = dp(8)
            (positive.parent as? ViewGroup)?.setPaddingRelative(dp(16), dp(8), dp(16), dp(16))
        }
        dialog.findViewById<TextView>(android.R.id.message)?.setTextColor(sub)
        val titleId = context.resources.getIdentifier("alertTitle", "id", "android")
        dialog.findViewById<TextView>(titleId)?.apply {
            setTextColor(fg)
            typeface = medium
            isSingleLine = false
            maxLines = Int.MAX_VALUE
        }
        dialog.window?.let { window ->
            systemBars(window)
            window.setSoftInputMode(window.attributes.softInputMode and WindowManager.LayoutParams.SOFT_INPUT_MASK_ADJUST.inv() or
                WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        }
        if (context.resources.configuration.screenWidthDp < 360 || context.resources.configuration.fontScale >= 1.3f) {
            val buttons = listOf(AlertDialog.BUTTON_NEGATIVE, AlertDialog.BUTTON_NEUTRAL, AlertDialog.BUTTON_POSITIVE)
                .mapNotNull { dialog.getButton(it) }
            (buttons.firstOrNull()?.parent as? LinearLayout)?.apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.END
                for (index in 0 until childCount) {
                    if (getChildAt(index) !is Button) getChildAt(index).visibility = View.GONE
                }
                buttons.forEachIndexed { index, button ->
                    button.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                        .apply { if (index > 0) topMargin = dp(8) }
                }
            }
        }
    }

    fun systemBars(window: Window) {
        val decor = window.decorView
        val light = context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK != Configuration.UI_MODE_NIGHT_YES
        window.statusBarColor = bg
        window.navigationBarColor = bg
        if (Build.VERSION.SDK_INT >= 29) window.isNavigationBarContrastEnforced = true
        if (Build.VERSION.SDK_INT >= 30) {
            val appearance = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
            window.insetsController?.setSystemBarsAppearance(if (light) appearance else 0, appearance)
        } else {
            val appearance = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
            decor.systemUiVisibility = if (light) decor.systemUiVisibility or appearance
            else decor.systemUiVisibility and appearance.inv()
        }
    }

    fun dialogPage(activity: Activity, content: View) {
        activity.window.setBackgroundDrawable(panel())
        systemBars(activity.window)
        val viewport = object : FrameLayout(activity) {
            override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
                val available = (dp(resources.configuration.screenHeightDp) - dp(48)).coerceAtLeast(1)
                val height = if (View.MeasureSpec.getMode(heightMeasureSpec) == View.MeasureSpec.UNSPECIFIED) available
                else minOf(available, View.MeasureSpec.getSize(heightMeasureSpec))
                super.onMeasure(widthMeasureSpec, View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.AT_MOST))
            }
        }.apply {
            addView(content, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        activity.setContentView(viewport)
        val available = dp(context.resources.configuration.screenWidthDp).coerceAtLeast(1)
        activity.window.setLayout(minOf((available - dp(32)).coerceAtLeast(1), dp(420)), ViewGroup.LayoutParams.WRAP_CONTENT)
        activity.window.setSoftInputMode(activity.window.attributes.softInputMode and WindowManager.LayoutParams.SOFT_INPUT_MASK_ADJUST.inv() or
            WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        Motion.enter(content)
    }
}

object Motion {
    private val easing = PathInterpolator(0.2f, 0f, 0f, 1f)

    fun enabled(context: Context): Boolean = ValueAnimator.areAnimatorsEnabled() &&
        Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) > 0f

    fun enter(view: View, delay: Long = 0) {
        view.animate().cancel()
        view.alpha = 1f
        view.translationY = 0f
        if (!enabled(view.context)) return
        view.alpha = 0f
        view.translationY = 8 * view.resources.displayMetrics.density
        view.animate().alpha(1f).translationY(0f).setStartDelay(delay.coerceIn(0, 80))
            .setDuration(220).setInterpolator(easing).start()
    }

    fun section(container: ViewGroup) {
        TransitionManager.endTransitions(container)
        if (enabled(container.context)) {
            TransitionManager.beginDelayedTransition(container, TransitionSet().apply {
                ordering = TransitionSet.ORDERING_TOGETHER
                addTransition(ChangeBounds())
                addTransition(Fade())
                duration = 180
                interpolator = easing
            })
        }
    }

    fun emphasis(view: View, usable: Boolean) {
        view.animate().cancel()
        view.isEnabled = usable
        view.alpha = 1f
    }
}

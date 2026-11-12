package io.github.detach

import android.animation.ValueAnimator
import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Build
import android.provider.Settings
import android.transition.ChangeBounds
import android.transition.Fade
import android.transition.TransitionManager
import android.transition.TransitionSet
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import android.view.animation.PathInterpolator
import android.widget.Button
import android.widget.EditText
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
    val caution = context.getColor(R.color.caution)
    val regular: Typeface = Typeface.create("sans-serif", Typeface.NORMAL)
    val medium: Typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)

    fun dp(value: Int) = (value * context.resources.displayMetrics.density).toInt()

    fun text(label: CharSequence, size: Float = 15f, color: Int = fg, strong: Boolean = false) = TextView(context).apply {
        text = label
        textSize = size
        setTextColor(color)
        typeface = if (strong) medium else regular
        includeFontPadding = false
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

    fun ripple(fill: Int = Color.TRANSPARENT, stroke: Int? = null) = RippleDrawable(
        ColorStateList.valueOf(context.getColor(R.color.press)), shape(fill, stroke), shape(Color.WHITE),
    )

    private fun button(label: String, action: () -> Unit) = Button(context, null, android.R.attr.borderlessButtonStyle).apply {
        text = label
        textSize = 14f
        typeface = medium
        isAllCaps = false
        isSingleLine = false
        gravity = Gravity.CENTER
        minHeight = dp(48)
        minimumHeight = dp(48)
        minWidth = dp(48)
        minimumWidth = dp(48)
        setPadding(dp(16), dp(8), dp(16), dp(8))
        stateListAnimator = null
        setOnClickListener { action() }
    }

    fun primary(label: String, action: () -> Unit) = button(label, action).apply {
        setTextColor(onAccent)
        background = ripple(accent)
    }

    fun secondary(label: String, action: () -> Unit) = button(label, action).apply {
        setTextColor(fg)
        background = ripple(stroke = line)
    }

    fun quiet(label: String, action: () -> Unit) = button(label, action).apply {
        setTextColor(accent)
        background = ripple()
    }

    fun toggle(checked: Boolean, label: String) = Switch(context).apply {
        isChecked = checked
        contentDescription = label
        minHeight = dp(48)
        minimumHeight = dp(48)
        minimumWidth = dp(56)
        setPadding(dp(8), 0, 0, 0)
        val checkedState = intArrayOf(android.R.attr.state_checked)
        trackTintList = ColorStateList(arrayOf(checkedState, intArrayOf()), intArrayOf(accent, line))
        thumbTintList = ColorStateList(arrayOf(checkedState, intArrayOf()), intArrayOf(onAccent, sub))
    }

    fun icon(resource: Int, size: Int = 24, tint: Int = fg) = ImageView(context).apply {
        setImageResource(resource)
        imageTintList = ColorStateList.valueOf(tint)
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
        setTextColor(fg)
        setHintTextColor(sub)
        textSize = 16f
        typeface = regular
        minimumHeight = dp(56)
        backgroundTintList = ColorStateList(arrayOf(intArrayOf(android.R.attr.state_focused), intArrayOf()), intArrayOf(accent, line))
        setPadding(dp(4), dp(14), dp(4), dp(14))
    }

    fun style(dialog: AlertDialog) {
        dialog.window?.setBackgroundDrawable(panel())
        for (which in intArrayOf(AlertDialog.BUTTON_POSITIVE, AlertDialog.BUTTON_NEGATIVE, AlertDialog.BUTTON_NEUTRAL)) {
            dialog.getButton(which)?.apply {
                setTextColor(accent)
                typeface = medium
                isAllCaps = false
                minimumHeight = dp(48)
            }
        }
        dialog.findViewById<TextView>(android.R.id.message)?.setTextColor(sub)
        val titleId = context.resources.getIdentifier("alertTitle", "id", "android")
        dialog.findViewById<TextView>(titleId)?.apply {
            setTextColor(fg)
            typeface = medium
        }
    }

    fun dialogPage(activity: Activity, content: View) {
        activity.window.setBackgroundDrawable(panel())
        activity.setContentView(content)
        activity.window.setLayout(minOf(context.resources.displayMetrics.widthPixels - dp(32), dp(420)), ViewGroup.LayoutParams.WRAP_CONTENT)
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
        val target = if (usable) 1f else 0.5f
        if (enabled(view.context)) view.animate().alpha(target).setDuration(150).setStartDelay(0).start()
        else view.alpha = target
    }
}

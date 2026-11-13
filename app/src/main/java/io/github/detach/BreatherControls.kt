package io.github.detach

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.hardware.biometrics.BiometricManager
import android.os.Build
import android.text.format.DateFormat
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.NumberPicker
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.util.Date
import android.provider.Settings as SystemSettings

/**
 * A breather is deliberately general: it unblocks every Detach restriction for a short,
 * authenticated window, then protection returns on its own. Length is chosen on the native
 * Android number wheel.
 */
object BreatherControls {
    const val MIN_MINUTES = 1
    const val MAX_MINUTES = 60
    const val DEFAULT_MINUTES = 10

    fun build(activity: Activity, onChanged: () -> Unit): View {
        val ui = Ui(activity)
        val column = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            tag = "breather-controls"
        }
        fun render() {
            column.removeAllViews()
            val remaining = Breather.remainingMs(activity)
            column.addView(ui.section("BREATHER"))
            column.addView(ui.note(if (remaining > 0) {
                val minutes = (remaining + 59_999L) / 60_000L
                "Breather on · $minutes min left"
            } else {
                "Unblock Instagram & YouTube for a while."
            }).apply { accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE })
            column.addView(ui.space(12))
            column.addView(ui.secondary(if (remaining > 0) "Extend breather" else "Start a breather") {
                if (!Auth.available(activity)) {
                    authenticationRequired(activity)
                } else {
                    choose(activity) {
                        render()
                        onChanged()
                    }
                }
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            if (remaining > 0) {
                column.addView(ui.space(8))
                column.addView(ui.quiet("End breather now") {
                    Breather.end(activity)
                    render()
                    onChanged()
                }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            }
        }
        render()
        return column
    }

    private fun authenticationRequired(activity: Activity) {
        val builder = AlertDialog.Builder(activity)
            .setTitle("Authentication required")
            .setMessage(if (Build.VERSION.SDK_INT >= 29) {
                "Set a Detach password or enroll biometrics first."
            } else {
                "Set a Detach password first."
            })
            .setNegativeButton("Back", null)
        if (Build.VERSION.SDK_INT >= 29) {
            builder.setPositiveButton("Enroll biometrics") { _, _ ->
                val intent = if (Build.VERSION.SDK_INT >= 30) {
                    Intent(SystemSettings.ACTION_BIOMETRIC_ENROLL).putExtra(
                        SystemSettings.EXTRA_BIOMETRIC_AUTHENTICATORS_ALLOWED,
                        BiometricManager.Authenticators.BIOMETRIC_WEAK,
                    )
                } else {
                    Intent(SystemSettings.ACTION_SECURITY_SETTINGS)
                }
                runCatching { activity.startActivity(intent) }.onFailure {
                    Toast.makeText(activity, "Open Security settings to add a fingerprint.", Toast.LENGTH_LONG).show()
                }
            }
        }
        val dialog = builder.create()
        dialog.setOnShowListener { Ui(activity).style(dialog) }
        dialog.show()
    }

    /** The native wheel used to pick a breather length; exposed for tests. */
    internal fun minutePicker(activity: Activity, ui: Ui, initial: Int) = NumberPicker(activity).apply {
        tag = "breather-minutes"
        minValue = MIN_MINUTES
        maxValue = MAX_MINUTES
        value = initial.coerceIn(MIN_MINUTES, MAX_MINUTES)
        wrapSelectorWheel = false
        // Wheel-only: avoids an unexpected keyboard and keeps values always in range.
        descendantFocusability = NumberPicker.FOCUS_BLOCK_DESCENDANTS
        contentDescription = "Breather length in minutes"
        if (Build.VERSION.SDK_INT >= 29) {
            textColor = ui.fg
            textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 26f, resources.displayMetrics)
            selectionDividerHeight = ui.dp(2)
        }
    }

    private fun endsAt(activity: Activity, minutes: Int): String =
        "Ends at ${DateFormat.getTimeFormat(activity).format(Date(System.currentTimeMillis() + minutes * 60_000L))}"

    private fun choose(activity: Activity, onChanged: () -> Unit) {
        val ui = Ui(activity)
        val remaining = Breather.remainingMs(activity)
        val initial = if (remaining > 0) ((remaining + 59_999L) / 60_000L).toInt() else DEFAULT_MINUTES
        val content = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(ui.dp(24), ui.dp(4), ui.dp(24), ui.dp(8))
        }
        content.addView(ui.note(if (remaining > 0) {
            "Replaces the time left."
        } else {
            "Protection returns on its own."
        }))
        val picker = minutePicker(activity, ui, initial)
        val unit = ui.text("min", 18f, ui.sub, strong = true).apply {
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        val wheel = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, ui.dp(12), 0, ui.dp(4))
            addView(picker, LinearLayout.LayoutParams(ui.dp(96), ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(unit, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                marginStart = ui.dp(4)
            })
        }
        content.addView(wheel, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        val summary = ui.text(endsAt(activity, picker.value), 14f, ui.sub).apply {
            gravity = Gravity.CENTER
            tag = "breather-ends"
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        content.addView(summary, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        picker.setOnValueChangedListener { _, _, value -> summary.text = endsAt(activity, value) }

        val dialog = AlertDialog.Builder(activity)
            .setTitle(if (remaining > 0) "Extend your breather" else "Take a breather")
            .setView(ScrollView(activity).apply { addView(content) })
            .setPositiveButton("Authenticate & start", null)
            .setNegativeButton("Cancel", null)
            .create()
        dialog.setOnShowListener {
            ui.style(dialog)
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val minutes = picker.value.coerceIn(MIN_MINUTES, MAX_MINUTES)
                dialog.dismiss()
                if (!Auth.available(activity)) {
                    authenticationRequired(activity)
                    return@setOnClickListener
                }
                Breather.confirmAndBegin(activity, minutes, Breather.Feature.entries.toSet()) { started ->
                    if (started) {
                        onChanged()
                        BreatherIntro.play(activity, minutes)
                    } else {
                        Toast.makeText(activity, "Breather not started", Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
        dialog.show()
    }
}

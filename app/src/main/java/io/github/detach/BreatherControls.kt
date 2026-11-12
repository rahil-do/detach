package io.github.detach

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.res.ColorStateList
import android.hardware.biometrics.BiometricManager
import android.os.Build
import android.text.InputType
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Toast
import android.provider.Settings as SystemSettings

object BreatherControls {
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
                "Breather active · up to $minutes min left. Only selected features are temporarily unblocked."
            } else {
                "Temporarily unblock only what you choose for 1–60 minutes. Your usual settings and tamper protection stay unchanged."
            }).apply { accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE })
            column.addView(ui.space(12))
            column.addView(ui.secondary(if (remaining > 0) "Change or extend breather" else "Start a breather") {
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
                "A breather needs your fingerprint or Detach password every time. Set a Detach password in the app’s password settings, or enroll biometrics in Android settings. A phone PIN alone cannot start a breather."
            } else {
                "On this Android version, a breather needs your Detach password every time. Set a Detach password in the app’s password settings first. A phone PIN alone cannot start a breather."
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
                    Toast.makeText(activity, "Open Android security settings to enroll biometrics, or set a Detach password.", Toast.LENGTH_LONG).show()
                }
            }
        }
        val dialog = builder.create()
        dialog.setOnShowListener { Ui(activity).style(dialog) }
        dialog.show()
    }

    private fun choose(activity: Activity, onChanged: () -> Unit) {
        val ui = Ui(activity)
        val selected = Breather.activeFeatures(activity)
        val content = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(ui.dp(22), ui.dp(8), ui.dp(22), ui.dp(8))
        }
        content.addView(ui.note("Choose features to unblock temporarily. Everything else stays restricted. Starting or extending always requires authentication."))
        content.addView(ui.space(12))
        val durationLabel = ui.text("Duration in minutes (1–60)")
        val duration = ui.style(EditText(activity)).apply {
            id = View.generateViewId()
            inputType = InputType.TYPE_CLASS_NUMBER
            isSingleLine = true
            setText("5")
            contentDescription = "Breather duration in minutes, 1 to 60"
            tag = "breather-duration"
        }
        durationLabel.labelFor = duration.id
        content.addView(durationLabel)
        content.addView(duration)
        val choices = Breather.Feature.entries.associateWith { feature ->
            CheckBox(activity).apply {
                text = feature.label
                textSize = 15f
                typeface = ui.regular
                setTextColor(ui.fg)
                buttonTintList = ColorStateList(
                    arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                    intArrayOf(ui.accent, ui.sub),
                )
                minimumHeight = ui.dp(48)
                minimumWidth = ui.dp(48)
                isChecked = feature in selected
                tag = feature
                content.addView(this, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            }
        }
        val error = ui.note("").apply {
            setTextColor(ui.caution)
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        content.addView(error)
        val dialog = AlertDialog.Builder(activity)
            .setTitle("Choose your breather")
            .setView(ScrollView(activity).apply { addView(content) })
            .setPositiveButton("Authenticate & start", null)
            .setNegativeButton("Cancel", null)
            .create()
        dialog.setOnShowListener {
            ui.style(dialog)
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val minutes = duration.text.toString().toIntOrNull()
                val features = choices.filterValues { it.isChecked }.keys.toSet()
                when {
                    minutes == null || minutes !in 1..60 -> {
                        duration.error = "Choose 1 to 60 minutes"
                        duration.requestFocus()
                    }
                    features.isEmpty() -> error.text = "Choose at least one feature to unblock."
                    !Auth.available(activity) -> {
                        dialog.dismiss()
                        authenticationRequired(activity)
                    }
                    else -> {
                        dialog.dismiss()
                        Breather.confirmAndBegin(activity, minutes, features) { started ->
                            if (started) onChanged()
                            else Toast.makeText(activity, "Breather not started. No new access was granted.", Toast.LENGTH_LONG).show()
                        }
                    }
                }
            }
        }
        dialog.show()
    }
}

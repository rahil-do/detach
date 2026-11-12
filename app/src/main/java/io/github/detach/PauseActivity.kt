package io.github.detach

import android.app.Activity
import android.os.Bundle
import android.widget.ScrollView
import android.widget.LinearLayout
import android.widget.Toast

/**
 * Opened from the "Detach is on" notification. Confirms with fingerprint or password (if either
 * exists), then asks the running service to switch itself off for a few minutes.
 * Not reachable by other apps.
 */
class PauseActivity : Activity() {

    private var asked = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!DetachService.isRunning) {
            Toast.makeText(
                this,
                if (Pause.isPending(this)) "Detach is already paused" else "Detach isn't running",
                Toast.LENGTH_SHORT,
            ).show()
            finish()
            return
        }
        val ui = Ui(this)
        val minutes = Settings(this).pauseMinutes

        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(ui.dp(24), ui.dp(28), ui.dp(24), ui.dp(16))
        }
        col.addView(ui.icon(R.drawable.ic_pause, 28, ui.caution))
        col.addView(ui.space(20))
        col.addView(ui.text("Make room for a payment", 23f, strong = true).apply { isAccessibilityHeading = true })
        col.addView(ui.space(12))
        val monitored = Settings(this).resumeOnSocialForeground && Pause.canSelfEnable(this) &&
            PauseForegroundMonitor.hasUsageAccess(this)
        col.addView(ui.text(if (monitored) {
            "Accessibility and overlays switch off completely. Your opt-in local app monitor can resume protection when Instagram or YouTube opens."
        } else {
            "Accessibility and overlays switch off completely. No app monitor runs in timer-only mode."
        }, 15f, ui.sub))
        col.addView(ui.space(20))
        col.addView(ui.note(if (Pause.canSelfEnable(this)) {
            "Automatic resume after $minutes ${if (minutes == 1) "minute" else "minutes"}. Android may delay the timer. Without app detection, the timer cannot know whether banking has finished."
        } else {
            "Reminder after $minutes ${if (minutes == 1) "minute" else "minutes"}. Automatic resume needs the optional USB grant; otherwise turn Detach on in Accessibility settings."
        }).apply {
            background = ui.shape()
            setPadding(ui.dp(14), ui.dp(14), ui.dp(14), ui.dp(14))
        })
        col.addView(ui.space(12))
        col.addView(ui.text("You can always resume sooner from Detach.", 15f, strong = true))
        col.addView(ui.space(24))
        col.addView(ui.primary("Pause protection") { confirm() })
        col.addView(ui.space(4))
        col.addView(ui.quiet("Keep protection on") { finish() })
        ui.dialogPage(this, ScrollView(this).apply { addView(col) })
    }

    /** Ask as soon as the window is really in front; the fingerprint prompt needs that. */
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        // With nothing to confirm with, the Pause button itself is the confirmation.
        if (hasFocus && !asked && !isFinishing && Auth.available(this)) {
            asked = true
            confirm()
        }
    }

    private fun confirm() = Auth.confirm(this, "Pause for payments") { pause() }

    private fun pause() {
        val ok = DetachService.pauseForPayments()
        Toast.makeText(
            this,
            if (ok) "Protection paused. Your payment timer is set." else "Could not pause protection. Check Accessibility or try again.",
            Toast.LENGTH_SHORT,
        ).show()
        finish()
    }

    override fun onStop() {
        super.onStop()
        Auth.cancel()
        if (!isFinishing) finish()
    }
}

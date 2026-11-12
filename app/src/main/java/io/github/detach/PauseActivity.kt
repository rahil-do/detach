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
        col.addView(ui.text("Detach’s service and overlays will switch off completely. No background watcher runs.", 15f, ui.sub))
        col.addView(ui.space(20))
        col.addView(ui.note("Reminder after $minutes ${if (minutes == 1) "minute" else "minutes"}. This does not restart protection.").apply {
            background = ui.shape()
            setPadding(ui.dp(14), ui.dp(14), ui.dp(14), ui.dp(14))
        })
        col.addView(ui.space(12))
        col.addView(ui.text("Resume yourself when you’ve finished paying.", 15f, strong = true))
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
            if (ok) "Protection paused. Resume after paying." else "Detach isn't running",
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

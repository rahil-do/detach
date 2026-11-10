package io.github.detach

import android.app.Activity
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
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
        val d = resources.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()
        val minutes = Settings(this).pauseMinutes

        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(24), dp(24), dp(16))
            minimumWidth = dp(300)
        }
        col.addView(TextView(this).apply {
            text = "Pause Detach for $minutes ${if (minutes == 1) "minute" else "minutes"}?"
            textSize = 20f
            setTypeface(Typeface.DEFAULT, Typeface.BOLD)
        })
        col.addView(TextView(this).apply {
            text = "For payment apps that won't open while Detach is on. Blocking and the \"Detach is protected\" screen are off until it comes back."
            textSize = 14f
            setPadding(0, dp(8), 0, dp(12))
        })
        val buttons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
        }
        buttons.addView(Button(this, null, android.R.attr.borderlessButtonStyle).apply {
            text = "Cancel"
            isAllCaps = false
            setOnClickListener { finish() }
        })
        buttons.addView(Button(this, null, android.R.attr.borderlessButtonStyle).apply {
            text = "Pause"
            isAllCaps = false
            setTypeface(Typeface.DEFAULT, Typeface.BOLD)
            setOnClickListener { confirm() }
        })
        col.addView(buttons)
        setContentView(col)
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
            if (ok) "Detach paused until ${Pause.untilText(this)}" else "Detach isn't running",
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

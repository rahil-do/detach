package io.github.detach

import android.app.Activity
import android.content.Context
import android.os.Bundle
import android.os.CountDownTimer
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * Opened from the "Detach is protected" panel.
 *  - Password set: fingerprint (if enrolled) or the password.
 *  - No password, fingerprint enrolled: fingerprint.
 *  - Neither: a 30-second wait.
 * On success, Detach's system pages are open for 2 minutes.
 */
class UnlockActivity : Activity() {

    private var timer: CountDownTimer? = null
    private var biometric = false
    private var asked = false

    private lateinit var msg: TextView
    private lateinit var ok: Button
    private var input: EditText? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val lock = Lock(this)
        biometric = Auth.biometricAvailable(this)
        // The keyboard would sit behind the fingerprint prompt; it comes up with "Use password".
        if (biometric) window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_HIDDEN)
        val ui = Ui(this)

        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(ui.dp(24), ui.dp(28), ui.dp(24), ui.dp(16))
        }
        col.addView(ui.icon(R.drawable.ic_lock, 28, ui.accent))
        col.addView(ui.space(20))
        col.addView(ui.text("A deliberate change", 23f, strong = true).apply { isAccessibilityHeading = true })
        col.addView(ui.space(8))
        col.addView(ui.text("Unlock Detach settings", 14f, ui.accent, true))
        msg = ui.text("", 15f, ui.sub).apply {
            setPadding(0, ui.dp(12), 0, ui.dp(20))
        }
        col.addView(msg)
        val buttons = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, ui.dp(16), 0, 0)
        }
        ok = ui.primary("Unlock") { }
        buttons.addView(ok)
        buttons.addView(ui.quiet("Keep settings locked") { finish() })

        when {
            lock.isSet -> {
                msg.text = if (biometric) {
                    "Use your fingerprint or enter your Detach password. You'll then have 2 minutes to change Detach's system settings."
                } else {
                    "Enter your Detach password. You'll then have 2 minutes to change Detach's system settings."
                }
                val field = passwordEditText(this, "Password")
                input = field
                col.addView(field)
                ok.text = "Unlock"
                ok.setOnClickListener {
                    val wait = lock.lockoutSeconds()
                    if (wait > 0) { field.error = "Too many tries. Wait $wait s"; return@setOnClickListener }
                    val typed = field.text.toString()
                    ok.isEnabled = false
                    HashWorker.run(field, { lock.verify(typed) }) { good ->
                        ok.isEnabled = true
                        if (good) {
                            granted()
                        } else {
                            field.text.clear()
                            val w = lock.lockoutSeconds()
                            field.error = if (w > 0) "Too many tries. Wait $w s" else "Wrong password"
                        }
                    }
                }
                if (!biometric) field.requestFocus()
            }
            biometric -> {
                msg.text = "Confirm with your fingerprint. You'll then have 2 minutes to change Detach's system settings."
                ok.text = "Unlock"
                ok.setOnClickListener { askBiometric() }
            }
            else -> startWait()
        }
        col.addView(buttons)
        ui.dialogPage(this, ScrollView(this).apply { addView(col) })
        setFinishOnTouchOutside(false)
    }

    /** Ask as soon as the window is really in front; the fingerprint prompt needs that. */
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && biometric && !asked && !isFinishing) {
            asked = true
            askBiometric()
        }
    }

    private fun askBiometric() = Auth.confirm(
        this, "Unlock Detach settings",
        // "Use password", or the fingerprint can't be used right now.
        fallback = { input?.let { showKeyboard(it) } ?: startWait() },
    ) { granted() }

    private fun showKeyboard(field: EditText) {
        field.requestFocus()
        field.post {
            (getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager)
                ?.showSoftInput(field, InputMethodManager.SHOW_IMPLICIT)
        }
    }

    /** No password (and no usable fingerprint): make them wait. */
    private fun startWait() {
        if (timer != null) return
        msg.text = "Take a breath. If you still want to change Detach's system settings after the wait, you'll have 2 minutes."
        ok.isEnabled = false
        ok.text = "Wait 30"
        ok.setOnClickListener { granted() }
        timer = object : CountDownTimer(30_000, 1_000) {
            override fun onTick(left: Long) {
                ok.text = "Wait ${(left + 999) / 1000}"
            }
            override fun onFinish() {
                ok.isEnabled = true
                ok.text = "Continue"
            }
        }.start()
    }

    private fun granted() {
        Guard.grant()
        finish()
    }

    override fun onStop() {
        super.onStop()
        // Leaving the screen resets the wait, so it can't run out in the background.
        timer?.cancel()
        Auth.cancel()
        if (!isFinishing) finish()
    }
}

package io.github.detach

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.hardware.biometrics.BiometricManager
import android.hardware.biometrics.BiometricPrompt
import android.os.Build
import android.os.CancellationSignal
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.Toast

/**
 * One way to confirm "it's really you": fingerprint (Android's own biometric prompt) or the
 * Detach password. The phone's PIN/pattern is never accepted: the only fallback from the
 * fingerprint is the Detach password.
 */
object Auth {
    private var signal: CancellationSignal? = null

    /**
     * True if a fingerprint (or another biometric Android rates at least "weak", i.e. Class 2)
     * is enrolled and usable. Android 9 has the prompt but no way to ask this, so it uses the
     * password only.
     */
    fun biometricAvailable(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < 29) return false
        return runCatching {
            val bm = context.getSystemService(BiometricManager::class.java)
            when {
                bm == null -> false
                Build.VERSION.SDK_INT >= 30 ->
                    bm.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_WEAK) == BiometricManager.BIOMETRIC_SUCCESS
                else -> {
                    @Suppress("DEPRECATION")
                    bm.canAuthenticate() == BiometricManager.BIOMETRIC_SUCCESS
                }
            }
        }.getOrDefault(false)
    }

    /** Something exists to confirm with. If not, callers decide what "unprotected" means. */
    fun available(context: Context) = Lock(context).isSet || biometricAvailable(context)

    /**
     * Runs [onSuccess] once the user is confirmed.
     *  - Fingerprint available (and [allowBiometric]): the system prompt. Its second button is
     *    "Use password" if a password is set, otherwise "Cancel".
     *  - "Use password", or the fingerprint can't be used (locked out, failed to start): [fallback].
     *    By default that's the password dialog if a password is set, and nothing otherwise.
     *  - No fingerprint: the password dialog.
     *  - Neither a fingerprint nor a password: [fallback] if the caller gave one (so it can
     *    apply its own rule, e.g. a wait), otherwise [onSuccess] straight away.
     * Must be called from a visible activity, on the main thread.
     */
    fun confirm(
        activity: Activity,
        reason: String,
        allowBiometric: Boolean = true,
        fallback: (() -> Unit)? = null,
        onSuccess: () -> Unit,
    ) {
        val passwordSet = Lock(activity).isSet
        val other: () -> Unit = fallback ?: { if (passwordSet) passwordDialog(activity, onSuccess = onSuccess) }
        when {
            allowBiometric && biometricAvailable(activity) -> biometric(activity, reason, passwordSet, other, onSuccess)
            passwordSet -> other()
            fallback != null -> fallback()
            else -> onSuccess()
        }
    }

    /** Dismisses a fingerprint prompt that is still showing (the activity is leaving the screen). */
    fun cancel() {
        signal?.let { runCatching { it.cancel() } }
        signal = null
    }

    private fun biometric(activity: Activity, reason: String, passwordSet: Boolean, other: () -> Unit, onSuccess: () -> Unit) {
        cancel()
        val mine = CancellationSignal()
        var done = false
        fun once(block: () -> Unit) {
            if (done) return
            done = true
            if (signal === mine) signal = null
            if (!activity.isFinishing && !activity.isDestroyed) block()
        }
        val started = runCatching {
            val executor = activity.mainExecutor
            val builder = BiometricPrompt.Builder(activity)
                .setTitle("Detach")
                .setSubtitle(reason)
                .setNegativeButton(if (passwordSet) "Use password" else "Cancel", executor) { _, _ ->
                    once { if (passwordSet) other() }
                }
            if (Build.VERSION.SDK_INT >= 29) builder.setConfirmationRequired(false)
            if (Build.VERSION.SDK_INT >= 30) builder.setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_WEAK)
            signal = mine
            builder.build().authenticate(mine, executor, object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult?) = once(onSuccess)

                // A finger that doesn't match (onAuthenticationFailed) leaves the prompt up; nothing to do.
                override fun onAuthenticationError(errorCode: Int, errString: CharSequence?) = once {
                    when (errorCode) {
                        // Dismissed by the user or by Android: just stop.
                        BiometricPrompt.BIOMETRIC_ERROR_CANCELED, BiometricPrompt.BIOMETRIC_ERROR_USER_CANCELED -> Unit
                        // Too many wrong touches (or a timeout) must not open an easier way in:
                        // with no password to fall back on, the answer is "try again later".
                        BiometricPrompt.BIOMETRIC_ERROR_LOCKOUT,
                        BiometricPrompt.BIOMETRIC_ERROR_LOCKOUT_PERMANENT,
                        BiometricPrompt.BIOMETRIC_ERROR_TIMEOUT -> {
                            if (!errString.isNullOrBlank()) Toast.makeText(activity, errString, Toast.LENGTH_SHORT).show()
                            if (passwordSet) other()
                        }
                        else -> {
                            if (!errString.isNullOrBlank()) Toast.makeText(activity, errString, Toast.LENGTH_SHORT).show()
                            other()
                        }
                    }
                }
            })
        }.isSuccess
        if (!started) once(other)
    }

    /**
     * Asks for the Detach password in a dialog and runs [onSuccess] when it is right.
     * Wrong tries count towards [Lock]'s lockout.
     */
    fun passwordDialog(activity: Activity, positive: String = "Unlock", onSuccess: () -> Unit) {
        val lock = Lock(activity)
        val d = activity.resources.displayMetrics.density
        val input = passwordEditText(activity, "Password")
        val box = LinearLayout(activity).apply {
            setPadding((22 * d).toInt(), (8 * d).toInt(), (22 * d).toInt(), 0)
            addView(input, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        val dialog = AlertDialog.Builder(activity)
            .setTitle("Enter password")
            .setView(box)
            .setPositiveButton(positive, null)
            .setNegativeButton("Cancel", null)
            .create()
        dialog.setOnShowListener {
            val ok = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
            ok.setOnClickListener {
                val wait = lock.lockoutSeconds()
                if (wait > 0) { input.error = "Too many tries. Wait $wait s"; return@setOnClickListener }
                val typed = input.text.toString()
                ok.isEnabled = false
                HashWorker.run(input, { lock.verify(typed) }) { good ->
                    ok.isEnabled = true
                    if (good) {
                        dialog.dismiss()
                        if (!activity.isFinishing && !activity.isDestroyed) onSuccess()
                    } else {
                        input.text.clear()
                        val w = lock.lockoutSeconds()
                        input.error = if (w > 0) "Too many tries. Wait $w s" else "Wrong password"
                    }
                }
            }
        }
        dialog.window?.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
        dialog.show()
        input.requestFocus()
    }
}

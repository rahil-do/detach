package io.github.detach

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView

/** Brief hand-off screen used by the widget. The bank app is never started until Detach is off. */
class PaymentLaunchActivity : Activity() {
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private var packageNameToLaunch: String? = null
    private var startedAt = 0L
    private lateinit var message: TextView

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        packageNameToLaunch = intent.getStringExtra(EXTRA_PACKAGE)
        val packageName = packageNameToLaunch
        if (packageName == null || packageName !in Settings(this).paymentPackages ||
            this.packageManager.getLaunchIntentForPackage(packageName) == null) {
            finish()
            return
        }
        showPreparing(packageName)
        startedAt = SystemClock.elapsedRealtime()
        handler.post { begin(packageName) }
    }

    private fun begin(packageName: String) {
        if (Pause.isPending(this) && !Pause.isServiceEnabled(this)) {
            launch(packageName)
            return
        }
        if (!DetachService.pauseForPayments(packageName)) {
            showError("Detach needs to be active before it can prepare a payment app.")
            return
        }
        awaitDisabled(packageName)
    }

    private fun awaitDisabled(packageName: String) {
        if (!Pause.isServiceEnabled(this)) {
            // Keep the transition perceptible but do not add a noticeable wait.
            val remaining = (120L - (SystemClock.elapsedRealtime() - startedAt)).coerceAtLeast(0L)
            handler.postDelayed({ launch(packageName) }, remaining)
        } else if (SystemClock.elapsedRealtime() - startedAt < DISABLE_TIMEOUT_MS) {
            handler.postDelayed({ awaitDisabled(packageName) }, CHECK_INTERVAL_MS)
        } else {
            showError("Detach took too long to pause. Your payment app was not opened.")
        }
    }

    private fun launch(packageName: String) {
        val launch = packageManager.getLaunchIntentForPackage(packageName)
        if (launch == null || isFinishing || isDestroyed) {
            showError("This payment app is no longer available.")
            return
        }
        runCatching { startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)) }
            .onFailure { showError("Couldn’t open this payment app.") }
            .onSuccess { finish() }
    }

    private fun showPreparing(packageName: String) {
        val label = runCatching {
            packageManager.getApplicationLabel(packageManager.getApplicationInfo(packageName, 0)).toString()
        }.getOrDefault("your payment app")
        val padding = (resources.displayMetrics.density * 28).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(padding, padding, padding, padding)
            setBackgroundColor(getColor(R.color.window))
        }
        root.addView(ProgressBar(this).apply { isIndeterminate = true }, LinearLayout.LayoutParams(-2, -2).apply { gravity = Gravity.CENTER_HORIZONTAL })
        root.addView(text("Preparing $label", 23f, true).apply { gravity = Gravity.CENTER; setPadding(0, padding, 0, 8) })
        message = text("Turning off Detach before opening your payment app.", 15f, false).apply { gravity = Gravity.CENTER; setTextColor(getColor(R.color.secondary_ink)) }
        root.addView(message)
        setContentView(root)
    }

    private fun showError(errorMessage: String) {
        message.text = errorMessage
        val close = TextView(this).apply {
            this.text = "Close"
            gravity = Gravity.CENTER
            setTextColor(getColor(R.color.on_accent))
            textSize = 15f
            setPadding(32, 20, 32, 20)
            background = GradientDrawable().apply { setColor(getColor(R.color.accent)); cornerRadius = 999f }
            setOnClickListener { finish() }
        }
        (message.parent as? LinearLayout)?.addView(close, LinearLayout.LayoutParams(-2, -2).apply { gravity = Gravity.CENTER_HORIZONTAL; topMargin = 40 })
    }

    private fun text(value: String, size: Float, strong: Boolean) = TextView(this).apply {
        text = value
        textSize = size
        setTextColor(getColor(R.color.ink))
        if (strong) typeface = android.graphics.Typeface.create("sans-serif", android.graphics.Typeface.BOLD)
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    companion object {
        const val EXTRA_PACKAGE = "payment_package"
        private const val CHECK_INTERVAL_MS = 16L
        private const val DISABLE_TIMEOUT_MS = 2_000L
    }
}

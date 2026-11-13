package io.github.detach

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.widget.ScrollView
import android.widget.LinearLayout
import android.widget.Toast

/**
 * Opened from the "Detach is on" notification or the Quick Settings tile. Confirms with fingerprint or password (if either
 * exists), then asks the running service to switch itself off for a few minutes.
 *
 * "Pause and open <app>" is the dependable way to open a UPI or bank app directly: payment apps
 * can check the enabled accessibility services while they start, before Detach could react to
 * their first window. After the confirmed pause it waits (briefly, bounded) until Android no
 * longer lists Detach's service as enabled, and only then starts the app's ordinary launcher
 * intent. Only selected apps with a launcher entry are offered; nothing is passed to them.
 * Not reachable by other apps.
 */
class PauseActivity : Activity() {

    private var asked = false
    private var apps: List<Pair<String, String>> = emptyList()
    private val handler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!DetachService.isRunning) {
            // Detach's own screen is in front, so Android allows app detection lost during this
            // pause to be re-attached to it (same token and deadline).
            if (Pause.isPending(this) && !Pause.isExpired(this)) PauseForegroundMonitor.reattach(this)
            // And the timer and status a force-stop removed (never authority).
            if (Pause.isPending(this)) Pause.resumeIfDue(this)
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
        col.addView(ui.text(StatusNotifications.PAUSE_ACTION, 23f, strong = true).apply { isAccessibilityHeading = true })
        col.addView(ui.space(12))
        val monitored = Settings(this).resumeOnSocialForeground && Pause.canSelfEnable(this) &&
            PauseForegroundMonitor.hasUsageAccess(this)
        col.addView(ui.text(when {
            monitored -> "Resumes in about $minutes min, or when Instagram or YouTube opens."
            Pause.canSelfEnable(this) -> "Resumes in about $minutes min."
            else -> "Turn Detach back on in Accessibility when done."
        }, 15f, ui.sub))
        col.addView(ui.space(24))
        col.addView(ui.primary("Pause") { confirm() })
        apps = openableApps(this)
        for ((packageName, label) in apps) {
            col.addView(ui.space(4))
            col.addView(ui.secondary("Pause and open $label") { confirm(packageName) })
        }
        col.addView(ui.space(4))
        col.addView(ui.quiet("Keep protection on") { finish() })
        ui.dialogPage(this, ScrollView(this).apply { addView(col) })
    }

    /** Ask as soon as the window is really in front; the fingerprint prompt needs that. */
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        // With nothing to confirm with, the Pause button itself is the confirmation. When there are
        // apps to open after pausing, the owner picks one first.
        if (hasFocus && !asked && !isFinishing && apps.isEmpty() && Auth.available(this)) {
            asked = true
            confirm()
        }
    }

    private fun confirm(open: String? = null) = Auth.confirm(this, StatusNotifications.PAUSE_ACTION) { pause(open) }

    private fun pause(open: String?) {
        val launch = open?.let { launchIntent(this, it) }
        val ok = DetachService.pauseForPayments(open)
        if (!ok || launch == null) {
            toast(if (ok) "Paused for payment" else "Couldn't pause. Try again.")
            finish()
            return
        }
        awaitOffThenOpen(launch, SystemClock.uptimeMillis() + OFF_WAIT_MS)
    }

    /**
     * Opens [launch] only once Detach is gone from Android's enabled accessibility services, so
     * the payment app's own check at startup cannot see it. Never opens it while still listed.
     */
    private fun awaitOffThenOpen(launch: Intent, deadline: Long) {
        if (isDestroyed) return
        if (!Pause.serviceListed(this)) {
            val opened = runCatching { startActivity(launch) }.isSuccess
            toast(if (opened) "Paused for payment" else "Paused. Open the app yourself.")
            finish()
        } else if (SystemClock.uptimeMillis() >= deadline) {
            toast("Still turning off. Open the app in a moment.")
            finish()
        } else handler.postDelayed({ awaitOffThenOpen(launch, deadline) }, OFF_POLL_MS)
    }

    private fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_SHORT).show()

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    override fun onStop() {
        super.onStop()
        Auth.cancel()
        if (!isFinishing) finish()
    }

    companion object {
        internal const val OFF_POLL_MS = 50L
        internal const val OFF_WAIT_MS = 2_000L

        /** Only an ordinary launcher intent for a selected app, to a new task; no data or extras. */
        internal fun launchIntent(context: Context, packageName: String): Intent? =
            packageName.takeIf { it in Settings(context).paymentPackages && it != context.packageName }?.let {
                runCatching { context.packageManager.getLaunchIntentForPackage(it) }.getOrNull()
                    ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }

        /** Selected apps that have a launcher entry, as package to label, sorted by label. */
        internal fun openableApps(context: Context): List<Pair<String, String>> {
            val manager = context.packageManager
            return Settings(context).paymentPackages.mapNotNull { packageName ->
                if (launchIntent(context, packageName) == null) return@mapNotNull null
                val label = runCatching {
                    manager.getApplicationLabel(manager.getApplicationInfo(packageName, 0)).toString().trim()
                }.getOrNull()?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                packageName to label
            }.sortedWith(compareBy(java.text.Collator.getInstance()) { it.second })
        }
    }
}

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
        col.addView(ui.space(12))
        col.addView(ui.text("Pause before you open the payment app, or the app you pay from, so it starts with Detach already off. Opening a UPI app straight from the home screen is detected only after it has started, which can be too late.", 15f, ui.sub))
        col.addView(ui.space(20))
        col.addView(ui.note(if (Pause.canSelfEnable(this)) {
            "Automatic resume after $minutes ${if (minutes == 1) "minute" else "minutes"}; it waits while the phone is locked. Android may delay the timer. " +
                if (monitored) "App detection keeps it waiting while a selected app is seen in front."
                else "Without app detection the timer cannot see a payment app still open and can turn Detach back on during a long checkout; choose enough time."
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

    private fun confirm(open: String? = null) = Auth.confirm(this, "Pause for payments") { pause(open) }

    private fun pause(open: String?) {
        val launch = open?.let { launchIntent(this, it) }
        val ok = DetachService.pauseForPayments(open)
        if (!ok || launch == null) {
            toast(if (ok) "Protection paused. Your payment timer is set." else "Could not pause protection. Check Accessibility or try again.")
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
            toast(if (opened) "Protection paused. Your payment timer is set." else "Protection paused. Open the payment app yourself.")
            finish()
        } else if (SystemClock.uptimeMillis() >= deadline) {
            toast("Protection is pausing, but Android still lists Detach as on. Wait a moment, then open the payment app yourself.")
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

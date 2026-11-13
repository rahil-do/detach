package io.github.detach

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import java.time.LocalDate

/**
 * Keeps track of whether Detach can still recognise Instagram's and YouTube's screens.
 * If an app update changes the layout so much that none of the detection methods work,
 * this posts a local notification instead of failing silently.
 * Only timestamps and counters are stored, on the phone, in private app storage.
 */
class Health(private val context: Context) {
    private val p = context.getSharedPreferences("health", Context.MODE_PRIVATE)

    @Volatile private var dirty = false
    private var lastFlush = 0L

    // in-memory copies, flushed at most once a minute
    private val stamps = HashMap<String, Long>()
    private var day = p.getString("day", "") ?: ""
    private var igEvals = p.getInt("ig_evals", 0)
    private var igTabHits = p.getInt("ig_tab_hits", 0)
    private var ytEvals = p.getInt("yt_evals", 0)
    private var ytBarHits = p.getInt("yt_bar_hits", 0)

    init {
        // Notifications from older versions; once per process (the status screen builds a Health
        // for every render, and each cancel is a call into the notification service).
        if (!legacyCleared) {
            legacyCleared = true
            val manager = context.getSystemService(NotificationManager::class.java)
            manager.cancel("ig".hashCode())
            manager.cancel("yt".hashCode())
        }
    }

    fun seen(signal: String) {
        stamps[signal] = System.currentTimeMillis()
        dirty = true
    }

    @Synchronized
    fun igEval(tabBarFound: Boolean) {
        rollDay()
        igEvals++
        if (tabBarFound) {
            igTabHits++
            recover("ig")
        }
        dirty = true
        maybeFlush()
    }

    @Synchronized
    fun ytEval(navBarFound: Boolean) {
        rollDay()
        ytEvals++
        if (navBarFound) {
            ytBarHits++
            recover("yt")
        }
        dirty = true
        maybeFlush()
    }

    private fun rollDay() {
        val today = LocalDate.now().toString()
        if (today != day) {
            day = today
            igEvals = 0; igTabHits = 0; ytEvals = 0; ytBarHits = 0
        }
    }

    private fun maybeFlush() {
        val now = System.currentTimeMillis()
        if (!dirty || now - lastFlush < 60_000) return
        lastFlush = now
        dirty = false
        p.edit().apply {
            putString("day", day)
            putInt("ig_evals", igEvals); putInt("ig_tab_hits", igTabHits)
            putInt("yt_evals", ytEvals); putInt("yt_bar_hits", ytBarHits)
            stamps.forEach { (k, v) -> putLong("seen_$k", v) }
        }.apply()
        checkAlerts()
    }

    private fun checkAlerts() {
        if (igEvals >= 300 && igTabHits == 0) alert("ig")
        if (ytEvals >= 300 && ytBarHits == 0) alert("yt")
    }

    private fun alert(key: String) {
        if (Pause.isPending(context)) return
        if (p.getBoolean("alert_active_$key", false)) return
        val now = System.currentTimeMillis()
        val last = p.getLong("alerted_$key", 0)
        if (last > 0 && now >= last && now - last < 12 * 3600_000L) return
        val nm = context.getSystemService(NotificationManager::class.java)
        if (!nm.areNotificationsEnabled()) return
        p.edit().putLong("alerted_$key", now).putBoolean("alert_active_$key", true).apply()
        refreshAlert()
    }

    private fun recover(key: String) {
        if (!p.getBoolean("alert_active_$key", false)) return
        p.edit().putBoolean("alert_active_$key", false).apply()
        refreshAlert(allowPost = false)
    }

    private fun refreshAlert(allowPost: Boolean = true) {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.cancel("ig".hashCode())
        nm.cancel("yt".hashCode())
        if (!p.getBoolean("alert_active_ig", false) && !p.getBoolean("alert_active_yt", false)) {
            nm.cancel(NOTIFICATION_ID)
            return
        }
        if (!allowPost && nm.activeNotifications.none { it.id == NOTIFICATION_ID }) return
        nm.createNotificationChannel(
            NotificationChannel("health", "Protection checks", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "When a screen isn't recognised"
                lockscreenVisibility = Notification.VISIBILITY_PRIVATE
            },
        )
        val open = PendingIntent.getActivity(
            context, NOTIFICATION_ID,
            Intent(context, MainActivity::class.java)
                .setAction(ACTION_DIAGNOSTICS)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val text = "Some screens aren't recognised. Open diagnostics."
        val publicVersion = Notification.Builder(context, "health")
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Detach")
            .setContentText("Open Detach")
            .setContentIntent(open)
            .setLocalOnly(true)
            .setShowWhen(false)
            .build()
        val notification = Notification.Builder(context, "health")
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Check protection")
            .setContentText(text)
            .setContentIntent(open)
            .setPublicVersion(publicVersion)
            .addAction(Notification.Action.Builder(null, "Open diagnostics", open).build())
            .setLocalOnly(true)
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .setOnlyAlertOnce(true)
            .setAutoCancel(true)
            .setShowWhen(false)
            .setCategory(Notification.CATEGORY_ERROR)
            .build()
        try {
            nm.notify(NOTIFICATION_ID, notification)
        } catch (_: SecurityException) {
            nm.cancel(NOTIFICATION_ID)
            p.edit().remove("alert_active_ig").remove("alert_active_yt")
                .remove("alerted_ig").remove("alerted_yt").apply()
        }
    }

    /** For the status screen. */
    fun report(): String {
        fun ago(key: String): String {
            val t = stamps[key] ?: p.getLong("seen_$key", 0)
            if (t == 0L) return "not yet"
            val m = (System.currentTimeMillis() - t) / 60_000
            return if (m < 1) "just now" else if (m < 120) "$m min ago" else "${m / 60} h ago"
        }
        return buildString {
            appendLine("Instagram tabs (by id):        ${ago("ig_tab_id")}")
            appendLine("Instagram tabs (by label):     ${ago("ig_tab_label")}")
            appendLine("Instagram stories row:         ${ago("ig_tray")}")
            appendLine("Instagram reel viewer:         ${ago("ig_clips")}")
            appendLine("YouTube tabs:                  ${ago("yt_bar")}")
            appendLine("YouTube Shorts player:         ${ago("yt_shorts")}")
            appendLine()
            append("Last action: ${p.getString("last_action", "none")}")
        }
    }

    fun lastAction(what: String) {
        p.edit().putString("last_action", "$what (${java.time.LocalTime.now().withNano(0)})").apply()
    }

    companion object {
        const val ACTION_DIAGNOSTICS = "io.github.detach.action.DIAGNOSTICS"
        private const val NOTIFICATION_ID = 7003
        @Volatile private var legacyCleared = false
    }
}

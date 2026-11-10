package io.github.detach

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
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

    fun seen(signal: String) {
        stamps[signal] = System.currentTimeMillis()
        dirty = true
    }

    @Synchronized
    fun igEval(tabBarFound: Boolean) {
        rollDay()
        igEvals++
        if (tabBarFound) igTabHits++
        dirty = true
        maybeFlush()
    }

    @Synchronized
    fun ytEval(navBarFound: Boolean) {
        rollDay()
        ytEvals++
        if (navBarFound) ytBarHits++
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
        // Plenty of use today but the navigation bar was never recognised: layout changed.
        if (igEvals >= 300 && igTabHits == 0) alert("ig", "Instagram")
        if (ytEvals >= 300 && ytBarHits == 0) alert("yt", "YouTube")
    }

    private fun alert(key: String, app: String) {
        val last = p.getLong("alerted_$key", 0)
        if (System.currentTimeMillis() - last < 12 * 3600_000L) return
        p.edit().putLong("alerted_$key", System.currentTimeMillis()).apply()
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel("health", "Detach health", NotificationManager.IMPORTANCE_DEFAULT)
        )
        nm.notify(
            key.hashCode(),
            Notification.Builder(context, "health")
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle("Detach can't recognise $app")
                .setContentText("$app's layout seems to have changed after an update. Blocking may not work until Detach is updated.")
                .setStyle(Notification.BigTextStyle())
                .build()
        )
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
}

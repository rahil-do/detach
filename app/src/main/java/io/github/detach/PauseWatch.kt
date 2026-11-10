package io.github.detach

import android.app.AppOpsManager
import android.app.Service
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Process
import android.widget.Toast

/**
 * Runs only while Detach is paused for payments. The accessibility service is off during a
 * pause, so this is the only way to notice Instagram or YouTube being opened: once a second it
 * asks Android which apps came to the front (Usage access: package names and times, never
 * screen content) and ends the pause as soon as one of them does.
 *
 * Needs Usage access (granted by the owner) and the ability to switch the service back on.
 * Without either it is never started and the pause simply runs its full time.
 */
class PauseWatchService : Service() {

    private val handler = Handler(Looper.getMainLooper())
    private var lastCheck = 0L
    private var usageAccess = false

    private val tick = object : Runnable {
        override fun run() {
            val app = applicationContext
            if (!Pause.isPending(app)) { stopSelf(); return }
            if (Pause.isExpired(app)) { Pause.resume(app); stopSelf(); return }
            val opened = usageAccess && runCatching { blockedAppOpened() }.getOrDefault(false)
            if (opened) {
                Toast.makeText(app, "Detach is back on", Toast.LENGTH_SHORT).show()
                Pause.resume(app)
                stopSelf()
                return
            }
            handler.postDelayed(this, POLL_MS)
        }
    }

    /** True if Instagram or YouTube came to the front since the last check. */
    private fun blockedAppOpened(): Boolean {
        val usm = getSystemService(UsageStatsManager::class.java) ?: return false
        val now = System.currentTimeMillis()
        // Windows overlap by a few seconds on purpose: Android records these events with a small
        // delay and on its own clock, so back-to-back windows could miss one. Seeing the same
        // event twice is harmless, since the first hit ends the pause.
        val events = usm.queryEvents(lastCheck - OVERLAP_MS, now + OVERLAP_MS)
        lastCheck = now
        val e = UsageEvents.Event()
        var hit = false
        while (events.hasNextEvent()) {
            events.getNextEvent(e)
            // 1 = ACTIVITY_RESUMED (called MOVE_TO_FOREGROUND before Android 10)
            if (e.eventType == 1 && e.packageName in Targets.PACKAGES) hit = true
        }
        return hit
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val n = Pause.pausedNotification(this)
        runCatching {
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(Pause.NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            } else {
                startForeground(Pause.NOTIFICATION_ID, n)
            }
        }.onFailure { stopSelf(); return START_NOT_STICKY }
        if (lastCheck == 0L) {
            // Watch from the moment the pause began.
            lastCheck = Pause.startedAt(this).takeIf { it > 0 }?.coerceAtMost(System.currentTimeMillis()) ?: System.currentTimeMillis()
            usageAccess = hasUsageAccess(this)
            handler.postDelayed(tick, POLL_MS)
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacks(tick)
        // Leave the notification to Pause, which shows whatever matches the state now.
        runCatching { stopForeground(STOP_FOREGROUND_DETACH) }
        Pause.refreshNotification(this)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val POLL_MS = 1000L
        private const val OVERLAP_MS = 3000L

        const val GRANT_COMMAND = "adb shell appops set io.github.detach GET_USAGE_STATS allow"

        fun hasUsageAccess(context: Context): Boolean = runCatching {
            val ops = context.getSystemService(AppOpsManager::class.java) ?: return false
            val mode = if (Build.VERSION.SDK_INT >= 29) {
                ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName)
            } else {
                @Suppress("DEPRECATION")
                ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName)
            }
            mode == AppOpsManager.MODE_ALLOWED
        }.getOrDefault(false)

        /**
         * Starts the watcher whenever Detach can switch itself back on. Even without Usage
         * access it keeps the process alive, so the pause ends on time rather than whenever
         * Android gets round to delivering the alarm. Called while Detach's own screen is in
         * front, so a plain startService is allowed.
         */
        fun start(context: Context) {
            if (!Pause.canSelfEnable(context)) return
            runCatching { context.startService(Intent(context, PauseWatchService::class.java)) }
        }

        fun stop(context: Context) {
            runCatching { context.stopService(Intent(context, PauseWatchService::class.java)) }
        }
    }
}

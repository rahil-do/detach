package io.github.detach

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings as SysSettings
import java.util.Date

/**
 * "Pause for payments": UPI apps refuse to run while a sideloaded accessibility service is on,
 * so Detach can switch its own service off for a few minutes ([durationMs]) and then back on.
 *
 * Switching off is easy (the service calls disableSelf()). Switching back on means writing
 * Detach's own entry into the system's list of enabled accessibility services, which needs
 * WRITE_SECURE_SETTINGS, granted once over adb ([GRANT_COMMAND]). Without it the pause still
 * works, but turning Detach back on is done by hand in Accessibility settings.
 *
 * The end of the pause is stored as a wall-clock time, so it survives the process being killed.
 * Four things can end it, all through [resume], which does nothing if no pause is pending:
 * an alarm, an in-process timer, the boot receiver, and opening the app.
 */
object Pause {
    /** The longest a pause can be set to; also the sanity limit for a stored end time. */
    const val MAX_MS = 60 * 60_000L

    /** How long the next pause will last: the owner's setting, 1 to 60 minutes. */
    fun durationMs(context: Context) = Settings(context).pauseMinutes * 60_000L

    /** When the current pause began (wall clock), or 0. */
    fun startedAt(context: Context): Long = prefs(context).getLong(KEY_STARTED, 0L)

    const val ACTION_RESUME = "io.github.detach.action.RESUME"
    const val GRANT_COMMAND = "adb shell pm grant io.github.detach android.permission.WRITE_SECURE_SETTINGS"

    private const val CHANNEL_STATUS = "status"
    private const val CHANNEL_HEALTH = "health"
    const val NOTIFICATION_ID = 7001
    private const val ALERT_ID = 7002
    private const val KEY_UNTIL = "until"
    private const val KEY_STARTED = "started"

    private val handler = Handler(Looper.getMainLooper())
    private var timer: Runnable? = null

    private fun prefs(context: Context) = context.getSharedPreferences("pause", Context.MODE_PRIVATE)

    /** Wall-clock time the pause ends, or 0 if Detach isn't paused. */
    fun until(context: Context): Long = prefs(context).getLong(KEY_UNTIL, 0L)

    /** A pause was started and Detach hasn't come back on since. */
    fun isPending(context: Context) = until(context) != 0L

    /** The pause is over (or the clock was moved so far that the stored time makes no sense). */
    fun isExpired(context: Context): Boolean {
        val until = until(context)
        if (until == 0L) return false
        val now = System.currentTimeMillis()
        return now >= until || until - now > MAX_MS + 60_000L
    }

    fun untilText(context: Context): String =
        android.text.format.DateFormat.getTimeFormat(context).format(Date(until(context)))

    /** True once the owner has granted WRITE_SECURE_SETTINGS over adb. */
    fun canSelfEnable(context: Context) =
        context.checkSelfPermission(android.Manifest.permission.WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED

    /**
     * Starts a pause. Called by the running service just before it switches itself off.
     * Records the end time, schedules the resume and swaps the notification.
     */
    fun begin(context: Context) {
        val app = context.applicationContext
        val now = System.currentTimeMillis()
        val duration = durationMs(app)
        val until = now + duration
        prefs(app).edit().putLong(KEY_UNTIL, until).putLong(KEY_STARTED, now).commit() // written before the service goes away
        cancelTimers(app)
        scheduleAlarm(app, until)
        // Fast path while the process is still alive; the alarm covers the case where it isn't.
        timer = Runnable { resume(app) }.also { handler.postDelayed(it, duration) }
        refreshNotification(app)
        // Ends the pause early if Instagram or YouTube is opened (needs Usage access).
        PauseWatchService.start(app)
    }

    /**
     * Ends a pending pause now: turns the service back on if allowed to, otherwise asks the
     * user to do it. Safe to call any number of times. Returns true if nothing is left to do.
     */
    fun resume(context: Context): Boolean {
        val app = context.applicationContext
        if (!isPending(app)) return true
        cancelTimers(app)
        if (enableService(app)) {
            prefs(app).edit().remove(KEY_UNTIL).apply()
            refreshNotification(app) // the service posts the "on" notification once it's connected
            return true
        }
        // Can't do it ourselves: the pause is over, and the notification now says so.
        if (!isExpired(app)) prefs(app).edit().putLong(KEY_UNTIL, System.currentTimeMillis()).apply()
        refreshNotification(app)
        return false
    }

    /** For the app's screen: finish a pause that ran out while nothing else could end it. */
    fun resumeIfDue(context: Context) {
        if (isPending(context) && isExpired(context) && canSelfEnable(context)) resume(context)
    }

    /** The service is (back) on: whatever pause there was is over. */
    fun onServiceStarted(context: Context) {
        val app = context.applicationContext
        cancelTimers(app)
        prefs(app).edit().remove(KEY_UNTIL).apply()
        refreshNotification(app)
    }

    /**
     * Adds Detach's own service to the system's list of enabled accessibility services, leaving
     * every other entry exactly as it was. This is the only secure setting Detach ever writes.
     */
    private fun enableService(context: Context): Boolean {
        if (!canSelfEnable(context)) return false
        return runCatching {
            val cr = context.contentResolver
            val me = ComponentName(context, DetachService::class.java)
            val current = SysSettings.Secure.getString(cr, SysSettings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
            val present = current.split(':').any { ComponentName.unflattenFromString(it) == me }
            val listed = present || SysSettings.Secure.putString(
                cr, SysSettings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                if (current.isEmpty()) me.flattenToString() else current + ":" + me.flattenToString(),
            )
            listed && SysSettings.Secure.putInt(cr, SysSettings.Secure.ACCESSIBILITY_ENABLED, 1)
        }.getOrDefault(false)
    }

    // ------------------------------------------------------------------ timers

    private fun resumeIntent(context: Context, requestCode: Int): PendingIntent = PendingIntent.getBroadcast(
        context, requestCode,
        Intent(context, PauseReceiver::class.java).setAction(ACTION_RESUME),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    // Exact alarms are only set where they need no permission (below Android 12) or are already allowed.
    @android.annotation.SuppressLint("MissingPermission")
    private fun scheduleAlarm(context: Context, at: Long) {
        val am = context.getSystemService(AlarmManager::class.java) ?: return
        val pi = resumeIntent(context, 1)
        // Exact only where it needs no extra permission; otherwise Android may deliver it a little late.
        val exact = Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()
        runCatching {
            if (exact) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
            else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        }.onFailure {
            runCatching { am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi) }
        }
    }

    private fun cancelTimers(context: Context) {
        timer?.let { handler.removeCallbacks(it) }
        timer = null
        PauseWatchService.stop(context)
        runCatching { context.getSystemService(AlarmManager::class.java)?.cancel(resumeIntent(context, 1)) }
    }

    // ------------------------------------------------------------------ notification

    /**
     * Shows the one notification that matches the current state: "on, tap to pause", "paused
     * until…", "pause over, turn me back on", or none. Plain notifications, no foreground
     * service; if notifications aren't allowed this quietly does nothing.
     */
    fun refreshNotification(context: Context) {
        val app = context.applicationContext
        runCatching {
            val nm = app.getSystemService(NotificationManager::class.java) ?: return
            val immutable = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            val stillOff = isPending(app) && !canSelfEnable(app) && isExpired(app)
            if (!stillOff) nm.cancel(ALERT_ID)
            val openAccessibility by lazy {
                PendingIntent.getActivity(
                    app, 3,
                    Intent(SysSettings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    immutable,
                )
            }
            when {
                isPending(app) && canSelfEnable(app) -> nm.notify(
                    NOTIFICATION_ID,
                    status(app, nm, "Detach paused until ${untilText(app)}", "Tap to resume now", resumeIntent(app, 2)),
                )
                isPending(app) && !isExpired(app) -> nm.notify(
                    NOTIFICATION_ID,
                    status(app, nm, "Detach paused until ${untilText(app)}", "Tap to turn Detach back on", openAccessibility),
                )
                isPending(app) -> {
                    // Detach can't switch itself back on, so this one is allowed to make a sound.
                    nm.createNotificationChannel(
                        NotificationChannel(CHANNEL_HEALTH, "Detach health", NotificationManager.IMPORTANCE_DEFAULT)
                    )
                    // Its own id, so Android treats it as new and lets it alert.
                    nm.cancel(NOTIFICATION_ID)
                    nm.notify(
                        ALERT_ID,
                        Notification.Builder(app, CHANNEL_HEALTH)
                            .setSmallIcon(R.drawable.ic_notification)
                            .setContentTitle("Detach is still off")
                            .setContentText("The pause is over. Tap to turn Detach back on")
                            .setContentIntent(openAccessibility)
                            .setOngoing(true)
                            .setOnlyAlertOnce(true)
                            .build(),
                    )
                }
                DetachService.isRunning && Settings(app).pauseNotification -> nm.notify(
                    NOTIFICATION_ID,
                    status(
                        app, nm, "Detach is on", "Tap to pause for payments",
                        PendingIntent.getActivity(
                            app, 4,
                            Intent(app, PauseActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                            immutable,
                        ),
                    ),
                )
                else -> nm.cancel(NOTIFICATION_ID)
            }
        }
    }

    /** The "paused until…" notification, also used by [PauseWatchService] while it watches. */
    fun pausedNotification(context: Context): Notification {
        val app = context.applicationContext
        return status(
            app, app.getSystemService(NotificationManager::class.java),
            "Detach paused until ${untilText(app)}", "Tap to resume now", resumeIntent(app, 2),
        )
    }

    private fun status(context: Context, nm: NotificationManager, title: String, text: String, tap: PendingIntent): Notification {
        // Low importance: no sound, no heads-up.
        nm.createNotificationChannel(NotificationChannel(CHANNEL_STATUS, "Detach status", NotificationManager.IMPORTANCE_LOW))
        return Notification.Builder(context, CHANNEL_STATUS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(tap)
            .setOngoing(true)
            .setShowWhen(false)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_STATUS)
            .build()
    }
}

/**
 * Ends a pause: from the alarm, from a tap on the "paused" notification, and after a reboot or
 * an update of Detach (both of which drop the alarm). Not exported: only Android itself and
 * Detach can reach it.
 */
class PauseReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Pause.ACTION_RESUME,
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED -> Pause.resume(context)
        }
    }
}

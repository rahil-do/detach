package io.github.detach

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Handler
import android.os.Looper
import java.util.concurrent.CopyOnWriteArraySet

/**
 * What a pending payment pause will do next, for both the status notification and Detach's
 * screen, so the two never disagree. Checked in this order.
 */
internal enum class PauseStatus {
    /**
     * A resume was requested and is still under way: a forced rebind is in progress, or Detach
     * is listed as enabled again while a valid request waits for Android to connect it.
     */
    RECONNECTING,
    /**
     * Detach is listed as enabled, but its service is not connected and no valid request is
     * waiting (it expired, for example after Android lost the bind): nothing will retry, so a
     * Resume tap is needed.
     */
    RESUME_STALLED,
    /** The pause's end time has passed. */
    DUE,
    /** App detection is wanted for this pause and running (or a start is on its way). */
    DETECTION_ON,
    /** App detection is wanted for this pause but is not running. */
    DETECTION_STOPPED,
    /** Only the timer: it still has this pause's automatic authority. */
    AUTOMATIC,
    /** No automatic authority (restart, update, or already spent): tap Resume. */
    MANUAL,
    /** No USB grant: Detach can't switch itself back on. */
    NEEDS_USB;

    /** The timer (or app detection) can still resume this pause without a tap. */
    val automatic get() = this == DETECTION_ON || this == DETECTION_STOPPED || this == AUTOMATIC

    companion object {
        fun of(context: Context): PauseStatus = when {
            Pause.rebinding -> RECONNECTING
            Pause.serviceListed(context) &&
                (DetachService.isRunning || Pause.resumeAuthorized(context)) -> RECONNECTING
            Pause.serviceListed(context) -> RESUME_STALLED
            Pause.isExpired(context) -> DUE
            PauseForegroundMonitor.wanted(context) && PauseForegroundMonitor.runningOrStarting() -> DETECTION_ON
            PauseForegroundMonitor.wanted(context) -> DETECTION_STOPPED
            Pause.resumesAutomatically(context) -> AUTOMATIC
            Pause.canSelfEnable(context) -> MANUAL
            else -> NEEDS_USB
        }
    }
}

/**
 * Tells Detach's visible screen that protection or pause state may have changed (the service
 * connected or went away, a pause began, ended or lost its automatic authority, app detection
 * started or stopped). Event driven: nothing polls. Listeners run on the main thread.
 */
internal object StatusEvents {
    private val listeners = CopyOnWriteArraySet<Runnable>()
    private val main by lazy { Handler(Looper.getMainLooper()) }

    fun add(listener: Runnable) { listeners.add(listener) }
    fun remove(listener: Runnable) { listeners.remove(listener) }

    fun changed() {
        if (listeners.isEmpty()) return
        runCatching { main.post { listeners.forEach { it.run() } } }
    }
}

object StatusNotifications {
    private const val CHANNEL = "status"
    private const val OPEN_REQUEST = 7100
    private const val PAUSE_REQUEST = 7101
    private const val LEGACY_REMINDER_ID = 7002
    internal const val PAUSE_ACTION = "Pause for payment"
    internal const val PAUSED_TITLE = "Paused"
    internal const val RECONNECTING = "Not back soon? Open Detach"
    internal const val DETECTION_STOPPED = "Detection stopped"
    internal const val RESUME_STALLED = "Didn't resume · tap to retry"
    internal const val TAP_TO_RESUME = "Tap to resume"
    internal const val OPEN_TO_RESUME = "Open Detach to resume"

    /** The one line shown while a payment pause can resume by itself. */
    internal fun resumesBy(context: Context) = "Resumes by about ${Pause.untilText(context)}"

    /** Detection stopped, but the timer still resumes: same time as the screen shows. */
    internal fun detectionStoppedText(context: Context) =
        "$DETECTION_STOPPED · ${resumesBy(context).replaceFirstChar { it.lowercase() }}"

    /** What this process last posted as the status notification, or null if it is not posted. */
    @Volatile private var posted: String? = null

    fun refresh(context: Context) {
        try {
            post(context.applicationContext)
        } finally {
            StatusEvents.changed()
        }
    }

    private fun listed(manager: NotificationManager, id: Int): Boolean =
        runCatching { manager.activeNotifications.any { it.id == id } }.getOrDefault(true)

    private fun cancelStatus(manager: NotificationManager) {
        if (posted != null || listed(manager, Pause.NOTIFICATION_ID)) manager.cancel(Pause.NOTIFICATION_ID)
        posted = null
    }

    /**
     * Posts the status only when what it says changed (or it is no longer shown), as an update
     * of the same notification: never cancelled and re-posted for the same content.
     */
    private fun post(app: Context) {
        val manager = app.getSystemService(NotificationManager::class.java)
        if (listed(manager, LEGACY_REMINDER_ID)) manager.cancel(LEGACY_REMINDER_ID)
        // A monitor notification without a running monitor (process killed, or a stop racing the
        // notification's first post) must not keep claiming that detection is on.
        PauseForegroundMonitor.clearOrphanNotification(app, fromVisibleActivity = false)
        val paused = Pause.isPending(app)
        val breather = if (paused) 0L else Breather.remainingMs(app)
        if (!paused && (!DetachService.isRunning || !Settings(app).pauseNotification)) {
            cancelStatus(manager)
            return
        }
        val status = if (paused) PauseStatus.of(app) else null

        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, "Protection status", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Silent status and pause button"
                setSound(null, null)
                enableVibration(false)
                setShowBadge(false)
                lockscreenVisibility = Notification.VISIBILITY_PRIVATE
            },
        )
        val open = PendingIntent.getActivity(
            app, OPEN_REQUEST,
            Intent(app, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        // One short line per status; the screen and docs carry the explanations.
        val title = when {
            !paused && breather > 0L -> "Breather"
            !paused -> "Detach is on"
            status == PauseStatus.RECONNECTING -> "Resuming…"
            else -> PAUSED_TITLE
        }
        val text: String? = when (status) {
            null -> if (breather > 0L) "${(breather + 59_999L) / 60_000L} min left" else null
            // Also what stays shown if Detach's process dies while waiting, so it says what to do then.
            PauseStatus.RECONNECTING -> RECONNECTING
            PauseStatus.RESUME_STALLED -> RESUME_STALLED
            PauseStatus.DUE, PauseStatus.MANUAL -> TAP_TO_RESUME
            // Only the monitor's own notification says detection is on: it disappears with the
            // monitor, while this one cannot be updated once the process is gone. Opening Detach
            // (this notification's tap) restarts detection.
            PauseStatus.DETECTION_STOPPED -> detectionStoppedText(app)
            PauseStatus.DETECTION_ON, PauseStatus.AUTOMATIC -> resumesBy(app)
            PauseStatus.NEEDS_USB -> OPEN_TO_RESUME
        }
        val content = "$paused|$title|$text"
        if (content == posted && listed(manager, Pause.NOTIFICATION_ID)) return
        val publicVersion = Notification.Builder(app, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Detach")
            .setContentText("Open Detach for status")
            .setContentIntent(open)
            .setLocalOnly(true)
            .setShowWhen(false)
            .build()
        val builder = Notification.Builder(app, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(open)
            .setPublicVersion(publicVersion)
            .setLocalOnly(true)
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setCategory(Notification.CATEGORY_STATUS)
        if (!paused) {
            val pause = PendingIntent.getActivity(
                app, PAUSE_REQUEST,
                Intent(app, PauseActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            builder.addAction(
                Notification.Action.Builder(
                    Icon.createWithResource(app, R.drawable.ic_notification), PAUSE_ACTION, pause,
                ).build(),
            )
        }
        try {
            manager.notify(Pause.NOTIFICATION_ID, builder.build())
            posted = content
        } catch (_: SecurityException) {
            cancelStatus(manager)
        }
    }

    /**
     * The owner opted in to app detection for this pause and it could run, but no monitor is
     * running in this process (Android stopped it, refused to start it, or killed the process).
     */
    internal fun detectionStopped(context: Context): Boolean =
        Pause.isPending(context) && PauseStatus.of(context) == PauseStatus.DETECTION_STOPPED
}

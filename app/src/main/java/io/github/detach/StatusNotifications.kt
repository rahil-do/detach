package io.github.detach

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon

object StatusNotifications {
    private const val CHANNEL = "status"
    private const val OPEN_REQUEST = 7100
    private const val PAUSE_REQUEST = 7101
    private const val LEGACY_REMINDER_ID = 7002
    internal const val PAY_HINT =
        "Paying from a checkout? Pause first, or select the app you pay from in Detach, so the UPI app opens with Detach already off."
    internal const val RESTART_HINT =
        "If Google Pay still says an accessibility app is on, restart your phone once. Detach stays paused through the restart until you resume it in Detach."

    fun refresh(context: Context) {
        val app = context.applicationContext
        val manager = app.getSystemService(NotificationManager::class.java)
        manager.cancel(LEGACY_REMINDER_ID)
        val paused = Pause.isPending(app)
        val expired = paused && Pause.isExpired(app)
        val breather = if (paused) 0L else Breather.remainingMs(app)
        if (!paused && (!DetachService.isRunning || !Settings(app).pauseNotification)) {
            manager.cancel(Pause.NOTIFICATION_ID)
            return
        }

        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, "Protection status", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Silent protection status and payment-pause controls"
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
        val title = when {
            !paused && breather > 0L -> "Breather active"
            !paused -> "Protection is on"
            expired -> "Protection is still paused"
            else -> "Protection paused"
        }
        val text = when {
            !paused && breather > 0L -> "Selected features available for up to ${(breather + 59_999L) / 60_000L} min · usual limits return automatically"
            !paused -> "On-device protection · you're in control"
            expired -> "Timer is due. If protection has not resumed, open Detach."
            Pause.resumesAutomatically(app) -> "Automatic resume after ${Pause.untilText(app)} · open Detach to resume sooner"
            Pause.canSelfEnable(app) -> "Reminder at ${Pause.untilText(app)} · open Detach and tap Resume"
            else -> "Reminder at ${Pause.untilText(app)} · automatic resume needs optional USB setup"
        }
        val details = when {
            paused -> "$text\n\n$RESTART_HINT"
            breather > 0L -> text
            else -> "$text\n\n$PAY_HINT"
        }
        val publicVersion = Notification.Builder(app, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Detach")
            .setContentText("Open Detach to view protection status.")
            .setContentIntent(open)
            .setLocalOnly(true)
            .setShowWhen(false)
            .build()
        val builder = Notification.Builder(app, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(details))
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
                    Icon.createWithResource(app, R.drawable.ic_notification), "Pause for payments", pause,
                ).build(),
            )
        }
        try {
            manager.notify(Pause.NOTIFICATION_ID, builder.build())
        } catch (_: SecurityException) {
            manager.cancel(Pause.NOTIFICATION_ID)
        }
    }
}

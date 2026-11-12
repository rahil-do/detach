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

    fun refresh(context: Context) {
        val app = context.applicationContext
        val manager = app.getSystemService(NotificationManager::class.java)
        manager.cancel(LEGACY_REMINDER_ID)
        val paused = Pause.isPending(app)
        val expired = paused && Pause.isExpired(app)
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
            !paused -> "Protection is on"
            expired -> "Protection is still paused"
            else -> "Protection paused"
        }
        val text = when {
            !paused -> "On-device protection · you're in control"
            expired -> "Finished paying? Open Detach to resume protection."
            else -> "Reminder at ${Pause.untilText(app)} · stays off until you resume"
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
            .setStyle(Notification.BigTextStyle().bigText(text))
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

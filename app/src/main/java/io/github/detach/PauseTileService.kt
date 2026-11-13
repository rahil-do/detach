package io.github.detach

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

/**
 * "Pause for payment" Quick Settings tile, so protection can be switched off before a checkout
 * opens a UPI app: payment apps may check the enabled accessibility services as they start,
 * before Detach can react to their first window.
 *
 * Only Android's System UI can bind this (BIND_QUICK_SETTINGS_TILE). A tap never pauses by
 * itself: it opens the same [PauseActivity] as the notification action, with the same
 * fingerprint/password confirmation. On the lock screen the phone is unlocked first. While a
 * pause is pending, or when Detach isn't running, a tap opens Detach instead.
 */
class PauseTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        val tile = qsTile ?: return
        val paused = Pause.isPending(this)
        tile.label = LABEL
        tile.state = if (paused) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        if (Build.VERSION.SDK_INT >= 29) tile.subtitle = when {
            paused -> "Paused"
            DetachService.isRunning -> "Before you pay"
            else -> "Detach is off"
        }
        tile.contentDescription = when {
            paused -> "$LABEL. Protection is paused. Opens Detach."
            DetachService.isRunning -> "$LABEL. Asks you to confirm, then switches protection off."
            else -> "$LABEL. Detach is off. Opens Detach."
        }
        runCatching { tile.updateTile() }
    }

    override fun onClick() {
        super.onClick()
        val launch = Runnable { open(target(this)) }
        if (isLocked) unlockAndRun(launch) else launch.run()
    }

    private fun open(intent: Intent) {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val started = runCatching {
            if (Build.VERSION.SDK_INT >= 34) {
                val request = if (intent.component?.className == PauseActivity::class.java.name) PAUSE_REQUEST else OPEN_REQUEST
                startActivityAndCollapse(PendingIntent.getActivity(
                    this, request, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                ))
            } else collapseInto(intent)
        }.isSuccess
        if (!started) runCatching { startActivity(intent) }
    }

    /** Android 13 and older only; Android 14+ requires (and gets) the PendingIntent form above. */
    @Suppress("DEPRECATION")
    @android.annotation.SuppressLint("StartActivityAndCollapseDeprecated")
    private fun collapseInto(intent: Intent) = startActivityAndCollapse(intent)

    companion object {
        const val LABEL = "Pause for payment"
        private const val PAUSE_REQUEST = 7102
        private const val OPEN_REQUEST = 7103

        /** Only ever one of Detach's own non-exported screens; never a direct pause. */
        internal fun target(context: Context): Intent = Intent(
            context,
            if (DetachService.isRunning && !Pause.isPending(context)) PauseActivity::class.java else MainActivity::class.java,
        )
    }
}

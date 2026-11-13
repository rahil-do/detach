package io.github.detach

import android.app.AlarmManager
import android.app.KeyguardManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings as SysSettings
import java.util.Date
import java.util.UUID

/**
 * "Pause for payments": UPI apps refuse to run while a sideloaded accessibility service is on,
 * so Detach can switch its own service off and remind the owner after [durationMs].
 *
 * Switching off is easy (the service calls disableSelf()). Switching back on means writing
 * Detach's own entry into the system's list of enabled accessibility services, which needs
 * WRITE_SECURE_SETTINGS, granted once over adb ([GRANT_COMMAND]). Without it the pause still
 * works, but turning Detach back on is done by hand in Accessibility settings.
 *
 * A single timer may resume a valid same-boot pause. The optional Usage Access monitor checks
 * packages only while paused. Without it, the timer cannot know whether banking has finished.
 * Boot, updates and opening the app never create or renew automatic authorization.
 */
object Pause {
    /** The longest a pause can be set to; also the sanity limit for a stored end time. */
    const val MAX_MS = 60 * 60_000L
    const val RESUME_AUTHORIZATION_MS = 3 * 60_000L
    internal const val AUTOMATIC_DELAY_MS = MAX_MS
    /** How often a due timer looks again while the phone is locked. */
    internal const val LOCKED_RETRY_MS = 30_000L
    /** A locked phone defers the timer only while this much automatic authority is left. */
    internal const val LOCKED_DEFER_MARGIN_MS = 20 * 60_000L

    /** How long the next pause will last: the owner's setting, 1 to 60 minutes. */
    fun durationMs(context: Context) = Settings(context).pauseMinutes * 60_000L

    /** When the current pause began (wall clock), or 0. */
    fun startedAt(context: Context): Long =
        runCatching { prefs(context).getLong(KEY_STARTED, 0L) }.getOrDefault(0L).coerceAtLeast(0L)

    const val ACTION_RESUME = "io.github.detach.action.RESUME"
    const val ACTION_REMIND = "io.github.detach.action.PAYMENT_REMINDER"
    const val GRANT_COMMAND = "adb shell pm grant io.github.detach android.permission.WRITE_SECURE_SETTINGS"

    const val NOTIFICATION_ID = 7001
    private const val KEY_UNTIL = "until"
    private const val KEY_STARTED = "started"
    private const val KEY_RESUME_AUTHORIZED_AT = "resume_authorized_at"
    private const val KEY_RESUME_AUTHORIZED_ELAPSED = "resume_authorized_elapsed"
    private const val KEY_RESUME_BOOT_COUNT = "resume_boot_count"
    private const val KEY_TOKEN = "automatic_token"
    private const val KEY_ELAPSED = "automatic_elapsed"
    private const val KEY_BOOT = "automatic_boot"
    private const val KEY_DURATION = "automatic_duration"
    private const val KEY_ATTEMPTED = "automatic_attempted"
    private const val KEY_PAYMENT = "payment_package"
    /** The selected app was the active window when it started this pause (not just an event). */
    private const val KEY_PAYMENT_IN_FRONT = "payment_in_front"
    // Written by 0.5.0 previews to skip re-pausing over the same app after a timer resume.
    // No longer used (see shouldAutoPausePayment); removed whenever pause state is rewritten.
    private const val KEY_SUPPRESSED_PAYMENT = "suppressed_payment"
    private const val KEY_SUPPRESSED_ELAPSED = "suppressed_elapsed"
    private const val KEY_SUPPRESSED_BOOT = "suppressed_boot"

    private fun prefs(context: Context) = context.getSharedPreferences("pause", Context.MODE_PRIVATE)

    private fun SharedPreferences.Editor.removeResumeAuthorization(): SharedPreferences.Editor =
        remove(KEY_RESUME_AUTHORIZED_AT).remove(KEY_RESUME_AUTHORIZED_ELAPSED).remove(KEY_RESUME_BOOT_COUNT)

    private fun SharedPreferences.Editor.removeLegacySuppression(): SharedPreferences.Editor =
        remove(KEY_SUPPRESSED_PAYMENT).remove(KEY_SUPPRESSED_ELAPSED).remove(KEY_SUPPRESSED_BOOT)

    private fun bootCount(context: Context): Int = runCatching {
        SysSettings.Global.getInt(context.contentResolver, SysSettings.Global.BOOT_COUNT, 0)
    }.getOrDefault(-1)

    /** Wall-clock reminder time, or 0 if Detach isn't paused. Invalid stored data stays paused. */
    fun until(context: Context): Long =
        runCatching { prefs(context).getLong(KEY_UNTIL, 0L) }.getOrDefault(-1L)

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

    /**
     * This pause can still switch Detach back on by itself: the USB grant is present and the
     * pause's same-boot automatic authority is intact. After a restart it is not (the pause and
     * its reminder remain, but resuming takes a tap on Resume in Detach).
     */
    fun resumesAutomatically(context: Context): Boolean = canSelfEnable(context) && automaticToken(context) != null

    /** True once the owner has granted WRITE_SECURE_SETTINGS over adb. */
    fun canSelfEnable(context: Context) =
        context.checkSelfPermission(android.Manifest.permission.WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED

    /**
     * Starts a pause: [record] then [afterRecorded]. A false result means callers must keep
     * protection enabled because persistence failed.
     */
    @Synchronized
    fun begin(context: Context, paymentPackage: String? = null, paymentInFront: Boolean = false): Boolean {
        if (!record(context, paymentPackage, paymentInFront)) return false
        afterRecorded(context)
        return true
    }

    /**
     * The part of starting a pause that must happen before the service calls disableSelf():
     * the durable pending marker (fail-closed: false means keep protection on) and the optional
     * monitor start, which relies on the still-bound accessibility service's allowance to start
     * a foreground service. Everything else waits for [afterRecorded], so a payment app that
     * checks accessibility at launch finds Detach switched off as early as possible.
     * [paymentInFront]: [paymentPackage] was read from the active window, so the optional monitor
     * may treat it as in front until Android reports it left (rather than as a 30 second hint).
     */
    @Synchronized
    internal fun record(context: Context, paymentPackage: String? = null, paymentInFront: Boolean = false): Boolean {
        val app = context.applicationContext
        val now = System.currentTimeMillis()
        val duration = durationMs(app)
        val until = now + duration
        val stored = prefs(app)
        val payment = paymentPackage?.takeIf { it in Settings(app).paymentPackages }
        val saved = stored.edit().putLong(KEY_UNTIL, until).putLong(KEY_STARTED, now)
            .putString(KEY_TOKEN, UUID.randomUUID().toString())
            .putLong(KEY_ELAPSED, SystemClock.elapsedRealtime()).putInt(KEY_BOOT, bootCount(app))
            .putLong(KEY_DURATION, duration).putBoolean(KEY_ATTEMPTED, false)
            .putString(KEY_PAYMENT, payment).putBoolean(KEY_PAYMENT_IN_FRONT, payment != null && paymentInFront)
            .removeLegacySuppression().removeResumeAuthorization().commit()
        if (!saved) {
            stored.edit().remove(KEY_UNTIL).remove(KEY_STARTED).remove(KEY_TOKEN).removeResumeAuthorization().commit()
            return false
        }
        PauseForegroundMonitor.startForPause(app)
        return true
    }

    /** Replaces any older alarms with this pause's single reminder and shows the paused status. */
    @Synchronized
    internal fun afterRecorded(context: Context) {
        val app = context.applicationContext
        cancelAlarms(app)
        if (isPending(app) && until(app) > 0L) scheduleAlarm(app, until(app))
        refreshNotification(app)
    }

    /**
     * Explicitly authorizes one reconnect and requests secure-settings enablement when allowed.
     * Returns true when the enable request succeeds; the reconnect still must consume authority.
     */
    @Synchronized
    fun resume(context: Context): Boolean {
        val app = context.applicationContext
        if (!isPending(app)) return true
        PauseForegroundMonitor.stop(app)
        if (!prefs(app).edit().putLong(KEY_RESUME_AUTHORIZED_AT, System.currentTimeMillis())
                .putLong(KEY_RESUME_AUTHORIZED_ELAPSED, SystemClock.elapsedRealtime())
                .putInt(KEY_RESUME_BOOT_COUNT, bootCount(app)).commit()) {
            prefs(app).edit().removeResumeAuthorization().commit()
            refreshNotification(app)
            return false
        }
        cancelAlarms(app)
        if (enableService(app)) {
            PauseForegroundMonitor.stop(app)
            refreshNotification(app)
            return true
        }
        // Can't do it ourselves: the pause is over, and the notification now says so.
        if (!isExpired(app)) prefs(app).edit().putLong(KEY_UNTIL, System.currentTimeMillis()).commit()
        refreshNotification(app)
        return false
    }

    internal fun automaticToken(context: Context): String? = runCatching {
        val stored = prefs(context)
        val now = System.currentTimeMillis()
        val elapsed = SystemClock.elapsedRealtime()
        val started = startedAt(context)
        val beganElapsed = stored.getLong(KEY_ELAPSED, -1L)
        val duration = stored.getLong(KEY_DURATION, 0L)
        val wallAge = now - started
        val elapsedAge = elapsed - beganElapsed
        if (!isPending(context) || started <= 0L || beganElapsed < 0L ||
            duration !in 60_000L..MAX_MS || until(context) != started + duration ||
            stored.getInt(KEY_BOOT, -1) < 0 || stored.getInt(KEY_BOOT, -1) != bootCount(context) ||
            wallAge !in 0..duration + AUTOMATIC_DELAY_MS || elapsedAge !in 0..duration + AUTOMATIC_DELAY_MS ||
            kotlin.math.abs(wallAge - elapsedAge) > 5_000L || stored.getBoolean(KEY_ATTEMPTED, true)) null
        else stored.getString(KEY_TOKEN, null)?.takeIf { it.isNotEmpty() }
    }.getOrNull()

    internal fun automaticDeadlineElapsed(context: Context): Long = runCatching {
        val stored = prefs(context)
        stored.getLong(KEY_ELAPSED, 0L) + stored.getLong(KEY_DURATION, 0L) + AUTOMATIC_DELAY_MS
    }.getOrDefault(0L)

    internal fun startingPaymentPackage(context: Context): String? = runCatching {
        prefs(context).getString(KEY_PAYMENT, null)?.takeIf { it in Settings(context).paymentPackages }
    }.getOrNull()

    /** The starting package was confirmed as the active window, not only seen in an event. */
    internal fun startingPaymentWasInFront(context: Context): Boolean = runCatching {
        startingPaymentPackage(context) != null && prefs(context).getBoolean(KEY_PAYMENT_IN_FRONT, false)
    }.getOrDefault(false)

    internal fun timerDue(context: Context): Boolean = runCatching {
        automaticToken(context) != null && System.currentTimeMillis() >= until(context) &&
            SystemClock.elapsedRealtime() >= prefs(context).getLong(KEY_ELAPSED, Long.MAX_VALUE) +
            prefs(context).getLong(KEY_DURATION, MAX_MS)
    }.getOrDefault(false)

    @Synchronized
    internal fun onTimer(context: Context) {
        val token = automaticToken(context)
        if (token != null && timerDue(context)) {
            when {
                PauseForegroundMonitor.paymentIsKnown(context) ->
                    scheduleAlarm(context, System.currentTimeMillis() + ForegroundPackages.STATE_MAX_MS)
                deferWhileLocked(context) -> scheduleAlarm(context, System.currentTimeMillis() + LOCKED_RETRY_MS)
                else -> resumeAutomatically(context, token)
            }
        }
        remind(context)
    }

    /**
     * While the screen is off or locked the front app is unknown, and a payment app left open
     * (a PIN or OTP screen) checks accessibility again as soon as the phone is unlocked. Instagram
     * and YouTube cannot be used while locked, so the timer waits for an unlock instead of turning
     * Detach on behind the lock screen. Bounded: once less than [LOCKED_DEFER_MARGIN_MS] of the
     * pause's automatic authority is left it resumes anyway, so a pause never outlives it just
     * because the phone stayed in a pocket.
     */
    private fun deferWhileLocked(context: Context): Boolean {
        val unlocked = runCatching {
            context.getSystemService(PowerManager::class.java)?.isInteractive == true &&
                context.getSystemService(KeyguardManager::class.java)?.isKeyguardLocked == false
        }.getOrDefault(true)
        return !unlocked && SystemClock.elapsedRealtime() + LOCKED_DEFER_MARGIN_MS < automaticDeadlineElapsed(context)
    }

    /**
     * [settled]: the monitor has observed continuously for at least one poll since it (re)started,
     * for example after an unlock, so a payment app coming back to the front has had time to show
     * up. Only a due timer waits for it; an observed Instagram/YouTube foreground does not.
     */
    @Synchronized
    internal fun onObservedForeground(
        context: Context, token: String, packageName: String?, paymentForeground: Boolean, settled: Boolean = true,
    ) {
        if (token != automaticToken(context) || paymentForeground ||
            !Settings(context).resumeOnSocialForeground || !PauseForegroundMonitor.hasUsageAccess(context)) return
        val social = packageName in Targets.PACKAGES
        if (social || settled && timerDue(context)) resumeAutomatically(context, token)
    }

    /** Detach's own service is listed in the enabled accessibility services. Unreadable counts as listed. */
    internal fun serviceListed(context: Context): Boolean = runCatching {
        val service = ComponentName(context, DetachService::class.java)
        SysSettings.Secure.getString(context.contentResolver, SysSettings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            .orEmpty().split(':').any { ComponentName.unflattenFromString(it) == service }
    }.getOrDefault(true)

    private fun resumeAutomatically(context: Context, token: String): Boolean {
        if (token != automaticToken(context)) return false
        if (serviceListed(context)) return false
        val stored = prefs(context)
        if (!stored.edit().putBoolean(KEY_ATTEMPTED, true).commit()) return false
        if (!canSelfEnable(context)) {
            PauseForegroundMonitor.stop(context)
            refreshNotification(context)
            return false
        }
        return resume(context)
    }

    /**
     * True when [packageName] is a selected app (UPI, bank or an app the owner pays from) and
     * auto-pause is on. There is deliberately no exemption after an automatic resume: if the
     * timer brings Detach back while a selected app is still in front, it pauses again at once.
     * That cannot become a tight loop, because every reconnect needs fresh authority (a timer
     * that is due at least a minute after the new pause began, an observed Instagram/YouTube
     * foreground, or an explicit Resume tap), and a selected app's foreground is never one.
     * Not synchronized: called for accessibility events on the main thread and only reads.
     */
    fun shouldAutoPausePayment(context: Context, packageName: String?): Boolean {
        if (packageName == null) return false
        val settings = Settings(context)
        return settings.autoPausePayments && packageName in settings.paymentPackages
    }

    /** Allows one reconnect after a valid manual or automatic request within three minutes. */
    @Synchronized
    fun consumeResumeAuthorization(context: Context): Boolean {
        val stored = prefs(context)
        val issuedAt = runCatching { stored.getLong(KEY_RESUME_AUTHORIZED_AT, 0L) }.getOrDefault(0L)
        val issuedElapsed = runCatching { stored.getLong(KEY_RESUME_AUTHORIZED_ELAPSED, -1L) }.getOrDefault(-1L)
        val issuedBoot = runCatching { stored.getInt(KEY_RESUME_BOOT_COUNT, -1) }.getOrDefault(-1)
        val now = System.currentTimeMillis()
        val elapsed = SystemClock.elapsedRealtime()
        val valid = isPending(context) && issuedAt > 0L && issuedAt <= now && now - issuedAt <= RESUME_AUTHORIZATION_MS &&
            issuedElapsed >= 0L && issuedElapsed <= elapsed && elapsed - issuedElapsed <= RESUME_AUTHORIZATION_MS &&
            issuedBoot >= 0 && issuedBoot == bootCount(context)
        return stored.edit().removeResumeAuthorization().commit() && valid
    }

    /** For the app's screen: restore the reminder without ever resuming automatically. */
    fun resumeIfDue(context: Context) {
        remind(context)
    }

    @Synchronized
    fun remind(context: Context) {
        if (!isPending(context)) {
            cancelAlarms(context)
            return
        }
        if (!isExpired(context)) scheduleAlarm(context, until(context))
        else if (automaticToken(context) == null) cancelAlarms(context)
        refreshNotification(context)
    }

    @Synchronized
    internal fun restoreAfterRestart(context: Context) {
        prefs(context).edit().removeResumeAuthorization().remove(KEY_TOKEN).commit()
        PauseForegroundMonitor.stop(context)
        remind(context)
    }

    /** The service is (back) on: whatever pause there was is over. */
    @Synchronized
    fun onServiceStarted(context: Context) {
        val app = context.applicationContext
        cancelAlarms(app)
        prefs(app).edit().remove(KEY_UNTIL).remove(KEY_STARTED).remove(KEY_TOKEN).remove(KEY_ELAPSED)
            .remove(KEY_BOOT).remove(KEY_DURATION).remove(KEY_ATTEMPTED).remove(KEY_PAYMENT).remove(KEY_PAYMENT_IN_FRONT)
            .removeLegacySuppression().removeResumeAuthorization().commit()
        PauseForegroundMonitor.stop(app)
        refreshNotification(app)
    }

    /**
     * Adds Detach's own service to the system's list of enabled accessibility services, leaving
     * every other entry exactly as it was. This is the only path that writes secure settings.
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

    private fun reminderIntent(context: Context): PendingIntent = PendingIntent.getBroadcast(
        context, 1,
        Intent(context, PauseReceiver::class.java).setAction(ACTION_REMIND),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    // Exact alarms are only set where they need no permission (below Android 12) or are already allowed.
    @android.annotation.SuppressLint("MissingPermission")
    private fun scheduleAlarm(context: Context, at: Long) {
        val am = context.getSystemService(AlarmManager::class.java) ?: return
        val pi = reminderIntent(context)
        // Exact only where it needs no extra permission; otherwise Android may deliver it a little late.
        val exact = Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()
        runCatching {
            if (exact) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
            else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        }.onFailure {
            runCatching { am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi) }
        }
    }

    private fun cancelAlarms(context: Context) {
        listOf(ACTION_REMIND to 1, ACTION_RESUME to 1, ACTION_RESUME to 2).forEach { (action, requestCode) ->
            runCatching {
                val pending = PendingIntent.getBroadcast(
                    context, requestCode, Intent(context, PauseReceiver::class.java).setAction(action),
                    PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE,
                ) ?: return@runCatching
                context.getSystemService(AlarmManager::class.java)?.cancel(pending)
                pending.cancel()
            }
        }
    }

    // ------------------------------------------------------------------ notification

    fun refreshNotification(context: Context) {
        runCatching { StatusNotifications.refresh(context) }
    }
}

/**
 * The private timer can spend existing same-boot authority; boot/update only restore reminders.
 * Legacy resume broadcasts remain ignored. Only Android itself and Detach can reach this receiver.
 */
class PauseReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Pause.ACTION_REMIND -> Pause.onTimer(context)
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED -> Pause.restoreAfterRestart(context)
        }
    }
}

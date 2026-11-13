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
import android.os.Handler
import android.os.Looper
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
 * packages only while paused. With Usage Access granted, every automatic resume first makes one
 * bounded package-only query and waits while a selected app was last reported in front; without
 * it, the timer cannot know whether banking has finished.
 * Boot, updates and opening the app never create or renew automatic authorization.
 */
object Pause {
    /** The longest a pause can be set to; also the sanity limit for a stored end time. */
    const val MAX_MS = 60 * 60_000L
    const val RESUME_AUTHORIZATION_MS = 3 * 60_000L
    internal const val AUTOMATIC_DELAY_MS = MAX_MS
    /** How soon a lost or overdue timer is set again (see [remind]), and the first locked retry. */
    internal const val LOCKED_RETRY_MS = 30_000L
    /**
     * How long a due timer waits before looking again while the phone stays locked: each locked
     * look backs off one step, up to the last. Unlocking (any unlocked look) starts over.
     */
    internal val LOCKED_RETRIES_MS = longArrayOf(LOCKED_RETRY_MS, 60_000L, 2 * 60_000L, 5 * 60_000L)
    /** A locked phone defers the timer only while this much automatic authority is left. */
    internal const val LOCKED_DEFER_MARGIN_MS = 20 * 60_000L
    /**
     * Android gives an inexact alarm no delivery window when it is due sooner than this (and a
     * window of 3/4 of the lead time otherwise), see [nextAlarmAt].
     */
    internal const val PRECISE_LEAD_MS = 10_000L
    /** After a reconnect request, how long Android may take before Detach checks for a stuck bind. */
    internal const val RECONNECT_CHECK_MS = 5_000L
    /** How long Detach's entry stays out of the enabled list while forcing Android to rebind it. */
    internal const val REBIND_GAP_MS = 1_000L
    /** Forced rebinds per reconnect request without a new tap or timer (bounded, never a loop). */
    internal const val MAX_REBINDS = 2
    /**
     * Allowance on top of Android's latest delivery of the reminder alarm before Detach treats
     * it as lost (see [alarmOutstanding]); covers an alarm that is being delivered right now.
     */
    internal const val ALARM_SLACK_MS = 5_000L

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
    /** Durable "rebind in progress": Detach's entry was taken out and must be put back (see [forceRebind]). */
    private const val KEY_REBIND_ELAPSED = "rebind_elapsed"
    private const val KEY_REBIND_BOOT = "rebind_boot"
    /** The outstanding reminder alarm: the deadline it approaches and Android's latest delivery of it. */
    private const val KEY_ALARM_TARGET = "alarm_target"
    private const val KEY_ALARM_LATEST_ELAPSED = "alarm_latest_elapsed"
    private const val KEY_ALARM_BOOT = "alarm_boot"
    /** Locked looks in a row by a due timer (see [LOCKED_RETRIES_MS]). */
    private const val KEY_LOCKED_RETRIES = "locked_retries"

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
        val now = WallClock.now()
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
        val now = WallClock.now()
        val duration = durationMs(app)
        val until = now + duration
        val stored = prefs(app)
        val payment = paymentPackage?.takeIf { it in Settings(app).paymentPackages }
        val saved = stored.edit().putLong(KEY_UNTIL, until).putLong(KEY_STARTED, now)
            .putString(KEY_TOKEN, UUID.randomUUID().toString())
            .putLong(KEY_ELAPSED, SystemClock.elapsedRealtime()).putInt(KEY_BOOT, bootCount(app))
            .putLong(KEY_DURATION, duration).putBoolean(KEY_ATTEMPTED, false)
            .putString(KEY_PAYMENT, payment).putBoolean(KEY_PAYMENT_IN_FRONT, payment != null && paymentInFront)
            .remove(KEY_LOCKED_RETRIES).removeLegacySuppression().removeResumeAuthorization().commit()
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
        // The optional monitor keeps running (and keeps the process in the foreground) until the
        // reconnect is authorized and requested. If recording the authorization fails it stays
        // only while the pause can still use it (a manual tap); an automatic attempt has already
        // spent the pause's automatic authority.
        if (!prefs(app).edit().putLong(KEY_RESUME_AUTHORIZED_AT, WallClock.now())
                .putLong(KEY_RESUME_AUTHORIZED_ELAPSED, SystemClock.elapsedRealtime())
                .putInt(KEY_RESUME_BOOT_COUNT, bootCount(app)).commit()) {
            prefs(app).edit().removeResumeAuthorization().commit()
            if (automaticToken(app) == null) PauseForegroundMonitor.stop(app)
            refreshNotification(app)
            return false
        }
        cancelAlarms(app)
        rebinds = 0
        if (enableService(app)) {
            scheduleReconnectCheck(app)
            scheduleExpiryRefresh(app)
            PauseForegroundMonitor.stop(app)
            refreshNotification(app)
            return true
        }
        // Can't do it ourselves: the pause is over, and the notification now says so. App
        // detection has nothing left to resume.
        if (!isExpired(app)) prefs(app).edit().putLong(KEY_UNTIL, WallClock.now()).commit()
        PauseForegroundMonitor.stop(app)
        refreshNotification(app)
        return false
    }

    internal fun automaticToken(context: Context): String? = runCatching {
        val stored = prefs(context)
        val now = WallClock.now()
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

    /** Elapsed time left until the timer's elapsed half is due (at most [MAX_MS]). */
    private fun elapsedUntilDue(context: Context): Long = runCatching {
        prefs(context).getLong(KEY_ELAPSED, 0L) + prefs(context).getLong(KEY_DURATION, MAX_MS) - SystemClock.elapsedRealtime()
    }.getOrDefault(MAX_MS).coerceAtMost(MAX_MS)

    internal fun timerDue(context: Context): Boolean = runCatching {
        automaticToken(context) != null && WallClock.now() >= until(context) &&
            SystemClock.elapsedRealtime() >= prefs(context).getLong(KEY_ELAPSED, Long.MAX_VALUE) +
            prefs(context).getLong(KEY_DURATION, MAX_MS)
    }.getOrDefault(false)

    @Synchronized
    internal fun onTimer(context: Context) {
        // This alarm was delivered: none is outstanding until one is set below.
        clearAlarmRecord(context)
        finishInterruptedRebind(context)
        val token = automaticToken(context)
        if (token != null && timerDue(context)) {
            val locked = phoneLocked(context)
            if (!locked) runCatching { prefs(context).edit().remove(KEY_LOCKED_RETRIES).apply() }
            when {
                PauseForegroundMonitor.paymentIsKnown(context) ->
                    scheduleAlarm(context, WallClock.now() + ForegroundPackages.STATE_MAX_MS, stepped = false)
                locked && deferWhileLocked(context) -> {
                    scheduleAlarm(context, WallClock.now() + lockedRetryDelay(context), stepped = false)
                    // Best effort: if app detection was lost, bring it back so that after the
                    // unlock it can hold the pause while a payment app is in front. Android 12+
                    // usually refuses a foreground start from an inexact alarm; then nothing changes.
                    PauseForegroundMonitor.reattach(context)
                }
                // Also the locked fallback once the margin is reached: a selected app that Android
                // last reported in front defers it too.
                else -> resumeAutomatically(context, token) {
                    val retry = if (locked) lockedRetryDelay(context) else ForegroundPackages.STATE_MAX_MS
                    scheduleAlarm(context, WallClock.now() + retry, stepped = false)
                }
            }
        } else if (token != null) {
            // The wall clock can reach the end time a little before elapsed time does (a clock
            // correction): remind() schedules nothing once the end time has passed, so look
            // again when the elapsed half is due.
            if (isExpired(context)) scheduleAlarm(context, WallClock.now() + elapsedUntilDue(context).coerceAtLeast(1_000L), stepped = false)
            PauseForegroundMonitor.reattach(context)
        }
        remind(context)
    }

    /** The screen is off or the keyguard is locked. Unreadable counts as unlocked. */
    private fun phoneLocked(context: Context): Boolean = !runCatching {
        context.getSystemService(PowerManager::class.java)?.isInteractive == true &&
            context.getSystemService(KeyguardManager::class.java)?.isKeyguardLocked == false
    }.getOrDefault(true)

    /**
     * While the screen is off or locked the front app is unknown, and a payment app left open
     * (a PIN or OTP screen) checks accessibility again as soon as the phone is unlocked. Instagram
     * and YouTube cannot be used while locked, so the timer waits for an unlock instead of turning
     * Detach on behind the lock screen, looking again less often the longer the phone stays
     * locked ([LOCKED_RETRIES_MS]). Bounded: once less than [LOCKED_DEFER_MARGIN_MS] of the
     * pause's automatic authority is left it resumes anyway (unless a selected app is known to be
     * in front), so a pause never outlives it just because the phone stayed in a pocket.
     */
    private fun deferWhileLocked(context: Context): Boolean =
        SystemClock.elapsedRealtime() + LOCKED_DEFER_MARGIN_MS < automaticDeadlineElapsed(context)

    /** The next locked retry delay, one backoff step further than the last. */
    private fun lockedRetryDelay(context: Context): Long {
        val stored = runCatching { prefs(context) }.getOrNull() ?: return LOCKED_RETRY_MS
        val step = runCatching { stored.getInt(KEY_LOCKED_RETRIES, 0) }.getOrDefault(0).coerceIn(0, LOCKED_RETRIES_MS.size - 1)
        runCatching { stored.edit().putInt(KEY_LOCKED_RETRIES, step + 1).apply() }
        return LOCKED_RETRIES_MS[step]
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
        if (social || settled && timerDue(context)) resumeAutomatically(context, token, social = social)
    }

    /** When the monitor last resumed because Instagram/YouTube was in front (this process only). */
    @Volatile private var socialResumeAt = Long.MIN_VALUE

    /**
     * The reconnect now starting followed a resume for Instagram/YouTube within a few seconds.
     * Read once by the reconnecting service: right after such a resume, the active window can
     * still be the payment app the owner just left, so it is re-read before pausing again.
     */
    internal fun takeRecentSocialResume(): Boolean {
        val at = socialResumeAt
        socialResumeAt = Long.MIN_VALUE
        return at != Long.MIN_VALUE && SystemClock.elapsedRealtime() - at in 0..SOCIAL_RESUME_WINDOW_MS
    }

    private const val SOCIAL_RESUME_WINDOW_MS = 10_000L

    /** Detach's own service is listed in the enabled accessibility services. Unreadable counts as listed. */
    internal fun serviceListed(context: Context): Boolean = runCatching {
        val service = ComponentName(context, DetachService::class.java)
        SysSettings.Secure.getString(context.contentResolver, SysSettings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            .orEmpty().split(':').any { ComponentName.unflattenFromString(it) == service }
    }.getOrDefault(true)

    /**
     * Every automatic resume goes through here. [social]: Instagram/YouTube was seen in front.
     * [onPaymentInFront]: runs instead of resuming when a selected app is known to be in front
     * ([PauseForegroundMonitor.paymentLeftInFront]); the caller looks again later.
     */
    private fun resumeAutomatically(
        context: Context, token: String, social: Boolean = false, onPaymentInFront: () -> Unit = {},
    ): Boolean {
        if (token != automaticToken(context)) return false
        // Listed means a reconnect is already on its way, unless nothing is connecting it: no
        // unexpired request is waiting and the service isn't running (Android lost the bind,
        // for example when Detach's process was killed mid-resume). Then the timer may still
        // request one, and resume() forces Android to rebind.
        if (serviceListed(context) && (DetachService.isRunning || resumeAuthorized(context))) return false
        if (canSelfEnable(context) && PauseForegroundMonitor.paymentLeftInFront(context)) {
            onPaymentInFront()
            return false
        }
        if (social) socialResumeAt = SystemClock.elapsedRealtime()
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
        val valid = resumeAuthorized(context)
        return prefs(context).edit().removeResumeAuthorization().commit() && valid
    }

    /** A reconnect request is waiting: issued this boot, under three minutes ago, not consumed. Read only. */
    internal fun resumeAuthorized(context: Context): Boolean {
        val stored = prefs(context)
        val issuedAt = runCatching { stored.getLong(KEY_RESUME_AUTHORIZED_AT, 0L) }.getOrDefault(0L)
        val issuedElapsed = runCatching { stored.getLong(KEY_RESUME_AUTHORIZED_ELAPSED, -1L) }.getOrDefault(-1L)
        val issuedBoot = runCatching { stored.getInt(KEY_RESUME_BOOT_COUNT, -1) }.getOrDefault(-1)
        val now = WallClock.now()
        val elapsed = SystemClock.elapsedRealtime()
        return isPending(context) && issuedAt > 0L && issuedAt <= now && now - issuedAt <= RESUME_AUTHORIZATION_MS &&
            issuedElapsed >= 0L && issuedElapsed <= elapsed && elapsed - issuedElapsed <= RESUME_AUTHORIZATION_MS &&
            issuedBoot >= 0 && issuedBoot == bootCount(context)
    }

    /** For the app's screen: restore the reminder without ever resuming automatically. */
    fun resumeIfDue(context: Context) {
        remind(context)
    }

    /**
     * Restores the pause's reminder and status. A future deadline gets its alarm (again). A due
     * timer that still has automatic authority keeps the alarm it already has: one still
     * approaching a later time is set again for that same time (a force-stop removes Detach's
     * alarms; setting it again never moves it later), and one inside Android's delivery window is
     * left alone. If the last alarm is gone or overdue past Android's latest delivery (lost to a
     * force-stop, or none was set), the timer is set again [LOCKED_RETRY_MS] from now. That never
     * creates or extends authority: the timer re-checks it, and the lock and payment checks, when
     * it fires.
     */
    @Synchronized
    fun remind(context: Context) {
        finishInterruptedRebind(context)
        if (!isPending(context)) {
            cancelAlarms(context)
            return
        }
        if (!isExpired(context)) scheduleAlarm(context, until(context))
        else if (automaticToken(context) == null) cancelAlarms(context)
        else {
            val target = alarmTarget(context)
            when {
                target > WallClock.now() -> scheduleAlarm(context, target, stepped = false)
                !alarmOutstanding(context) -> scheduleAlarm(context, WallClock.now() + LOCKED_RETRY_MS, stepped = false)
            }
        }
        refreshNotification(context)
    }

    /**
     * BOOT_COMPLETED is a real restart only if the boot count changed (or is unreadable) or the
     * elapsed clock is behind the pause's start. Android 15+ also delivers BOOT_COMPLETED when a
     * force-stopped app is started again, within the same boot: that must not cost the pause
     * its automatic resume.
     */
    internal fun rebootedSincePause(context: Context): Boolean = runCatching {
        val stored = prefs(context)
        val boot = stored.getInt(KEY_BOOT, -1)
        val current = bootCount(context)
        boot < 0 || current < 0 || boot != current || stored.getLong(KEY_ELAPSED, Long.MAX_VALUE) > SystemClock.elapsedRealtime()
    }.getOrDefault(true)

    /** Boot completed: a real reboot ends automatic authority; a same-boot re-delivery only re-arms. */
    @Synchronized
    internal fun onBootCompleted(context: Context) {
        if (!isPending(context) || rebootedSincePause(context)) restoreAfterRestart(context)
        else restoreAfterStop(context)
    }

    /** After a reboot or an update: the pause and its reminder stay, automatic authority does not. */
    @Synchronized
    internal fun restoreAfterRestart(context: Context) {
        prefs(context).edit().removeResumeAuthorization().remove(KEY_TOKEN).commit()
        PauseForegroundMonitor.stop(context)
        remind(context)
    }

    /**
     * Detach was stopped (force-stop removes its alarms and notifications) and is running again
     * in the same boot: restore the timer and the status, and re-attach app detection for the
     * same pause while its timer is not due yet. Authority is unchanged.
     */
    @Synchronized
    internal fun restoreAfterStop(context: Context) {
        if (isPending(context) && !isExpired(context)) PauseForegroundMonitor.reattach(context)
        remind(context)
    }

    /** The service is (back) on: whatever pause there was is over. */
    @Synchronized
    fun onServiceStarted(context: Context) {
        val app = context.applicationContext
        cancelAlarms(app)
        prefs(app).edit().remove(KEY_UNTIL).remove(KEY_STARTED).remove(KEY_TOKEN).remove(KEY_ELAPSED)
            .remove(KEY_BOOT).remove(KEY_DURATION).remove(KEY_ATTEMPTED).remove(KEY_PAYMENT).remove(KEY_PAYMENT_IN_FRONT)
            .remove(KEY_LOCKED_RETRIES).removeLegacySuppression().removeResumeAuthorization().commit()
        PauseForegroundMonitor.stop(app)
        refreshNotification(app)
    }

    /**
     * Adds Detach's own service to the system's list of enabled accessibility services, leaving
     * every other entry exactly as it was. If Detach is already listed but its service is not
     * running, Android has lost the bind (seen when Detach's process was killed while Android
     * was binding it: the entry stays, nothing ever connects, and writing the same list again
     * changes nothing), so the entry is taken out and put back to make Android bind it again.
     * Only this file writes secure settings.
     */
    private fun enableService(context: Context): Boolean {
        if (!canSelfEnable(context)) return false
        return runCatching {
            val cr = context.contentResolver
            val me = ComponentName(context, DetachService::class.java)
            val current = SysSettings.Secure.getString(cr, SysSettings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
            val present = current.split(':').any { ComponentName.unflattenFromString(it) == me }
            if (present && !DetachService.isRunning) return@runCatching forceRebind(context)
            val listed = present || SysSettings.Secure.putString(
                cr, SysSettings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                if (current.isEmpty()) me.flattenToString() else current + ":" + me.flattenToString(),
            )
            listed && SysSettings.Secure.putInt(cr, SysSettings.Secure.ACCESSIBILITY_ENABLED, 1)
        }.getOrDefault(false)
    }

    private val main by lazy { Handler(Looper.getMainLooper()) }
    private val rebindToken = Any()
    private val checkToken = Any()
    private val expiryToken = Any()
    /** Forced rebinds since the last reconnect request (this process only). */
    @Volatile private var rebinds = 0
    /** Detach's entry is out of the list for [REBIND_GAP_MS] and this process will put it back. */
    @Volatile private var rebindInFlight = false

    /** A forced rebind is between taking Detach's entry out and putting it back (this process). */
    internal val rebinding: Boolean get() = rebindInFlight

    /**
     * Removes only Detach's own entry from the enabled list, then after [REBIND_GAP_MS] puts the
     * list back exactly as it was (or appends Detach if something else changed it meanwhile), with
     * accessibility_enabled kept at 1. The gap lets Android notice the removal; writing both at
     * once can look like no change at all. Authorization is untouched: the reconnect that follows
     * still has to consume a valid request, or it switches itself off again.
     * A durable marker is written first, so that if this process dies during the gap the next
     * run of Detach (opening it, the tile, the timer, boot) finishes the job, still only while a
     * waiting request allows it ([finishInterruptedRebind]).
     */
    private fun forceRebind(context: Context): Boolean {
        val app = context.applicationContext
        if (!canSelfEnable(app)) return false
        return runCatching {
            val cr = app.contentResolver
            val me = ComponentName(app, DetachService::class.java)
            val original = SysSettings.Secure.getString(cr, SysSettings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
            val without = original.split(':').filter { it.isNotEmpty() && ComponentName.unflattenFromString(it) != me }.joinToString(":")
            if (!prefs(app).edit().putLong(KEY_REBIND_ELAPSED, SystemClock.elapsedRealtime())
                    .putInt(KEY_REBIND_BOOT, bootCount(app)).commit()) return@runCatching false
            if (!SysSettings.Secure.putString(cr, SysSettings.Secure.ENABLED_ACCESSIBILITY_SERVICES, without)) {
                clearRebindMarker(app)
                return@runCatching false
            }
            rebindInFlight = true
            main.removeCallbacksAndMessages(rebindToken)
            main.postAtTime({ addBack(app, original, without) }, rebindToken, SystemClock.uptimeMillis() + REBIND_GAP_MS)
            true
        }.getOrDefault(false)
    }

    /**
     * Detach's entry may be put back: no pause is pending (the reconnect already happened, or
     * the owner had Detach on outside a pause), or a valid reconnect request is still waiting.
     * A pause recorded during the gap (a selected payment app came to the front) removes the
     * request, so Detach then stays out of the list.
     */
    private fun mayRelist(context: Context): Boolean = !isPending(context) || resumeAuthorized(context)

    /** Second half of [forceRebind]. */
    @Synchronized
    private fun addBack(context: Context, original: String, without: String) {
        rebindInFlight = false
        clearRebindMarker(context)
        if (mayRelist(context) && relist(context, original, without)) scheduleReconnectCheck(context)
        refreshNotification(context)
    }

    /** Puts Detach's entry back ([original] if nothing else changed the list meanwhile). */
    private fun relist(context: Context, original: String?, without: String?): Boolean = runCatching {
        val cr = context.contentResolver
        val me = ComponentName(context, DetachService::class.java)
        val current = SysSettings.Secure.getString(cr, SysSettings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
        if (current.split(':').any { ComponentName.unflattenFromString(it) == me }) return@runCatching false
        val restored = when {
            original != null && current == without -> original
            current.isEmpty() -> me.flattenToString()
            else -> current + ":" + me.flattenToString()
        }
        SysSettings.Secure.putString(cr, SysSettings.Secure.ENABLED_ACCESSIBILITY_SERVICES, restored) &&
            SysSettings.Secure.putInt(cr, SysSettings.Secure.ACCESSIBILITY_ENABLED, 1)
    }.getOrDefault(false)

    private fun clearRebindMarker(context: Context) {
        runCatching { prefs(context).edit().remove(KEY_REBIND_ELAPSED).remove(KEY_REBIND_BOOT).commit() }
    }

    /**
     * A forced rebind whose process died during the gap left Detach out of the list. Put it
     * back only under the same rule as [addBack], and only for a marker from this boot that is
     * at most [RESUME_AUTHORIZATION_MS] old; otherwise just forget it (fail closed: the pause
     * stays and Resume works as usual).
     */
    private fun finishInterruptedRebind(context: Context) {
        if (rebindInFlight) return
        val stored = runCatching { prefs(context) }.getOrNull() ?: return
        if (!stored.contains(KEY_REBIND_ELAPSED)) return
        val at = runCatching { stored.getLong(KEY_REBIND_ELAPSED, -1L) }.getOrDefault(-1L)
        val boot = runCatching { stored.getInt(KEY_REBIND_BOOT, -1) }.getOrDefault(-1)
        clearRebindMarker(context)
        val age = SystemClock.elapsedRealtime() - at
        if (at < 0L || boot < 0 || boot != bootCount(context) || age !in 0..RESUME_AUTHORIZATION_MS) return
        if (!canSelfEnable(context) || serviceListed(context) || !mayRelist(context)) return
        if (relist(context, null, null)) scheduleReconnectCheck(context)
    }

    /**
     * Runs [block] once a forced rebind started by this call chain has put Detach back (at once
     * if none is in progress). The timer's receiver keeps its broadcast open until then, so the
     * process is not left cached, and killed, with Detach out of the list.
     */
    internal fun afterRebind(block: () -> Unit) {
        if (rebindInFlight) main.postDelayed({ block() }, REBIND_GAP_MS + 500L) else block()
    }

    /** One check, [RECONNECT_CHECK_MS] from now, that the requested reconnect really happened. */
    private fun scheduleReconnectCheck(context: Context) {
        val app = context.applicationContext
        main.removeCallbacksAndMessages(checkToken)
        main.postAtTime({ recoverStuckReconnect(app) }, checkToken, SystemClock.uptimeMillis() + RECONNECT_CHECK_MS)
    }

    /**
     * One status refresh just after a reconnect request expires, so that a reconnect Android
     * never completed stops saying it is waiting (see [PauseStatus.RESUME_STALLED]). One-shot.
     */
    private fun scheduleExpiryRefresh(context: Context) {
        val app = context.applicationContext
        main.removeCallbacksAndMessages(expiryToken)
        main.postAtTime({ refreshNotification(app) }, expiryToken, SystemClock.uptimeMillis() + RESUME_AUTHORIZATION_MS + 1_000L)
    }

    /**
     * Detach is listed as enabled but its service is not connected, although Android has had
     * [RECONNECT_CHECK_MS] to connect it. Forces a rebind, at most [MAX_REBINDS] times per
     * reconnect request (a Resume tap or a timer/app-detection resume starts a new count).
     * During a pause this only happens while an unexpired request is waiting, so the reconnect
     * consumes that request; outside a pause Detach is listed because the owner turned it on.
     * Returns true if a rebind was started.
     */
    @Synchronized
    internal fun recoverStuckReconnect(context: Context): Boolean {
        val app = context.applicationContext
        if (DetachService.isRunning || rebindInFlight || !canSelfEnable(app) || !serviceListed(app) || rebinds >= MAX_REBINDS) return false
        if (isPending(app) && !resumeAuthorized(app)) return false
        rebinds++
        val started = forceRebind(app)
        if (started) refreshNotification(app)
        return started
    }

    /** Listed as enabled, but Android has not connected the service (yet). */
    internal fun waitingForReconnect(context: Context): Boolean = serviceListed(context) && !DetachService.isRunning

    // ------------------------------------------------------------------ timers

    private fun reminderIntent(context: Context): PendingIntent = PendingIntent.getBroadcast(
        context, 1,
        Intent(context, PauseReceiver::class.java).setAction(ACTION_REMIND),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    /**
     * When to set the next inexact alarm for a deadline at [at]. Android delivers an inexact
     * alarm (set/setAndAllowWhileIdle) anywhere up to 3/4 of its lead time late, and with no
     * window if it is due within [PRECISE_LEAD_MS]. A short setWindow() does not help: for apps
     * targeting Android 12+ the system widens any window to min(3/4 of the lead time, 10 min),
     * and it is not allowed while idle. So a far deadline is approached in steps: each alarm is
     * set at 4/7 of the remaining time, so even delivered at the very end of its window it fires
     * by [at], and each delivery sets the next, shorter step, down to a final alarm with no
     * window. That is a bounded handful of one-shot alarms per pause (about 7 for an hour, 4
     * for five minutes), locked or not: a phone locked after paying must not leave the last
     * alarm minutes late when it is unlocked again. Short retries (a locked phone, a payment app
     * in front) are not stepped: a few seconds either way do not matter for them.
     */
    internal fun nextAlarmAt(now: Long, at: Long): Long {
        val lead = at - now
        if (lead < PRECISE_LEAD_MS) return at
        return now + lead * 4 / 7
    }

    /** Android's latest delivery of an inexact alarm set at [now] for [trigger] (AOSP: 3/4 of the lead, at most 1 h). */
    internal fun latestInexactDelivery(now: Long, trigger: Long): Long {
        val lead = trigger - now
        return trigger + if (lead < PRECISE_LEAD_MS) 0L else minOf(lead * 3 / 4, 60 * 60_000L)
    }

    /** The deadline the outstanding reminder alarm approaches (wall clock), or 0. */
    private fun alarmTarget(context: Context): Long = runCatching {
        val stored = prefs(context)
        if (stored.getInt(KEY_ALARM_BOOT, -1) != bootCount(context)) 0L else stored.getLong(KEY_ALARM_TARGET, 0L)
    }.getOrDefault(0L)

    /**
     * The last reminder alarm set (this boot) has not been delivered, and Android may still
     * deliver it: its latest delivery plus [ALARM_SLACK_MS] has not passed. Whether a
     * PendingIntent exists says nothing here: on Android 9 to 14 a force-stop removes the alarm
     * but keeps the PendingIntent, and a delivered alarm's PendingIntent outlives it too.
     */
    private fun alarmOutstanding(context: Context): Boolean = runCatching {
        val stored = prefs(context)
        val latest = stored.getLong(KEY_ALARM_LATEST_ELAPSED, -1L)
        stored.getInt(KEY_ALARM_BOOT, -1) == bootCount(context) && latest >= 0L &&
            SystemClock.elapsedRealtime() <= latest + ALARM_SLACK_MS
    }.getOrDefault(false)

    private fun clearAlarmRecord(context: Context) {
        runCatching { prefs(context).edit().remove(KEY_ALARM_TARGET).remove(KEY_ALARM_LATEST_ELAPSED).remove(KEY_ALARM_BOOT).apply() }
    }

    // Exact alarms are only set where they need no permission (below Android 12) or are already
    // allowed (granted, or Detach's battery use is "Unrestricted", which also exempts it).
    @android.annotation.SuppressLint("MissingPermission")
    private fun scheduleAlarm(context: Context, at: Long, stepped: Boolean = true) {
        val am = context.getSystemService(AlarmManager::class.java) ?: return
        val pi = reminderIntent(context)
        val now = WallClock.now()
        val exact = Build.VERSION.SDK_INT < 31 || runCatching { am.canScheduleExactAlarms() }.getOrDefault(false)
        val inexactAt = if (stepped) nextAlarmAt(now, at) else at
        val latest = runCatching {
            if (exact) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
            else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, inexactAt, pi)
            if (exact) at else latestInexactDelivery(now, inexactAt)
        }.getOrElse {
            runCatching { am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, inexactAt, pi) }
            latestInexactDelivery(now, inexactAt)
        }
        runCatching {
            prefs(context).edit().putLong(KEY_ALARM_TARGET, at)
                .putLong(KEY_ALARM_LATEST_ELAPSED, SystemClock.elapsedRealtime() + (latest - now).coerceAtLeast(0L))
                .putInt(KEY_ALARM_BOOT, bootCount(context)).apply()
        }
    }

    private fun cancelAlarms(context: Context) {
        clearAlarmRecord(context)
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
 * The private timer can spend existing same-boot authority; a reboot or update only restores
 * reminders, and a same-boot BOOT_COMPLETED (after a force-stop) re-arms the unchanged pause.
 * Legacy resume broadcasts remain ignored. Only Android itself and Detach can reach this receiver.
 */
class PauseReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Pause.ACTION_REMIND -> {
                // Kept open (well under Android's limit) while a forced rebind has Detach out of
                // the enabled list, so the process is not cached and killed in that second.
                val result: PendingResult? = goAsync()
                try {
                    Pause.onTimer(context)
                } finally {
                    Pause.afterRebind { result?.finish() }
                }
            }
            // Also re-delivered by Android 15+ when a force-stopped Detach is started again.
            Intent.ACTION_BOOT_COMPLETED -> Pause.onBootCompleted(context)
            Intent.ACTION_MY_PACKAGE_REPLACED -> Pause.restoreAfterRestart(context)
        }
    }
}

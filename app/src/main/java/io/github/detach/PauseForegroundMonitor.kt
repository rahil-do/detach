package io.github.detach

import android.app.AppOpsManager
import android.app.KeyguardManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.os.UserManager
import android.provider.Settings as SystemSettings

internal class ForegroundPackages {
    /**
     * Instagram or YouTube, observed resuming during this pause and not yet observed pausing.
     * Unlike the old 30 second hint this lasts until the app's own background event (or until
     * observation is interrupted, which clears everything): Instagram and YouTube are
     * effectively single-activity apps, so a user scrolling in them produces no new events, and
     * a payment hold that clears late must still find them in front. It never outlives a
     * payment hold that ends by timeout rather than by the payment app's own background event:
     * that app may still be on screen (split screen), so only a fresh foreground counts then.
     */
    var social: String? = null
        private set
    /** Selected apps observed in front, held until Android reports them in the background. */
    private val payments = mutableMapOf<String, Long>()
    /**
     * The selected app that started the pause, read from the active window. Its own foreground
     * event predates the pause, so it is held exactly like [payments]: only its own background
     * event or [PAYMENT_HOLD_MS] releases it. An Instagram/YouTube foreground does not: in split
     * screen (multi-resume) the payment app stays resumed and on screen next to Instagram.
     */
    private val seeds = mutableMapOf<String, Long>()
    /** Unobserved starting-package hints: only [STATE_MAX_MS], like any other unconfirmed hint. */
    private val hints = mutableMapOf<String, Long>()

    fun accept(packageName: String, eventType: Int, paymentPackages: Set<String>, observedAt: Long) {
        when (eventType) {
            UsageEvents.Event.MOVE_TO_FOREGROUND -> {
                if (packageName in paymentPackages) {
                    payments[packageName] = observedAt
                    seeds.remove(packageName)
                    hints.remove(packageName)
                }
                if (packageName in Targets.PACKAGES) social = packageName
            }
            UsageEvents.Event.MOVE_TO_BACKGROUND -> {
                payments.remove(packageName)
                seeds.remove(packageName)
                hints.remove(packageName)
                if (social == packageName) social = null
            }
        }
        payments.keys.retainAll(paymentPackages)
        seeds.keys.retainAll(paymentPackages)
        hints.keys.retainAll(paymentPackages)
    }

    /**
     * The selected app that started the pause, before any usage event for it is observed (its own
     * foreground event predates the pause). [inFront]: it was read from the active window, so it
     * is held like an observed foreground until it is observed leaving or the hold ends.
     * Otherwise the pause came from an event alone, the app may never have been in front, and it
     * is only a [STATE_MAX_MS] hint.
     */
    fun seed(packageName: String, paymentPackages: Set<String>, observedAt: Long, inFront: Boolean) {
        if (packageName !in paymentPackages) return
        if (inFront) seeds[packageName] = observedAt else hints[packageName] = observedAt
    }

    /** The starting apps still held only by inference, with no event of their own observed yet. */
    fun seeded(): Set<String> = seeds.keys.toSet()

    /** Instagram or YouTube in front as far as continuous observation knows, or null. */
    fun socialForeground(): String? = social

    /**
     * A selected app (UPI, bank or an app the owner pays from) is still in front. Its evidence
     * lasts until Android reports it moved to the background, not a fixed 30 seconds, so sitting
     * on one payment or PIN screen does not look like "payment finished". This is only safe
     * because the monitor clears all state whenever observation is interrupted (screen off or
     * locked, or a gap between queries longer than [PauseForegroundMonitor.LOOKBACK_MS]), and
     * [PAYMENT_HOLD_MS] still bounds a missed background event. Unobserved hints from [seed]
     * keep the short [STATE_MAX_MS] lifetime.
     */
    fun paymentForegroundAt(now: Long): Boolean {
        val heldOut = payments.entries.removeAll { now - it.value !in 0..PAYMENT_HOLD_MS } or
            seeds.entries.removeAll { now - it.value !in 0..PAYMENT_HOLD_MS }
        hints.entries.removeAll { now - it.value !in 0..STATE_MAX_MS }
        // A hold that timed out was never seen leaving: an Instagram/YouTube foreground observed
        // while it was held may sit next to it in split screen, so it no longer counts.
        if (heldOut) social = null
        return payments.isNotEmpty() || seeds.isNotEmpty() || hints.isNotEmpty()
    }

    fun clear() {
        social = null
        payments.clear()
        seeds.clear()
        hints.clear()
    }

    companion object {
        internal const val STATE_MAX_MS = 30_000L
        /** Upper bound for continuously observed selected-app evidence without a new event. */
        internal const val PAYMENT_HOLD_MS = 10 * 60_000L
    }
}

/**
 * Optional, opt-in app detection during a payment pause. Lifecycle rules, all fail-closed:
 *
 * - Started by [Pause.record] while the accessibility service is still bound (the only moment
 *   Android lets Detach start a foreground service from the background), and re-attached to the
 *   same pause's existing authority by [reattach] from contexts that may start one: Detach's own
 *   visible screen, the still-bound service, or the timer where Android allows it. Nothing here
 *   creates or extends automatic authority; every action re-checks [Pause.automaticToken].
 * - Survives the owner swiping Detach (or "Clear all") out of Recents: the manifest keeps
 *   `stopWithTask="false"` and [onTaskRemoved] only re-checks eligibility. A foreground service
 *   also keeps Android from killing the process for the removed task.
 * - Sticky: if Android kills the process anyway it restarts the service with a null intent,
 *   which monitors only if the pending pause, the opt-in, both grants and the pause's automatic
 *   authority are all still valid, and otherwise stops at once. Android 12+ may refuse the
 *   restarted service's foreground start; it then stops cleanly and the timer remains.
 * - Battery: one bounded Usage Events query about every [POLL_MS], only while the screen is on
 *   and unlocked, on a private thread, with no wake lock. Nothing runs while off or locked.
 */
class PauseForegroundMonitor : Service() {
    private val mainHandler = Handler(Looper.getMainLooper())
    private lateinit var workerThread: HandlerThread
    private lateinit var handler: Handler
    private val packages = ForegroundPackages()
    private val eventBuffer = UsageEvents.Event()
    @Volatile private var token: String? = null
    @Volatile private var stopping = false
    @Volatile private var paymentEvidenceAt = Long.MIN_VALUE
    @Volatile private var paymentCheckedAt = Long.MIN_VALUE
    /**
     * When continuous observation (re)started: monitor start, unlock, or after a query gap.
     * Long.MIN_VALUE while off/locked. Until a poll has passed since then, a payment app that
     * is returning to the front (for example after unlock) may not have been reported yet.
     */
    @Volatile private var observingSince = Long.MIN_VALUE
    /** The newest start delivered to this instance; stopping never discards a newer one. */
    @Volatile private var lastStartId = 0
    private var queryAfter = 0L
    /**
     * Events already applied that are still inside the next query's overlap, keyed by package,
     * type and time, so a replayed event is never applied twice (a replayed Instagram foreground
     * must not come back after a payment hold timed out and cleared it).
     */
    private val applied = HashMap<String, Long>()
    /** Newest applied event time per package: an older event stored late never overrides it. */
    private val newestApplied = HashMap<String, Long>()
    private var registered = false
    private var settings: SharedPreferences? = null
    private var watchingOps = false
    private val poll = Runnable { checkForeground() }
    private val expire = Runnable { stopMonitoring() }
    /**
     * Screen on or unlock was reported while the keyguard still read as locked (unlock animation,
     * face unlock, Smart Lock). Looks again a few times, only while the screen is on.
     */
    private val unlockRecheck = Runnable {
        when {
            !eligible() -> stopMonitoring()
            interactiveAndUnlocked() && observingSince == Long.MIN_VALUE -> startObserving()
        }
    }
    private val preferencesChanged = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
        handler.post { if (!eligible()) stopMonitoring() }
    }
    private val operationChanged = AppOpsManager.OnOpChangedListener { _, _ ->
        handler.post { if (!eligible()) stopMonitoring() }
    }
    private val screenChanged = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            handler.removeCallbacks(poll)
            handler.removeCallbacks(unlockRecheck)
            if (!interactiveAndUnlocked()) {
                paymentEvidenceAt = Long.MIN_VALUE
                observingSince = Long.MIN_VALUE
                handler.post {
                    packages.clear()
                    // The deadline runnable uses uptime, which stops while asleep.
                    if (!eligible()) stopMonitoring()
                }
                if (intent.action != Intent.ACTION_SCREEN_OFF && isInteractive()) {
                    for (delay in UNLOCK_RECHECKS_MS) handler.postDelayed(unlockRecheck, delay)
                }
            } else {
                // Set here, not on the worker, so a timer firing in between already waits a poll.
                if (observingSince == Long.MIN_VALUE) observingSince = SystemClock.elapsedRealtime()
                handler.post { if (eligible()) startObserving() else stopMonitoring() }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        workerThread = HandlerThread("detach-pause-packages").also { it.start() }
        handler = Handler(workerThread.looper)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        lastStartId = startId
        requestedAt = Long.MIN_VALUE
        // Every start reaches startForeground() before anything can stop it: a service started
        // with startForegroundService() that stops first makes Android crash the whole process.
        // A sticky restart after the process was killed may be refused here on Android 12+.
        val foreground = runCatching { showForegroundNotification() }.isSuccess
        // A null intent is Android's sticky restart (the service is not exported, and an app
        // cannot start a service with a null intent); it may only resume the current pause.
        val current = Pause.automaticToken(this)
        val requested = if (intent == null) current else intent.getStringExtra(EXTRA_TOKEN)
        if (!foreground || requested == null || requested != current || !allowed(this)) {
            // A stale or duplicate start must not end monitoring of the current pause.
            if (active === this && eligible()) return START_STICKY
            stopMonitoring()
            return START_NOT_STICKY
        }
        if (active === this && token == requested && !stopping) return START_STICKY
        stopping = false
        token = requested
        active = this
        if (!registered) {
            val filter = IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_USER_PRESENT)
            }
            if (Build.VERSION.SDK_INT >= 33) registerReceiver(screenChanged, filter, RECEIVER_NOT_EXPORTED)
            else registerReceiver(screenChanged, filter)
            registered = true
            settings = getSharedPreferences("settings", MODE_PRIVATE).also {
                it.registerOnSharedPreferenceChangeListener(preferencesChanged)
            }
            if (!runCatching {
                getSystemService(AppOpsManager::class.java).startWatchingMode(
                    AppOpsManager.OPSTR_GET_USAGE_STATS, packageName, operationChanged,
                )
                watchingOps = true
            }.isSuccess) {
                stopMonitoring()
                return START_NOT_STICKY
            }
        }
        handler.removeCallbacks(expire)
        handler.postDelayed(expire, (Pause.automaticDeadlineElapsed(this) - SystemClock.elapsedRealtime() + 1L).coerceAtLeast(1L))
        handler.removeCallbacks(poll)
        handler.removeCallbacks(unlockRecheck)
        // Always pre-filled, also when re-attached from Detach's own screen: in split screen the
        // starting app can still be in front next to Detach. In full screen its own background
        // event, read back to the pause start by the first query, releases it.
        handler.post {
            queryAfter = 0L
            applied.clear()
            newestApplied.clear()
            packages.clear()
            observingSince = Long.MIN_VALUE
            Pause.startingPaymentPackage(this)?.let {
                packages.seed(it, Settings(this).paymentPackages, Pause.startedAt(this), Pause.startingPaymentWasInFront(this))
            }
            if (interactiveAndUnlocked()) startObserving()
        }
        Pause.refreshNotification(this)
        return START_STICKY
    }

    /**
     * The owner removed a Detach task from Recents (often "Clear all" while closing the payment
     * app). The pause is not over, so monitoring continues; only an ineligible monitor stops.
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        if (!::handler.isInitialized) return
        handler.post { if (!eligible()) stopMonitoring() }
    }

    private fun settled(): Boolean {
        val since = observingSince
        return since != Long.MIN_VALUE && paymentCheckedAt - since >= POLL_MS
    }

    private fun eligible() = !stopping && token != null && token == Pause.automaticToken(this) && allowed(this)

    private fun isInteractive(): Boolean = getSystemService(PowerManager::class.java)?.isInteractive == true

    private fun interactiveAndUnlocked(): Boolean =
        isInteractive() &&
            getSystemService(KeyguardManager::class.java)?.isKeyguardLocked == false &&
            getSystemService(UserManager::class.java)?.isUserUnlocked == true

    /** Worker thread: observation (re)starts now, then the first check runs. */
    private fun startObserving() {
        if (observingSince == Long.MIN_VALUE) observingSince = SystemClock.elapsedRealtime()
        checkForeground()
    }

    private fun checkForeground() {
        val checkedToken = token ?: return
        if (!eligible()) {
            stopMonitoring()
            return
        }
        if (!interactiveAndUnlocked()) return
        val now = WallClock.now()
        if (queryAfter > 0L && now - queryAfter > LOOKBACK_MS) {
            packages.clear()
            observingSince = Long.MIN_VALUE
        }
        if (observingSince == Long.MIN_VALUE) observingSince = SystemClock.elapsedRealtime()
        // Consecutive windows overlap: Android stamps an event when it is reported but stores it
        // a little later, so an event stamped just before the previous query can appear only
        // after it. Already applied events in the overlap are skipped below.
        val overlapStart = if (queryAfter > 0L) queryAfter - QUERY_OVERLAP_MS else 0L
        val after = maxOf(overlapStart, now - LOOKBACK_MS, Pause.startedAt(this))
        if (after > now) {
            stopMonitoring()
            return
        }
        // First query of a sticky restart or re-attach that began well after the pause did: the
        // starting app may have left in between, before this window. Read that app's own events
        // back to the pause start once (bounded by the hold), so its seed is released by its
        // real background event rather than held, or dropped, on a guess. Other packages' older
        // events are ignored: only continuous observation counts for them.
        val seeded = if (queryAfter == 0L) packages.seeded() else emptySet()
        val from = if (seeded.isEmpty()) after
            else minOf(after, maxOf(Pause.startedAt(this), now - ForegroundPackages.PAYMENT_HOLD_MS))
        val events = runCatching { getSystemService(UsageStatsManager::class.java)?.queryEvents(from, now) }.getOrNull()
        if (events == null || !eligible()) {
            stopMonitoring()
            return
        }
        if (checkedToken != token) return
        val paymentPackages = Settings(this).paymentPackages
        while (events.hasNextEvent()) {
            events.getNextEvent(eventBuffer)
            val observedPackage = eventBuffer.packageName ?: continue
            // Only foreground/background matter. Tracking other types (for example a stop event
            // stored before a late pause event) could make the de-duplication below skip the
            // pause event as "older than the newest applied".
            if (eventBuffer.eventType != UsageEvents.Event.MOVE_TO_FOREGROUND &&
                eventBuffer.eventType != UsageEvents.Event.MOVE_TO_BACKGROUND
            ) continue
            val at = eventBuffer.timeStamp
            if (at in after..now || at in from until after && observedPackage in seeded) {
                val key = "$observedPackage/${eventBuffer.eventType}/$at"
                if (key in applied || at < (newestApplied[observedPackage] ?: Long.MIN_VALUE)) continue
                applied[key] = at
                newestApplied[observedPackage] = at
                packages.accept(observedPackage, eventBuffer.eventType, paymentPackages, at)
            }
        }
        queryAfter = now
        // The next window starts no earlier than now - QUERY_OVERLAP_MS.
        applied.values.removeAll { it < now - QUERY_OVERLAP_MS }
        newestApplied.values.removeAll { it < now - QUERY_OVERLAP_MS }
        if (!eligible() || checkedToken != token || !interactiveAndUnlocked()) return
        val paymentForeground = packages.paymentForegroundAt(now)
        paymentCheckedAt = SystemClock.elapsedRealtime()
        paymentEvidenceAt = if (paymentForeground) paymentCheckedAt else Long.MIN_VALUE
        // Runs on every poll: Instagram/YouTube already in front resumes as soon as payment
        // evidence clears, without needing a fresh foreground event.
        Pause.onObservedForeground(this, checkedToken, packages.socialForeground(), paymentForeground, settled())
        if (!eligible()) {
            stopMonitoring()
            return
        }
        // At most one poll chain: a screen/unlock broadcast or an unlock re-check can start a
        // check while a poll is already queued (the main thread's removeCallbacks races it).
        handler.removeCallbacks(poll)
        handler.postDelayed(poll, POLL_MS)
    }

    private fun showForegroundNotification() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "Optional payment pause monitor", NotificationManager.IMPORTANCE_LOW).apply {
            description = "Local package-only checks during a payment pause"
            lockscreenVisibility = Notification.VISIBILITY_PRIVATE
            setShowBadge(false)
        })
        val open = PendingIntent.getActivity(
            this, NOTIFICATION_ID, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val publicVersion = Notification.Builder(this, CHANNEL).setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Detach").setContentText("Optional local monitor running").setLocalOnly(true).build()
        val builder = Notification.Builder(this, CHANNEL).setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Payment pause: app detection on")
            .setContentText("Resumes protection when Instagram or YouTube opens. Package names only, while unlocked.")
            .setStyle(Notification.BigTextStyle().bigText(
                "Resumes protection when Instagram or YouTube opens, not while a selected payment app is in front. " +
                    "Checks app package names about every 2 seconds while your screen is unlocked; no screen content.",
            ))
            .setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true)
            .setLocalOnly(true)
            .setVisibility(Notification.VISIBILITY_PRIVATE).setPublicVersion(publicVersion)
            .setCategory(Notification.CATEGORY_SERVICE)
        // Shown at once rather than after Android's 10 second deferral: a deferred post racing
        // the service's stop is how a monitor notification outlived its service.
        if (Build.VERSION.SDK_INT >= 31) builder.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
        val notification = builder.build()
        if (Build.VERSION.SDK_INT >= 34) startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        else startForeground(NOTIFICATION_ID, notification)
    }

    private fun stopMonitoring() {
        stopping = true
        token = null
        paymentEvidenceAt = Long.MIN_VALUE
        observingSince = Long.MIN_VALUE
        if (active === this) active = null
        handler.removeCallbacksAndMessages(null)
        val startId = lastStartId
        val finish = Runnable {
            runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
            // Only up to the newest start this instance has seen: a newer start still on its
            // way re-validates (and stops again if it is not needed).
            stopSelf(startId)
            Pause.refreshNotification(this)
        }
        if (Looper.myLooper() == Looper.getMainLooper()) finish.run() else mainHandler.post(finish)
    }

    private fun release() {
        stopping = true
        handler.removeCallbacksAndMessages(null)
        if (active === this) active = null
        token = null
        if (registered) {
            unregisterReceiver(screenChanged)
            registered = false
        }
        settings?.unregisterOnSharedPreferenceChangeListener(preferencesChanged)
        settings = null
        if (watchingOps) {
            getSystemService(AppOpsManager::class.java).stopWatchingMode(operationChanged)
            watchingOps = false
        }
    }

    override fun onDestroy() {
        release()
        workerThread.quitSafely()
        mainHandler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    companion object {
        internal const val POLL_MS = 2_000L
        internal const val LOOKBACK_MS = 10_000L
        /** How far each query reaches back before the previous one, for events stored late. */
        internal const val QUERY_OVERLAP_MS = 5_000L
        internal const val NOTIFICATION_ID = 7004
        /** Screen on/unlock seen while still locked: look again at these delays, screen on only. */
        internal val UNLOCK_RECHECKS_MS = longArrayOf(250, 1_000, 3_000)
        /** How long an accepted start request counts as on its way to onStartCommand. */
        private const val START_GRACE_MS = 10_000L
        private const val CHANNEL = "payment_pause_monitor"
        private const val EXTRA_TOKEN = "pause_token"
        private const val ACTION_CLEAR = "io.github.detach.action.CLEAR_MONITOR_NOTIFICATION"
        @Volatile private var active: PauseForegroundMonitor? = null
        /** elapsedRealtime of the last accepted start request not yet delivered, or MIN_VALUE. */
        @Volatile private var requestedAt = Long.MIN_VALUE

        /** A monitor is running in this process. */
        val isActive: Boolean get() = active != null

        /** Running, or a start Android accepted is on its way. */
        internal fun runningOrStarting(): Boolean =
            active != null || SystemClock.elapsedRealtime() - requestedAt in 0..START_GRACE_MS

        /**
         * Usage Access is granted: the app op is allowed, or left at its default while the
         * permission itself is granted (as `pm grant ... PACKAGE_USAGE_STATS` leaves it).
         */
        fun hasUsageAccess(context: Context): Boolean = runCatching {
            val app = context.applicationContext
            val manager = app.getSystemService(AppOpsManager::class.java)
            val mode = if (Build.VERSION.SDK_INT >= 29) {
                manager.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, app.applicationInfo.uid, app.packageName)
            } else manager.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, app.applicationInfo.uid, app.packageName)
            mode == AppOpsManager.MODE_ALLOWED || mode == AppOpsManager.MODE_DEFAULT &&
                app.checkSelfPermission(android.Manifest.permission.PACKAGE_USAGE_STATS) == PackageManager.PERMISSION_GRANTED
        }.getOrDefault(false)

        fun usageAccessIntent(context: Context): Intent = Intent(SystemSettings.ACTION_USAGE_ACCESS_SETTINGS)
            .setData(Uri.parse("package:${context.packageName}"))

        private fun allowed(context: Context) = Settings(context).resumeOnSocialForeground &&
            Pause.isPending(context) && Pause.canSelfEnable(context) && hasUsageAccess(context)

        /**
         * The owner wants app detection for the pending pause and it could run now: opted in,
         * both grants, and the pause's own automatic authority is intact. Says nothing about
         * whether it is running.
         */
        internal fun wanted(context: Context): Boolean {
            val app = context.applicationContext
            return Pause.automaticToken(app) != null && allowed(app)
        }

        /** Requests a monitor for the pending pause's existing token. */
        internal fun startForPause(context: Context): Boolean {
            val app = context.applicationContext
            val token = Pause.automaticToken(app) ?: return false
            if (!allowed(app)) return false
            val started = runCatching {
                app.startForegroundService(
                    Intent(app, PauseForegroundMonitor::class.java).putExtra(EXTRA_TOKEN, token),
                ) != null
            }.getOrDefault(false)
            if (started) requestedAt = SystemClock.elapsedRealtime()
            return started
        }

        /**
         * Re-attaches app detection to the pending pause after the monitor was lost (process
         * killed, start refused, or the opt-in or Usage Access completed during the pause). Only
         * from contexts where Android allows a foreground-service start; elsewhere Android
         * refuses and this returns false. Uses the pause's existing token and deadline; it never
         * creates or extends authority. [whileServiceBound]: called by the still-bound
         * accessibility service, which is listed as enabled at that moment.
         */
        internal fun reattach(context: Context, whileServiceBound: Boolean = false): Boolean {
            val app = context.applicationContext
            if (runningOrStarting()) return true
            if (!wanted(app)) return false
            // Detach listed as enabled means a resume is already on its way.
            if (!whileServiceBound && Pause.serviceListed(app)) return false
            return startForPause(app)
        }

        /**
         * Removes a monitor notification left behind without a running monitor. A plain cancel
         * works unless Android still marks it as a foreground-service notification, which only a
         * service can remove: then, from Detach's visible screen only, a token-less start puts
         * up the same notification id and stops at once, taking it down.
         */
        internal fun clearOrphanNotification(context: Context, fromVisibleActivity: Boolean) {
            if (runningOrStarting()) return
            val app = context.applicationContext
            val manager = app.getSystemService(NotificationManager::class.java) ?: return
            fun listed() = runCatching { manager.activeNotifications.any { it.id == NOTIFICATION_ID } }.getOrDefault(false)
            if (!listed()) return
            runCatching { manager.cancel(NOTIFICATION_ID) }
            if (!fromVisibleActivity || !listed()) return
            runCatching {
                app.startForegroundService(Intent(app, PauseForegroundMonitor::class.java).setAction(ACTION_CLEAR))
                requestedAt = SystemClock.elapsedRealtime()
            }
        }

        /**
         * The timer should wait: a selected app was seen in front by a fresh query, or observation
         * has just (re)started, for example on unlock, and has not had a full poll to see a
         * payment app return to the front. The second case is bounded to two polls.
         */
        internal fun paymentIsKnown(context: Context): Boolean {
            val monitor = active ?: return false
            val now = SystemClock.elapsedRealtime()
            if (!allowed(context) || monitor.token != Pause.automaticToken(context)) return false
            val since = monitor.observingSince
            val settling = since != Long.MIN_VALUE && now - since in 0..POLL_MS * 2 && !monitor.settled()
            return settling || now - monitor.paymentEvidenceAt in 0..POLL_MS * 2 && now - monitor.paymentCheckedAt in 0..POLL_MS * 2
        }

        internal fun stop(context: Context) {
            active?.stopMonitoring()
            // A start Android accepted but has not delivered yet must not be stopped from here:
            // Android would crash the process because it never reached startForeground(). It
            // re-validates on arrival and stops itself. Otherwise this also cancels a sticky
            // restart that is still pending.
            if (SystemClock.elapsedRealtime() - requestedAt !in 0..START_GRACE_MS) {
                runCatching { context.applicationContext.stopService(Intent(context.applicationContext, PauseForegroundMonitor::class.java)) }
            }
        }
    }
}

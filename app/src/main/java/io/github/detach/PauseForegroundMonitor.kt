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
    var foreground: String? = null
        private set
    private var foregroundUpdatedAt = 0L
    private val payments = mutableMapOf<String, Long>()
    /** Unobserved starting-package hints: only [STATE_MAX_MS], like any other unconfirmed hint. */
    private val hints = mutableMapOf<String, Long>()

    fun accept(packageName: String, eventType: Int, paymentPackages: Set<String>, observedAt: Long) {
        when (eventType) {
            UsageEvents.Event.MOVE_TO_FOREGROUND -> {
                foreground = packageName
                foregroundUpdatedAt = observedAt
                if (packageName in paymentPackages) {
                    payments[packageName] = observedAt
                    hints.remove(packageName)
                }
            }
            UsageEvents.Event.MOVE_TO_BACKGROUND -> {
                payments.remove(packageName)
                hints.remove(packageName)
                if (foreground == packageName) foreground = null
            }
        }
        payments.keys.retainAll(paymentPackages)
        hints.keys.retainAll(paymentPackages)
    }

    /**
     * The selected app that started the pause, before any usage event for it is observed (its own
     * foreground event predates the pause). [inFront]: it was read from the active window, so it
     * is held like an observed foreground. Otherwise the pause came from an event alone, the app
     * may never have been in front, and it is only a [STATE_MAX_MS] hint.
     */
    fun seed(packageName: String, paymentPackages: Set<String>, observedAt: Long, inFront: Boolean) {
        if (packageName !in paymentPackages) return
        if (inFront) payments[packageName] = observedAt else hints[packageName] = observedAt
    }

    fun foregroundAt(now: Long): String? = foreground.takeIf { now - foregroundUpdatedAt in 0..STATE_MAX_MS }

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
        payments.entries.removeAll { now - it.value !in 0..PAYMENT_HOLD_MS }
        hints.entries.removeAll { now - it.value !in 0..STATE_MAX_MS }
        return payments.isNotEmpty() || hints.isNotEmpty()
    }

    fun clear() {
        foreground = null
        payments.clear()
        hints.clear()
        foregroundUpdatedAt = 0L
    }

    companion object {
        internal const val STATE_MAX_MS = 30_000L
        /** Upper bound for continuously observed selected-app evidence without a new event. */
        internal const val PAYMENT_HOLD_MS = 10 * 60_000L
    }
}

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
    private var queryAfter = 0L
    private var registered = false
    private var settings: SharedPreferences? = null
    private var watchingOps = false
    private val poll = Runnable { checkForeground() }
    private val expire = Runnable { stopMonitoring() }
    private val preferencesChanged = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
        handler.post { if (!eligible()) stopMonitoring() }
    }
    private val operationChanged = AppOpsManager.OnOpChangedListener { _, _ ->
        handler.post { if (!eligible()) stopMonitoring() }
    }
    private val screenChanged = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            handler.removeCallbacks(poll)
            if (!interactiveAndUnlocked()) {
                paymentEvidenceAt = Long.MIN_VALUE
                observingSince = Long.MIN_VALUE
                handler.post { packages.clear() }
            } else {
                if (observingSince == Long.MIN_VALUE) observingSince = SystemClock.elapsedRealtime()
                handler.post { if (eligible()) checkForeground() else stopMonitoring() }
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
        val requestedToken = intent?.getStringExtra(EXTRA_TOKEN)
        if (requestedToken == null || requestedToken != Pause.automaticToken(this) || !allowed(this)) {
            stopMonitoring()
            return START_NOT_STICKY
        }
        stopping = false
        token = requestedToken
        if (!runCatching { showForegroundNotification() }.isSuccess) {
            stopMonitoring()
            return START_NOT_STICKY
        }
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
        handler.post {
            queryAfter = 0L
            packages.clear()
            observingSince = if (interactiveAndUnlocked()) SystemClock.elapsedRealtime() else Long.MIN_VALUE
            Pause.startingPaymentPackage(this)?.let {
                packages.seed(it, Settings(this).paymentPackages, Pause.startedAt(this), Pause.startingPaymentWasInFront(this))
            }
            if (interactiveAndUnlocked()) checkForeground()
        }
        return START_NOT_STICKY
    }

    private fun settled(): Boolean {
        val since = observingSince
        return since != Long.MIN_VALUE && paymentCheckedAt - since >= POLL_MS
    }

    private fun eligible() = !stopping && token != null && token == Pause.automaticToken(this) && allowed(this)

    private fun interactiveAndUnlocked(): Boolean =
        getSystemService(PowerManager::class.java)?.isInteractive == true &&
            getSystemService(KeyguardManager::class.java)?.isKeyguardLocked == false &&
            getSystemService(UserManager::class.java)?.isUserUnlocked == true

    private fun checkForeground() {
        val checkedToken = token ?: return
        if (!eligible()) {
            stopMonitoring()
            return
        }
        if (!interactiveAndUnlocked()) return
        val now = System.currentTimeMillis()
        if (queryAfter > 0L && now - queryAfter > LOOKBACK_MS) {
            packages.clear()
            observingSince = Long.MIN_VALUE
        }
        if (observingSince == Long.MIN_VALUE) observingSince = SystemClock.elapsedRealtime()
        val after = maxOf(queryAfter, now - LOOKBACK_MS, Pause.startedAt(this))
        if (after > now) {
            stopMonitoring()
            return
        }
        val events = runCatching { getSystemService(UsageStatsManager::class.java)?.queryEvents(after, now) }.getOrNull()
        if (events == null || !eligible()) {
            stopMonitoring()
            return
        }
        if (checkedToken != token) return
        val paymentPackages = Settings(this).paymentPackages
        while (events.hasNextEvent()) {
            events.getNextEvent(eventBuffer)
            val observedPackage = eventBuffer.packageName ?: continue
            if (eventBuffer.timeStamp in after..now) {
                packages.accept(observedPackage, eventBuffer.eventType, paymentPackages, eventBuffer.timeStamp)
            }
        }
        queryAfter = now
        if (!eligible() || checkedToken != token || !interactiveAndUnlocked()) return
        val paymentForeground = packages.paymentForegroundAt(now)
        paymentCheckedAt = SystemClock.elapsedRealtime()
        paymentEvidenceAt = if (paymentForeground) paymentCheckedAt else Long.MIN_VALUE
        Pause.onObservedForeground(this, checkedToken, packages.foregroundAt(now), paymentForeground, settled())
        if (eligible()) handler.postDelayed(poll, POLL_MS) else stopMonitoring()
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
        val notification = Notification.Builder(this, CHANNEL).setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Payment pause: local monitor")
            .setContentText("Checks app packages about every 2 seconds while unlocked. No screen content.")
            .setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true)
            .setLocalOnly(true)
            .setVisibility(Notification.VISIBILITY_PRIVATE).setPublicVersion(publicVersion)
            .setCategory(Notification.CATEGORY_SERVICE).build()
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
        val finish = Runnable {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
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
        internal const val NOTIFICATION_ID = 7004
        private const val CHANNEL = "payment_pause_monitor"
        private const val EXTRA_TOKEN = "pause_token"
        @Volatile private var active: PauseForegroundMonitor? = null

        fun hasUsageAccess(context: Context): Boolean = runCatching {
            val app = context.applicationContext
            val manager = app.getSystemService(AppOpsManager::class.java)
            val mode = if (Build.VERSION.SDK_INT >= 29) {
                manager.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, app.applicationInfo.uid, app.packageName)
            } else manager.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, app.applicationInfo.uid, app.packageName)
            mode == AppOpsManager.MODE_ALLOWED
        }.getOrDefault(false)

        fun usageAccessIntent(context: Context): Intent = Intent(SystemSettings.ACTION_USAGE_ACCESS_SETTINGS)
            .setData(Uri.parse("package:${context.packageName}"))

        private fun allowed(context: Context) = Settings(context).resumeOnSocialForeground &&
            Pause.isPending(context) && Pause.canSelfEnable(context) && hasUsageAccess(context)

        internal fun startForPause(context: Context): Boolean {
            val app = context.applicationContext
            val token = Pause.automaticToken(app) ?: return false
            if (!allowed(app)) return false
            return runCatching {
                app.startForegroundService(Intent(app, PauseForegroundMonitor::class.java).putExtra(EXTRA_TOKEN, token)) != null
            }.getOrDefault(false)
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
            context.applicationContext.stopService(Intent(context.applicationContext, PauseForegroundMonitor::class.java))
        }
    }
}

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

    fun accept(packageName: String, eventType: Int, paymentPackages: Set<String>, observedAt: Long) {
        when (eventType) {
            UsageEvents.Event.MOVE_TO_FOREGROUND -> {
                foreground = packageName
                foregroundUpdatedAt = observedAt
                if (packageName in paymentPackages) payments[packageName] = observedAt
            }
            UsageEvents.Event.MOVE_TO_BACKGROUND -> {
                payments.remove(packageName)
                if (foreground == packageName) foreground = null
            }
        }
        payments.keys.retainAll(paymentPackages)
    }

    fun foregroundAt(now: Long): String? = foreground.takeIf { now - foregroundUpdatedAt in 0..STATE_MAX_MS }

    fun paymentForegroundAt(now: Long): Boolean {
        payments.entries.removeAll { now - it.value !in 0..STATE_MAX_MS }
        return payments.isNotEmpty()
    }

    fun clear() {
        foreground = null
        payments.clear()
        foregroundUpdatedAt = 0L
    }

    companion object {
        internal const val STATE_MAX_MS = 30_000L
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
                handler.post { packages.clear() }
            } else handler.post { if (eligible()) checkForeground() else stopMonitoring() }
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
            Pause.startingPaymentPackage(this)?.let {
                packages.accept(it, UsageEvents.Event.MOVE_TO_FOREGROUND, Settings(this).paymentPackages, Pause.startedAt(this))
            }
            if (interactiveAndUnlocked()) checkForeground()
        }
        return START_NOT_STICKY
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
        if (queryAfter > 0L && now - queryAfter > LOOKBACK_MS) packages.clear()
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
        Pause.onObservedForeground(this, checkedToken, packages.foregroundAt(now), paymentForeground)
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

        internal fun paymentIsKnown(context: Context): Boolean {
            val monitor = active ?: return false
            val now = SystemClock.elapsedRealtime()
            return allowed(context) && monitor.token == Pause.automaticToken(context) &&
                now - monitor.paymentEvidenceAt in 0..POLL_MS * 2 && now - monitor.paymentCheckedAt in 0..POLL_MS * 2
        }

        internal fun stop(context: Context) {
            active?.stopMonitoring()
            context.applicationContext.stopService(Intent(context.applicationContext, PauseForegroundMonitor::class.java))
        }
    }
}

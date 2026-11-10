package io.github.detach

import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Rect
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.util.DisplayMetrics
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * The only moving part. Android delivers events to it ONLY from Instagram and YouTube
 * (see res/xml/accessibility_config.xml), so it never sees banking/UPI or any other app.
 *
 * Flow: event → (throttled) read the active window → rules decide → cover the feed and/or
 * perform one action (switch to DMs, Back, tap Home). Every action must be decided twice,
 * ~250 ms apart, before it runs, so half-loaded screens don't cause wrong moves.
 */
class DetachService : AccessibilityService() {

    private lateinit var settings: Settings
    private lateinit var health: Health
    private lateinit var ig: InstagramRules
    private lateinit var yt: YouTubeRules
    private lateinit var cover: FeedCover
    private lateinit var guard: GuardOverlay
    private lateinit var lock: Lock

    private val main = Handler(Looper.getMainLooper())
    private lateinit var thread: HandlerThread
    private lateinit var worker: Handler

    // --- worker-thread state ---
    private var evalQueued = false
    private var lastEval = 0L
    private var pending: Action? = null
    private var pendingAt = 0L
    private var cooldownUntil = 0L
    private var pausedUntil = 0L
    private val recentActions = ArrayDeque<Long>()
    @Volatile private var lastIgSnap: Snap? = null
    @Volatile private var coverOn = false
    @Volatile private var guardOn = false
    /** Set when the service is switching itself off for a pause: no more overlays. */
    @Volatile private var pausing = false

    // For showing the feed cover instantly when Instagram comes back to the front
    @Volatile private var lastIgEventAt = 0L
    @Volatile private var lastScreenWasHomeFeed = false
    @Volatile private var lastHomeCover: Rect? = null

    // What the protection panel offers; worked out each time it comes up
    private var guardPassword = false
    private var guardBiometric = false

    private val evalRunnable = Runnable {
        evalQueued = false
        lastEval = SystemClock.uptimeMillis()
        runCatching { evaluate() }
    }

    private val watchdog = object : Runnable {
        override fun run() {
            if (!coverOn && !guardOn) return
            requestEval(immediate = true)
            worker.postDelayed(this, WATCHDOG_MS)
        }
    }

    private val inspectReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Inspector.ACTION_DUMP -> worker.post { Inspector.dump(rootInActiveWindow) }
                Inspector.ACTION_TRACE -> Inspector.tracing = intent.getBooleanExtra("on", true)
            }
        }
    }

    override fun onServiceConnected() {
        settings = Settings(this)
        health = Health(this)
        ig = InstagramRules(settings, health)
        yt = YouTubeRules(settings, health)
        cover = FeedCover(this) { backToStories() }
        lock = Lock(this)
        guard = GuardOverlay(this, onUnlock = { openUnlock() }, onGoBack = { leaveProtectedPage() })
        thread = HandlerThread("detach").also { it.start() }
        worker = Handler(thread.looper)

        val filter = IntentFilter().apply {
            addAction(Inspector.ACTION_DUMP)
            addAction(Inspector.ACTION_TRACE)
        }
        // Only senders holding DUMP (i.e. adb over USB) can trigger the inspector.
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(inspectReceiver, filter, android.Manifest.permission.DUMP, null, RECEIVER_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag") // API < 33 has no flags; DUMP permission protects it
            registerReceiver(inspectReceiver, filter, android.Manifest.permission.DUMP, null)
        }

        pausing = false
        instance = this
        // Running again: any pause is over, and the "on" notification goes (back) up.
        Pause.onServiceStarted(this)
    }

    /**
     * "Pause for payments": records the pause, schedules the resume, swaps the notification,
     * takes down the overlays and switches this service off. Main thread only.
     */
    private fun pauseNow() {
        pausing = true
        Pause.begin(this)
        health.lastAction("paused for payments")
        coverOn = false
        guardOn = false
        worker.removeCallbacksAndMessages(null)
        cover.hide()
        guard.hide()
        disableSelf()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        val pkg = event.packageName?.toString() ?: return
        if (pkg !in Targets.ALL) return // defensive; the system already filters
        Inspector.trace(event)
        val now = SystemClock.uptimeMillis()
        if (pkg == Targets.INSTAGRAM) {
            // Instagram just came to the front and was last on the home feed: put the cover up
            // straight away at its last position. The next evaluation (a few ms later) corrects
            // or removes it, so the feed never gets a chance to flash.
            val returning = now - lastIgEventAt > 1500
            lastIgEventAt = now
            val r = lastHomeCover
            if (returning && !coverOn && lastScreenWasHomeFeed && r != null &&
                settings.igEnabled && settings.igFeed &&
                foreground() == Targets.INSTAGRAM // not just visible behind another app
            ) {
                showCover(r)
            }
        }
        val windowChanged = event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
        if (pkg == Targets.INSTAGRAM && event.eventType == AccessibilityEvent.TYPE_VIEW_SCROLLED &&
            event.source?.viewIdResourceName?.endsWith("clips_viewer_view_pager") == true
        ) {
            worker.post { ig.onClipsScrolled(now) }
        }
        // New screens: right away. Scrolling: fast, so covers keep up. Other content changes
        // (videos playing, counters updating): slower, to save battery.
        val gap = if (event.eventType == AccessibilityEvent.TYPE_VIEW_SCROLLED) 80L else 160L
        requestEval(immediate = windowChanged, gap = gap)
    }

    /** Package of the focused window. Only the name is read, never its content. */
    private fun foreground(): String? = rootInActiveWindow?.packageName?.toString()

    /** Run an evaluation soon: right away if [immediate], otherwise at most one per [gap] ms. */
    private fun requestEval(delay: Long = 0, immediate: Boolean = false, gap: Long = 160) {
        if (!::worker.isInitialized) return
        worker.postDelayed({
            if (immediate) {
                worker.removeCallbacks(evalRunnable)
                evalQueued = true
                worker.post(evalRunnable)
                return@postDelayed
            }
            if (evalQueued) return@postDelayed
            evalQueued = true
            val wait = (lastEval + gap - SystemClock.uptimeMillis()).coerceAtLeast(0)
            worker.postDelayed(evalRunnable, wait)
        }, delay)
    }

    private fun evaluate() {
        val root = rootInActiveWindow
        // Only the package name is looked at here; anything that isn't Instagram/YouTube is ignored.
        val pkg = root?.packageName?.toString()
        if (root == null || pkg !in Targets.ALL) {
            hideCover()
            hideGuard()
            pending = null
            lastIgSnap = null
            return
        }
        val (w, h) = screenSize()
        // The app's own window (smaller than the screen in split-screen / pop-up view).
        val win = Rect().also { root.getBoundsInScreen(it) }
        if (win.isEmpty) win.set(0, 0, w, h)
        if (pkg in Targets.GUARDED) {
            hideCover()
            pending = null
            lastIgSnap = null
            val snap = Snap(root, w, h)
            if (Guard.isProtectedScreen(snap) && !Guard.isGranted()) showGuard(win) else hideGuard()
            return
        }
        hideGuard()
        val t0 = SystemClock.uptimeMillis()
        val snap = Snap(root, w, h)
        val now = SystemClock.uptimeMillis()
        val d = if (pkg == Targets.INSTAGRAM) {
            lastIgSnap = snap
            ig.evaluate(snap, now).also {
                lastScreenWasHomeFeed = it.coverIsHomeFeed
                if (it.coverIsHomeFeed) lastHomeCover = it.cover
            }
        } else {
            yt.evaluate(snap)
        }
        if (Inspector.tracing) {
            android.util.Log.i(Inspector.TAG, "EVAL $pkg nodes=${snap.all.size} ${SystemClock.uptimeMillis() - t0}ms " +
                "cover=${d.cover} action=${d.action?.reason}")
        }

        // Never let a cover reach outside the app's own window (e.g. over a UPI app in split-screen).
        val c = d.cover?.let { r -> Rect(r).takeIf { it.intersect(win) } }
        if (c != null && d.action == null) showCover(c, d.coverLabel, d.coverBackToTop) else hideCover()

        val a = d.action
        if (a == null) {
            pending = null
            return
        }
        if (now < pausedUntil || now < cooldownUntil) {
            requestEval(maxOf(pausedUntil, cooldownUntil) - now + 20)
            return
        }
        if (a.sameKind(pending)) {
            val waited = now - pendingAt
            if (waited >= 250) {
                pending = null
                execute(a, snap, now)
            } else {
                requestEval(250 - waited + 10)
            }
        } else {
            pending = a
            pendingAt = now
            requestEval(300) // confirm before acting
        }
    }

    private fun execute(a: Action, snap: Snap, now: Long) {
        while (recentActions.isNotEmpty() && now - recentActions.first() > 6000) recentActions.removeFirst()
        recentActions.addLast(now)
        if (recentActions.size > 8) {
            // Something is ping-ponging; step aside for a bit rather than trapping the user.
            pausedUntil = now + 15_000
            recentActions.clear()
            health.lastAction("paused 15 s (loop guard) after: ${a.reason}")
            return
        }
        // The user may have switched apps since the screen was read: never act on another app.
        if (foreground() != snap.pkg) return
        cooldownUntil = now + 700
        health.lastAction(a.reason)
        if (Inspector.tracing) android.util.Log.i(Inspector.TAG, "ACTION ${a::class.simpleName}: ${a.reason}")
        when (a) {
            is Action.Back -> performGlobalAction(GLOBAL_ACTION_BACK)
            is Action.ToDms -> goToDms(snap)
            is Action.Click -> if (!a.node.node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                performGlobalAction(GLOBAL_ACTION_BACK)
            }
        }
        requestEval(750) // check the result
    }

    private fun goToDms(snap: Snap) {
        val tab = ig.tabNodes(snap)[InstagramRules.Tab.MESSAGES]
        if (tab != null && tab.node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return
        val ok = runCatching {
            startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse("instagram://direct-inbox"))
                    .setPackage(Targets.INSTAGRAM)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }.isSuccess
        if (!ok && foreground() == Targets.INSTAGRAM) performGlobalAction(GLOBAL_ACTION_BACK)
    }

    /** "Back to stories" on the cover: re-tapping Home makes Instagram scroll to the top. */
    private fun backToStories() {
        worker.post {
            val snap = lastIgSnap ?: return@post
            ig.tabNodes(snap)[InstagramRules.Tab.HOME]?.node?.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            requestEval(400)
        }
    }

    private fun showGuard(win: Rect) {
        if (pausing) return
        val first = !guardOn
        guardOn = true
        if (first) {
            guardPassword = lock.isSet
            guardBiometric = Auth.biometricAvailable(this)
        }
        val pw = guardPassword
        val bio = guardBiometric
        main.post { if (!pausing) guard.show(pw, bio, win) }
        if (first && !coverOn) worker.postDelayed(watchdog, WATCHDOG_MS)
        if (first) health.lastAction("protected Detach's settings page")
    }

    private fun hideGuard() {
        if (!guardOn) return
        guardOn = false
        if (!coverOn) worker.removeCallbacks(watchdog)
        main.post { guard.hide() }
    }

    private fun openUnlock() {
        val ok = runCatching {
            startActivity(Intent(this, UnlockActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }.isSuccess
        if (ok) {
            hideGuard()
        } else {
            main.post {
                android.widget.Toast.makeText(this, "Open the Detach app to unlock", android.widget.Toast.LENGTH_LONG).show()
            }
        }
    }

    /** "Go back": leave the protected page; if Back doesn't get out of it, go Home. */
    private fun leaveProtectedPage() {
        if (foreground() in Targets.GUARDED) performGlobalAction(GLOBAL_ACTION_BACK)
        worker.postDelayed({
            val root = rootInActiveWindow
            val pkg = root?.packageName?.toString()
            if (root != null && pkg in Targets.GUARDED) {
                val (w, h) = screenSize()
                if (Guard.isProtectedScreen(Snap(root, w, h))) performGlobalAction(GLOBAL_ACTION_HOME)
            }
            requestEval()
        }, 350)
    }

    private fun showCover(r: Rect, label: String = HOME_LABEL, backToTop: Boolean = true) {
        if (pausing) return
        val first = !coverOn
        coverOn = true
        main.post { if (!pausing) cover.show(r, label, backToTop) }
        if (first && !guardOn) worker.postDelayed(watchdog, WATCHDOG_MS)
    }

    private fun hideCover() {
        if (!coverOn) return
        coverOn = false
        if (!guardOn) worker.removeCallbacks(watchdog)
        main.post { cover.hide() }
    }

    private fun screenSize(): Pair<Int, Int> {
        val wm = getSystemService(WindowManager::class.java)
        return if (Build.VERSION.SDK_INT >= 30) {
            val b = wm.currentWindowMetrics.bounds
            b.width() to b.height()
        } else {
            val m = DisplayMetrics()
            @Suppress("DEPRECATION")
            wm.defaultDisplay.getRealMetrics(m)
            m.widthPixels to m.heightPixels
        }
    }

    companion object {
        const val HOME_LABEL = "Feed hidden by Detach"

        /** While a cover/panel is up, how often to check the app is still in front. */
        const val WATCHDOG_MS = 250L

        /**
         * The connected service, for the rest of the app (same process) to talk to. Set in
         * onServiceConnected, cleared when Android unbinds or destroys the service.
         */
        @Volatile private var instance: DetachService? = null

        val isRunning: Boolean get() = instance != null

        /**
         * Asks the running service to switch itself off for the set pause time. Call on the
         * main thread, after the user has been confirmed. False if the service isn't running.
         */
        fun pauseForPayments(): Boolean {
            val s = instance ?: return false
            return runCatching { s.pauseNow() }.isSuccess
        }
    }

    override fun onUnbind(intent: Intent?): Boolean {
        if (instance === this) instance = null
        return super.onUnbind(intent)
    }

    override fun onInterrupt() {
        main.post { cover.hide(); guard.hide() }
        coverOn = false
        guardOn = false
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        // Switched off for some other reason than a pause: the "on" notification goes away.
        // (During a pause this leaves the "paused until…" notification alone.)
        Pause.refreshNotification(this)
        runCatching { unregisterReceiver(inspectReceiver) }
        if (::cover.isInitialized) main.post { cover.hide(); guard.hide() }
        if (::thread.isInitialized) thread.quitSafely()
        super.onDestroy()
    }
}

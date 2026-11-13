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
import android.view.Surface
import android.view.WindowInsets
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import java.util.concurrent.atomic.AtomicLong

/**
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
    private val coverState = CoverState()
    private val coverRevision = AtomicLong()
    private val guardRevision = AtomicLong()
    @Volatile private var coverPackage: String? = null
    @Volatile private var guardPackage: String? = null

    private val main = Handler(Looper.getMainLooper())
    private lateinit var thread: HandlerThread
    private lateinit var worker: Handler

    // --- worker-thread state ---
    private var evalQueued = false
    private var lastEval = 0L
    private var pending: Action? = null
    private var pendingPackage: String? = null
    private var pendingAt = 0L
    private var cooldownUntil = 0L
    private var pausedUntil = 0L
    private var loadingSince = 0L
    private val recentActions = ArrayDeque<Long>()
    @Volatile private var coverOn = false
    @Volatile private var guardOn = false
    /** Set when the service is switching itself off for a pause: no more overlays. */
    @Volatile private var pausing = false

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
            if (pausing || !coverOn && !guardOn) return
            val pkg = foreground()
            if ((coverOn && pkg != coverPackage) || (guardOn && pkg != guardPackage)) {
                requestEval(immediate = true)
            }
            worker.postDelayed(this, WATCHDOG_MS)
        }
    }

    private val inspectReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (!BuildConfig.DEBUG || pausing || !::worker.isInitialized) return
            when (intent.action) {
                Inspector.ACTION_DUMP -> worker.post {
                    if (!pausing) {
                        val root = rootInActiveWindow
                        val pkg = root?.packageName?.toString()
                        if (!pauseIfPayment(pkg) && pkg in Targets.PACKAGES) Inspector.dump(root)
                    }
                }
                Inspector.ACTION_TRACE -> Inspector.tracing = intent.getBooleanExtra("on", true)
            }
        }
    }

    override fun onServiceConnected() {
        if (Pause.isPending(this) && !Pause.consumeResumeAuthorization(this)) {
            stopProtection()
            // Still bound, so Android allows the optional monitor to be brought back for the
            // existing pause (it never authorizes anything itself).
            PauseForegroundMonitor.reattach(this, whileServiceBound = true)
            Pause.refreshNotification(this)
            disableSelf()
            return
        }
        settings = Settings(this)
        // Just resumed because Instagram/YouTube came to the front: the active window can still be
        // the payment app the owner left a moment ago. Re-read it a little later instead of
        // pausing on this first read (a selected app really in front still pauses within ~1 s).
        val afterSocialResume = Pause.takeRecentSocialResume()
        // A timer or monitor resume can land while a selected app is still in front: pause again
        // straight away rather than start protecting it.
        val startupPackage = foreground()
        if (!afterSocialResume && Pause.shouldAutoPausePayment(this, startupPackage) &&
            Pause.record(this, startupPackage, paymentInFront = true)) {
            stopProtection()
            disableSelf()
            Pause.afterRecorded(this)
            return
        }
        health = Health(this)
        ig = InstagramRules(settings, health) { Breather.activeFeatures(this) }
        yt = YouTubeRules(settings, health) { Breather.activeFeatures(this) }
        cover = FeedCover(this, onBackToStories = { backToStories() }, onBackToSearch = { backToSearch() })
        lock = Lock(this)
        guard = GuardOverlay(this, onUnlock = { openUnlock() }, onGoBack = { leaveProtectedPage() })
        thread = HandlerThread("detach").also { it.start() }
        worker = Handler(thread.looper)
        updateEventFilter()

        if (BuildConfig.DEBUG) {
            val filter = IntentFilter().apply {
                addAction(Inspector.ACTION_DUMP)
                addAction(Inspector.ACTION_TRACE)
            }
            if (Build.VERSION.SDK_INT >= 33) {
                registerReceiver(inspectReceiver, filter, android.Manifest.permission.DUMP, null, RECEIVER_EXPORTED)
            } else {
                @Suppress("UnspecifiedRegisterReceiverFlag")
                registerReceiver(inspectReceiver, filter, android.Manifest.permission.DUMP, null)
            }
        }

        pausing = false
        instance = this
        // Running again: any pause is over, and the "on" notification goes (back) up.
        Pause.onServiceStarted(this)
        refreshBreather()
        connectedAt = SystemClock.uptimeMillis()
        socialSettleUntil = if (afterSocialResume) connectedAt + SOCIAL_RESUME_CHECKS_MS[0] else 0L
        // Right after binding the active window is often not available yet, so the check above
        // can miss a selected app that is already open. Look again, by package name only.
        scheduleForegroundChecks(if (afterSocialResume) SOCIAL_RESUME_CHECKS_MS else FOREGROUND_CHECKS_MS)
    }

    /** uptimeMillis of the last successful startup, or 0. */
    @Volatile private var connectedAt = 0L
    /** After a resume for Instagram/YouTube: until then a selected app's window is not trusted yet. */
    @Volatile private var socialSettleUntil = 0L

    /**
     * Just (re)connected: window events now are mostly transitions of whatever was in front a
     * moment ago (for example the payment app the owner just left for Instagram).
     */
    private fun justConnected(): Boolean =
        connectedAt != 0L && SystemClock.uptimeMillis() - connectedAt in 0 until STARTUP_GRACE_MS

    /**
     * "Pause for payments": records the pause, schedules the reminder, swaps the notification,
     * takes down the overlays and switches this service off. Main thread only.
     * [inFront]: [paymentPackage] was read from the active window (see [Pause.record]).
     */
    private fun pauseNow(paymentPackage: String? = null, inFront: Boolean = false): Boolean {
        if (pausing) return false
        // Durable marker first (fail closed), then off as fast as possible: a payment app may be
        // checking the enabled accessibility services while it starts.
        if (!Pause.record(this, paymentPackage, inFront)) {
            if (paymentPackage != null) onInterrupt()
            return false
        }
        stopProtection()
        disableSelf()
        // Detach is already out of the enabled list; alarms and notifications can follow.
        Pause.afterRecorded(this)
        if (::health.isInitialized) health.lastAction("paused for payments")
        return true
    }

    private val breatherExpiry = Runnable { refreshBreather() }

    private fun refreshBreather() {
        if (pausing || !::worker.isInitialized) return
        main.removeCallbacks(breatherExpiry)
        val remaining = Breather.remainingMs(this)
        worker.post {
            if (!pausing) {
                ig.reset()
                coverState.clear()
                pending = null
                pendingPackage = null
                requestEval(immediate = true)
            }
        }
        if (remaining > 0L) main.postDelayed(breatherExpiry, remaining + 1L)
        Pause.refreshNotification(this)
    }

    private fun stopProtection() {
        pausing = true
        coverRevision.incrementAndGet()
        guardRevision.incrementAndGet()
        coverOn = false
        guardOn = false
        coverPackage = null
        guardPackage = null
        main.removeCallbacksAndMessages(null)
        if (::worker.isInitialized) worker.removeCallbacksAndMessages(null)
        if (::cover.isInitialized) cover.hide()
        if (::guard.isInitialized) guard.hide()
        coverState.clear()
        pending = null
        pendingPackage = null
        if (instance === this) instance = null
        if (::thread.isInitialized) thread.quitSafely()
    }

    private fun updateEventFilter() {
        serviceInfo?.let { info ->
            info.packageNames = settings.eventPackages().toTypedArray()
            info.notificationTimeout = 16
            serviceInfo = info
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        val pkg = event.packageName?.toString() ?: return
        if (pausing || !::worker.isInitialized) return
        // One active-window read per event; its package is passed on rather than read again.
        val activePackage = foreground()
        // Moments after a resume for Instagram/YouTube, a selected app as the active window (or
        // as the sender) is most likely the one just left; the scheduled startup re-checks decide.
        if (SystemClock.uptimeMillis() < socialSettleUntil &&
            (Pause.shouldAutoPausePayment(this, activePackage) || Pause.shouldAutoPausePayment(this, pkg))) return
        if (pauseIfPayment(activePackage, confirmed = true)) return
        if (pkg != activePackage && Pause.shouldAutoPausePayment(this, pkg)) {
            onSelectedAppEvent(pkg, activePackage)
            return
        }
        if (pkg in Targets.SYSTEM_UI) {
            if (activePackage == pkg) {
                coverRevision.incrementAndGet()
                guardRevision.incrementAndGet()
                cover.hide()
                guard.hide()
                worker.post {
                    if (foreground() == pkg) {
                        hideCover()
                        hideGuard()
                        pending = null
                    }
                    requestEval(120)
                }
            }
            return
        }
        if (pkg !in Targets.READABLE) return
        if (activePackage != pkg) {
            requestEval(immediate = true)
            return
        }
        Inspector.trace(event)
        val now = SystemClock.uptimeMillis()
        if (pkg == Targets.INSTAGRAM && event.eventType == AccessibilityEvent.TYPE_VIEW_CLICKED) {
            val source = event.source
            val instagramSource = source?.takeIf { it.packageName?.toString() == Targets.INSTAGRAM && !it.isPassword }
            val id = instagramSource?.viewIdResourceName.orEmpty()
            val description = instagramSource?.contentDescription?.toString().orEmpty()
            if (id.endsWith("reels_tray_container") || description.contains("'s story", ignoreCase = true)) {
                worker.post { ig.onStoryTrayTapped(now) }
            }
        }
        val windowChanged = event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ||
            event.eventType == AccessibilityEvent.TYPE_VIEW_SELECTED || event.eventType == AccessibilityEvent.TYPE_VIEW_CLICKED
        if (pkg == Targets.INSTAGRAM && event.eventType == AccessibilityEvent.TYPE_VIEW_SCROLLED) {
            val source = event.source
            if (source?.packageName?.toString() == Targets.INSTAGRAM && !source.isPassword &&
                source.viewIdResourceName?.endsWith("clips_viewer_view_pager") == true
            ) worker.post { ig.onClipsScrolled(now) }
        }
        // New screens: right away. Scrolling: fast, so covers keep up. Other content changes
        // (videos playing, counters updating): slower, to save battery.
        val gap = if (event.eventType == AccessibilityEvent.TYPE_VIEW_SCROLLED) 16L else 48L
        requestEval(immediate = windowChanged, gap = gap)
    }

    /** Package of the focused window. Only the name is read, never its content. */
    private fun foreground(): String? = rootInActiveWindow?.packageName?.toString()

    /**
     * Pauses if [pkg] is a selected app in front. [confirmed]: [pkg] was just read from the
     * active window on the main thread, so it isn't read a second time.
     */
    private fun pauseIfPayment(pkg: String?, confirmed: Boolean = false): Boolean {
        if (!Pause.shouldAutoPausePayment(this, pkg)) return false
        if (Looper.myLooper() == Looper.getMainLooper()) {
            if (confirmed || foreground() == pkg) pauseNow(pkg, inFront = true)
        } else {
            main.post {
                if (!pausing && foreground() == pkg && Pause.shouldAutoPausePayment(this, pkg)) pauseNow(pkg, inFront = true)
            }
        }
        return true
    }

    /**
     * A selected app sent an event but isn't the active window. Any app can send accessibility
     * events from the background, so an event alone is not evidence that the app is in front:
     * otherwise a selected shopping app (or an SDK inside it) could switch protection off while
     * the owner is on the home screen, or straight after they tap Resume. Pause at once only
     * while Android reports no active window at all, which is the brief gap while a newly
     * launched app takes focus. Whenever another app is the active window, only re-check the
     * active package for about a second; the pause then needs the selected app's own window.
     * This is a late fallback either way: the dependable setups pause before the payment app
     * starts (select the app you pay from, or "Pause and open" from the tile or notification).
     */
    private fun onSelectedAppEvent(pkg: String, activePackage: String?) {
        // Right after connecting, an event with no active window is usually the exit transition of
        // the app just left, so it needs the selected app's own window like any other case.
        if (activePackage == null && !justConnected()) pauseNow(pkg) else scheduleForegroundChecks()
    }

    private val foregroundChecks = Any()

    /** Main thread. Package-only re-checks of the active window, bounded to about a second. */
    private fun scheduleForegroundChecks(delays: LongArray = FOREGROUND_CHECKS_MS) {
        if (pausing) return
        main.removeCallbacksAndMessages(foregroundChecks)
        for (delay in delays) {
            main.postDelayed({
                if (!pausing && pauseIfPayment(foreground(), confirmed = true)) main.removeCallbacksAndMessages(foregroundChecks)
            }, foregroundChecks, delay)
        }
    }

    /** Run an evaluation soon: right away if [immediate], otherwise at most one per [gap] ms. */
    private fun requestEval(delay: Long = 0, immediate: Boolean = false, gap: Long = 48) {
        if (pausing || !::worker.isInitialized) return
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
        if (pausing) return
        val root = rootInActiveWindow
        // Only the package name is looked at here; anything that isn't Instagram/YouTube is ignored.
        val pkg = root?.packageName?.toString()
        if (pauseIfPayment(pkg)) return
        if (pkg in Targets.SYSTEM_UI) {
            hideCover()
            hideGuard()
            pending = null
            requestEval(250)
            return
        }
        if (root == null || pkg !in Targets.READABLE) {
            hideCover()
            hideGuard()
            coverState.clear()
            pending = null
            pendingPackage = null
            ig.reset()
            loadingSince = 0L
            return
        }
        val (w, h) = screenSize()
        // The app's own window (smaller than the screen in split-screen / pop-up view).
        val win = Rect().also { root.getBoundsInScreen(it) }
        if (!win.intersect(safeScreenBounds(w, h)) || win.isEmpty) {
            hideCover()
            hideGuard()
            coverState.clear()
            pending = null
            return
        }
        if (pkg in Targets.GUARDED) {
            hideCover()
            coverState.clear()
            pending = null
            ig.reset()
            val snap = Snap(root, w, h)
            if (Guard.isProtectedScreen(snap) && !Guard.isGranted()) showGuard(pkg!!, win) else hideGuard()
            return
        }
        hideGuard()
        val t0 = SystemClock.uptimeMillis()
        val snap = Snap(root, w, h)
        val now = SystemClock.uptimeMillis()
        val loading = snap.all.asSequence().drop(1).none { it.onScreen && (it.id != null || it.label().isNotBlank() || it.clickable) }
        if (pkg != Targets.INSTAGRAM) ig.reset()
        val protectionEnabled = if (pkg == Targets.INSTAGRAM) settings.igEnabled else settings.ytShorts
        val d = if (loading && protectionEnabled) Decision.NONE else if (pkg == Targets.INSTAGRAM) {
            ig.evaluate(snap, now)
        } else {
            yt.evaluate(snap)
        }
        if (BuildConfig.DEBUG && Inspector.tracing) {
            android.util.Log.i(Inspector.TAG, "EVAL $pkg nodes=${snap.all.size} ${SystemClock.uptimeMillis() - t0}ms " +
                "cover=${d.cover} action=${d.action?.reason}")
        }

        // Never let a cover reach outside the app's own window (e.g. over a UPI app in split-screen).
        val frame = coverState.update(pkg!!, win, d, now, loading && protectionEnabled)
        if (frame != null) showCover(frame) else hideCover()
        if (loading) {
            if (loadingSince == 0L) loadingSince = now
            if (now - loadingSince < 500) requestEval(48)
        } else loadingSince = 0L

        val a = d.action
        if (a == null) {
            pending = null
            return
        }
        if (now < pausedUntil || now < cooldownUntil) {
            requestEval(maxOf(pausedUntil, cooldownUntil) - now + 20)
            return
        }
        if (pkg == pendingPackage && a.sameKind(pending)) {
            val waited = now - pendingAt
            if (waited >= 250) {
                pending = null
                execute(a, snap, now)
            } else {
                requestEval(250 - waited + 10)
            }
        } else {
            pending = a
            pendingPackage = pkg
            pendingAt = now
            requestEval(300) // confirm before acting
        }
    }

    private fun execute(a: Action, snap: Snap, now: Long) {
        if (pausing || foreground() != snap.pkg) return
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
        if (BuildConfig.DEBUG && Inspector.tracing) android.util.Log.i(Inspector.TAG, "ACTION ${a::class.simpleName}: ${a.reason}")
        when (a) {
            is Action.Back -> performGlobalAction(GLOBAL_ACTION_BACK)
            is Action.ToDms -> goToDms(snap)
            is Action.Click -> if (!runCatching { a.node.node.performAction(AccessibilityNodeInfo.ACTION_CLICK) }.getOrDefault(false)) {
                if (!pausing && foreground() == snap.pkg) performGlobalAction(GLOBAL_ACTION_BACK)
            }
        }
        requestEval(80)
    }

    private fun goToDms(snap: Snap) {
        if (pausing || !messagesAllowed() || foreground() != Targets.INSTAGRAM) return
        val tab = ig.tabNodes(snap)[InstagramRules.Tab.MESSAGES]
        if (tab != null && runCatching { tab.node.performAction(AccessibilityNodeInfo.ACTION_CLICK) }.getOrDefault(false)) return
        if (pausing || !messagesAllowed() || foreground() != Targets.INSTAGRAM) return
        val ok = runCatching {
            startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse("instagram://direct-inbox"))
                    .setPackage(Targets.INSTAGRAM)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }.isSuccess
        if (!ok && foreground() == Targets.INSTAGRAM) performGlobalAction(GLOBAL_ACTION_BACK)
    }

    private fun messagesAllowed() = settings.igMessages || Breather.allows(this, Breather.Feature.IG_DMS)

    /** "Back to stories" on the cover: re-tapping Home makes Instagram scroll to the top. */
    private fun backToStories() {
        worker.post {
            if (pausing || foreground() != Targets.INSTAGRAM) return@post
            val root = rootInActiveWindow ?: return@post
            if (root.packageName?.toString() != Targets.INSTAGRAM) return@post
            val (w, h) = screenSize()
            val snap = Snap(root, w, h)
            if (!ig.evaluate(snap, SystemClock.uptimeMillis()).coverBackToTop || foreground() != snap.pkg) return@post
            runCatching { ig.tabNodes(snap)[InstagramRules.Tab.HOME]?.node?.performAction(AccessibilityNodeInfo.ACTION_CLICK) }
            requestEval(48)
        }
    }

    private fun backToSearch() {
        worker.post {
            if (pausing || foreground() != Targets.INSTAGRAM) return@post
            val root = rootInActiveWindow ?: return@post
            if (root.packageName?.toString() != Targets.INSTAGRAM) return@post
            val (w, h) = screenSize()
            val snap = Snap(root, w, h)
            if (!ig.evaluate(snap, SystemClock.uptimeMillis()).coverBackToSearch || foreground() != snap.pkg) return@post
            val clicked = runCatching {
                ig.tabNodes(snap)[InstagramRules.Tab.SEARCH]?.node?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true
            }.getOrDefault(false)
            if (!clicked && !pausing && foreground() == snap.pkg) performGlobalAction(GLOBAL_ACTION_BACK)
            requestEval(48)
        }
    }

    private fun showGuard(pkg: String, win: Rect) {
        if (pausing) return
        val first = !guardOn
        guardOn = true
        guardPackage = pkg
        if (first) {
            guardPassword = lock.isSet
            guardBiometric = Auth.biometricAvailable(this)
        }
        val pw = guardPassword
        val bio = guardBiometric
        val revision = guardRevision.incrementAndGet()
        main.post {
            if (pausing || revision != guardRevision.get()) return@post
            val root = rootInActiveWindow
            if (root?.packageName?.toString() != pkg) {
                guard.hide()
                guardOn = false
                return@post
            }
            val window = Rect().also { root.getBoundsInScreen(it) }
            val (width, height) = screenSize()
            if (!window.intersect(safeScreenBounds(width, height)) || !window.intersect(win)) {
                guard.hide()
                guardOn = false
                return@post
            }
            if (pausing || revision != guardRevision.get()) return@post
            guard.show(pw, bio, window)
        }
        if (first && !coverOn) worker.postDelayed(watchdog, WATCHDOG_MS)
        if (first) health.lastAction("protected Detach's settings page")
    }

    private fun hideGuard() {
        guardOn = false
        guardPackage = null
        val revision = guardRevision.incrementAndGet()
        if (!coverOn) worker.removeCallbacks(watchdog)
        main.post { if (revision == guardRevision.get()) guard.hide() }
    }

    private fun openUnlock() {
        if (pausing || foreground() !in Targets.GUARDED) return
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
        if (pausing || foreground() !in Targets.GUARDED) return
        performGlobalAction(GLOBAL_ACTION_BACK)
        worker.postDelayed({
            if (pausing) return@postDelayed
            val root = rootInActiveWindow
            val pkg = root?.packageName?.toString()
            if (root != null && pkg in Targets.GUARDED) {
                val (w, h) = screenSize()
                if (Guard.isProtectedScreen(Snap(root, w, h)) && !pausing && foreground() == pkg) performGlobalAction(GLOBAL_ACTION_HOME)
            }
            requestEval()
        }, 350)
    }

    private fun showCover(frame: CoverFrame) {
        if (pausing) return
        val first = !coverOn
        coverOn = true
        coverPackage = frame.pkg
        val revision = coverRevision.incrementAndGet()
        main.post {
            if (pausing || revision != coverRevision.get()) return@post
            val root = rootInActiveWindow
            if (root?.packageName?.toString() != frame.pkg) {
                cover.hide()
                coverOn = false
                return@post
            }
            val window = Rect().also { root.getBoundsInScreen(it) }
            val (width, height) = screenSize()
            val bounds = Rect(frame.bounds)
            if (!window.intersect(safeScreenBounds(width, height)) || !bounds.intersect(window)) {
                cover.hide()
                coverOn = false
                return@post
            }
            if (pausing || revision != coverRevision.get()) return@post
            cover.show(bounds, frame.label, frame.backToStories, frame.backToSearch)
            if (!cover.isShowing) coverOn = false
        }
        if (first && !guardOn) worker.postDelayed(watchdog, WATCHDOG_MS)
    }

    private fun hideCover() {
        coverOn = false
        coverPackage = null
        val revision = coverRevision.incrementAndGet()
        if (!guardOn) worker.removeCallbacks(watchdog)
        main.post { if (revision == coverRevision.get()) cover.hide() }
    }

    private fun safeScreenBounds(width: Int, height: Int): Rect {
        val wm = getSystemService(WindowManager::class.java)
        if (Build.VERSION.SDK_INT >= 30) {
            val insets = wm.currentWindowMetrics.windowInsets.getInsetsIgnoringVisibility(
                WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout(),
            )
            return Rect(insets.left, insets.top, width - insets.right, height - insets.bottom)
        }
        val usable = DisplayMetrics()
        @Suppress("DEPRECATION")
        val display = wm.defaultDisplay
        @Suppress("DEPRECATION")
        display.getMetrics(usable)
        val statusId = resources.getIdentifier("status_bar_height", "dimen", "android")
        val statusHeight = if (statusId == 0) 0 else resources.getDimensionPixelSize(statusId)
        val navigationWidth = (width - usable.widthPixels).coerceAtLeast(0)
        @Suppress("DEPRECATION")
        val navigationOnLeft = display.rotation == Surface.ROTATION_270
        return Rect(
            if (navigationOnLeft) navigationWidth else 0,
            statusHeight,
            if (navigationOnLeft) width else width - navigationWidth,
            minOf(height, usable.heightPixels),
        )
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
        const val WATCHDOG_MS = 120L

        /** When to look again for a selected app that isn't the active window yet. */
        internal val FOREGROUND_CHECKS_MS = longArrayOf(50, 150, 300, 600, 1000)

        /** After a resume for Instagram/YouTube: the first re-read waits out the window switch. */
        internal val SOCIAL_RESUME_CHECKS_MS = longArrayOf(400, 700, 1000)

        /** After connecting, how long an event with no active window cannot pause by itself. */
        internal const val STARTUP_GRACE_MS = 1_000L

        /**
         * The connected service, for the rest of the app (same process) to talk to. Set in
         * onServiceConnected, cleared when Android unbinds or destroys the service.
         */
        @Volatile private var instance: DetachService? = null

        val isRunning: Boolean get() = instance != null

        /**
         * Asks the running service to switch itself off for the set pause time. Call on the
         * main thread, after the user has been confirmed. False if the service isn't running.
         * [paymentPackage]: the selected app the owner is about to open (recorded only if selected).
         */
        fun pauseForPayments(paymentPackage: String? = null): Boolean {
            val s = instance ?: return false
            return runCatching { s.pauseNow(paymentPackage) }.getOrDefault(false)
        }

        fun refreshSettings() {
            val service = instance ?: return
            service.main.post {
                if (!service.pausing) {
                    service.updateEventFilter()
                    service.refreshBreather()
                }
            }
        }
    }

    override fun onUnbind(intent: Intent?): Boolean {
        stopProtection()
        return super.onUnbind(intent)
    }

    override fun onInterrupt() {
        val coverVersion = coverRevision.incrementAndGet()
        val guardVersion = guardRevision.incrementAndGet()
        main.post {
            if (::cover.isInitialized && coverVersion == coverRevision.get()) cover.hide()
            if (::guard.isInitialized && guardVersion == guardRevision.get()) guard.hide()
        }
        coverOn = false
        guardOn = false
    }

    override fun onDestroy() {
        stopProtection()
        // Switched off for some other reason than a pause: the "on" notification goes away.
        // (During a pause this leaves the "paused until…" notification alone.)
        Pause.refreshNotification(this)
        runCatching { unregisterReceiver(inspectReceiver) }
        super.onDestroy()
    }
}

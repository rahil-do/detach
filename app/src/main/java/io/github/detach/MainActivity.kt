package io.github.detach

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.Toast
import android.provider.Settings as SysSettings

@Suppress("DEPRECATION")
class MainActivity : Activity() {
    private lateinit var settings: Settings
    private lateinit var lock: Lock
    private lateinit var root: LinearLayout
    private lateinit var scroll: ScrollView
    private val ui by lazy { Ui(this) }
    private var unlocked = false
    private var silent = false
    private var rendered = false
    private var restoredScroll = 0
    private var revealDiagnostics = false
    private var lastStatus: String? = null
    private var pendingPreDraw: ViewTreeObserver.OnPreDrawListener? = null
    private val expanded = mutableSetOf<String>()
    private val protectedDialogs = mutableSetOf<AlertDialog>()
    private var authorizationVisit = 0L
    private var visitActive = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settings = Settings(this)
        lock = Lock(this)
        expanded.addAll(savedInstanceState?.getStringArrayList("expanded") ?: emptyList())
        restoredScroll = savedInstanceState?.getInt("scroll") ?: 0
        revealDiagnostics = savedInstanceState?.getBoolean("revealDiagnostics") ?: (intent?.action == Health.ACTION_DIAGNOSTICS)
        if (revealDiagnostics) expanded.add("diagnostic-info")
        ui.systemBars(window)
        window.decorView.setBackgroundColor(ui.bg)
        root = column().apply {
            val side = maxOf(ui.dp(24), (resources.displayMetrics.widthPixels - ui.dp(640)) / 2)
            setPadding(side, ui.dp(20), side, ui.dp(32))
        }
        scroll = ScrollView(this).apply {
            isFillViewport = true
            isSaveEnabled = false
            clipToPadding = false
            addView(root)
            setOnApplyWindowInsetsListener { view, insets ->
                val top: Int
                val bottom: Int
                if (Build.VERSION.SDK_INT >= 30) {
                    val bars = insets.getInsets(android.view.WindowInsets.Type.systemBars())
                    top = bars.top
                    bottom = bars.bottom
                } else {
                    top = insets.systemWindowInsetTop
                    bottom = insets.systemWindowInsetBottom
                }
                view.setPadding(0, top, 0, bottom)
                insets
            }
        }
        setContentView(scroll)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putStringArrayList("expanded", ArrayList(expanded))
        outState.putInt("scroll", scroll.scrollY)
        outState.putBoolean("revealDiagnostics", revealDiagnostics)
        super.onSaveInstanceState(outState)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.action == Health.ACTION_DIAGNOSTICS) {
            expanded.add("diagnostic-info")
            revealDiagnostics = true
            render()
        }
    }

    /** One-shot: the pause's end time was reached (the status text changes once). */
    private val pauseTick = Runnable {
        Pause.resumeIfDue(this)
        render()
    }

    /** One-shot: the breather ended. */
    private val breatherTick = Runnable { render() }

    /**
     * One-shot, [Pause.RECONNECT_CHECK_MS] after the screen started showing that Android has
     * not connected Detach although it is listed as enabled: force a rebind (bounded in Pause).
     */
    private val reconnectCheck = Runnable { Pause.recoverStuckReconnect(this) }

    /** Whatever the status shows was rendered for this state; anything else re-renders once. */
    private var renderedStatus: String? = null

    /** Pause, service and detection changes (see StatusEvents): re-render only if the status changed. */
    private val statusChanged = Runnable { if (visitActive && rendered && statusKey() != renderedStatus) render() }

    /** Detach added to or removed from the enabled accessibility services, by Android, the owner or Detach. */
    private val servicesChanged = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) = statusChanged.run()
    }

    override fun onResume() {
        super.onResume()
        visitActive = true
        StatusEvents.add(statusChanged)
        runCatching {
            contentResolver.registerContentObserver(
                SysSettings.Secure.getUriFor(SysSettings.Secure.ENABLED_ACCESSIBILITY_SERVICES), false, servicesChanged,
            )
        }
        // Opening Detach restores whatever a force-stop removed: the timer, the status
        // notification, and app detection for the same pause (before its timer is due).
        Pause.resumeIfDue(this)
        rearmDetection()
        Pause.refreshNotification(this)
        PauseForegroundMonitor.clearOrphanNotification(this, fromVisibleActivity = true)
        render()
    }

    /**
     * While Detach is in front Android allows it to start the optional monitor, so app detection
     * lost during this pause (process killed, swiped away on an older build, start refused, or
     * opt-in/Usage Access completed only now) is re-attached to the pause's existing authority.
     * Before the timer is due only: opening Detach never spends due timer authority.
     */
    private fun rearmDetection() {
        if (Pause.isPending(this) && !Pause.isExpired(this)) PauseForegroundMonitor.reattach(this)
    }

    override fun onPause() {
        StatusEvents.remove(statusChanged)
        runCatching { contentResolver.unregisterContentObserver(servicesChanged) }
        root.removeCallbacks(pauseTick)
        root.removeCallbacks(breatherTick)
        root.removeCallbacks(reconnectCheck)
        super.onPause()
    }

    override fun onStop() {
        visitActive = false
        authorizationVisit++
        protectedDialogs.toList().forEach { it.dismiss() }
        unlocked = false
        super.onStop()
        DetachService.refreshSettings()
        Auth.cancel()
    }

    private fun render() {
        pendingPreDraw?.let { root.viewTreeObserver.removeOnPreDrawListener(it) }
        val previousY = if (rendered) scroll.scrollY else restoredScroll
        val anchor = (0 until root.childCount).map { root.getChildAt(it) }
            .firstOrNull { it.bottom > previousY && it.tag is String }
        val anchorTag = anchor?.tag
        val anchorOffset = anchor?.let { previousY - it.top } ?: 0
        android.transition.TransitionManager.endTransitions(root)
        root.removeCallbacks(pauseTick)
        root.removeCallbacks(breatherTick)
        root.removeCallbacks(reconnectCheck)
        root.removeAllViews()
        root.addView(LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(ui.icon(R.drawable.ic_mark, 28, ui.accent))
            addView(ui.text("Detach", 23f, strong = true).apply { setPadding(ui.dp(10), 0, 0, 0) })
        })
        root.addView(ui.note("Keep the useful. Leave the feed.").apply { setPadding(0, ui.dp(10), 0, ui.dp(24)) })

        val paused = Pause.isPending(this)
        val currentStatus = when {
            paused -> if (Pause.isExpired(this)) "reminder-due" else "paused"
            serviceEnabled() && DetachService.isRunning -> "active"
            serviceEnabled() -> "connecting"
            else -> "off"
        }
        root.addView(status(paused).apply {
            tag = "status"
            if (rendered && currentStatus != lastStatus && !revealDiagnostics) Motion.enter(this)
        })
        lastStatus = currentStatus
        renderedStatus = statusKey()
        if (paused && !Pause.isExpired(this)) {
            root.postDelayed(pauseTick, (Pause.until(this) - WallClock.now()).coerceAtLeast(0) + 1000)
        }
        // No polling while waiting for Android: the service connecting (or the list changing)
        // re-renders through StatusEvents and the settings observer. One bounded check only.
        if (Pause.waitingForReconnect(this)) root.postDelayed(reconnectCheck, Pause.RECONNECT_CHECK_MS)
        val breatherRemaining = Breather.remainingMs(this)
        if (breatherRemaining > 0) root.postDelayed(breatherTick, breatherRemaining + 1L)
        if (!paused && !serviceEnabled()) root.addView(setup().apply { tag = "setup" })
        else if (needsNotificationPermission()) root.addView(ui.quiet("Allow notifications") {
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 1)
        })

        root.addView(instagram().apply { tag = "instagram" })
        root.addView(youtube().apply { tag = "youtube" })
        root.addView(column().apply {
            tag = "breather"
            addView(BreatherControls.build(this@MainActivity) {
                DetachService.refreshSettings()
                Pause.refreshNotification(this@MainActivity)
                render()
            })
            addView(ui.space(20))
            addView(ui.hairline())
        })
        root.addView(payments(paused).apply { tag = "payments" })
        root.addView(protection().apply { tag = "protection" })
        root.addView(privacy().apply { tag = "privacy" })
        root.addView(diagnostics().apply { tag = "diagnostics" })

        val animateReveal = rendered && Motion.enabled(this)
        pendingPreDraw = object : ViewTreeObserver.OnPreDrawListener {
            override fun onPreDraw(): Boolean {
                root.viewTreeObserver.removeOnPreDrawListener(this)
                pendingPreDraw = null
                val replacement = anchorTag?.let { root.findViewWithTag<View>(it) }
                scroll.scrollTo(0, replacement?.let { it.top + anchorOffset } ?: previousY)
                if (revealDiagnostics) {
                    revealDiagnostics = false
                    val target = root.findViewWithTag<View>("diagnostics").top
                    if (animateReveal) scroll.smoothScrollTo(0, target) else scroll.scrollTo(0, target)
                    root.findViewWithTag<View>("diagnostic-info").sendAccessibilityEvent(android.view.accessibility.AccessibilityEvent.TYPE_VIEW_FOCUSED)
                }
                return true
            }
        }
        root.viewTreeObserver.addOnPreDrawListener(pendingPreDraw)
        if (!rendered && previousY == 0 && !revealDiagnostics) {
            for (index in 0 until minOf(4, root.childCount)) Motion.enter(root.getChildAt(index), index * 24L)
        }
        rendered = true
    }

    /**
     * Title and (optional, one short line) detail of the status block. A pending pause uses the
     * notification's [PauseStatus], so the screen and the notification never disagree.
     */
    private fun statusText(paused: Boolean): Pair<String, String> {
        val active = serviceEnabled() && DetachService.isRunning && !paused
        val connecting = serviceEnabled() && !DetachService.isRunning && !paused
        if (paused) {
            val status = PauseStatus.of(this)
            val title = when {
                status == PauseStatus.RECONNECTING -> "Resuming…"
                status.automatic -> "Paused · ${StatusNotifications.resumesBy(this).replaceFirstChar { it.lowercase() }}"
                else -> "Paused"
            }
            val detail = when (status) {
                PauseStatus.RECONNECTING -> "Not back soon? Tap Resume again."
                PauseStatus.RESUME_STALLED -> "Didn't resume. Tap Resume to retry."
                PauseStatus.DUE -> "Timer done. Tap Resume."
                PauseStatus.DETECTION_ON -> "Or sooner, when Instagram or YouTube opens."
                PauseStatus.DETECTION_STOPPED -> "App detection stopped."
                PauseStatus.AUTOMATIC -> ""
                PauseStatus.MANUAL -> "Tap Resume when you're done paying."
                PauseStatus.NEEDS_USB -> "Resume opens Accessibility settings."
            }
            return title to detail
        }
        val title = when {
            active -> "Protection is on"
            connecting -> "Waiting for Android"
            else -> "Protection is off"
        }
        val detail = when {
            active && Breather.remainingMs(this) > 0 -> "Breather on · Instagram & YouTube unblocked"
            connecting -> "Turned on, but not connected yet."
            else -> ""
        }
        return title to detail
    }

    /** Everything the status-dependent parts of the screen are built from. */
    private fun statusKey(): String {
        val paused = Pause.isPending(this)
        val (title, detail) = statusText(paused)
        return "$paused|${Pause.isExpired(this)}|${serviceEnabled()}|${DetachService.isRunning}|${Pause.canSelfEnable(this)}|$title|$detail"
    }

    private fun status(paused: Boolean): View {
        val active = serviceEnabled() && DetachService.isRunning && !paused
        val connecting = serviceEnabled() && !DetachService.isRunning && !paused
        val color = if (active) ui.accent else ui.caution
        val (title, detail) = statusText(paused)
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(View(this@MainActivity).apply { setBackgroundColor(color) }, LinearLayout.LayoutParams(ui.dp(3), ViewGroup.LayoutParams.MATCH_PARENT))
            addView(column().apply {
                setPadding(ui.dp(16), ui.dp(2), 0, ui.dp(2))
                addView(ui.text(title, 23f, strong = true).apply { isAccessibilityHeading = true })
                if (detail.isNotEmpty()) addView(ui.note(detail).apply { setPadding(0, ui.dp(8), 0, 0) })
                if (connecting) addView(ui.quiet("Check Accessibility settings") {
                    open(Intent(SysSettings.ACTION_ACCESSIBILITY_SETTINGS))
                })
                if (paused) {
                    addView(ui.space(14))
                    addView(ui.primary("Resume") {
                        if (Pause.resume(this@MainActivity)) {
                            toast("Resuming…")
                            render()
                        } else {
                            Toast.makeText(this@MainActivity, "Turn on Use Detach within 3 minutes, or tap Resume again.", Toast.LENGTH_LONG).show()
                            open(Intent(SysSettings.ACTION_ACCESSIBILITY_SETTINGS))
                        }
                    })
                }
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
    }

    private fun setup() = column().apply {
        addView(ui.section("Set up protection"))
        addView(ui.note("Reads only Instagram, YouTube and Detach's Settings pages."))
        addView(ui.space(14))
        addView(ui.primary("Open Accessibility") { open(Intent(SysSettings.ACTION_ACCESSIBILITY_SETTINGS)) })
        addView(ui.note("1. Downloaded apps › Detach\n2. Turn on Use Detach").apply { setPadding(0, ui.dp(10), 0, 0) })
        if (Build.VERSION.SDK_INT >= 33) {
            addView(disclosure("restricted", "Android says Restricted setting?") {
                addView(ui.note("1. Open App info\n2. Menu ⋮ › Allow restricted settings\n3. Come back and turn on Use Detach"))
                addView(ui.space(8))
                addView(ui.secondary("Open App info") {
                    open(Intent(SysSettings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.parse("package:$packageName")))
                })
            })
        }
        if (needsNotificationPermission()) {
            addView(ui.quiet("Allow notifications · optional") {
                requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 1)
            })
        }
    }

    private fun instagram() = column().apply {
        addView(ui.section("App controls"))
        val options = column()
        addView(appRow(R.drawable.ic_instagram_glyph, "Instagram", settings.igEnabled,
            { if (it) "Limits on" else "Off" }) {
            settings.igEnabled = it
            setEnabledLook(options, it)
            DetachService.refreshSettings()
        })
        options.addView(optionRow("Hide home feed", "Stories can stay", settings.igFeed) { settings.igFeed = it })
        options.addView(optionRow("Block Reels tab", "Shared reels: see below", settings.igReelsTab) { settings.igReelsTab = it })
        options.addView(optionRow("Hide Explore & reels in search", "Account search stays", settings.igExplore) { settings.igExplore = it })
        options.addView(optionRow("Hide others’ profile posts", "Name and bio stay", settings.igOtherProfiles) { settings.igOtherProfiles = it })
        options.addView(ui.hairline())
        options.addView(optionRow("Allow Stories", "Off leaves Stories", settings.igStories) { settings.igStories = it })
        options.addView(optionRow("Allow DMs", "Inbox and chat threads", settings.igMessages) { settings.igMessages = it })
        options.addView(optionRow("Allow reels sent in DMs", "One reel, no swiping", settings.igSharedReels) { settings.igSharedReels = it })
        setEnabledLook(options, settings.igEnabled, animate = false)
        addView(disclosure("instagram-options", "Customize Instagram") { addView(options) })
        addView(ui.hairline())
    }

    private fun youtube() = column().apply {
        addView(appRow(R.drawable.ic_youtube_glyph, "YouTube", settings.ytShorts,
            { if (it) "Shorts blocked" else "Off" }) {
            settings.ytShorts = it
            DetachService.refreshSettings()
        })
        addView(ui.hairline())
    }

    private fun payments(paused: Boolean) = column().apply {
        addView(ui.section("Payments"))
        addView(ui.text("Detach turns off while you pay.", 15f).apply {
            tag = "payment-setup"
            setPadding(0, 0, 0, ui.dp(4))
        })
        // Conditional one-line warnings: only when they apply.
        if (paused) addView(ui.note("GPay still blocked? Restart once. Resume here after.").apply {
            tag = "payment-restart"
            setPadding(0, ui.dp(8), 0, 0)
        })
        developerOptionsNote()?.let { addView(it) }
        val others = OtherAccessibilityApps.labels(this@MainActivity)
        if (others.isNotEmpty()) addView(column().apply {
            tag = "other-accessibility"
            setPadding(0, ui.dp(8), 0, 0)
            addView(ui.note("${others.joinToString()} ${if (others.size == 1) "is" else "are"} also on. Payment apps may refuse."))
            addView(ui.quiet("Open Accessibility settings") { open(Intent(SysSettings.ACTION_ACCESSIBILITY_SETTINGS)) })
        })
        addView(ui.space(12))
        if (!paused && DetachService.isRunning) addView(ui.secondary(StatusNotifications.PAUSE_ACTION) {
            Auth.confirm(this@MainActivity, StatusNotifications.PAUSE_ACTION) {
                toast(if (DetachService.pauseForPayments()) "Paused for payment" else "Couldn't pause. Try again.")
                render()
            }
        })
        addView(disclosure("payment-options", "Payment settings") {
            addView(ui.secondary("Payment apps (${settings.paymentPackages.size})") { requirePassword { choosePaymentApps() } })
            addView(reminderRow())
            addView(optionRow("Auto-pause for payment apps", "Checks app names, never screen content", settings.autoPausePayments) {
                settings.autoPausePayments = it
            })
            addView(ui.note(listOf(
                "Opening a selected app turns protection off, without your password, until it resumes.",
                resumeWhileSelectedNote(),
            ).filter { it.isNotEmpty() }.joinToString("\n")).apply {
                tag = "payment-auto-note"
                setPadding(0, 0, 0, ui.dp(12))
            })
            addView(optionRow("Resume when Instagram or YouTube opens", "Local app check while paused", settings.resumeOnSocialForeground) { enabled ->
                if (enabled) confirmForegroundResume() else {
                    settings.resumeOnSocialForeground = false
                    PauseForegroundMonitor.stop(this@MainActivity)
                    render()
                }
            })
            if (settings.resumeOnSocialForeground && !PauseForegroundMonitor.hasUsageAccess(this@MainActivity)) {
                addView(ui.note("Needs Usage Access."))
                addView(ui.quiet("Review Usage Access") { open(PauseForegroundMonitor.usageAccessIntent(this@MainActivity)) })
            }
            addView(optionRow("Status notification", "Silent, with a Pause button", settings.pauseNotification) {
                settings.pauseNotification = it
                Pause.refreshNotification(this@MainActivity)
            })
            if (Build.VERSION.SDK_INT >= 33) addView(ui.quiet("Add Quick Settings tile") { addPauseTile() })
            if (!Pause.canSelfEnable(this@MainActivity)) addView(disclosure("usb", "Auto-resume (USB setup)") {
                addView(ui.note("Lets Detach turn itself back on after a pause."))
                addView(ui.note("1. Turn on USB debugging\n2. From a computer, run once:").apply { setPadding(0, ui.dp(8), 0, 0) })
                addView(ui.note(Pause.GRANT_COMMAND).apply {
                    typeface = Typeface.MONOSPACE
                    setTextIsSelectable(true)
                    setPadding(0, ui.dp(10), 0, ui.dp(10))
                })
                addView(ui.note("3. Turn USB debugging off. The grant stays."))
            })
        })
        addView(ui.hairline())
    }

    /**
     * Some UPI and bank apps refuse while USB debugging or Developer options are on. The USB
     * grant is a one-time `pm grant`: Android keeps it after both are switched off (until Detach
     * is uninstalled or it is revoked), so the owner can switch them off without losing resume.
     * Shown only while they are on. Names Developer options: switching it off also switches USB
     * debugging off, and it is the cause even when USB debugging is already off.
     */
    private fun developerOptionsNote(): View? {
        if (!developerOptionsOn()) return null
        val granted = Pause.canSelfEnable(this)
        return column().apply {
            tag = "developer-options"
            setPadding(0, ui.dp(8), 0, 0)
            addView(ui.note("Turn off Developer options before paying.${if (granted) " The USB grant stays." else ""}"))
            addView(ui.quiet("Open Developer options") {
                open(Intent(SysSettings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS))
            })
        }
    }

    private fun developerOptionsOn(): Boolean = runCatching {
        SysSettings.Global.getInt(contentResolver, SysSettings.Global.DEVELOPMENT_SETTINGS_ENABLED, 0) != 0 ||
            SysSettings.Global.getInt(contentResolver, SysSettings.Global.ADB_ENABLED, 0) != 0
    }.getOrDefault(false)

    /**
     * Only app detection can see that a selected app is still in front. In timer-only mode the
     * timer turns Detach back on when it is due (it waits only while the phone is locked), so the
     * copy must not promise that a selected app keeps protection paused.
     */
    private fun resumeWhileSelectedNote(): String = when {
        !Pause.canSelfEnable(this) -> ""
        settings.resumeOnSocialForeground && PauseForegroundMonitor.hasUsageAccess(this) -> ""
        else -> "Without app detection, the timer can resume mid-checkout."
    }

    /** Asks System UI to offer the "Pause for payment" tile (Android 13+); the owner decides. */
    private fun addPauseTile() {
        if (Build.VERSION.SDK_INT < 33) return
        val asked = runCatching {
            getSystemService(android.app.StatusBarManager::class.java).requestAddTileService(
                android.content.ComponentName(this, PauseTileService::class.java),
                PauseTileService.LABEL,
                android.graphics.drawable.Icon.createWithResource(this, R.drawable.ic_pause),
                mainExecutor,
            ) { }
        }.isSuccess
        if (!asked) toast("Edit Quick Settings to add the tile")
    }

    private fun reminderRow() = LinearLayout(this).apply {
        gravity = Gravity.CENTER_VERTICAL
        setPadding(0, ui.dp(8), 0, ui.dp(8))
        addView(ui.text("Pause length", 14f), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        fun adjust(label: String, delta: Int) = ui.secondary(label) {
            val next = (settings.pauseMinutes + delta).coerceIn(1, 60)
            if (next != settings.pauseMinutes) requirePassword {
                settings.pauseMinutes = next
                render()
            }
        }.apply {
            contentDescription = if (delta < 0) "Decrease payment pause by one minute" else "Increase payment pause by one minute"
            isEnabled = if (delta < 0) settings.pauseMinutes > 1 else settings.pauseMinutes < 60
        }
        addView(adjust("−", -1))
        addView(ui.text("${settings.pauseMinutes} min", 14f, strong = true).apply {
            gravity = Gravity.CENTER
            setPadding(ui.dp(10), 0, ui.dp(10), 0)
            contentDescription = "Payment pause for ${settings.pauseMinutes} minutes"
        })
        addView(adjust("+", 1))
    }

    private fun choosePaymentApps() {
        PaymentAppPicker.show(this) {
            DetachService.refreshSettings()
            toast("Saved")
            render()
        }
    }

    private fun confirmForegroundResume() {
        val visit = authorizationVisit
        val ready = Pause.canSelfEnable(this) && PauseForegroundMonitor.hasUsageAccess(this)
        val detail = "While paused, Detach checks which app is open — names only, every 2 s, while unlocked. Nothing is saved or sent. Needs Usage Access and USB setup."
        val dialog = AlertDialog.Builder(this)
            .setTitle("Allow app detection?")
            .setMessage(detail)
            .setNegativeButton("Not now") { _, _ -> render() }
            .setOnCancelListener { render() }
            .setPositiveButton(if (ready) "Enable" else "Review setup") { _, _ ->
                if (!visitActive || authorizationVisit != visit || isFinishing || isDestroyed) return@setPositiveButton
                if (ready && Pause.canSelfEnable(this) && PauseForegroundMonitor.hasUsageAccess(this)) {
                    settings.resumeOnSocialForeground = true
                    rearmDetection()
                    Pause.refreshNotification(this)
                    render()
                } else {
                    expanded.add("payment-options")
                    expanded.add("usb")
                    render()
                    if (!PauseForegroundMonitor.hasUsageAccess(this)) open(PauseForegroundMonitor.usageAccessIntent(this))
                    else toast("Finish USB setup first")
                }
            }
            .create()
        protectDialog(dialog)
        dialog.show()
        ui.style(dialog)
    }

    private fun protectDialog(dialog: AlertDialog) {
        protectedDialogs.add(dialog)
        dialog.setOnDismissListener { protectedDialogs.remove(dialog) }
    }

    private fun protection() = column().apply {
        addView(ui.section("Settings protection"))
        addView(ui.text(when {
            !lock.isSet -> "Add a password"
            unlocked -> "Unlocked"
            else -> "Locked"
        }, 19f, strong = true))
        addView(ui.note(when {
            !lock.isSet -> "Stops impulse changes to your limits."
            unlocked -> "Locks again when you leave."
            Auth.biometricAvailable(this@MainActivity) -> "Fingerprint or password to change."
            else -> "Password needed to change."
        }).apply { setPadding(0, ui.dp(8), 0, ui.dp(14)) })
        if (!lock.isSet) addView(ui.secondary("Set password") { setPasswordDialog() })
        else addView(LinearLayout(this@MainActivity).apply {
            addView(ui.secondary("Change password") { setPasswordDialog() }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(ui.quiet("Remove") { removePassword() })
        })
        addView(disclosure("lock-info", "How it works") {
            addView(ui.note("It also guards turning off, force-stopping or uninstalling Detach.\nNo password? Fingerprint, or a 30-second wait.\nChanging the password needs the current one."))
        })
        addView(ui.hairline())
    }

    private fun privacy() = column().apply {
        addView(ui.note("No internet. Nothing leaves your phone.").apply { setPadding(0, ui.dp(16), 0, 0) })
        addView(disclosure("privacy-info", "Privacy details") {
            addView(ui.note("Reads screens only in Instagram, YouTube and Detach's Settings pages.\nPayment checks and app detection use app names only.\nSettings, a hashed password and counters stay on this phone."))
            addView(ui.space(12))
        })
    }

    private fun diagnostics() = disclosure("diagnostic-info", "Diagnostics") {
        addView(ui.note(Health(this@MainActivity).report()).apply {
            typeface = Typeface.MONOSPACE
            textSize = 12f
            setTextIsSelectable(true)
            setPadding(0, 0, 0, ui.dp(12))
        })
    }

    private fun column() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

    private fun disclosure(key: String, label: String, content: LinearLayout.() -> Unit): View {
        val box = column()
        val body = column().apply {
            content()
            visibility = if (key in expanded) View.VISIBLE else View.GONE
        }
        lateinit var header: ViewGroup
        header = ui.disclosure(label, key in expanded) {
            val opening = key !in expanded
            if (opening) expanded.add(key) else expanded.remove(key)
            Motion.section(root)
            body.visibility = if (opening) View.VISIBLE else View.GONE
            ui.updateDisclosure(header, opening)
        }.apply { tag = key }
        box.addView(header)
        box.addView(body)
        return box
    }

    private fun appRow(icon: Int, title: String, value: Boolean, subtitle: (Boolean) -> String, change: (Boolean) -> Unit): View {
        val row = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, ui.dp(16), 0, ui.dp(12))
        }
        row.addView(ui.icon(icon, 28))
        val labels = column().apply { setPadding(ui.dp(14), 0, ui.dp(8), 0) }
        labels.addView(ui.text(title, 18f, strong = true))
        val detail = ui.note(subtitle(value)).apply { setPadding(0, ui.dp(5), 0, 0) }
        labels.addView(detail)
        row.addView(labels, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(guardedSwitch(value, "$title protection") {
            change(it)
            detail.text = subtitle(it)
        })
        return row
    }

    private fun optionRow(title: String, hint: String, value: Boolean, change: (Boolean) -> Unit) = LinearLayout(this).apply {
        gravity = Gravity.CENTER_VERTICAL
        setPadding(0, ui.dp(10), 0, ui.dp(10))
        val labels = column().apply {
            setPadding(0, 0, ui.dp(10), 0)
            addView(ui.text(title, 15f))
            addView(ui.note(hint).apply { setPadding(0, ui.dp(4), 0, 0) })
        }
        addView(labels, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        addView(guardedSwitch(value, title) {
            change(it)
            DetachService.refreshSettings()
        })
    }

    private fun guardedSwitch(value: Boolean, label: String, change: (Boolean) -> Unit): Switch = ui.toggle(value, label).apply {
        setOnCheckedChangeListener { control, wanted ->
            if (silent) return@setOnCheckedChangeListener
            if (!lock.isSet || unlocked) {
                change(wanted)
            } else {
                flip(control as Switch, !wanted)
                requirePassword {
                    flip(control, wanted)
                    change(wanted)
                }
            }
        }
    }

    private fun flip(control: Switch, value: Boolean) {
        silent = true
        control.isChecked = value
        silent = false
    }

    private fun setEnabledLook(group: ViewGroup, enabled: Boolean, animate: Boolean = true) {
        fun walk(view: View) {
            view.isEnabled = enabled
            if (view is ViewGroup) for (index in 0 until view.childCount) walk(view.getChildAt(index))
        }
        walk(group)
        if (animate) Motion.emphasis(group, enabled) else group.alpha = 1f
    }

    private fun requirePassword(alwaysAsk: Boolean = false, action: () -> Unit) {
        if (!visitActive) return
        if (!lock.isSet || (unlocked && !alwaysAsk)) {
            action()
            return
        }
        val visit = authorizationVisit
        val confirmed = {
            if (visitActive && authorizationVisit == visit && !isFinishing && !isDestroyed) {
                if (!alwaysAsk) unlocked = true
                action()
                render()
            }
        }
        if (alwaysAsk) Auth.passwordDialog(this, positive = "Continue", onSuccess = confirmed)
        else Auth.confirm(this, "Unlock Detach's switches", onSuccess = confirmed)
    }

    private fun removePassword() = requirePassword(alwaysAsk = true) {
        lock.clear()
        unlocked = false
        toast("Password removed")
    }

    private fun setPasswordDialog() {
        val visit = authorizationVisit
        val changing = lock.isSet
        val current = if (changing) passwordEditText(this, "Current password") else null
        val first = passwordEditText(this, "New password (4+ characters)")
        val second = passwordEditText(this, "Repeat new password")
        val box = column().apply {
            setPadding(ui.dp(24), ui.dp(8), ui.dp(24), 0)
            current?.let { addView(it) }
            addView(first)
            addView(second)
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle(if (changing) "Change password" else "Set a password")
            .setMessage("Forgot it? Reinstalling Detach resets everything.")
            .setView(box)
            .setPositiveButton("Save", null)
            .setNegativeButton("Cancel", null)
            .create()
        protectDialog(dialog)
        dialog.setOnShowListener {
            ui.style(dialog)
            val save = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
            save.setOnClickListener {
                if (!visitActive || authorizationVisit != visit || dialog !in protectedDialogs) return@setOnClickListener
                val password = first.text.toString()
                val repeated = second.text.toString()
                when {
                    password.length < 4 -> { first.error = "At least 4 characters"; return@setOnClickListener }
                    password != repeated -> { second.error = "Doesn't match"; return@setOnClickListener }
                }
                if (current != null && lock.lockoutSeconds() > 0) {
                    current.error = "Too many tries. Wait ${lock.lockoutSeconds()} s"
                    return@setOnClickListener
                }
                val old = current?.text?.toString()
                save.isEnabled = false
                HashWorker.run(first, {
                    if (old != null && !lock.verify(old)) false else { lock.set(password); true }
                }) { saved ->
                    save.isEnabled = true
                    if (saved) {
                        unlocked = false
                        dialog.dismiss()
                        toast(if (changing) "Password changed" else "Password saved")
                        render()
                    } else {
                        current?.text?.clear()
                        val wait = lock.lockoutSeconds()
                        current?.error = if (wait > 0) "Too many tries. Wait $wait s" else "Wrong current password"
                    }
                }
            }
        }
        dialog.show()
        (current ?: first).requestFocus()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        render()
    }

    private fun needsNotificationPermission() = Build.VERSION.SDK_INT >= 33 &&
        checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED

    private fun serviceEnabled(): Boolean {
        val services = SysSettings.Secure.getString(contentResolver, SysSettings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
        return services.split(':').any { it.startsWith("$packageName/") }
    }

    private fun open(intent: Intent) {
        runCatching { startActivity(intent) }.onFailure { toast("Couldn't open that screen") }
    }

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
}

package io.github.detach

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
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

    private val pauseTick = Runnable {
        Pause.resumeIfDue(this)
        render()
    }

    private val connectionTick = Runnable { render() }
    private val breatherTick = Runnable { render() }

    override fun onResume() {
        super.onResume()
        visitActive = true
        Pause.resumeIfDue(this)
        Pause.refreshNotification(this)
        render()
    }

    override fun onPause() {
        root.removeCallbacks(pauseTick)
        root.removeCallbacks(connectionTick)
        root.removeCallbacks(breatherTick)
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
        root.removeCallbacks(connectionTick)
        root.removeCallbacks(breatherTick)
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
        if (paused && !Pause.isExpired(this)) {
            root.postDelayed(pauseTick, (Pause.until(this) - System.currentTimeMillis()).coerceAtLeast(0) + 1000)
        }
        if (serviceEnabled() && !DetachService.isRunning) root.postDelayed(connectionTick, 1000)
        val breatherRemaining = Breather.remainingMs(this)
        if (breatherRemaining > 0) root.postDelayed(breatherTick, breatherRemaining + 1L)
        if (!paused && !serviceEnabled()) root.addView(setup().apply { tag = "setup" })
        else if (needsNotificationPermission()) root.addView(ui.quiet("Allow status & reminder notifications") {
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
            addView(ui.hairline())
        })
        root.addView(payments(paused).apply { tag = "payments" })
        root.addView(protection().apply { tag = "protection" })
        root.addView(privacy().apply { tag = "privacy" })
        root.addView(diagnostics().apply { tag = "diagnostics" })
        root.addView(ui.note("On your device. Under your control.").apply { setPadding(0, ui.dp(20), 0, 0) })

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

    private fun status(paused: Boolean): View {
        val active = serviceEnabled() && DetachService.isRunning && !paused
        val connecting = serviceEnabled() && !DetachService.isRunning && !paused
        val color = if (active) ui.accent else ui.caution
        val title = when {
            paused -> "Protection is paused"
            active -> "Protection is on"
            connecting -> "Waiting for Android"
            else -> "Ready when you are"
        }
        val detail = when {
            paused && serviceEnabled() -> "Resume requested. Waiting for Android to reconnect; protection is not confirmed yet."
            paused && Pause.isExpired(this) -> "The pause timer is due. If automatic resume is unavailable or delayed, use Resume protection."
            paused && Pause.canSelfEnable(this) -> "Automatic resume after ${Pause.untilText(this)}. You can resume sooner below."
            paused -> "Reminder at ${Pause.untilText(this)}. Automatic resume needs the optional USB setup below."
            active && Breather.remainingMs(this) > 0 -> "Your breather is active. Only selected features are temporarily available."
            active -> "Your app choices below are being applied."
            connecting -> "Accessibility is enabled, but the service hasn’t connected yet. Protection is not confirmed."
            else -> "One Android permission to get started."
        }
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(View(this@MainActivity).apply { setBackgroundColor(color) }, LinearLayout.LayoutParams(ui.dp(3), ViewGroup.LayoutParams.MATCH_PARENT))
            addView(column().apply {
                setPadding(ui.dp(16), ui.dp(2), 0, ui.dp(2))
                addView(ui.text(title, 23f, strong = true).apply { isAccessibilityHeading = true })
                addView(ui.note(detail).apply { setPadding(0, ui.dp(8), 0, 0) })
                if (connecting) addView(ui.quiet("Check Accessibility settings") {
                    open(Intent(SysSettings.ACTION_ACCESSIBILITY_SETTINGS))
                })
                if (paused) {
                    addView(ui.space(14))
                    addView(ui.primary("Resume protection") {
                        if (Pause.resume(this@MainActivity)) {
                            toast("Resume requested. Waiting for Android.")
                            render()
                        } else {
                            Toast.makeText(this@MainActivity, "Turn Use Detach on within 3 minutes. If you take longer, return to Detach and tap Resume again.", Toast.LENGTH_LONG).show()
                            open(Intent(SysSettings.ACTION_ACCESSIBILITY_SETTINGS))
                        }
                    })
                    addView(ui.note(if (Pause.canSelfEnable(this@MainActivity)) "The timer can also resume protection. Optional app detection is configured under Payments."
                        else "Opens Accessibility. Turn Use Detach on within 3 minutes of tapping Resume. If you take longer, return here and tap Resume again.").apply { setPadding(0, ui.dp(8), 0, 0) })
                }
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
    }

    private fun setup() = column().apply {
        addView(ui.section("Set up protection"))
        addView(ui.note("Detach uses Accessibility only in Instagram, YouTube and its protected Settings pages. It never reads banking or payment screens and has no internet access."))
        addView(ui.space(14))
        addView(ui.primary("Open Accessibility") { open(Intent(SysSettings.ACTION_ACCESSIBILITY_SETTINGS)) })
        addView(ui.note("Open Detach under Downloaded apps, then turn on Use Detach.").apply { setPadding(0, ui.dp(10), 0, 0) })
        if (Build.VERSION.SDK_INT >= 33) {
            addView(disclosure("restricted", "Android says Restricted setting?") {
                addView(ui.note("For an app installed from a file: open App info, tap the top-right menu and choose Allow restricted settings. Then return to Accessibility."))
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
            addView(ui.note("For protection status, payment reminders and app compatibility warnings."))
        }
    }

    private fun instagram() = column().apply {
        addView(ui.section("App controls"))
        val options = column()
        addView(appRow(R.drawable.ic_instagram_glyph, "Instagram", settings.igEnabled,
            { if (it) "Your selected limits are on" else "No limits applied" }) {
            settings.igEnabled = it
            setEnabledLook(options, it)
            DetachService.refreshSettings()
        })
        options.addView(optionRow("Hide home feed", "Stories can stay available", settings.igFeed) { settings.igFeed = it })
        options.addView(optionRow("Block Reels tab", "Shared reels are controlled separately below", settings.igReelsTab) { settings.igReelsTab = it })
        options.addView(optionRow("Hide Explore & reels in search", "Account search stays available", settings.igExplore) { settings.igExplore = it })
        options.addView(optionRow("Hide others’ profile posts", "Their name, bio and buttons stay visible", settings.igOtherProfiles) { settings.igOtherProfiles = it })
        options.addView(ui.hairline())
        options.addView(optionRow("Allow Stories", "Off leaves Stories immediately", settings.igStories) { settings.igStories = it })
        options.addView(optionRow("Allow DMs", "Inbox and chat threads", settings.igMessages) { settings.igMessages = it })
        options.addView(optionRow("Allow reels sent in DMs", "One shared reel; no swiping to the next", settings.igSharedReels) { settings.igSharedReels = it })
        setEnabledLook(options, settings.igEnabled, animate = false)
        addView(disclosure("instagram-options", "Customize Instagram") { addView(options) })
        addView(ui.hairline())
    }

    private fun youtube() = column().apply {
        addView(appRow(R.drawable.ic_youtube_glyph, "YouTube", settings.ytShorts,
            { if (it) "Shorts blocked. Videos stay available." else "No limits applied" }) {
            settings.ytShorts = it
            DetachService.refreshSettings()
        })
        addView(ui.hairline())
    }

    private fun payments(paused: Boolean) = column().apply {
        addView(ui.section("Payments"))
        addView(ui.text("Switch off. Pay. Resume.", 19f, strong = true))
        addView(ui.note("A payment pause fully disables Accessibility and removes overlays. The timer resumes protection with the optional USB grant; otherwise it reminds you to resume in Settings. App detection is a separate opt-in below.").apply { setPadding(0, ui.dp(8), 0, ui.dp(14)) })
        if (!paused && DetachService.isRunning) addView(ui.secondary("Pause protection for payment") {
            Auth.confirm(this@MainActivity, "Pause for payments") {
                toast(if (DetachService.pauseForPayments()) "Protection paused. Your payment timer is set."
                    else "Could not pause protection. Check Accessibility or try again.")
                render()
            }
        })
        addView(disclosure("payment-options", "Payment preferences") {
            addView(optionRow("Auto-pause for payment apps", "Checks selected package names, never screen content", settings.autoPausePayments) {
                settings.autoPausePayments = it
            })
            addView(ui.note("Opening a selected app bypasses your password and turns all protection off during the pause. Instagram, YouTube and uninstalling Detach are unrestricted until protection resumes. Only selected apps are detected; embedded payments are not.").apply {
                setPadding(0, 0, 0, ui.dp(12))
            })
            addView(ui.secondary("Choose payment & banking apps") { requirePassword { choosePaymentApps() } })
            addView(ui.note("${settings.paymentPackages.size} apps selected. Four UPI apps are included by default; select any other banks you use.").apply { setPadding(0, ui.dp(8), 0, ui.dp(8)) })
            addView(reminderRow())
            addView(optionRow("Resume when Instagram or YouTube opens", "Optional local app detection during payment pauses", settings.resumeOnSocialForeground) { enabled ->
                if (enabled) confirmForegroundResume() else {
                    settings.resumeOnSocialForeground = false
                    PauseForegroundMonitor.stop(this@MainActivity)
                    render()
                }
            })
            if (settings.resumeOnSocialForeground && !PauseForegroundMonitor.hasUsageAccess(this@MainActivity)) {
                addView(ui.note("App detection is unavailable without Usage Access. The payment timer still applies."))
                addView(ui.quiet("Review Usage Access") { open(PauseForegroundMonitor.usageAccessIntent(this@MainActivity)) })
            }
            addView(optionRow("Show protection status", "Silent notification with a payment-pause action", settings.pauseNotification) {
                settings.pauseNotification = it
                Pause.refreshNotification(this@MainActivity)
            })
            if (!Pause.canSelfEnable(this@MainActivity)) addView(disclosure("usb", "Enable automatic Resume over USB") {
                addView(ui.note("This optional grant lets the timer, your explicit Resume tap, and opt-in app detection re-enable Detach. Without it, Android requires you to turn Detach on in Accessibility settings. Timer-only mode cannot tell whether you are still banking; choose enough time."))
                addView(ui.note(Pause.GRANT_COMMAND).apply {
                    typeface = Typeface.MONOSPACE
                    setTextIsSelectable(true)
                    setPadding(0, ui.dp(10), 0, ui.dp(10))
                })
            })
        })
        addView(ui.hairline())
    }

    private fun reminderRow() = LinearLayout(this).apply {
        gravity = Gravity.CENTER_VERTICAL
        setPadding(0, ui.dp(8), 0, ui.dp(8))
        addView(ui.text("Payment pause", 14f), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
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
            toast("Payment apps saved on this device")
            render()
        }
    }

    private fun confirmForegroundResume() {
        val visit = authorizationVisit
        val ready = Pause.canSelfEnable(this) && PauseForegroundMonitor.hasUsageAccess(this)
        val detail = "While payment protection is paused, a separate local monitor checks foreground app packages about every 2 seconds while your screen is unlocked. It does not read screen content, save app history, or send data anywhere. It stops after resume and does not poll while the screen is off or locked. Android can delay detection or prevent it from starting.\n\nThis needs Usage Access and the optional USB grant. Timer-only mode has no app monitor and cannot know whether you are still banking. Enabling applies to your next payment pause."
        val dialog = AlertDialog.Builder(this)
            .setTitle("Enable local app detection?")
            .setMessage(detail)
            .setNegativeButton("Not now") { _, _ -> render() }
            .setOnCancelListener { render() }
            .setPositiveButton(if (ready) "Enable" else "Review setup") { _, _ ->
                if (!visitActive || authorizationVisit != visit || isFinishing || isDestroyed) return@setPositiveButton
                if (ready && Pause.canSelfEnable(this) && PauseForegroundMonitor.hasUsageAccess(this)) {
                    settings.resumeOnSocialForeground = true
                    render()
                } else {
                    expanded.add("payment-options")
                    expanded.add("usb")
                    render()
                    if (!PauseForegroundMonitor.hasUsageAccess(this)) open(PauseForegroundMonitor.usageAccessIntent(this))
                    else toast("Complete the optional USB grant, then enable app detection.")
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
            !lock.isSet -> "Add a little friction"
            unlocked -> "Unlocked for this visit"
            else -> "Your settings are locked"
        }, 19f, strong = true))
        addView(ui.note(when {
            !lock.isSet -> "A password keeps an impulse from changing your limits."
            unlocked -> "They lock again when you leave this screen."
            Auth.biometricAvailable(this@MainActivity) -> "Use your fingerprint or password to change app controls."
            else -> "Use your password to change app controls."
        }).apply { setPadding(0, ui.dp(8), 0, ui.dp(14)) })
        if (!lock.isSet) addView(ui.secondary("Set password") { setPasswordDialog() })
        else addView(LinearLayout(this@MainActivity).apply {
            addView(ui.secondary("Change password") { setPasswordDialog() }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(ui.quiet("Remove") { removePassword() })
        })
        addView(disclosure("lock-info", "How protection works") {
            addView(ui.note("The same password protects Detach’s system settings from being switched off, force-stopped, cleared or uninstalled. Without a password, that screen asks for your fingerprint, or a 30-second wait if none is available. Changing or removing a password always requires the current password."))
        })
        addView(ui.hairline())
    }

    private fun privacy() = disclosure("privacy-info", "Private by design") {
        addView(ui.note("No internet permission. Nothing is uploaded.\n\nScreen content is read only in Instagram, YouTube and protected Settings pages. Payment auto-pause checks selected package names, never their content. Optional Usage Access detection checks recent foreground packages only during pauses; app history is not saved.\n\nPreferences, a hashed password, pause and breather timing, and diagnostic counters stay in private on-device storage. Screen content is not saved."))
        addView(ui.space(12))
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
        val first = passwordEditText(this, "New password (at least 4 characters)")
        val second = passwordEditText(this, "Repeat new password")
        val box = column().apply {
            setPadding(ui.dp(24), ui.dp(8), ui.dp(24), 0)
            current?.let { addView(it) }
            addView(first)
            addView(second)
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle(if (changing) "Change password" else "Set a password")
            .setMessage("You'll need it to change controls and unlock protected system settings. If you forget it, reinstalling Detach resets everything.")
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
        runCatching { startActivity(intent) }.onFailure { toast("Couldn't open that screen on this phone") }
    }

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
}

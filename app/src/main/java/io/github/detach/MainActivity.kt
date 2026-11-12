package io.github.detach

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.provider.Settings as SysSettings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast

/**
 * Detach's single screen:
 *  - status (is the accessibility service on?)
 *  - Instagram card: master switch + individual blocks
 *  - YouTube card: block Shorts
 *  - pause for payments
 *  - optional password lock for all switches (fingerprint or password unlocks)
 *  - privacy note and diagnostics
 * Built in code (no layout XML, no libraries) to keep the app tiny and dependency-free.
 */
@Suppress("DEPRECATION") // android.widget.Switch: fine, avoids pulling in AndroidX
class MainActivity : Activity() {

    private lateinit var settings: Settings
    private lateinit var lock: Lock

    /** Becomes true after the password is entered; resets when the app leaves the screen. */
    private var unlocked = false
    /** Set while switches are flipped from code, so their listeners ignore it. */
    private var silent = false

    private lateinit var root: LinearLayout
    private val palette by lazy { Palette() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settings = Settings(this)
        lock = Lock(this)
        window.decorView.setBackgroundColor(palette.bg)

        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(18), dp(18), dp(28))
        }
        val scroll = ScrollView(this).apply {
            addView(root)
            isFillViewport = true
            // Edge-to-edge: keep content clear of the status and navigation bars.
            setOnApplyWindowInsetsListener { v, insets ->
                val top: Int
                val bottom: Int
                if (Build.VERSION.SDK_INT >= 30) {
                    val b = insets.getInsets(android.view.WindowInsets.Type.systemBars())
                    top = b.top; bottom = b.bottom
                } else {
                    top = insets.systemWindowInsetTop; bottom = insets.systemWindowInsetBottom
                }
                v.setPadding(0, top, 0, bottom)
                insets
            }
        }
        setContentView(scroll)
    }

    /** Redraws the screen when a running pause reaches its end. */
    private val pauseTick = Runnable {
        Pause.resumeIfDue(this)
        render()
    }

    override fun onResume() {
        super.onResume()
        // Safety net: a pause that ran out while the alarm couldn't end it.
        Pause.resumeIfDue(this)
        Pause.refreshNotification(this)
        render()
    }

    override fun onPause() {
        super.onPause()
        root.removeCallbacks(pauseTick)
    }

    override fun onStop() {
        super.onStop()
        Auth.cancel()
        unlocked = false
    }

    // ------------------------------------------------------------------ screen

    private fun render() {
        root.removeAllViews()

        root.addView(TextView(this).apply {
            text = "Detach"
            textSize = 30f
            setTypeface(Typeface.DEFAULT, Typeface.BOLD)
            setTextColor(palette.text)
            setPadding(dp(4), dp(8), 0, dp(2))
        })
        root.addView(TextView(this).apply {
            text = "Use Instagram and YouTube without the endless scrolling."
            textSize = 14f
            setTextColor(palette.subtext)
            setPadding(dp(4), 0, 0, dp(14))
        })

        root.removeCallbacks(pauseTick)
        val paused = Pause.isPending(this)
        when {
            // Never the setup card while a pause is pending: Detach is off on purpose.
            paused -> {
                root.addView(pausedStatus())
                if (!Pause.isExpired(this)) {
                    root.postDelayed(pauseTick, (Pause.until(this) - System.currentTimeMillis()).coerceAtLeast(0) + 1000)
                }
            }
            serviceEnabled() -> {
                root.addView(statusPill())
                if (needsNotificationPermission()) root.addView(notificationHint())
            }
            else -> root.addView(setupCard())
        }
        if (paused || serviceEnabled()) root.addView(pauseCard(paused))
        root.addView(instagramCard())
        root.addView(youtubeCard())
        root.addView(lockCard())
        root.addView(privacyNote())
        root.addView(diagnostics())
    }

    /** First-run setup, shown until the accessibility service is on (e.g. on a friend's phone). */
    private fun setupCard(): View {
        val card = card()
        card.addView(TextView(this).apply {
            text = "Set up Detach"
            textSize = 20f
            setTypeface(Typeface.DEFAULT, Typeface.BOLD)
            setTextColor(palette.text)
        })
        card.addView(TextView(this).apply {
            text = "Detach works through Android's Accessibility feature. It only looks at Instagram, YouTube and its own Settings pages, and never reads UPI, banking, messages or any other app. It has no internet access."
            textSize = 14f
            setTextColor(palette.subtext)
            setPadding(0, dp(6), 0, dp(8))
        })

        var n = 1
        card.addView(step(n++, "Turn on Detach",
            "In Accessibility, open \"Detach\" (it may be under \"Downloaded apps\") and switch on \"Use Detach\". Android will warn you that it can see the screen; Detach only looks at the apps listed above.",
            "Open Accessibility") {
            open(Intent(SysSettings.ACTION_ACCESSIBILITY_SETTINGS))
        })
        if (Build.VERSION.SDK_INT >= 33) {
            card.addView(step(n++, "If Android says \"Restricted setting\"",
                "Android blocks Accessibility for apps installed from a file. Open App info, tap ⋮ (top right), choose \"Allow restricted settings\", then do step 1 again.",
                "Open App info") {
                open(Intent(SysSettings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.parse("package:$packageName")))
            })
        }
        if (needsNotificationPermission()) {
            card.addView(step(n, "Allow notifications (optional)",
                "Only used to tell you if an Instagram or YouTube update stops Detach from working.",
                "Allow") {
                requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 1)
            })
        }
        return card
    }

    private fun needsNotificationPermission() = Build.VERSION.SDK_INT >= 33 &&
        checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED

    private fun notificationHint(): View = TextView(this).apply {
        text = "🔔  Allow notifications so Detach can tell you if an app update breaks it. Tap here."
        textSize = 13f
        setTextColor(palette.accent)
        setPadding(dp(6), dp(10), dp(6), 0)
        setOnClickListener { requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 1) }
    }

    private fun open(intent: Intent) {
        runCatching { startActivity(intent) }.onFailure { toast("Couldn't open that screen on this phone") }
    }

    private fun step(n: Int, title: String, body: String, button: String, onClick: () -> Unit): View {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(12), 0, 0)
        }
        box.addView(TextView(this).apply {
            text = "$n.  $title"
            textSize = 16f
            setTypeface(Typeface.DEFAULT, Typeface.BOLD)
            setTextColor(palette.text)
        })
        box.addView(TextView(this).apply {
            text = body
            textSize = 13f
            setTextColor(palette.subtext)
            setPadding(dp(22), dp(2), 0, dp(8))
        })
        box.addView(LinearLayout(this).apply {
            setPadding(dp(22), 0, 0, 0)
            addView(pillButton(button, primary = true, onClick = onClick))
        })
        return box
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        render()
    }

    private fun statusPill(): View {
        val on = serviceEnabled()
        return TextView(this).apply {
            text = if (on) "●  Active" else "⚠  Off. Tap to turn on \"Detach\" in Accessibility settings"
            textSize = 14f
            setTypeface(Typeface.DEFAULT, Typeface.BOLD)
            setTextColor(if (on) palette.okText else palette.warnText)
            background = rounded(if (on) palette.okBg else palette.warnBg, 100f)
            setPadding(dp(16), dp(10), dp(16), dp(10))
            if (!on) setOnClickListener { open(Intent(SysSettings.ACTION_ACCESSIBILITY_SETTINGS)) }
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
    }

    /** Shown instead of the status pill / setup card while Detach is paused for payments. */
    private fun pausedStatus(): View {
        val over = Pause.isExpired(this)
        val auto = Pause.canSelfEnable(this)
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        box.addView(TextView(this).apply {
            text = if (over) "⚠  Pause is over. Detach is still off" else "⏸  Paused until ${Pause.untilText(this@MainActivity)}"
            textSize = 14f
            setTypeface(Typeface.DEFAULT, Typeface.BOLD)
            setTextColor(palette.warnText)
            background = rounded(palette.warnBg, 100f)
            setPadding(dp(16), dp(10), dp(16), dp(10))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        })
        box.addView(LinearLayout(this).apply {
            setPadding(0, dp(10), 0, 0)
            addView(pillButton(if (auto) "Resume now" else "Turn Detach back on", primary = true) {
                if (auto && Pause.resume(this@MainActivity)) {
                    toast("Detach is back on")
                    render()
                } else {
                    // No permission to do it ourselves (or it failed): the user flips the switch.
                    open(Intent(SysSettings.ACTION_ACCESSIBILITY_SETTINGS))
                }
            })
        })
        return box
    }

    private fun pauseCard(paused: Boolean): View {
        val minutes = settings.pauseMinutes
        val unit = if (minutes == 1) "minute" else "minutes"
        val card = card()
        card.addView(TextView(this).apply {
            text = "⏸  Pause for payments"
            textSize = 17f
            setTypeface(Typeface.DEFAULT, Typeface.BOLD)
            setTextColor(palette.text)
        })
        card.addView(TextView(this).apply {
            text = "Some payment (UPI) apps won't open while Detach is on. A pause turns Detach off for $minutes $unit, then back on. Nothing is blocked or protected during a pause."
            textSize = 13f
            setTextColor(palette.subtext)
            setPadding(0, dp(4), 0, dp(10))
        })
        if (!paused) {
            card.addView(LinearLayout(this).apply {
                addView(pillButton("Pause for $minutes $unit", primary = false) {
                    Auth.confirm(this@MainActivity, "Pause for payments") {
                        if (DetachService.pauseForPayments()) {
                            toast("Detach paused until ${Pause.untilText(this@MainActivity)}")
                        } else {
                            toast("Detach isn't running. Turn it off and on again in Accessibility settings")
                        }
                        render()
                    }
                })
            })
        }
        if (!Pause.canSelfEnable(this)) {
            card.addView(TextView(this).apply {
                text = "Detach can't turn itself back on yet: after a pause you'll have to do it in Accessibility settings. To allow it, run this once from a computer (USB debugging):\n${Pause.GRANT_COMMAND}"
                textSize = 12f
                setTextColor(palette.subtext)
                setTextIsSelectable(true)
                setPadding(0, dp(10), 0, 0)
            })
        }
        if (Pause.canSelfEnable(this) && !PauseWatchService.hasUsageAccess(this)) {
            card.addView(TextView(this).apply {
                text = "Optional: let Detach end a pause as soon as Instagram or YouTube is opened. This needs Usage access (which apps are opened and when; used only during a pause). Tap here, then allow Detach."
                textSize = 12f
                setTextColor(palette.accent)
                setPadding(0, dp(10), 0, 0)
                setOnClickListener { open(Intent(SysSettings.ACTION_USAGE_ACCESS_SETTINGS)) }
            })
        }
        card.addView(divider())
        // Pause length: − / + one minute at a time, guarded like the switches.
        card.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(10), 0, dp(10))
            addView(TextView(this@MainActivity).apply {
                text = "Pause length"
                textSize = 15f
                setTextColor(palette.text)
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            })
            fun step(label: String, delta: Int) = pillButton(label, primary = false) {
                val next = (settings.pauseMinutes + delta).coerceIn(1, 60)
                if (next != settings.pauseMinutes) requirePassword {
                    settings.pauseMinutes = next
                    render()
                }
            }.apply { minWidth = dp(44); minimumWidth = dp(44); setPadding(dp(12), 0, dp(12), 0) }
            addView(step("−", -1))
            addView(TextView(this@MainActivity).apply {
                text = "$minutes min"
                textSize = 15f
                setTypeface(Typeface.DEFAULT, Typeface.BOLD)
                setTextColor(palette.text)
                gravity = Gravity.CENTER
                minWidth = dp(72)
            })
            addView(step("+", 1))
        })
        card.addView(optionRow("Show pause notification", "\"Detach is on. Tap to pause for payments\"", settings.pauseNotification) {
            settings.pauseNotification = it
            Pause.refreshNotification(this)
        })
        return card
    }

    private fun instagramCard(): View {
        val card = card()
        val options = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        card.addView(cardHeader(
            badge = instagramBadge(),
            title = "Instagram",
            subtitle = { on -> if (on) "Use Customize to choose what stays available" else "Not blocking anything" },
            value = settings.igEnabled,
        ) { on ->
            settings.igEnabled = on
            setEnabledLook(options, on)
        })

        options.addView(divider())
        options.addView(optionRow("Hide home feed", "Stories row stays", settings.igFeed) { settings.igFeed = it })
        options.addView(optionRow("Block Reels tab", "Reels sent in DMs still play, one at a time", settings.igReelsTab) { settings.igReelsTab = it })
        options.addView(optionRow("Hide Explore & reels in search", "You can still search for accounts", settings.igExplore) { settings.igExplore = it })
        options.addView(optionRow("Hide posts on others' profiles", "Their name, bio and buttons stay visible", settings.igOtherProfiles) { settings.igOtherProfiles = it })
        options.addView(divider())
        options.addView(optionRow("Allow Stories", "Turn this off to leave a Story immediately", settings.igStories) { settings.igStories = it })
        options.addView(optionRow("Allow DMs", "Turn this off to leave the inbox and chat threads", settings.igMessages) { settings.igMessages = it })
        options.addView(optionRow("Allow reels sent in DMs", "One shared Reel can play; swiping to another is still blocked", settings.igSharedReels) { settings.igSharedReels = it })
        setEnabledLook(options, settings.igEnabled)

        // Collapsed by default to keep the screen simple
        options.visibility = View.GONE
        val toggle = TextView(this).apply {
            text = "Customize  ▾"
            textSize = 14f
            setTypeface(Typeface.DEFAULT, Typeface.BOLD)
            setTextColor(palette.accent)
            setPadding(0, dp(12), 0, dp(2))
            setOnClickListener {
                val open = options.visibility != View.VISIBLE
                options.visibility = if (open) View.VISIBLE else View.GONE
                text = if (open) "Customize  ▴" else "Customize  ▾"
            }
        }
        card.addView(toggle)
        card.addView(options)
        return card
    }

    private fun youtubeCard(): View {
        val card = card()
        card.addView(cardHeader(
            badge = glyphBadge(R.drawable.ic_youtube_glyph, rounded(Color.rgb(255, 0, 51), dp(14).toFloat())),
            title = "YouTube",
            subtitle = { on -> if (on) "Shorts blocked; normal videos work" else "Not blocking anything" },
            value = settings.ytShorts,
        ) { on -> settings.ytShorts = on })
        return card
    }

    private fun lockCard(): View {
        val card = card()
        card.addView(TextView(this).apply {
            text = "🔒  Password lock"
            textSize = 17f
            setTypeface(Typeface.DEFAULT, Typeface.BOLD)
            setTextColor(palette.text)
        })
        card.addView(TextView(this).apply {
            text = when {
                !lock.isSet -> "Off. Anyone with your phone can change the switches above."
                unlocked -> "On. Unlocked for now; locks again when you leave this screen."
                Auth.biometricAvailable(this@MainActivity) -> "On. Your fingerprint or password is needed to change any switch."
                else -> "On. Your password is needed to change any switch."
            }
            textSize = 14f
            setTextColor(palette.subtext)
            setPadding(0, dp(4), 0, dp(10))
        })
        val buttons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        if (!lock.isSet) {
            buttons.addView(pillButton("Set password", primary = true) { setPasswordDialog() })
        } else {
            buttons.addView(pillButton("Change", primary = false) { setPasswordDialog() })
            buttons.addView(space(dp(10)))
            buttons.addView(pillButton("Remove", primary = false) { removePassword() })
        }
        card.addView(buttons)
        card.addView(TextView(this).apply {
            text = "The same password unlocks the \"Detach is protected\" screen that appears when someone tries to turn Detach off, force-stop, clear or uninstall it. Without a password, that screen asks for your fingerprint, or for a 30-second wait if the phone has none set up. Changing or removing the password always needs the password itself."
            textSize = 12f
            setTextColor(palette.subtext)
            setPadding(0, dp(12), 0, 0)
        })
        return card
    }

    private fun privacyNote(): View = TextView(this).apply {
        text = "Privacy\n" +
            "• No internet permission: Detach can't send anything off your phone.\n" +
            "• It only looks at Instagram, YouTube and its own Settings pages. It never reads UPI, banking or any other app.\n" +
            "• It stores only your switches and a hashed password."
        textSize = 13f
        setTextColor(palette.subtext)
        setLineSpacing(0f, 1.15f)
        setPadding(dp(6), dp(20), dp(6), 0)
    }

    private fun diagnostics(): View {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val report = TextView(this).apply {
            text = Health(this@MainActivity).report()
            typeface = Typeface.MONOSPACE
            textSize = 11f
            setTextColor(palette.subtext)
            visibility = View.GONE
            setPadding(dp(6), dp(6), 0, 0)
        }
        box.addView(TextView(this).apply {
            text = "Diagnostics  ▾"
            textSize = 13f
            setTextColor(palette.accent)
            setPadding(dp(6), dp(16), 0, 0)
            setOnClickListener {
                val open = report.visibility != View.VISIBLE
                report.visibility = if (open) View.VISIBLE else View.GONE
                text = if (open) "Diagnostics  ▴" else "Diagnostics  ▾"
            }
        })
        box.addView(report)
        return box
    }

    // ------------------------------------------------------------------ building blocks

    private fun card() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = rounded(palette.card, dp(22).toFloat())
        setPadding(dp(18), dp(16), dp(18), dp(16))
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            .apply { topMargin = dp(14) }
    }

    private fun cardHeader(
        badge: View,
        title: String,
        subtitle: (Boolean) -> String,
        value: Boolean,
        onChange: (Boolean) -> Unit,
    ): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        row.addView(badge, LinearLayout.LayoutParams(dp(44), dp(44)))
        val texts = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), 0, dp(8), 0)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        texts.addView(TextView(this).apply {
            text = title
            textSize = 18f
            setTypeface(Typeface.DEFAULT, Typeface.BOLD)
            setTextColor(palette.text)
        })
        val sub = TextView(this).apply {
            text = subtitle(value)
            textSize = 13f
            setTextColor(palette.subtext)
        }
        texts.addView(sub)
        row.addView(texts)
        row.addView(guardedSwitch(value) { on ->
            onChange(on)
            sub.text = subtitle(on)
        })
        return row
    }

    private fun optionRow(title: String, hint: String?, value: Boolean, onChange: (Boolean) -> Unit): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(10), 0, dp(10))
        }
        val texts = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        texts.addView(TextView(this).apply {
            text = title
            textSize = 15f
            setTextColor(palette.text)
        })
        if (hint != null) texts.addView(TextView(this).apply {
            text = hint
            textSize = 12f
            setTextColor(palette.subtext)
        })
        row.addView(texts)
        row.addView(guardedSwitch(value, onChange))
        return row
    }

    /** A switch that asks for fingerprint or password (if a password is set) before it changes anything. */
    private fun guardedSwitch(value: Boolean, onChange: (Boolean) -> Unit): Switch = Switch(this).apply {
        isChecked = value
        setOnCheckedChangeListener { sw, wanted ->
            if (silent) return@setOnCheckedChangeListener
            if (!lock.isSet || unlocked) {
                onChange(wanted)
                return@setOnCheckedChangeListener
            }
            // Put it back until the password is confirmed
            flip(sw as Switch, !wanted)
            requirePassword {
                flip(sw, wanted)
                onChange(wanted)
            }
        }
    }

    private fun flip(sw: Switch, v: Boolean) {
        silent = true
        sw.isChecked = v
        silent = false
    }

    private fun setEnabledLook(group: ViewGroup, enabled: Boolean) {
        group.alpha = if (enabled) 1f else 0.45f
        fun walk(v: View) {
            v.isEnabled = enabled
            if (v is ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i))
        }
        walk(group)
    }

    private fun pillButton(label: String, primary: Boolean, onClick: () -> Unit) = Button(this, null, android.R.attr.borderlessButtonStyle).apply {
        text = label
        isAllCaps = false
        textSize = 14f
        setTypeface(Typeface.DEFAULT, Typeface.BOLD)
        setTextColor(if (primary) palette.onAccent else palette.accent)
        background = if (primary) rounded(palette.accent, 100f)
        else GradientDrawable().apply { cornerRadius = 100f; setStroke(dp(1), palette.accent) }
        setPadding(dp(20), 0, dp(20), 0)
        minHeight = dp(40); minimumHeight = dp(40)
        setOnClickListener { onClick() }
    }

    /** Instagram's camera glyph on its yellow → orange → pink → purple gradient. */
    private fun instagramBadge(): View {
        val gradient = GradientDrawable(
            GradientDrawable.Orientation.BL_TR,
            intArrayOf(Color.rgb(254, 218, 117), Color.rgb(250, 126, 30), Color.rgb(214, 41, 118), Color.rgb(150, 47, 191), Color.rgb(79, 91, 213)),
        ).apply { cornerRadius = dp(14).toFloat() }
        return glyphBadge(R.drawable.ic_instagram_glyph, gradient)
    }

    private fun glyphBadge(glyph: Int, bg: android.graphics.drawable.Drawable): View =
        android.widget.FrameLayout(this).apply {
            background = bg
            addView(android.widget.ImageView(this@MainActivity).apply { setImageResource(glyph) },
                android.widget.FrameLayout.LayoutParams(dp(26), dp(26), Gravity.CENTER))
        }

    private fun divider() = View(this).apply {
        setBackgroundColor(palette.divider)
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)).apply { topMargin = dp(8) }
    }

    private fun space(w: Int) = View(this).apply { layoutParams = LinearLayout.LayoutParams(w, 1) }

    // ------------------------------------------------------------------ password dialogs

    /**
     * Runs [action] once the user is confirmed. Only locks anything if a password is set.
     * Switches: fingerprint or password; after one success they stay unlocked until you leave
     * the screen.
     * [alwaysAsk] (removing the password) ignores that and always asks for the typed password.
     */
    private fun requirePassword(alwaysAsk: Boolean = false, action: () -> Unit) {
        if (!lock.isSet || (unlocked && !alwaysAsk)) { action(); return }
        val confirmed = {
            if (!alwaysAsk) unlocked = true
            action()
            render()
        }
        if (alwaysAsk) Auth.passwordDialog(this, positive = "Continue", onSuccess = confirmed)
        else Auth.confirm(this, "Unlock Detach's switches", onSuccess = confirmed)
    }

    private fun removePassword() = requirePassword(alwaysAsk = true) {
        lock.clear()
        unlocked = false
        toast("Password removed")
    }

    /** Set a new password, or change it (then the current password is asked in the same dialog). */
    private fun setPasswordDialog() {
        val changing = lock.isSet
        val current = if (changing) passwordEditText(this, "Current password") else null
        val first = passwordEditText(this, "New password (at least 4 characters)")
        val second = passwordEditText(this, "Repeat new password")
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            current?.let { addView(it) }
            addView(first)
            addView(second)
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle(if (changing) "Change password" else "Set a password")
            .setMessage("You'll need it to change any switch and to get past the \"Detach is protected\" screen. If you forget it, reinstalling Detach resets everything.")
            .setView(padded(box))
            .setPositiveButton("Save", null)
            .setNegativeButton("Cancel", null)
            .create()
        dialog.setOnShowListener {
            val ok = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
            ok.setOnClickListener {
                val a = first.text.toString()
                val b = second.text.toString()
                when {
                    a.length < 4 -> { first.error = "At least 4 characters"; return@setOnClickListener }
                    a != b -> { second.error = "Doesn't match"; return@setOnClickListener }
                }
                if (current != null && lock.lockoutSeconds() > 0) {
                    current.error = "Too many tries. Wait ${lock.lockoutSeconds()} s"
                    return@setOnClickListener
                }
                val old = current?.text?.toString()
                ok.isEnabled = false
                HashWorker.run(first, {
                    if (old != null && !lock.verify(old)) false else { lock.set(a); true }
                }) { saved ->
                    ok.isEnabled = true
                    if (saved) {
                        unlocked = false // protection applies straight away
                        dialog.dismiss()
                        toast(if (changing) "Password changed" else "Password saved")
                        render()
                    } else {
                        current?.text?.clear()
                        val w = lock.lockoutSeconds()
                        current?.error = if (w > 0) "Too many tries. Wait $w s" else "Wrong current password"
                    }
                }
            }
        }
        dialog.show()
        (current ?: first).requestFocus()
    }

    private fun padded(v: View) = LinearLayout(this).apply {
        setPadding(dp(22), dp(8), dp(22), 0)
        addView(v, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }

    // ------------------------------------------------------------------ helpers

    private fun serviceEnabled(): Boolean {
        val list = SysSettings.Secure.getString(contentResolver, SysSettings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
        return list.split(':').any { it.startsWith("$packageName/") }
    }

    private fun toast(t: String) = Toast.makeText(this, t, Toast.LENGTH_SHORT).show()
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun rounded(color: Int, radius: Float) = GradientDrawable().apply { setColor(color); cornerRadius = radius }

    /** Colours follow the phone's light/dark mode and, on Android 12+, its wallpaper colours. */
    private inner class Palette {
        private val night = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        private fun sys(id: Int, fallback: Int) = if (Build.VERSION.SDK_INT >= 31) getColor(id) else fallback

        val bg = if (night) sys(android.R.color.system_neutral1_900, Color.rgb(18, 18, 20)) else sys(android.R.color.system_neutral1_10, Color.rgb(250, 250, 252))
        val card = if (night) sys(android.R.color.system_neutral2_800, Color.rgb(36, 36, 42)) else sys(android.R.color.system_neutral2_50, Color.rgb(238, 238, 244))
        val text = if (night) sys(android.R.color.system_neutral1_50, Color.WHITE) else sys(android.R.color.system_neutral1_900, Color.rgb(20, 20, 24))
        val subtext = if (night) sys(android.R.color.system_neutral2_300, Color.rgb(170, 170, 180)) else sys(android.R.color.system_neutral2_600, Color.rgb(95, 95, 105))
        val accent = if (night) sys(android.R.color.system_accent1_200, Color.rgb(160, 170, 255)) else sys(android.R.color.system_accent1_600, Color.rgb(59, 59, 152))
        val onAccent = if (night) sys(android.R.color.system_accent1_800, Color.rgb(20, 20, 60)) else Color.WHITE
        val divider = if (night) Color.argb(40, 255, 255, 255) else Color.argb(28, 0, 0, 0)
        val okBg = if (night) Color.rgb(22, 60, 38) else Color.rgb(220, 244, 228)
        val okText = if (night) Color.rgb(140, 225, 170) else Color.rgb(20, 110, 55)
        val warnBg = if (night) Color.rgb(75, 50, 10) else Color.rgb(255, 238, 205)
        val warnText = if (night) Color.rgb(255, 205, 120) else Color.rgb(140, 80, 0)
    }
}

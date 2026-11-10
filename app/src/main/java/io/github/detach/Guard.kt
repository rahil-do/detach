package io.github.detach

import android.accessibilityservice.AccessibilityService
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Makes Detach hard to switch off on impulse.
 *
 * When one of Detach's own system pages is open (its Accessibility page with the "Use Detach"
 * switch, its App info page with Force stop / Uninstall, its storage page, or the uninstall
 * prompt), a full-screen "protected" panel covers it. Getting past it needs a fingerprint or the
 * Detach password, or a 30-second wait if neither exists. After that, there are 2 minutes to
 * make the change.
 *
 * It only reacts to pages that mention Detach; every other Settings page is left alone.
 */
object Guard {
    private const val GRANT_MS = 2 * 60_000L

    @Volatile private var grantedUntil = 0L

    fun isGranted() = SystemClock.elapsedRealtime() < grantedUntil

    fun grant() {
        grantedUntil = SystemClock.elapsedRealtime() + GRANT_MS
    }

    private const val NAME = "Detach"

    /** Words that appear on pages where Detach could be turned off or removed. */
    private val dangerWords = listOf(
        "use detach", "detach shortcut", "uninstall", "force stop", "archive", "disable",
        "clear storage", "clear data", "clear cache", "deactivate",
    )

    fun isProtectedScreen(s: Snap): Boolean {
        var mentionsDetach = false
        var danger = false
        for (n in s.all) {
            if (!n.onScreen) continue
            val l = n.label().trim()
            if (l.isEmpty()) continue
            val low = l.lowercase()
            if (l == NAME || low.startsWith("use detach") || low.contains("uninstall detach") ||
                (low.contains("detach") && s.pkg?.contains("packageinstaller") == true)
            ) mentionsDetach = true
            if (dangerWords.any { low.startsWith(it) || low == it }) danger = true
            if (mentionsDetach && danger) return true
        }
        // The uninstall prompt only needs to mention the app
        if (mentionsDetach && s.pkg?.contains("packageinstaller") == true) return true
        return structuralMatch(s)
    }

    /**
     * Language-independent fallback: a Settings page titled "Detach" (the app name isn't
     * translated) that has an on/off switch (the Accessibility page) or a row of action buttons
     * just under the title (App info: Uninstall / Force stop).
     */
    private fun structuralMatch(s: Snap): Boolean {
        if (s.pkg != "com.android.settings") return false
        val title = s.visible { it.label().trim() == NAME && it.r.bottom < s.h * 0.3 } ?: return false
        val hasSwitch = s.anyVisible { it.cls?.endsWith("Switch") == true && it.r.top < s.h * 0.35 }
        val buttonsUnderTitle = s.all.count {
            it.onScreen && it.label().isNotBlank() && it.r.top > title.r.bottom &&
                it.r.bottom < title.r.bottom + s.h * 0.2 && it.r.width() < s.w * 0.45
        } >= 2
        return hasSwitch || buttonsUnderTitle
    }
}

/** The full-screen panel itself. Main thread only. */
class GuardOverlay(
    private val service: AccessibilityService,
    private val onUnlock: () -> Unit,
    private val onGoBack: () -> Unit,
) {
    private val wm = service.getSystemService(WindowManager::class.java)
    private var view: View? = null

    val isShowing get() = view != null

    /** Covers [win], the Settings/installer window (the whole screen unless split-screen). */
    fun show(passwordSet: Boolean, biometric: Boolean, win: android.graphics.Rect) {
        view?.let { v ->
            (v.layoutParams as? WindowManager.LayoutParams)?.let { lp ->
                if (lp.x != win.left || lp.y != win.top || lp.width != win.width() || lp.height != win.height()) {
                    lp.x = win.left; lp.y = win.top; lp.width = win.width(); lp.height = win.height()
                    runCatching { wm.updateViewLayout(v, lp) }
                }
            }
            return
        }
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.OPAQUE,
        ).apply {
            x = win.left; y = win.top
            width = win.width()
            height = win.height()
            gravity = Gravity.TOP or Gravity.START
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            title = "Detach protection"
        }
        val v = build(passwordSet, biometric)
        runCatching { wm.addView(v, lp) }.onSuccess { view = v }
    }

    fun hide() {
        view?.let { runCatching { wm.removeView(it) } }
        view = null
    }

    private fun build(passwordSet: Boolean, biometric: Boolean): View {
        val dark = (service.resources.configuration.uiMode and
            android.content.res.Configuration.UI_MODE_NIGHT_MASK) == android.content.res.Configuration.UI_MODE_NIGHT_YES
        val bg = if (dark) Color.rgb(18, 18, 20) else Color.rgb(250, 250, 252)
        val fg = if (dark) Color.WHITE else Color.rgb(20, 20, 24)
        val sub = if (dark) Color.rgb(170, 170, 180) else Color.rgb(95, 95, 105)
        val accent = Color.rgb(59, 59, 152)
        val d = service.resources.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()

        return LinearLayout(service).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(bg)
            isClickable = true
            setPadding(dp(32), 0, dp(32), 0)
            addView(TextView(service).apply {
                text = "🔒"
                textSize = 48f
                gravity = Gravity.CENTER
            })
            addView(TextView(service).apply {
                text = "Detach is protected"
                textSize = 24f
                setTypeface(Typeface.DEFAULT, Typeface.BOLD)
                setTextColor(fg)
                gravity = Gravity.CENTER
                setPadding(0, dp(12), 0, dp(8))
            })
            addView(TextView(service).apply {
                val what = "Turning Detach off, force-stopping, clearing or uninstalling it needs"
                text = when {
                    passwordSet && biometric -> "$what your fingerprint or your Detach password."
                    passwordSet -> "$what your Detach password."
                    biometric -> "$what your fingerprint.\nSet a password in Detach for stronger protection."
                    else -> "$what a 30-second wait.\nSet a password in Detach for stronger protection."
                }
                textSize = 15f
                setTextColor(sub)
                gravity = Gravity.CENTER
                setPadding(0, 0, 0, dp(28))
            })
            addView(Button(service, null, android.R.attr.borderlessButtonStyle).apply {
                text = "Go back"
                isAllCaps = false
                textSize = 16f
                setTypeface(Typeface.DEFAULT, Typeface.BOLD)
                setTextColor(Color.WHITE)
                background = GradientDrawable().apply { setColor(accent); cornerRadius = 100f }
                setPadding(dp(32), 0, dp(32), 0)
                setOnClickListener { onGoBack() }
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(48)))
            addView(Button(service, null, android.R.attr.borderlessButtonStyle).apply {
                text = when {
                    biometric -> "Unlock"
                    passwordSet -> "Enter password"
                    else -> "Wait 30 seconds"
                }
                isAllCaps = false
                textSize = 14f
                setTextColor(sub)
                setOnClickListener { onUnlock() }
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(10)
            })
        }
    }
}

package io.github.detach

import android.accessibilityservice.AccessibilityService
import android.graphics.PixelFormat
import android.os.Build
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.ScrollView

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
        if (win.width() <= 0 || win.height() < 40) { hide(); return }
        view?.let { v ->
            (v.layoutParams as? WindowManager.LayoutParams)?.let { lp ->
                if (lp.x != win.left || lp.y != win.top || lp.width != win.width() || lp.height != win.height()) {
                    lp.x = win.left; lp.y = win.top; lp.width = win.width(); lp.height = win.height()
                    if (runCatching { wm.updateViewLayout(v, lp) }.isFailure) hide()
                }
            }
            return
        }
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.OPAQUE,
        ).apply {
            x = win.left; y = win.top
            width = win.width()
            height = win.height()
            gravity = Gravity.TOP or Gravity.START
            layoutInDisplayCutoutMode = if (Build.VERSION.SDK_INT >= 30) {
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            } else WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            title = "Detach protection"
            windowAnimations = 0
            alpha = 1f
            if (Build.VERSION.SDK_INT >= 30) setFitInsetsTypes(0)
        }
        val v = build(passwordSet, biometric)
        runCatching { wm.addView(v, lp) }.onSuccess { view = v }
    }

    fun hide() {
        view?.let { runCatching { wm.removeViewImmediate(it) } }
        view = null
    }

    private fun build(passwordSet: Boolean, biometric: Boolean): View {
        val ui = Ui(service)
        val content = LinearLayout(service).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(ui.dp(28), ui.dp(32), ui.dp(28), ui.dp(32))
            addView(ui.icon(R.drawable.ic_lock, 32, ui.accent))
            addView(ui.text("Detach is protected", 24f, strong = true).apply {
                gravity = Gravity.CENTER
                isAccessibilityHeading = true
                setPadding(0, ui.dp(20), 0, ui.dp(12))
            })
            val confirmation = when {
                passwordSet && biometric -> "your fingerprint or Detach password"
                passwordSet -> "your Detach password"
                biometric -> "your fingerprint"
                else -> "a 30-second wait"
            }
            addView(ui.text("Turning off or removing Detach needs $confirmation.", 15f, ui.sub).apply {
                gravity = Gravity.CENTER
                setPadding(0, 0, 0, ui.dp(24))
            })
            addView(ui.primary("Go back") { if (view != null) onGoBack() })
            addView(ui.space(8))
            val unlockLabel = when {
                biometric -> "Unlock"
                passwordSet -> "Enter password"
                else -> "Wait 30 seconds"
            }
            addView(ui.quiet(unlockLabel) { if (view != null) onUnlock() })
        }
        return ScrollView(service).apply {
            isFillViewport = true
            isClickable = true
            setBackgroundColor(ui.bg)
            addView(content)
        }
    }
}

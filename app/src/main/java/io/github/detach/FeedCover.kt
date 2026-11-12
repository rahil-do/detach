package io.github.detach

import android.accessibilityservice.AccessibilityService
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Build
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/**
 * An opaque panel drawn over Instagram's home feed (below the Stories row).
 * It swallows touches, so the feed can't be seen or scrolled.
 * Must only be used from the main thread.
 */
class FeedCover(
    private val service: AccessibilityService,
    private val onBackToStories: () -> Unit,
    private val onBackToSearch: () -> Unit,
) {

    private val wm = service.getSystemService(WindowManager::class.java)
    private var view: View? = null
    private var shown: Rect? = null
    private var shownLabel: String? = null
    private var labelView: TextView? = null
    private var backButton: Button? = null
    private var buttonAction: (() -> Unit)? = null
    private var shownButtonLabel: String? = null

    val isShowing get() = view != null

    fun show(
        r: Rect,
        label: String = "Hidden by Detach",
        backToTop: Boolean = false,
        backToSearch: Boolean = false,
    ) {
        if (r.height() < 40 || r.width() <= 0) { hide(); return }
        val button = when {
            backToSearch -> "Back to search" to onBackToSearch
            backToTop -> "Back to stories" to onBackToStories
            else -> null
        }
        buttonAction = button?.second
        backButton?.text = button?.first.orEmpty()
        backButton?.visibility = if (button == null) View.GONE else View.VISIBLE
        if (r == shown && label == shownLabel && button?.first == shownButtonLabel) return
        val lp = (view?.layoutParams as? WindowManager.LayoutParams) ?: WindowManager.LayoutParams(
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.OPAQUE,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            layoutInDisplayCutoutMode = if (Build.VERSION.SDK_INT >= 30) {
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            } else WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            title = "Detach feed cover"
            windowAnimations = 0
            alpha = 1f
            if (Build.VERSION.SDK_INT >= 30) setFitInsetsTypes(0)
        }
        lp.x = r.left; lp.y = r.top; lp.width = r.width(); lp.height = r.height()
        val v = view
        if (v == null) {
            val nv = build()
            labelView?.text = label
            backButton?.text = button?.first.orEmpty()
            backButton?.visibility = if (button == null) View.GONE else View.VISIBLE
            if (runCatching { wm.addView(nv, lp) }.isFailure) return
            view = nv
        } else {
            // Same window, new text/size: no gap where content could flash through
            labelView?.text = label
            if (runCatching { wm.updateViewLayout(v, lp) }.isFailure) { hide(); return }
        }
        shown = Rect(r)
        shownLabel = label
        shownButtonLabel = button?.first
    }

    fun hide() {
        view?.let { runCatching { wm.removeViewImmediate(it) } }
        view = null
        shown = null
        shownLabel = null
        shownButtonLabel = null
        buttonAction = null
        labelView = null
        backButton = null
    }

    private fun build(): View {
        val ui = Ui(service)
        return LinearLayout(service).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(ui.bg)
            isClickable = true // swallow every touch on the feed area
            isFocusable = false
            addView(ui.text("", 15f, ui.sub).apply {
                gravity = Gravity.CENTER
                setPadding(ui.dp(24), 0, ui.dp(24), ui.dp(12))
                labelView = this
            })
            addView(ui.secondary("") { buttonAction?.invoke() }.apply {
                backButton = this
            })
        }
    }

}

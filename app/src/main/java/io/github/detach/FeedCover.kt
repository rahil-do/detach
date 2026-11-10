package io.github.detach

import android.accessibilityservice.AccessibilityService
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
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
class FeedCover(private val service: AccessibilityService, private val onBackToStories: () -> Unit) {

    private val wm = service.getSystemService(WindowManager::class.java)
    private var view: View? = null
    private var shown: Rect? = null
    private var shownLabel: String? = null
    private var labelView: TextView? = null
    private var backButton: Button? = null

    val isShowing get() = view != null

    fun show(r: Rect, label: String = "Hidden by Detach", backToTop: Boolean = false) {
        if (r.height() < 40) { hide(); return }
        if (r == shown && label == shownLabel) return
        val lp = (view?.layoutParams as? WindowManager.LayoutParams) ?: WindowManager.LayoutParams(
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.OPAQUE,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            title = "Detach feed cover"
        }
        lp.x = r.left; lp.y = r.top; lp.width = r.width(); lp.height = r.height()
        val v = view
        if (v == null) {
            val nv = build()
            labelView?.text = label
            backButton?.visibility = if (backToTop) View.VISIBLE else View.GONE
            runCatching { wm.addView(nv, lp) }.onSuccess { view = nv }
        } else {
            // Same window, new text/size: no gap where content could flash through
            labelView?.text = label
            backButton?.visibility = if (backToTop) View.VISIBLE else View.GONE
            runCatching { wm.updateViewLayout(v, lp) }
        }
        shown = Rect(r)
        shownLabel = label
    }

    fun hide() {
        view?.let { runCatching { wm.removeView(it) } }
        view = null
        shown = null
        shownLabel = null
    }

    private fun build(): View {
        val dark = (service.resources.configuration.uiMode and
            android.content.res.Configuration.UI_MODE_NIGHT_MASK) == android.content.res.Configuration.UI_MODE_NIGHT_YES
        val bg = if (dark) Color.BLACK else Color.WHITE
        val fg = if (dark) Color.rgb(170, 170, 170) else Color.rgb(110, 110, 110)
        return LinearLayout(service).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(bg)
            isClickable = true // swallow every touch on the feed area
            isFocusable = false
            addView(TextView(service).apply {
                setTextColor(fg)
                textSize = 15f
                gravity = Gravity.CENTER
                setPadding(48, 0, 48, 0)
                labelView = this
            })
            addView(Button(service, null, android.R.attr.borderlessButtonStyle).apply {
                text = "↑ Back to stories"
                setTextColor(fg)
                isAllCaps = false
                setOnClickListener { onBackToStories() }
                backButton = this
            })
        }
    }
}

package io.github.detach

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.app.Activity
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.text.format.DateFormat
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.animation.LinearInterpolator
import android.view.animation.PathInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.Toast
import java.util.Date

/**
 * Short, skippable confirmation played once a breather actually starts: a ring "inhales" and
 * fills, two soft pulses breathe outward, then the overlay settles away. Respects the system
 * "remove animations" setting by falling back to a plain message.
 */
object BreatherIntro {
    const val TAG = "breather-intro"
    private const val TOTAL_MS = 2300L
    private val ease = PathInterpolator(0.2f, 0f, 0f, 1f)
    private val inhale = PathInterpolator(0.4f, 0f, 0.2f, 1f)

    fun play(activity: Activity, minutes: Int) {
        if (activity.isFinishing || activity.isDestroyed) return
        val ends = DateFormat.getTimeFormat(activity).format(Date(System.currentTimeMillis() + minutes * 60_000L))
        val detail = "${minutes} min · ends at $ends"
        if (!Motion.enabled(activity)) {
            Toast.makeText(activity, "Breather on · $detail", Toast.LENGTH_SHORT).show()
            return
        }
        val decor = activity.window?.decorView as? ViewGroup ?: return
        decor.findViewWithTag<View>(TAG)?.let { decor.removeView(it) }
        val ui = Ui(activity)

        val ring = Ring(activity, ui)
        val title = ui.text("Breather on", 26f, ui.fg, strong = true).apply {
            gravity = Gravity.CENTER
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_ASSERTIVE
            contentDescription = "Breather on for $minutes minutes, ends at $ends"
        }
        val subtitle = ui.text(detail, 16f, ui.sub).apply {
            gravity = Gravity.CENTER
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        val content = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            addView(ring, LinearLayout.LayoutParams(ui.dp(220), ui.dp(220)))
            addView(title, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = ui.dp(20)
            })
            addView(subtitle, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = ui.dp(6)
            })
        }
        val scrim = Color.argb(245, Color.red(ui.bg), Color.green(ui.bg), Color.blue(ui.bg))
        val overlay = FrameLayout(activity).apply {
            tag = TAG
            isClickable = true
            isFocusable = true
            setBackgroundColor(scrim)
            alpha = 0f
            addView(content, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        }
        decor.addView(overlay, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        val animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = TOTAL_MS
            interpolator = LinearInterpolator()
        }
        fun phase(t: Float, startMs: Long, lengthMs: Long) =
            ((t * TOTAL_MS - startMs) / lengthMs).coerceIn(0f, 1f)

        animator.addUpdateListener {
            val t = it.animatedValue as Float
            val enter = ease.getInterpolation(phase(t, 0, 280))
            val exit = ease.getInterpolation(phase(t, 1900, 400))
            overlay.alpha = enter * (1f - exit)
            val lift = (1f - enter) * ui.dp(16) - exit * ui.dp(8)
            content.translationY = lift
            content.scaleX = 0.94f + 0.06f * enter
            content.scaleY = content.scaleX
            ring.fill = inhale.getInterpolation(phase(t, 120, 1000))
            ring.pulseA = phase(t, 700, 1100)
            ring.pulseB = phase(t, 1000, 1100)
            ring.invalidate()
            subtitle.alpha = ease.getInterpolation(phase(t, 450, 400))
            subtitle.translationY = (1f - subtitle.alpha) * ui.dp(6)
        }
        animator.addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator) {
                decor.removeView(overlay)
            }
        })
        overlay.setOnClickListener {
            // Skip: jump to the fade-out instead of cutting abruptly.
            if (animator.isRunning && animator.currentPlayTime < 1900) animator.currentPlayTime = 1900
        }
        overlay.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) = Unit
            override fun onViewDetachedFromWindow(v: View) {
                animator.removeAllListeners()
                animator.cancel()
            }
        })
        animator.start()
    }

    private class Ring(context: Context, ui: Ui) : View(context) {
        var fill = 0f
        var pulseA = 0f
        var pulseB = 0f
        private val accent = ui.accent
        private val stroke = ui.dp(8).toFloat()
        private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = stroke
            color = ui.line
        }
        private val arc = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = stroke
            strokeCap = Paint.Cap.ROUND
            color = accent
        }
        private val core = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        private val pulse = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = ui.dp(2).toFloat()
        }
        private val bounds = RectF()

        init {
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        }

        override fun onDraw(canvas: Canvas) {
            val cx = width / 2f
            val cy = height / 2f
            val radius = minOf(width, height) / 2f * 0.62f
            for (p in floatArrayOf(pulseA, pulseB)) {
                if (p <= 0f || p >= 1f) continue
                pulse.color = withAlpha(accent, (1f - p) * 0.55f)
                canvas.drawCircle(cx, cy, radius * (1f + 0.6f * p), pulse)
            }
            core.color = withAlpha(accent, 0.10f + 0.12f * fill)
            canvas.drawCircle(cx, cy, (radius - stroke) * (0.55f + 0.45f * fill), core)
            canvas.drawCircle(cx, cy, radius, track)
            bounds.set(cx - radius, cy - radius, cx + radius, cy + radius)
            if (fill > 0f) canvas.drawArc(bounds, -90f, 360f * fill, false, arc)
        }

        private fun withAlpha(color: Int, alpha: Float) =
            Color.argb((alpha.coerceIn(0f, 1f) * 255).toInt(), Color.red(color), Color.green(color), Color.blue(color))
    }
}

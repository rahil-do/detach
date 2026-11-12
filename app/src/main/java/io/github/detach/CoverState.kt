package io.github.detach

import android.graphics.Rect

data class CoverFrame(
    val pkg: String,
    val bounds: Rect,
    val label: String,
    val backToStories: Boolean = false,
    val backToSearch: Boolean = false,
)

class CoverState {
    private var current: CoverFrame? = null
    private var blockedAt = 0L

    @Synchronized
    fun update(pkg: String, window: Rect, decision: Decision, now: Long, loading: Boolean): CoverFrame? {
        if (window.width() <= 0 || window.height() < 40) {
            clear()
            return null
        }
        val requested = decision.cover ?: window.takeIf { decision.action != null }
        if (requested != null) {
            val clipped = Rect(requested)
            if (!clipped.intersect(window) || clipped.height() < 40 || clipped.width() <= 0) {
                clear()
                return null
            }
            blockedAt = now
            return CoverFrame(
                pkg, clipped,
                if (decision.action != null) "Hidden by Detach" else decision.coverLabel,
                decision.coverBackToTop && decision.action == null,
                decision.coverBackToSearch && decision.action == null,
            ).also { current = it }
        }
        val previous = current
        if (loading && previous?.pkg == pkg && now - blockedAt in 0 until 350) {
            if (window.height() >= 40 && window.width() > 0) {
                return previous.copy(bounds = Rect(window), backToStories = false, backToSearch = false).also { current = it }
            }
        }
        clear()
        return null
    }

    @Synchronized
    fun clear() {
        current = null
        blockedAt = 0L
    }
}

package io.github.detach

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo

/**
 * One pass over the accessibility tree of the active Instagram/YouTube window,
 * flattened so the rules can ask simple questions ("is X on screen?").
 */
class Snap(root: AccessibilityNodeInfo, val w: Int, val h: Int) {

    class N(
        val node: AccessibilityNodeInfo,
        val id: String?,
        val cls: String?,
        val desc: String?,
        val text: String?,
        val r: Rect,
        val selected: Boolean,
        val clickable: Boolean,
        /** Actually inside the visible screen (Instagram keeps other tabs laid out off-screen). */
        val onScreen: Boolean,
    ) {
        fun label(): String = desc?.takeIf { it.isNotBlank() } ?: text.orEmpty()
    }

    val pkg: String? = root.packageName?.toString()
    val all = ArrayList<N>(512)
    private val byId = HashMap<String, MutableList<N>>()

    init {
        walk(root, 0)
    }

    private fun walk(n: AccessibilityNodeInfo, depth: Int) {
        if (depth > 80 || all.size > 5000) return
        val r = Rect()
        n.getBoundsInScreen(r)
        val on = n.isVisibleToUser && r.width() > 0 && r.height() > 0 &&
            r.right > 0 && r.left < w && r.bottom > 0 && r.top < h
        val id = n.viewIdResourceName?.substringAfter(":id/")
        val x = N(
            n, id, n.className?.toString(), n.contentDescription?.toString(), n.text?.toString(),
            r, n.isSelected, n.isClickable, on,
        )
        all.add(x)
        if (id != null) byId.getOrPut(id) { ArrayList(2) }.add(x)
        // Instagram keeps whole tabs laid out off-screen; skipping them keeps each pass fast.
        val offScreen = !n.isVisibleToUser && (r.width() <= 0 || r.left >= w || r.right <= 0)
        if (offScreen && depth > 1) return
        for (i in 0 until n.childCount) {
            val c = n.getChild(i) ?: continue
            walk(c, depth + 1)
        }
    }

    fun visibleId(id: String): N? = byId[id]?.firstOrNull { it.onScreen }

    /** Of all on-screen nodes with this id, the one taking the most screen (e.g. the current reel). */
    fun largestVisibleId(id: String): N? =
        byId[id]?.filter { it.onScreen }?.maxByOrNull { visibleArea(it.r) }

    fun visible(pred: (N) -> Boolean): N? = all.firstOrNull { it.onScreen && pred(it) }

    fun anyVisible(pred: (N) -> Boolean): Boolean = visible(pred) != null

    fun visibleArea(r: Rect): Long {
        val vw = (minOf(r.right, w) - maxOf(r.left, 0)).coerceAtLeast(0)
        val vh = (minOf(r.bottom, h) - maxOf(r.top, 0)).coerceAtLeast(0)
        return vw.toLong() * vh
    }

    /** Bottom navigation lives in the lowest part of the screen. */
    fun inBottomBar(n: N) = n.r.top > h * 0.75
}

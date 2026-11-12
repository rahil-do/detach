package io.github.detach

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import org.robolectric.Shadows.shadowOf

/** A typical tall phone screen, in pixels. */
const val W = 1220
const val H = 2712

/**
 * Builds a fake accessibility tree, the same shape Detach gets from Android, so the rules can be
 * tested without a phone. Every node is a direct child of the root; the rules only look at ids,
 * labels, bounds and flags, never at nesting.
 */
class Screen(private val pkg: String) {
    private val root: AccessibilityNodeInfo = raw(null, null, null, Rect(0, 0, W, H))

    private fun raw(id: String?, desc: String?, text: String?, r: Rect): AccessibilityNodeInfo =
        AccessibilityNodeInfo.obtain().apply {
            packageName = pkg
            if (id != null) viewIdResourceName = "$pkg:id/$id"
            contentDescription = desc
            this.text = text
            setBoundsInScreen(r)
            isVisibleToUser = true
            className = "android.view.View"
        }

    fun node(
        id: String? = null,
        desc: String? = null,
        text: String? = null,
        l: Int = 0, t: Int = 0, r: Int = W, b: Int = H,
        selected: Boolean = false,
        clickable: Boolean = false,
        visible: Boolean = true,
        cls: String = "android.view.View",
    ): Screen {
        val n = raw(id, desc, text, Rect(l, t, r, b))
        n.isSelected = selected
        n.isClickable = clickable
        n.isVisibleToUser = visible
        n.className = cls
        shadowOf(root).addChild(n)
        return this
    }

    fun snap() = Snap(root, W, H)
}

// ---- Instagram building blocks, with the ids and positions seen on the real app ----

fun instagram() = Screen(Targets.INSTAGRAM)

fun Screen.igTabs(selected: String? = null): Screen {
    val tabs = listOf("feed_tab" to "Home", "clips_tab" to "Reels", "direct_tab" to "Message",
        "search_tab" to "Search and explore", "profile_tab" to "Profile")
    tabs.forEachIndexed { i, (id, label) ->
        node(id = id, desc = label, l = i * 244, t = 2509, r = (i + 1) * 244, b = 2644,
            clickable = true, selected = id == selected)
    }
    return this
}

fun Screen.igHomeHeader() = node(id = "title_logo", desc = "Instagram Home Feed", l = 446, t = 149, r = 773, b = 265)
    .node(desc = "reels tray container", l = 0, t = 286, r = W, b = 695)
    .node(desc = "someone's story, 1 of 26, Unseen.", l = 315, t = 319, r = 630, b = 623, clickable = true)

fun Screen.igFeedPost() = node(id = "row_feed_profile_header", desc = "someone posted a video", l = 0, t = 700, r = W, b = 850)

fun Screen.igReelViewer(by: String) = node(id = "clips_viewer_view_pager", l = 0, t = 128, r = W, b = 2509)
    .node(id = "clips_media_component", desc = "Reel by $by. Double tap to play or pause.", l = 0, t = 128, r = W, b = 2509)

fun Screen.igStoryViewer() = node(id = "reel_viewer_root_view", l = 0, t = 0, r = W, b = H)

fun Screen.igDmThread() = node(id = "direct_thread_header", l = 0, t = 128, r = W, b = 286)
    .node(desc = "Audio call", l = 900, t = 150, r = 1000, b = 260, clickable = true)

fun Screen.igProfileHeader() = node(id = "row_profile_header_imageview", l = 65, t = 379, r = 273, b = 587)
    .node(id = "profile_tab_layout", l = 0, t = 1496, r = W, b = 1631)

fun Screen.igGridTile(row: Int, col: Int, top: Int) =
    node(id = "grid_card_layout_container", desc = "Reel by Someone at row $row, column $col",
        l = (col - 1) * 406, t = top, r = (col - 1) * 406 + 402, b = top + 538, clickable = true)

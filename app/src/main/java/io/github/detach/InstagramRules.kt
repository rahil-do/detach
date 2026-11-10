package io.github.detach

import android.graphics.Rect

/**
 * Decides what to do on the current Instagram screen.
 *
 * Allowed: Stories row, DMs, reels/posts opened from a DM (only that one reel), own profile,
 * settings, account switching, creating posts/stories, notifications.
 * Blocked: home feed (covered), Reels tab, the Explore grid and reel/post grids in search
 * results (search box and accounts stay), other people's posts grid (their profile header
 * stays), swiping from a shared reel into suggested reels, reels opened outside chats.
 *
 * Every signal is detected in at least two independent ways (internal view id AND
 * accessibility label / shape), so one renamed id in an Instagram update doesn't break it.
 */
class InstagramRules(private val settings: Settings, private val health: Health) {

    enum class Tab { HOME, REELS, MESSAGES, SEARCH, PROFILE, UNKNOWN }

    private enum class Ctx { DM, OWN_PROFILE, OTHER }

    private var lastCtx = Ctx.OTHER

    // State of the reel viewer while it's open
    private var clipsOpen = false
    private var clipsAllowed = false
    private var clipsKey: String? = null
    private var clipsSince = 0L
    private var clipsScrolled = false

    /** Called when the reel viewer reports a scroll (i.e. the user swiped to the next reel). */
    fun onClipsScrolled(now: Long) {
        if (clipsOpen && now - clipsSince > 1200) clipsScrolled = true
    }

    fun evaluate(s: Snap, now: Long): Decision {
        if (!settings.igEnabled) {
            endClips()
            return Decision.NONE
        }
        val tab = selectedTab(s)
        health.igEval(tab != Tab.UNKNOWN)

        val clips = clipsViewer(s)
        if (clips != null) health.seen("ig_clips")

        // 1. Reels tab (also when reached by swiping sideways from Home)
        if (settings.igReelsTab && (tab == Tab.REELS || (clips != null && reelsTabHeader(s)))) {
            endClips()
            return Decision(action = Action.ToDms("Instagram Reels tab"))
        }

        // 3. A reel opened somewhere other than the Reels tab
        if (clips != null) {
            if (!settings.igReelsTab) return Decision.NONE
            if (!clipsOpen) {
                clipsOpen = true
                clipsAllowed = lastCtx != Ctx.OTHER
                clipsKey = null
                clipsSince = now
                clipsScrolled = false
            }
            if (!clipsAllowed) return Decision(action = Action.Back("reel not opened from a chat"))
            val key = reelKey(s)
            if (key != null) {
                if (clipsKey == null) clipsKey = key
                else if (key != clipsKey) return Decision(action = Action.Back("swiped past a shared reel"))
            }
            if (clipsScrolled) return Decision(action = Action.Back("swiped past a shared reel"))
            return Decision.NONE
        }
        endClips()

        // Remember where we are, so a reel opened next knows if it came from a chat.
        val own = isOwnProfile(s)
        lastCtx = when {
            tab == Tab.MESSAGES || isDmThread(s) -> Ctx.DM
            own -> Ctx.OWN_PROFILE
            else -> Ctx.OTHER
        }

        // 4. Someone else's profile: name, bio, buttons and highlights stay; posts/reels grid is covered
        if (settings.igOtherProfiles && !own && isProfile(s)) {
            return Decision(cover = profileGridRect(s), coverLabel = "Posts hidden by Detach")
        }

        // 5. Search: the search box and account results stay; Explore / result grids are covered
        if (settings.igExplore && inSearch(s, tab)) {
            gridRect(s, searchBoxBottom(s))?.let {
                return Decision(cover = it, coverLabel = "Explore and reels hidden by Detach\nTap \"Accounts\" above to see only people")
            }
            return Decision.NONE
        }

        // 6. Home: keep the Stories row, cover the feed under it
        if (settings.igFeed && ((tab == Tab.HOME && isHomeFeed(s)) || homeWithoutSelectedTab(s, tab))) {
            return Decision(cover = feedRect(s), coverIsHomeFeed = true, coverLabel = "Feed hidden by Detach", coverBackToTop = true)
        }
        return Decision.NONE
    }

    // ---- grids (Explore, search results, profiles) --------------------------------------

    private val tileIds = setOf(
        "grid_card_layout_container", "layout_container", "image_button", "image_preview",
        "media_grid_item", "grid_item_container", "preview_container",
    )
    /** Rows of post thumbnails that span the screen (e.g. previews under an account in search). */
    private val stripIds = setOf("media_previews_rv")
    private val tileDesc = Regex("""(at row \d+|column \d+|^(reel|photo|video|post|carousel)s? (by|of) )""", RegexOption.IGNORE_CASE)

    /** A thumbnail in a grid: narrow (a third of the screen or so) and tagged like a grid cell. */
    private fun isTile(s: Snap, n: Snap.N): Boolean =
        n.onScreen && n.r.height() > s.h / 20 && (
            n.id in stripIds ||
                (n.r.width() < s.w * 0.7 && (n.id in tileIds || tileDesc.containsMatchIn(n.desc.orEmpty())))
            )

    /** Search screens: the tab bar is hidden on the results page, so also look for search elements. */
    private fun inSearch(s: Snap, tab: Tab) = tab == Tab.SEARCH || s.anyVisible {
        val id = it.id.orEmpty()
        id.startsWith("row_search_") || id.contains("search_edit_text")
    }

    /** Covers from the first visible grid thumbnail down to the tab bar, or null if there's no grid. */
    private fun gridRect(s: Snap, notAbove: Int?): Rect? {
        val first = s.all.filter { isTile(s, it) }.minOfOrNull { it.r.top } ?: return null
        health.seen("ig_grid")
        val top = maxOf(first, notAbove ?: 0, 0)
        // Down to the tab bar, or the screen bottom when it's hidden (the results page's bottom
        // "Ask a follow-up" box gets covered too; the search box at the top still works).
        val bottom = tabBarTop(s) ?: s.h
        return if (bottom - top > 40) Rect(0, top, s.w, bottom) else null
    }

    /** Bottom of the search box and the filter chips (For you / Accounts / Reels …) under it. */
    private fun searchBoxBottom(s: Snap): Int? {
        val box = (s.visibleId("action_bar_search_edit_text")
            ?: s.visible { it.cls?.endsWith("EditText") == true && it.r.top < s.h * 0.25 }
            ?: s.visible { it.r.top < s.h * 0.25 && it.label().startsWith("Search", true) && it.r.width() > s.w / 2 })
            ?.r?.bottom
        val chips = s.all.filter { it.onScreen && it.id == "igds_prism_chip_label" && it.r.top < s.h * 0.3 }
            .maxOfOrNull { it.r.bottom + s.h / 100 }
        return listOfNotNull(box, chips).maxOrNull()
    }

    private fun profileGridRect(s: Snap): Rect {
        val top = s.visibleId("profile_tab_layout")?.r?.top
            ?: s.visible { it.desc.equals("Grid view", true) }?.r?.top
            ?: s.all.filter { isTile(s, it) }.minOfOrNull { it.r.top }
            ?: (s.h * 0.5).toInt() // can't find the grid: cover the lower half to be safe
        return Rect(0, maxOf(top, 0), s.w, tabBarTop(s) ?: s.h)
    }

    private fun endClips() {
        clipsOpen = false
        clipsAllowed = false
        clipsKey = null
        clipsScrolled = false
    }

    // ---- tabs ---------------------------------------------------------------------------

    private val tabIds = linkedMapOf(
        "feed_tab" to Tab.HOME, "clips_tab" to Tab.REELS, "direct_tab" to Tab.MESSAGES,
        "search_tab" to Tab.SEARCH, "profile_tab" to Tab.PROFILE,
    )

    fun tabNodes(s: Snap): Map<Tab, Snap.N> {
        val m = HashMap<Tab, Snap.N>()
        for ((id, t) in tabIds) s.visibleId(id)?.let { m[t] = it }
        if (m.size >= 3) return m
        // Fallback: clickable items in the bottom bar, recognised by their label
        for (n in s.all) {
            if (!n.onScreen || !n.clickable || !s.inBottomBar(n)) continue
            val t = labelToTab(n.label()) ?: continue
            m.putIfAbsent(t, n)
        }
        return m
    }

    fun selectedTab(s: Snap): Tab {
        for ((id, t) in tabIds) {
            val n = s.visibleId(id) ?: continue
            health.seen("ig_tab_id")
            if (n.selected) return t
        }
        for (n in s.all) {
            if (!n.onScreen || !n.selected || !s.inBottomBar(n)) continue
            val t = labelToTab(n.label()) ?: continue
            health.seen("ig_tab_label")
            return t
        }
        return Tab.UNKNOWN
    }

    private fun labelToTab(raw: String): Tab? {
        val l = raw.trim().lowercase()
        return when {
            l == "home" || l.startsWith("home,") -> Tab.HOME
            l == "reels" || l.startsWith("reels,") -> Tab.REELS
            l.startsWith("message") || l.startsWith("direct") || l.startsWith("chats") || l.startsWith("messenger") -> Tab.MESSAGES
            l.startsWith("search") || l.startsWith("explore") -> Tab.SEARCH
            l == "profile" || l.startsWith("profile,") -> Tab.PROFILE
            else -> null
        }
    }

    fun tabBarTop(s: Snap): Int? = tabNodes(s).values.minOfOrNull { it.r.top }

    // ---- reels viewer -------------------------------------------------------------------

    private fun clipsViewer(s: Snap): Snap.N? {
        s.largestVisibleId("clips_viewer_view_pager")?.let { if (it.r.width() > s.w / 2) return it }
        // Fallback: a full-screen element described as "Reel by …". Never on Home, where a
        // tall feed post could carry the same description.
        if (homeHeader(s)) return null
        return s.visible { it.desc?.startsWith("Reel by") == true && it.r.height() > s.h * 0.6 }
    }

    /** The "Reels | Friends" header only exists on the Reels tab. */
    private fun reelsTabHeader(s: Snap): Boolean {
        val top = s.h * 0.15
        val reels = s.anyVisible { it.r.bottom < top && it.label().equals("Reels", true) && it.id != "clips_tab" }
        val friends = s.anyVisible { it.r.bottom < top && it.label().equals("Friends", true) }
        return reels && friends
    }

    /** Identifies which reel is showing, so swiping to another one can be noticed. */
    private fun reelKey(s: Snap): String? {
        val media = s.largestVisibleId("clips_media_component") ?: s.largestVisibleId("clips_video_container")
        media?.desc?.takeIf { it.isNotBlank() }?.let { return it }
        s.largestVisibleId("clips_author_username")?.label()?.takeIf { it.isNotBlank() }?.let { return "@$it" }
        return s.all.filter { it.onScreen && it.desc?.startsWith("Reel by") == true }
            .maxByOrNull { s.visibleArea(it.r) }?.desc
    }

    // ---- DMs / profiles / home ----------------------------------------------------------

    private val callLabels = setOf("audio call", "video call", "start audio call", "start video call", "call")

    private fun isDmThread(s: Snap): Boolean = s.anyVisible { n ->
        val id = n.id.orEmpty()
        id.contains("thread_composer") || id.startsWith("direct_thread") || id.contains("message_composer") ||
            (n.cls?.endsWith("EditText") == true && n.label().startsWith("Message", true)) ||
            (n.r.bottom < s.h * 0.2 && n.label().lowercase() in callLabels)
    }

    private fun isOwnProfile(s: Snap): Boolean = s.anyVisible { n ->
        val l = n.label()
        l.equals("Edit profile", true) || l.startsWith("Your profile", true) ||
            n.id == "create_highlight_button_view"
    }

    private fun isProfile(s: Snap): Boolean =
        s.visibleId("row_profile_header_imageview") != null ||
            s.anyVisible { it.id?.startsWith("profile_header_") == true } ||
            // Fallback: the posts / followers / following counters of a profile header
            // (all three, so the followers *list* screen doesn't count)
            (headerCounter(s, "posts") && headerCounter(s, "followers") && headerCounter(s, "following"))

    private fun headerCounter(s: Snap, word: String) =
        s.anyVisible { it.label().trim().endsWith(word, true) && it.r.top < s.h * 0.45 }

    private fun storiesRow(s: Snap): Snap.N? =
        s.visibleId("reels_tray_container")
            ?: s.visible { it.desc.equals("reels tray container", true) }
            // Fallback: a horizontal row of "<name>'s story" bubbles near the top
            ?: s.visible { it.desc?.contains("'s story", true) == true && it.r.top < s.h * 0.4 }
                ?.let { bubble -> s.all.filter { it.onScreen && it.r.contains(bubble.r) && it.r.width() >= s.w * 0.9 }
                    .minByOrNull { it.r.height() } }

    private fun isHomeFeed(s: Snap): Boolean =
        s.visibleId("title_logo") != null ||
            s.anyVisible { it.desc.equals("Instagram Home Feed", true) } ||
            storiesRow(s) != null ||
            s.visibleId("row_feed_profile_header") != null

    /**
     * Right after Instagram starts, its tab bar can be on screen with no tab marked as selected
     * yet (the mark only appears after the first tab switch). Home is then recognised by its own
     * header instead. The tab bar must be visible, so this never fires inside the story viewer
     * or any other full-screen page that hides it.
     */
    private fun homeWithoutSelectedTab(s: Snap, tab: Tab): Boolean =
        tab == Tab.UNKNOWN && s.visibleId("feed_tab") != null && homeHeader(s)

    private fun homeHeader(s: Snap): Boolean =
        s.visibleId("main_feed_action_bar") != null || s.visibleId("title_logo") != null ||
            s.anyVisible { it.desc.equals("Instagram Home Feed", true) }

    private fun feedRect(s: Snap): Rect {
        val tray = storiesRow(s)
        if (tray != null) health.seen("ig_tray")
        val top = tray?.r?.bottom
            ?: s.visibleId("main_feed_action_bar")?.r?.bottom
            ?: s.visibleId("title_logo")?.r?.bottom
            ?: (s.h * 0.1).toInt()
        val bottom = tabBarTop(s) ?: s.h
        return Rect(0, top, s.w, bottom)
    }
}

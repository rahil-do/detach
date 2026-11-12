package io.github.detach

/**
 * YouTube: block the Shorts player (Shorts tab, a Short opened from Home, search, a channel…).
 * Normal videos, Home, Subscriptions, Library are untouched.
 */
class YouTubeRules(
    private val settings: Settings,
    private val health: Health,
    private val breather: () -> Set<Breather.Feature> = { emptySet() },
) {

    fun evaluate(s: Snap): Decision {
        val pivot = s.visibleId("pivot_bar")
        val bar = bottomTabs(s)
        val navFound = pivot != null || bar.isNotEmpty()
        health.ytEval(navFound)
        if (navFound) health.seen("yt_bar")
        if (!settings.ytShorts || Breather.Feature.YT_SHORTS in breather()) return Decision.NONE

        val shortsTabSelected = bar.any { it.selected && it.label().equals("Shorts", true) }
        val player = shortsPlayer(s)
        if (player) health.seen("yt_shorts")
        if (!shortsTabSelected && !player) return Decision.NONE

        // Only tap "Home" inside YouTube's real tab bar, never some other element labelled Home.
        val home = if (pivot != null) bar.firstOrNull { it.label().equals("Home", true) } else null
        return if (shortsTabSelected && home != null) {
            Decision(action = Action.Click(home, "YouTube Shorts tab"))
        } else {
            Decision(action = Action.Back("YouTube Short"))
        }
    }

    private fun bottomTabs(s: Snap): List<Snap.N> {
        val pivot = s.visibleId("pivot_bar")
        return s.all.filter {
            it.onScreen && it.clickable && it.label().isNotBlank() &&
                (if (pivot != null) pivot.r.contains(it.r) else s.inBottomBar(it)) &&
                it.label().lowercase() in setOf("home", "shorts", "subscriptions", "you", "create", "library")
        }
    }

    private fun shortsPlayer(s: Snap): Boolean {
        for (id in listOf("reel_recycler", "reel_player_page_container", "shorts_player_container")) {
            val n = s.largestVisibleId(id) ?: continue
            if (n.r.height() > s.bounds.height() / 2) return true
        }
        // Fallback: any large on-screen element whose id mentions shorts/reel_player
        return s.anyVisible {
            val id = it.id.orEmpty()
            (id.startsWith("shorts_player") || id.startsWith("shorts_video") || id.startsWith("reel_player")) &&
                it.r.height() > s.bounds.height() / 2
        }
    }
}

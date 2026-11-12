package io.github.detach

import android.graphics.Rect

sealed class Action(val reason: String) {
    /** Instagram: switch to the Messages tab. */
    class ToDms(reason: String) : Action(reason)
    /** System Back. */
    class Back(reason: String) : Action(reason)
    /** Tap a specific element (e.g. YouTube's Home tab). */
    class Click(val node: Snap.N, reason: String) : Action(reason)

    /** Two decisions are "the same" if they would do the same thing. */
    fun sameKind(o: Action?): Boolean {
        if (o == null || o::class != this::class || o.reason != reason) return false
        return this !is Click || o is Click && node.id == o.node.id && node.label() == o.node.label() &&
            node.r == o.node.r && node.node.windowId == o.node.node.windowId
    }
}

/** What a rule set wants: an optional area to cover, and an optional action. */
class Decision(
    val cover: Rect? = null,
    val action: Action? = null,
    /** The cover is the Instagram home-feed one (remembered to show instantly next time). */
    val coverIsHomeFeed: Boolean = false,
    /** Text shown on the cover. */
    val coverLabel: String = "Hidden by Detach",
    /** Show the "Back to stories" button (home feed only). */
    val coverBackToTop: Boolean = false,
    /** Show a button that returns Instagram's Explore surface to its Search field. */
    val coverBackToSearch: Boolean = false,
) {
    companion object {
        val NONE = Decision()
    }
}

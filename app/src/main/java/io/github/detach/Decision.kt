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
    fun sameKind(o: Action?) = o != null && o::class == this::class
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
) {
    companion object {
        val NONE = Decision()
    }
}

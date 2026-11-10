package io.github.detach

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class YouTubeRulesTest {
    private val ctx get() = RuntimeEnvironment.getApplication()
    private fun rules() = YouTubeRules(Settings(ctx), Health(ctx))
    private fun youtube() = Screen(Targets.YOUTUBE)

    private fun Screen.pivot(selected: String) = node(id = "pivot_bar", l = 0, t = 2509, r = W, b = 2644)
        .node(desc = "Home", l = 0, t = 2509, r = 305, b = 2644, clickable = true, selected = selected == "Home")
        .node(desc = "Shorts", l = 305, t = 2509, r = 610, b = 2644, clickable = true, selected = selected == "Shorts")
        .node(desc = "You", l = 915, t = 2509, r = W, b = 2644, clickable = true, selected = selected == "You")

    private fun Screen.shortsPlayer() = node(id = "reel_recycler", l = 0, t = 128, r = W, b = 2509)

    @Test fun `a normal video is left alone`() {
        val d = rules().evaluate(youtube().pivot("Home").node(id = "watch_player", l = 0, t = 128, r = W, b = 800).snap())
        assertNull(d.action)
        assertNull(d.cover)
    }

    @Test fun `the Shorts tab is sent back to Home by tapping the real Home tab`() {
        val d = rules().evaluate(youtube().pivot("Shorts").shortsPlayer().snap())
        val a = d.action as Action.Click
        assertEquals("Home", a.node.desc)
        assertEquals(2509, a.node.r.top) // the one inside the tab bar
    }

    @Test fun `a Short opened from elsewhere is closed with Back`() {
        assertTrue(rules().evaluate(youtube().pivot("Home").shortsPlayer().snap()).action is Action.Back)
    }

    @Test fun `nothing happens when Shorts blocking is switched off`() {
        Settings(ctx).ytShorts = false
        assertNull(rules().evaluate(youtube().pivot("Shorts").shortsPlayer().snap()).action)
    }

    /** Audit finding: an element labelled "Home" outside the tab bar must never be tapped. */
    @Test fun `a fake Home element outside the tab bar is never tapped`() {
        val s = youtube().shortsPlayer()
            .node(desc = "Shorts", l = 0, t = 2300, r = 300, b = 2400, clickable = true, selected = true)
            .node(desc = "Home", l = 320, t = 2300, r = 620, b = 2400, clickable = true)
        assertTrue(rules().evaluate(s.snap()).action is Action.Back)
    }
}

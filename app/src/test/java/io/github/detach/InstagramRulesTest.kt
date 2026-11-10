package io.github.detach

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class InstagramRulesTest {
    private val ctx get() = RuntimeEnvironment.getApplication()
    private fun rules() = InstagramRules(Settings(ctx), Health(ctx))

    // ---- home feed ----

    @Test fun `home feed is covered below the stories row`() {
        val d = rules().evaluate(instagram().igTabs("feed_tab").igHomeHeader().igFeedPost().snap(), 0)
        assertNull(d.action)
        assertTrue(d.coverIsHomeFeed)
        assertEquals(695, d.cover!!.top)      // bottom of the stories row
        assertEquals(2509, d.cover!!.bottom)  // top of the tab bar
    }

    /** Regression (0.2.2): on a fresh start Instagram marks no tab as selected. */
    @Test fun `home feed is covered on a fresh start when no tab is marked selected`() {
        val d = rules().evaluate(instagram().igTabs(selected = null).igHomeHeader().igFeedPost().snap(), 0)
        assertNotNull(d.cover)
        assertTrue(d.coverIsHomeFeed)
        assertNull(d.action)
    }

    @Test fun `story viewer is never covered`() {
        // Full-screen story: no tab bar. Even if home's header were still reported, nothing happens.
        val d = rules().evaluate(instagram().igHomeHeader().snap(), 0)
        assertNull(d.cover)
        assertNull(d.action)
    }

    @Test fun `tabs laid out off screen are ignored`() {
        val s = instagram().igTabs("feed_tab").igHomeHeader()
            .node(id = "clips_viewer_view_pager", l = W, t = 128, r = W, b = 2509, visible = false)
            .node(id = "clips_media_component", desc = "Reel by x", l = W, t = 128, r = 2 * W, b = 2509, visible = false)
        val d = rules().evaluate(s.snap(), 0)
        assertNotNull(d.cover)
        assertNull(d.action)
    }

    @Test fun `nothing happens when Instagram blocking is switched off`() {
        Settings(ctx).igEnabled = false
        val d = rules().evaluate(instagram().igTabs("clips_tab").igReelViewer("x").snap(), 0)
        assertNull(d.cover)
        assertNull(d.action)
    }

    // ---- reels ----

    @Test fun `reels tab redirects to DMs`() {
        val d = rules().evaluate(instagram().igTabs("clips_tab").igReelViewer("x").snap(), 0)
        assertTrue(d.action is Action.ToDms)
    }

    @Test fun `reels tab is recognised by its header when no tab is marked selected`() {
        val s = instagram().igTabs(null).igReelViewer("x")
            .node(desc = "Reels", l = 307, t = 169, r = 557, b = 270, selected = true)
            .node(desc = "Friends", l = 557, t = 169, r = 962, b = 270, clickable = true)
        assertTrue(rules().evaluate(s.snap(), 0).action is Action.ToDms)
    }

    @Test fun `a reel opened from a chat plays and swiping to another reel goes back`() {
        val r = rules()
        assertNull(r.evaluate(instagram().igDmThread().snap(), 0).action)
        assertNull(r.evaluate(instagram().igReelViewer("friend").snap(), 1_000).action)
        assertNull(r.evaluate(instagram().igReelViewer("friend").snap(), 5_000).action)
        assertTrue(r.evaluate(instagram().igReelViewer("stranger").snap(), 6_000).action is Action.Back)
    }

    @Test fun `a swipe in the reel viewer goes back even if the reel can't be identified`() {
        val r = rules()
        r.evaluate(instagram().igDmThread().snap(), 0)
        val viewer = { instagram().node(id = "clips_viewer_view_pager", l = 0, t = 128, r = W, b = 2509).snap() }
        assertNull(r.evaluate(viewer(), 1_000).action)
        r.onClipsScrolled(1_500)                       // the opening animation: ignored
        assertNull(r.evaluate(viewer(), 1_600).action)
        r.onClipsScrolled(4_000)                       // a real swipe
        assertTrue(r.evaluate(viewer(), 4_100).action is Action.Back)
    }

    @Test fun `a reel opened from anywhere but a chat is closed`() {
        val r = rules()
        r.evaluate(instagram().igTabs("feed_tab").igHomeHeader().snap(), 0)
        assertTrue(r.evaluate(instagram().igReelViewer("x").snap(), 1_000).action is Action.Back)
    }

    @Test fun `a reel on your own profile plays`() {
        val r = rules()
        r.evaluate(instagram().igTabs("profile_tab").igProfileHeader().node(desc = "Edit profile", t = 1036, b = 1135, r = 549).snap(), 0)
        assertNull(r.evaluate(instagram().igReelViewer("me").snap(), 1_000).action)
    }

    // ---- search and profiles ----

    @Test fun `explore grid is covered from the first thumbnail down`() {
        val s = instagram().igTabs("search_tab").igGridTile(1, 1, 308).igGridTile(1, 2, 308).igGridTile(2, 1, 852)
        val d = rules().evaluate(s.snap(), 0)
        assertNull(d.action)
        assertEquals(308, d.cover!!.top)
        assertEquals(2509, d.cover!!.bottom)
    }

    @Test fun `account search results are left alone`() {
        val s = instagram().igTabs("search_tab")
            .node(id = "row_search_user_container", l = 2, t = 378, r = 1219, b = 569, clickable = true)
            .node(id = "row_search_user_username", text = "nasa", l = 227, t = 433, r = 380, b = 491)
        val d = rules().evaluate(s.snap(), 0)
        assertNull(d.cover)
        assertNull(d.action)
    }

    @Test fun `another person's posts grid is covered but their header is not`() {
        val d = rules().evaluate(instagram().igTabs("search_tab").igProfileHeader().snap(), 0)
        assertNull(d.action)
        assertEquals(1496, d.cover!!.top)
    }

    @Test fun `your own profile is not covered`() {
        val s = instagram().igTabs("profile_tab").igProfileHeader().node(desc = "Edit profile", t = 1036, b = 1135, r = 549)
        val d = rules().evaluate(s.snap(), 0)
        assertNull(d.cover)
        assertNull(d.action)
    }

    // ---- hostile content: text on screen is chosen by other people ----

    @Test fun `text in a chat can't trigger the reels redirect`() {
        val s = instagram().igDmThread()
            .node(text = "Reels", l = 100, t = 200, r = 300, b = 260)
            .node(text = "Friends", l = 320, t = 200, r = 520, b = 260)
            .node(text = "Reel by someone. Double tap to play or pause.", l = 100, t = 900, r = 700, b = 1000)
        val d = rules().evaluate(s.snap(), 0)
        assertNull(d.action)
        assertNull(d.cover)
    }

    @Test fun `a feed post described as a reel doesn't make Detach press Back on Home`() {
        val s = instagram().igTabs("feed_tab").igHomeHeader()
            .node(desc = "Reel by someone. Double tap to play or pause.", l = 0, t = 700, r = W, b = 2500)
        val d = rules().evaluate(s.snap(), 0)
        assertNull(d.action)
        assertNotNull(d.cover)
    }

    @Test fun `a display name imitating your own profile only reduces blocking, never causes an action`() {
        val s = instagram().igTabs("search_tab").igProfileHeader().node(text = "Edit profile", l = 338, t = 347, r = 700, b = 402)
        val d = rules().evaluate(s.snap(), 0)
        assertNull(d.action)
    }

    @Test fun `Instagram rules never ask to tap an element`() {
        val hostile = listOf(
            instagram().igTabs(null).node(desc = "Home", l = 0, t = 2300, r = 300, b = 2400, clickable = true, selected = true),
            instagram().igTabs("clips_tab").node(desc = "Message", l = 0, t = 300, r = 300, b = 400, clickable = true),
            instagram().igReelViewer("x").node(desc = "Profile", l = 0, t = 2300, r = 300, b = 2400, clickable = true, selected = true),
        )
        val r = rules()
        hostile.forEachIndexed { i, s -> assertFalse(r.evaluate(s.snap(), i * 1_000L).action is Action.Click) }
    }
}

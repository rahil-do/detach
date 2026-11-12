package io.github.detach

import android.content.Context
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class BreatherRulesTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private var allowed = emptySet<Breather.Feature>()
    private lateinit var settings: Settings
    private lateinit var instagramRules: InstagramRules
    private lateinit var youtubeRules: YouTubeRules

    @Before fun setup() {
        context.getSharedPreferences("settings", Context.MODE_PRIVATE).edit().clear().commit()
        settings = Settings(context)
        instagramRules = InstagramRules(settings, Health(context)) { allowed }
        youtubeRules = YouTubeRules(settings, Health(context)) { allowed }
    }

    @Test fun `feed breather preserves permanent settings and restores the cover immediately after expiry`() {
        val home = instagram().igTabs("feed_tab").igHomeHeader().igFeedPost().snap()
        assertNotNull(instagramRules.evaluate(home, 0).cover)
        allowed = setOf(Breather.Feature.IG_FEED)
        assertNull(instagramRules.evaluate(home, 1_000).cover)
        assertTrue(settings.igFeed)
        allowed = emptySet()
        assertNotNull(instagramRules.evaluate(home, 2_000).cover)
    }

    @Test fun `feed breather never unblocks Reels or YouTube Shorts`() {
        allowed = setOf(Breather.Feature.IG_FEED)
        assertNotNull(instagramRules.evaluate(instagram().igTabs("clips_tab").snap(), 0).action)
        assertNotNull(youtubeRules.evaluate(shorts()).action)
    }

    @Test fun `Reels breather allows the tab and external players but not the home feed`() {
        allowed = setOf(Breather.Feature.IG_REELS)
        assertNull(instagramRules.evaluate(instagram().igTabs("clips_tab").snap(), 0).action)
        assertNull(instagramRules.evaluate(instagram().igReelViewer("external").snap(), 1_000).action)
        assertNotNull(instagramRules.evaluate(instagram().igTabs("feed_tab").igHomeHeader().igFeedPost().snap(), 2_000).cover)
        allowed = emptySet()
        assertNotNull(instagramRules.evaluate(instagram().igReelViewer("external").snap(), 3_000).action)
    }

    @Test fun `Stories breather overrides only its temporary allowance`() {
        settings.igStories = false
        val story = instagram().igStoryViewer().snap()
        assertNotNull(instagramRules.evaluate(story, 0).action)
        allowed = setOf(Breather.Feature.IG_STORIES)
        assertNull(instagramRules.evaluate(story, 1_000).action)
        assertFalse(settings.igStories)
        allowed = emptySet()
        assertNotNull(instagramRules.evaluate(story, 2_000).action)
    }

    @Test fun `DMs breather does not grant shared reels`() {
        settings.igMessages = false
        settings.igSharedReels = false
        allowed = setOf(Breather.Feature.IG_DMS)
        assertNull(instagramRules.evaluate(instagram().igDmThread().snap(), 0).action)
        assertNotNull(instagramRules.evaluate(instagram().igReelViewer("friend").snap(), 1_000).action)
        assertFalse(settings.igMessages)
        assertFalse(settings.igSharedReels)
    }

    @Test fun `shared reels breather permits one shared item not endless swiping`() {
        settings.igSharedReels = false
        allowed = setOf(Breather.Feature.IG_SHARED_REELS)
        instagramRules.evaluate(instagram().igDmThread().snap(), 0)
        assertNull(instagramRules.evaluate(instagram().igReelViewer("friend").snap(), 1_000).action)
        assertNotNull(instagramRules.evaluate(instagram().igReelViewer("someone else").snap(), 2_000).action)
        assertFalse(settings.igSharedReels)
    }

    @Test fun `shared reels allowance cannot open a permanently disabled inbox`() {
        settings.igMessages = false
        allowed = setOf(Breather.Feature.IG_SHARED_REELS)
        assertNotNull(instagramRules.evaluate(instagram().igDmThread().snap(), 0).action)
    }

    @Test fun `Explore and profile post breathers remain independent`() {
        val explore = instagram().igTabs("search_tab").igGridTile(1, 1, 308).igGridTile(1, 2, 308).snap()
        val profile = instagram().igTabs("search_tab").igProfileHeader().snap()
        allowed = setOf(Breather.Feature.IG_EXPLORE)
        assertNull(instagramRules.evaluate(explore, 0).cover)
        assertNotNull(instagramRules.evaluate(profile, 1_000).cover)
        allowed = setOf(Breather.Feature.IG_PROFILE_POSTS)
        assertNull(instagramRules.evaluate(profile, 2_000).cover)
        assertNotNull(instagramRules.evaluate(explore, 3_000).cover)
        assertTrue(settings.igExplore)
        assertTrue(settings.igOtherProfiles)
    }

    @Test fun `YouTube breather expires without rewriting permanent Shorts protection`() {
        val player = shorts()
        assertNotNull(youtubeRules.evaluate(player).action)
        allowed = setOf(Breather.Feature.YT_SHORTS)
        assertNull(youtubeRules.evaluate(player).action)
        assertTrue(settings.ytShorts)
        allowed = emptySet()
        assertNotNull(youtubeRules.evaluate(player).action)
    }

    @Test fun `an ended breather does not enable restrictions permanently switched off`() {
        settings.igFeed = false
        settings.ytShorts = false
        allowed = setOf(Breather.Feature.IG_FEED, Breather.Feature.YT_SHORTS)
        instagramRules.evaluate(instagram().igTabs("feed_tab").igHomeHeader().igFeedPost().snap(), 0)
        allowed = emptySet()
        assertNull(instagramRules.evaluate(instagram().igTabs("feed_tab").igHomeHeader().igFeedPost().snap(), 1_000).cover)
        assertNull(youtubeRules.evaluate(shorts()).action)
    }

    private fun shorts() = Screen(Targets.YOUTUBE)
        .node(id = "reel_recycler", l = 0, t = 128, r = W, b = 2509).snap()
}

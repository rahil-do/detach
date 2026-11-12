package io.github.detach

import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], shadows = [ForegroundServiceShadow::class])
class BreatherServiceIntegrationTest {
    private lateinit var service: DetachService
    private lateinit var cover: FeedCover
    private lateinit var settings: Settings

    @Before fun setup() {
        BreatherTestSupport.reset()
        ForegroundServiceShadow.disabled = false
        service = Robolectric.buildService(DetachService::class.java).create().get()
        settings = Settings(service)
        val health = Health(service)
        cover = FeedCover(service, {}, {})
        set("settings", settings)
        set("health", health)
        set("ig", InstagramRules(settings, health) { Breather.activeFeatures(service) })
        set("yt", YouTubeRules(settings, health) { Breather.activeFeatures(service) })
        set("cover", cover)
        set("guard", GuardOverlay(service, {}, {}))
        set("lock", Lock(service))
        set("worker", Handler(Looper.getMainLooper()))
        set("thread", HandlerThread("breather-integration"))
    }

    @After fun cleanup() {
        service.onDestroy()
        Breather.end(service)
        shadowOf(Looper.getMainLooper()).idle()
        ForegroundServiceShadow.activeRoot = null
    }

    @Test fun `expiry reevaluates a visible Short without another accessibility event`() {
        start(setOf(Breather.Feature.YT_SHORTS))
        foreground(shorts())
        invoke("refreshBreather")
        shadowOf(Looper.getMainLooper()).idle()
        assertFalse(cover.isShowing)
        assertTrue(Breather.allows(service, Breather.Feature.YT_SHORTS))
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(61))
        assertFalse(Breather.allows(service, Breather.Feature.YT_SHORTS))
        assertTrue(cover.isShowing)
        assertTrue(value("pending") is Action.Back || shadowOf(service).globalActionsPerformed.isNotEmpty())
        assertTrue(settings.ytShorts)
    }

    @Test fun `ending a breather early reapplies the active screen restriction`() {
        start(setOf(Breather.Feature.YT_SHORTS))
        foreground(shorts())
        invoke("refreshBreather")
        shadowOf(Looper.getMainLooper()).idle()
        assertFalse(cover.isShowing)
        Breather.end(service)
        invoke("refreshBreather")
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(cover.isShowing)
        assertNotNull(value("pending"))
    }

    @Test fun `temporary DMs permission is honored by navigation and revoked before later taps`() {
        settings.igMessages = false
        start(setOf(Breather.Feature.IG_DMS))
        val snap = instagram().igTabs("clips_tab").snap()
        ForegroundServiceShadow.activeRoot = snap.all.first().node
        var clicks = 0
        shadowOf(snap.visibleId("direct_tab")!!.node).setOnPerformActionListener { _, _ ->
            clicks++
            true
        }
        DetachService::class.java.getDeclaredMethod("goToDms", Snap::class.java)
            .apply { isAccessible = true }.invoke(service, snap)
        assertEquals(1, clicks)
        Breather.end(service)
        DetachService::class.java.getDeclaredMethod("goToDms", Snap::class.java)
            .apply { isAccessible = true }.invoke(service, snap)
        assertEquals(1, clicks)
        assertFalse(settings.igMessages)
    }

    @Test fun `breather never relaxes Settings protection or payment priority`() {
        start(Breather.Feature.entries.toSet())
        foreground(Screen("com.android.settings").node(text = "Detach").node(text = "Uninstall"))
        invoke("evaluate")
        assertTrue(value("guardOn") as Boolean)
        foreground(Screen("com.phonepe.app").node(text = "Private payment screen"))
        invoke("evaluate")
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(ForegroundServiceShadow.disabled)
        assertTrue(Pause.isPending(service))
        assertFalse(cover.isShowing)
        assertFalse(value("guardOn") as Boolean)
    }

    private fun start(features: Set<Breather.Feature>) {
        Lock(service).set(BreatherTestSupport.PASSWORD)
        assertEquals(true, BreatherTestSupport.start(BreatherTestSupport.activity(), 1, features))
    }

    private fun shorts() = Screen(Targets.YOUTUBE).node(id = "reel_recycler", l = 0, t = 128, r = W, b = 2509)

    private fun foreground(screen: Screen) {
        ForegroundServiceShadow.activeRoot = screen.snap().all.first().node
    }

    private fun set(name: String, value: Any) {
        DetachService::class.java.getDeclaredField(name).apply { isAccessible = true }.set(service, value)
    }

    private fun value(name: String): Any? = DetachService::class.java.getDeclaredField(name)
        .apply { isAccessible = true }.get(service)

    private fun invoke(name: String) {
        DetachService::class.java.getDeclaredMethod(name).apply { isAccessible = true }.invoke(service)
    }
}

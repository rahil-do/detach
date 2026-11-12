package io.github.detach

import android.graphics.PixelFormat
import android.graphics.Rect
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class FeedCoverTest {
    private lateinit var cover: FeedCover
    private var storiesClicks = 0
    private var searchClicks = 0

    @Before fun setup() {
        val service = Robolectric.buildService(DetachService::class.java).create().get()
        cover = FeedCover(service, { storiesClicks++ }, { searchClicks++ })
    }

    @After fun cleanup() {
        cover.hide()
    }

    private fun view(): LinearLayout = FeedCover::class.java.getDeclaredField("view")
        .apply { isAccessible = true }.get(cover) as LinearLayout

    @Test fun `the overlay is opaque and has no animated or out of bounds layout`() {
        val bounds = Rect(100, 200, 500, 1000)
        cover.show(bounds)
        val view = view()
        val layout = view.layoutParams as WindowManager.LayoutParams
        assertTrue(view.isOpaque)
        assertTrue(view.isClickable)
        assertFalse(view.isFocusable)
        assertEquals(PixelFormat.OPAQUE, layout.format)
        assertEquals(1f, layout.alpha)
        assertEquals(0, layout.windowAnimations)
        assertEquals(0, layout.flags and WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS)
        assertEquals(bounds.left, layout.x)
        assertEquals(bounds.top, layout.y)
        assertEquals(bounds.width(), layout.width)
        assertEquals(bounds.height(), layout.height)
    }

    @Test fun `resizing and relabelling reuse one window without uncovering the app`() {
        cover.show(Rect(0, 200, 500, 1000), "Feed", backToTop = true)
        val original = view()
        cover.show(Rect(0, 150, 500, 1200), "Explore", backToSearch = true)
        assertSame(original, view())
        (original.getChildAt(1) as Button).performClick()
        assertEquals(0, storiesClicks)
        assertEquals(1, searchClicks)
    }

    @Test fun `removing navigation controls also removes their previous action`() {
        cover.show(Rect(0, 200, 500, 1000), backToTop = true)
        val button = view().getChildAt(1) as Button
        cover.show(Rect(0, 200, 500, 1000))
        assertEquals(View.GONE, button.visibility)
        button.performClick()
        assertEquals(0, storiesClicks)
    }

    @Test fun `hiding invalidates callbacks held by an old overlay window`() {
        cover.show(Rect(0, 200, 500, 1000), backToTop = true)
        val button = view().getChildAt(1) as Button
        cover.hide()
        button.performClick()
        assertEquals(0, storiesClicks)
        assertFalse(cover.isShowing)
    }

    @Test fun `invalid widths remove an existing overlay instead of asking for a full width window`() {
        cover.show(Rect(0, 200, 500, 1000))
        cover.show(Rect(500, 200, 500, 1000))
        assertFalse(cover.isShowing)
        cover.show(Rect(500, 200, 499, 1000))
        assertFalse(cover.isShowing)
    }

    @Test fun `click confirmation includes the current target identity and geometry`() {
        val first = Screen(Targets.YOUTUBE).node(id = "home_tab", desc = "Home", l = 0, t = 2500, r = 300, b = 2600)
            .snap().visibleId("home_tab")!!
        val changed = Screen(Targets.YOUTUBE).node(id = "other_tab", desc = "Home", l = 0, t = 2500, r = 300, b = 2600)
            .snap().visibleId("other_tab")!!
        val moved = Screen(Targets.YOUTUBE).node(id = "home_tab", desc = "Home", l = 300, t = 2500, r = 600, b = 2600)
            .snap().visibleId("home_tab")!!
        val action = Action.Click(first, "Shorts")
        assertTrue(action.sameKind(Action.Click(first, "Shorts")))
        assertFalse(action.sameKind(Action.Click(changed, "Shorts")))
        assertFalse(action.sameKind(Action.Click(moved, "Shorts")))
    }

    @Test fun `cover uses the shared palette and accessible navigation targets`() {
        cover.show(Rect(0, 200, 500, 1000), backToTop = true)
        val panel = view()
        val ui = Ui(panel.context)
        assertEquals(ui.bg, (panel.background as android.graphics.drawable.ColorDrawable).color)
        val button = panel.getChildAt(1) as Button
        assertEquals("Back to stories", button.text.toString())
        assertTrue(button.minimumHeight >= ui.dp(48))
        assertEquals(1f, panel.alpha)
    }
}

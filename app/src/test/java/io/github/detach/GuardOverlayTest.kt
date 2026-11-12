package io.github.detach

import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.ColorDrawable
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.ScrollView
import android.widget.TextView
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
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class GuardOverlayTest {
    private lateinit var service: DetachService
    private lateinit var overlay: GuardOverlay
    private var unlocks = 0
    private var backs = 0

    @Before fun setup() {
        service = Robolectric.buildService(DetachService::class.java).create().get()
        overlay = GuardOverlay(service, { unlocks++ }, { backs++ })
    }

    @After fun cleanup() { overlay.hide() }

    private fun view(): ScrollView = GuardOverlay::class.java.getDeclaredField("view")
        .apply { isAccessible = true }.get(overlay) as ScrollView

    private fun descendants(root: View): List<View> = listOf(root) + if (root is ViewGroup) {
        (0 until root.childCount).flatMap { descendants(root.getChildAt(it)) }
    } else emptyList()

    @Test fun `protection panel is immediately opaque and stays inside its assigned bounds`() {
        val bounds = Rect(100, 200, 600, 900)
        overlay.show(true, false, bounds)
        val panel = view()
        val layout = panel.layoutParams as WindowManager.LayoutParams
        assertTrue(panel.isOpaque)
        assertTrue(panel.isClickable)
        assertEquals(Ui(service).bg, (panel.background as ColorDrawable).color)
        assertEquals(1f, panel.alpha)
        assertEquals(1f, layout.alpha)
        assertEquals(PixelFormat.OPAQUE, layout.format)
        assertEquals(0, layout.windowAnimations)
        assertEquals(0, layout.fitInsetsTypes)
        assertEquals(0, layout.flags and WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS)
        assertEquals(bounds, Rect(layout.x, layout.y, layout.x + layout.width, layout.y + layout.height))
    }

    @Test fun `resizing reuses the opaque protection window`() {
        overlay.show(true, false, Rect(0, 50, 600, 900))
        val original = view()
        overlay.show(true, false, Rect(20, 80, 500, 600))
        assertSame(original, view())
        val layout = view().layoutParams as WindowManager.LayoutParams
        assertEquals(20, layout.x)
        assertEquals(80, layout.y)
        assertEquals(480, layout.width)
        assertEquals(520, layout.height)
    }

    @Test fun `invalid geometry removes the panel rather than creating a fullscreen fallback`() {
        overlay.show(true, false, Rect(0, 0, 600, 900))
        overlay.show(true, false, Rect(300, 100, 300, 900))
        assertFalse(overlay.isShowing)
        overlay.show(true, false, Rect(0, 0, 600, 0))
        assertFalse(overlay.isShowing)
    }

    @Test fun `controls remain accessible and callbacks are invalidated when hidden`() {
        overlay.show(true, false, Rect(0, 0, 600, 900))
        val views = descendants(view())
        val heading = views.filterIsInstance<TextView>().first { it.text == "Detach is protected" }
        assertTrue(heading.isAccessibilityHeading)
        val buttons = views.filterIsInstance<Button>()
        assertTrue(buttons.all { it.minimumHeight >= Ui(service).dp(48) })
        buttons.first { it.text == "Enter password" }.performClick()
        buttons.first { it.text == "Go back" }.performClick()
        assertEquals(1, unlocks)
        assertEquals(1, backs)
        overlay.hide()
        buttons.forEach { it.performClick() }
        assertEquals(1, unlocks)
        assertEquals(1, backs)
    }

    @Test fun `small windows can scroll instead of clipping confirmation controls`() {
        overlay.show(false, false, Rect(0, 0, 300, 120))
        val panel = view()
        panel.measure(View.MeasureSpec.makeMeasureSpec(300, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(120, View.MeasureSpec.EXACTLY))
        panel.layout(0, 0, 300, 120)
        assertTrue(panel.getChildAt(0).height > panel.height)
        assertTrue(descendants(panel).filterIsInstance<Button>().any { it.text == "Wait 30 seconds" })
    }
}

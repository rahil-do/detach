package io.github.detach

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
class OverlaySnapshotTest {
    @Test fun `snapshot visibility is limited to the apps own window`() {
        val snapshot = instagram().window(600, 300, W, 1100)
            .node(id = "clips_viewer_view_pager", l = 0, t = 1200, r = W, b = H)
            .node(id = "feed_tab", l = 600, t = 1000, r = 800, b = 1100)
            .snap()
        assertNull(snapshot.visibleId("clips_viewer_view_pager"))
        assertTrue(snapshot.inBottomBar(snapshot.visibleId("feed_tab")!!))
        assertEquals(Rect(600, 300, W, 1100), snapshot.bounds)
        assertEquals(0, snapshot.visibleArea(Rect(0, 1200, W, H)))
    }

    @Test fun `foreign and unowned nodes cannot contribute private content or blocking signals`() {
        val root = node(Targets.INSTAGRAM, "Allowed root")
        val foreign = node("com.example.bank", "Private banking content")
        shadowOf(foreign).addChild(node(Targets.INSTAGRAM, "Private embedded descendant"))
        shadowOf(root).addChild(foreign)
        shadowOf(root).addChild(node(null, "Unowned content"))
        shadowOf(root).addChild(node(Targets.INSTAGRAM, "Allowed child"))
        val snapshot = Snap(root, W, H)
        assertEquals(listOf("Allowed root", "Allowed child"), snapshot.all.map { it.label() })
    }

    @Test fun `password fields and their descendants never enter rule snapshots`() {
        val root = node(Targets.INSTAGRAM, "Allowed root")
        val password = node(Targets.INSTAGRAM, "Private password").apply { isPassword = true }
        shadowOf(password).addChild(node(Targets.INSTAGRAM, "Private password descendant"))
        shadowOf(root).addChild(password)
        val snapshot = Snap(root, W, H)
        assertFalse(snapshot.all.any { it.label().contains("Private") })
    }

    private fun node(pkg: String?, text: String) = AccessibilityNodeInfo.obtain().apply {
        packageName = pkg
        this.text = text
        isVisibleToUser = true
        setBoundsInScreen(Rect(0, 0, W, H))
    }
}

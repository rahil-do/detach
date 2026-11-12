package io.github.detach

import android.graphics.Rect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class CoverStateTest {
    private val window = Rect(0, 80, 1080, 2300)
    private val home = Decision(cover = Rect(0, 500, 1080, 2200), coverBackToTop = true)

    @Test fun `navigation is covered immediately while Back awaits confirmation`() {
        val state = CoverState()
        val frame = state.update(Targets.INSTAGRAM, window, Decision(action = Action.Back("Reels")), 1000, false)
        assertEquals(window, frame!!.bounds)
        assertFalse(frame.backToStories)
    }

    @Test fun `an empty transitional tree never uncovers a previously blocked surface`() {
        val state = CoverState()
        state.update(Targets.INSTAGRAM, window, home, 1000, false)
        val frame = state.update(Targets.INSTAGRAM, window, Decision.NONE, 1100, true)
        assertEquals(window, frame!!.bounds)
        assertFalse(frame.backToStories)
        assertNull(state.update(Targets.INSTAGRAM, window, Decision.NONE, 1350, true))
    }

    @Test fun `a recognised allowed screen is uncovered without a transition delay`() {
        val state = CoverState()
        state.update(Targets.INSTAGRAM, window, home, 1000, false)
        assertNull(state.update(Targets.INSTAGRAM, window, Decision.NONE, 1001, false))
    }

    @Test fun `no previous Instagram geometry is reused for another package`() {
        val state = CoverState()
        state.update(Targets.INSTAGRAM, window, home, 1000, false)
        assertNull(state.update(Targets.YOUTUBE, window, Decision.NONE, 1001, true))
    }

    @Test fun `covers are always clipped to split screen window bounds`() {
        val state = CoverState()
        val split = Rect(300, 400, 900, 1500)
        val frame = state.update(Targets.INSTAGRAM, split, home, 1000, false)
        assertEquals(Rect(300, 500, 900, 1500), frame!!.bounds)
    }

    @Test fun `invalid geometry is never shown`() {
        val state = CoverState()
        assertNull(state.update(Targets.INSTAGRAM, window, Decision(cover = Rect(2000, 0, 3000, 1000)), 1000, false))
        assertNull(state.update(Targets.INSTAGRAM, window, Decision(cover = Rect(0, 100, 1000, 120)), 1000, false))
    }

    @Test fun `clearing state prevents stale covers on the next app launch`() {
        val state = CoverState()
        assertNotNull(state.update(Targets.INSTAGRAM, window, home, 1000, false))
        state.clear()
        assertNull(state.update(Targets.INSTAGRAM, window, Decision.NONE, 1001, true))
    }

    @Test fun `repeated empty trees cannot extend the loading cover deadline`() {
        val state = CoverState()
        state.update(Targets.INSTAGRAM, window, home, 1000, false)
        assertNotNull(state.update(Targets.INSTAGRAM, window, Decision.NONE, 1100, true))
        assertNotNull(state.update(Targets.INSTAGRAM, window, Decision.NONE, 1349, true))
        assertNull(state.update(Targets.INSTAGRAM, window, Decision.NONE, 1350, true))
    }

    @Test fun `a loading cover follows the new window without retaining old split screen geometry`() {
        val state = CoverState()
        state.update(Targets.INSTAGRAM, window, home, 1000, false)
        val movedWindow = Rect(400, 800, 900, 1600)
        assertEquals(movedWindow, state.update(Targets.INSTAGRAM, movedWindow, Decision.NONE, 1100, true)!!.bounds)
    }

    @Test fun `missing app bounds never fall back to the whole screen`() {
        val state = CoverState()
        state.update(Targets.INSTAGRAM, window, home, 1000, false)
        assertNull(state.update(Targets.INSTAGRAM, Rect(), home, 1100, false))
        assertNull(state.update(Targets.INSTAGRAM, window, Decision.NONE, 1101, true))
    }

    @Test fun `requested and window rectangles are copied before being retained`() {
        val state = CoverState()
        val requested = Rect(0, 0, 1500, 3000)
        val appWindow = Rect(window)
        val frame = state.update(Targets.INSTAGRAM, appWindow, Decision(cover = requested), 1000, false)!!
        requested.setEmpty()
        appWindow.setEmpty()
        assertEquals(window, frame.bounds)
    }
}

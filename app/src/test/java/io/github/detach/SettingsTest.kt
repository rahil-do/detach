package io.github.detach

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class SettingsTest {
    @Test fun `Instagram permission switches default to allowing intentional use and persist`() {
        val first = Settings(RuntimeEnvironment.getApplication())
        first.igStories = true
        first.igMessages = true
        first.igSharedReels = true
        assertTrue(first.igStories)
        assertTrue(first.igMessages)
        assertTrue(first.igSharedReels)

        first.igStories = false
        first.igMessages = false
        first.igSharedReels = false
        val restored = Settings(RuntimeEnvironment.getApplication())
        assertFalse(restored.igStories)
        assertFalse(restored.igMessages)
        assertFalse(restored.igSharedReels)
    }
}

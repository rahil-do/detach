package io.github.detach

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class GuardTest {
    private fun settings() = Screen("com.android.settings")
    private fun installer() = Screen("com.google.android.packageinstaller")

    @Test fun `Detach's accessibility page is protected`() {
        val s = settings().node(desc = "Detach", t = 128, b = 286).node(text = "Use Detach", l = 135, t = 398, r = 398, b = 466)
        assertTrue(Guard.isProtectedScreen(s.snap()))
    }

    @Test fun `Detach's app info page is protected`() {
        val s = settings().node(text = "Detach", l = 513, t = 579, r = 707, b = 660)
            .node(text = "Uninstall", l = 503, t = 914, r = 717, b = 974)
            .node(text = "Force stop", l = 870, t = 914, r = 1103, b = 974)
        assertTrue(Guard.isProtectedScreen(s.snap()))
    }

    @Test fun `Detach's pages are protected in any language by their layout`() {
        val s = settings().node(desc = "Detach", t = 128, b = 286)
            .node(text = "Detach verwenden", l = 135, t = 398, r = 398, b = 466)
            .node(l = 973, t = 365, r = 1119, b = 500, cls = "android.widget.Switch")
        assertTrue(Guard.isProtectedScreen(s.snap()))
    }

    @Test fun `the uninstall prompt for Detach is protected`() {
        assertTrue(Guard.isProtectedScreen(installer().node(text = "Do you want to uninstall Detach?", t = 1200, b = 1300).snap()))
    }

    @Test fun `other apps' pages are not protected`() {
        val info = settings().node(text = "WhatsApp", l = 513, t = 579, r = 707, b = 660)
            .node(text = "Uninstall", l = 503, t = 914, r = 717, b = 974)
            .node(text = "Force stop", l = 870, t = 914, r = 1103, b = 974)
        assertFalse(Guard.isProtectedScreen(info.snap()))
        assertFalse(Guard.isProtectedScreen(installer().node(text = "Do you want to uninstall WhatsApp?", t = 1200, b = 1300).snap()))
    }

    @Test fun `ordinary settings pages are not protected`() {
        val wifi = settings().node(text = "Internet", t = 128, b = 286).node(text = "Home Wi-Fi", t = 600, b = 700)
        assertFalse(Guard.isProtectedScreen(wifi.snap()))
    }

    @Test fun `a sentence that mentions Detach doesn't turn another page into a protected one`() {
        val s = settings().node(text = "Notification history", t = 128, b = 286)
            .node(text = "Detach is on. Tap to pause for payments", t = 1200, b = 1300)
            .node(text = "Force stop", l = 870, t = 1914, r = 1103, b = 1974)
        assertFalse(Guard.isProtectedScreen(s.snap()))
    }
}

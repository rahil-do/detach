package io.github.detach

import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowLog

@RunWith(RobolectricTestRunner::class)
class InspectorTest {
    @Test fun `debug dumps reject both notification and payment content`() {
        (Targets.SYSTEM_UI + Targets.GUARDED + Targets.PAYMENT_DEFAULTS + "com.example.bank").forEach { pkg ->
            ShadowLog.clear()
            val screen = Screen(pkg).node(text = "Private content").snap()
            Inspector.dump(screen.all.first().node)
            assertFalse(ShadowLog.getLogsForTag(Inspector.TAG).any { it.msg.contains("Private content") || it.msg.contains("DUMP BEGIN") })
        }
    }

    @Test fun `debug tracing rejects events outside the content allowlist`() {
        Inspector.tracing = true
        try {
            (Targets.SYSTEM_UI + Targets.GUARDED + Targets.PAYMENT_DEFAULTS + "com.example.bank").forEach { pkg ->
                ShadowLog.clear()
                Inspector.trace(AccessibilityEvent.obtain(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED).apply { packageName = pkg })
                assertFalse(ShadowLog.getLogsForTag(Inspector.TAG).any { it.msg.startsWith("EV ") })
            }
        } finally {
            Inspector.tracing = false
        }
    }

    @Test fun `dump traversal does not cross package boundaries or enter password fields`() {
        ShadowLog.clear()
        val root = node(Targets.INSTAGRAM, "Allowed screen")
        val payment = node("com.example.bank", "Private bank content")
        shadowOf(payment).addChild(node(Targets.INSTAGRAM, "Private embedded descendant"))
        shadowOf(root).addChild(payment)
        val password = node(Targets.INSTAGRAM, "Private password").apply { isPassword = true }
        shadowOf(password).addChild(node(Targets.INSTAGRAM, "Private password descendant"))
        shadowOf(root).addChild(password)
        shadowOf(root).addChild(node(Targets.INSTAGRAM, "Allowed child"))
        Inspector.dump(root)
        val output = ShadowLog.getLogsForTag(Inspector.TAG).joinToString { it.msg }
        assertEquals(BuildConfig.DEBUG, output.contains("Allowed screen"))
        assertEquals(BuildConfig.DEBUG, output.contains("Allowed child"))
        assertFalse(output.contains("Private"))
    }

    @Test fun `release inspector produces no output even when tracing is requested`() {
        if (BuildConfig.DEBUG) return
        ShadowLog.clear()
        Inspector.tracing = true
        try {
            Inspector.dump(node(Targets.INSTAGRAM, "Social screen"))
            Inspector.trace(AccessibilityEvent.obtain(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED).apply {
                packageName = Targets.INSTAGRAM
            })
            assertTrue(ShadowLog.getLogsForTag(Inspector.TAG).isEmpty())
        } finally {
            Inspector.tracing = false
        }
    }

    @Test fun `describing protected or unknown nodes never formats their private content`() {
        (Targets.SYSTEM_UI + Targets.GUARDED + Targets.PAYMENT_DEFAULTS + setOf("com.example.bank", null)).forEach { pkg ->
            assertEquals("[redacted]", Inspector.describe(node(pkg, "Private content")))
        }
        assertEquals("[redacted]", Inspector.describe(node(Targets.YOUTUBE, "Secret").apply { isPassword = true }))
    }

    @Test fun `password events are never traced even in a supported app`() {
        ShadowLog.clear()
        Inspector.tracing = true
        try {
            Inspector.trace(AccessibilityEvent.obtain(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED).apply {
                packageName = Targets.INSTAGRAM
                isPassword = true
                className = "Private password metadata"
            })
            assertTrue(ShadowLog.getLogsForTag(Inspector.TAG).isEmpty())
        } finally {
            Inspector.tracing = false
        }
    }

    private fun node(pkg: String?, text: String) = AccessibilityNodeInfo.obtain().apply {
        packageName = pkg
        this.text = text
        isVisibleToUser = true
    }
}

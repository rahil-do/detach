package io.github.detach

import android.content.Context
import android.os.SystemClock
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
class LockTest {
    private val ctx get() = RuntimeEnvironment.getApplication()

    @Before fun clearLockout() {
        // The wrong-try counter lives for the life of the process; start each test clean.
        for (name in listOf("failures", "lockedUntil")) {
            val f = Lock::class.java.getDeclaredField(name).apply { isAccessible = true }
            if (name == "failures") f.setInt(null, 0) else f.setLong(null, 0L)
        }
    }

    @Test fun `the right password is accepted and a wrong one is not`() {
        val lock = Lock(ctx).apply { set("correct horse") }
        assertTrue(lock.isSet)
        assertTrue(lock.verify("correct horse"))
        assertFalse(lock.verify("correct hors"))
        assertFalse(lock.verify(""))
    }

    @Test fun `nothing verifies when no password is set`() {
        assertFalse(Lock(ctx).verify(""))
        assertFalse(Lock(ctx).verify("anything"))
    }

    @Test fun `the password is never stored, and the same password hashes differently each time`() {
        val lock = Lock(ctx)
        lock.set("hunter2-secret")
        val first = ctx.getSharedPreferences("lock", Context.MODE_PRIVATE).all.toString()
        assertFalse(first.contains("hunter2"))
        lock.set("hunter2-secret")
        val second = ctx.getSharedPreferences("lock", Context.MODE_PRIVATE).all.toString()
        assertNotEquals(first, second)
    }

    @Test fun `five wrong tries lock out even the right password for 30 seconds`() {
        val lock = Lock(ctx).apply { set("right") }
        repeat(5) { assertFalse(lock.verify("wrong$it")) }
        assertTrue(lock.lockoutSeconds() > 0)
        assertFalse(lock.verify("right"))
        SystemClock.setCurrentTimeMillis(SystemClock.uptimeMillis())
        org.robolectric.shadows.ShadowSystemClock.advanceBy(Duration.ofSeconds(31))
        assertTrue(lock.verify("right"))
    }

    @Test fun `removing the password leaves nothing behind`() {
        val lock = Lock(ctx).apply { set("temp") }
        lock.clear()
        assertFalse(lock.isSet)
        assertTrue(ctx.getSharedPreferences("lock", Context.MODE_PRIVATE).all.isEmpty())
    }
}

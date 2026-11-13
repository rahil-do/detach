package io.github.detach

/**
 * Process-wide state that outlives a Robolectric test (the sandbox, and its statics, is shared
 * between tests, and every test restarts SystemClock from the same base): an undelivered start
 * request, a monitor instance and a social-resume mark. Reset before each test so no test
 * depends on which ran before it.
 */
internal object MonitorTestSupport {
    fun reset() {
        PauseForegroundMonitor::class.java.getDeclaredField("requestedAt").apply { isAccessible = true }.setLong(null, Long.MIN_VALUE)
        PauseForegroundMonitor::class.java.getDeclaredField("active").apply { isAccessible = true }.set(null, null)
        Pause::class.java.getDeclaredField("socialResumeAt").apply { isAccessible = true }.setLong(null, Long.MIN_VALUE)
        WallClock.source = { System.currentTimeMillis() }
    }

    /** Wall time that moves only with Robolectric's simulated clock, in step with elapsed time. */
    fun simulateWallClock() {
        val base = 1_800_000_000_000L
        WallClock.source = { base + android.os.SystemClock.elapsedRealtime() }
    }
}

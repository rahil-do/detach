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
        Pause::class.java.getDeclaredField("rebinds").apply { isAccessible = true }.setInt(null, 0)
        Pause::class.java.getDeclaredField("rebindInFlight").apply { isAccessible = true }.setBoolean(null, false)
        StatusNotifications::class.java.getDeclaredField("posted").apply { isAccessible = true }.set(null, null)
        // Screens from earlier tests are never paused, so they never unregister.
        (StatusEvents::class.java.getDeclaredField("listeners").apply { isAccessible = true }.get(null) as MutableCollection<*>).clear()
        WallClock.source = { System.currentTimeMillis() }
    }

    /**
     * What a force-stop leaves: no alarms, no notifications, no monitor and a fresh process
     * (statics reset, pending main-thread work gone). Stored preferences and the clock survive.
     * Android 9 to 14 keep the app's PendingIntents through a force-stop; Android 15+ (stay
     * stopped) removes them too: [removePendingIntents].
     */
    fun forceStop(context: android.content.Context, removePendingIntents: Boolean = false) {
        val alarms = context.getSystemService(android.app.AlarmManager::class.java)
        org.robolectric.Shadows.shadowOf(alarms).scheduledAlarms.toList().forEach { alarm ->
            alarm.operation?.let { alarms.cancel(it); if (removePendingIntents) it.cancel() }
        }
        killPausePostedWork()
        context.getSystemService(android.app.NotificationManager::class.java).cancelAll()
        val clock = WallClock.source
        reset()
        WallClock.source = clock
        @Suppress("ControlFlowWithEmptyBody")
        while (org.robolectric.Shadows.shadowOf(context as android.app.Application).nextStartedService != null) { }
    }

    /** The process dies: whatever Pause had posted to the main thread (rebind, checks) never runs. */
    fun killPausePostedWork() {
        val lazy = Pause::class.java.getDeclaredField("main\$delegate").apply { isAccessible = true }.get(Pause) as Lazy<*>
        (lazy.value as android.os.Handler).removeCallbacksAndMessages(null)
    }

    /** A real reboot: Android's boot count moves on, then BOOT_COMPLETED arrives. */
    fun reboot(context: android.content.Context) {
        val resolver = context.contentResolver
        val boot = android.provider.Settings.Global.getInt(resolver, android.provider.Settings.Global.BOOT_COUNT, 0)
        android.provider.Settings.Global.putInt(resolver, android.provider.Settings.Global.BOOT_COUNT, boot + 1)
        PauseReceiver().onReceive(context, android.content.Intent(android.content.Intent.ACTION_BOOT_COMPLETED))
    }

    /** Wall time that moves only with Robolectric's simulated clock, in step with elapsed time. */
    fun simulateWallClock() {
        val base = 1_800_000_000_000L
        WallClock.source = { base + android.os.SystemClock.elapsedRealtime() }
    }
}

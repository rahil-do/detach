package io.github.detach

/**
 * Wall-clock time for the payment pause and its optional app detection. Always the real clock in
 * the app; tests can drive it from the same simulated clock as elapsed time so pause timing never
 * depends on how fast the test machine runs.
 */
internal object WallClock {
    @Volatile internal var source: () -> Long = { System.currentTimeMillis() }

    fun now(): Long = source()
}

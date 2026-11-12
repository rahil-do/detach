package io.github.detach

import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings as SystemSettings
import java.util.UUID

object Breather {
    enum class Feature(val label: String) {
        IG_FEED("Instagram feed"),
        IG_REELS("Instagram Reels"),
        IG_EXPLORE("Instagram Explore"),
        IG_PROFILE_POSTS("Instagram profile posts"),
        IG_STORIES("Instagram Stories"),
        IG_DMS("Instagram DMs"),
        IG_SHARED_REELS("Reels shared in Instagram DMs"),
        YT_SHORTS("YouTube Shorts"),
    }

    private data class Authorization(val context: Context, val minutes: Int, val features: Set<Feature>)
    private data class Session(val remaining: Long, val features: Set<Feature>)

    private val monitor = Any()
    private val process = UUID.randomUUID().toString()
    private val authorization = ThreadLocal<Authorization>()
    private var pendingConfirmation: Confirmation? = null
    private var storageFailed = false
    private const val MINUTE_MS = 60_000L
    private const val CLOCK_TOLERANCE_MS = 2_000L

    private class Confirmation(private val activity: Activity) : Application.ActivityLifecycleCallbacks {
        var valid = true
            private set

        init {
            activity.application.registerActivityLifecycleCallbacks(this)
        }

        fun cancel() = synchronized(monitor) {
            valid = false
            activity.application.unregisterActivityLifecycleCallbacks(this)
            if (pendingConfirmation === this) pendingConfirmation = null
        }

        override fun onActivityStopped(host: Activity) {
            if (host === activity) cancel()
        }

        override fun onActivityDestroyed(host: Activity) {
            if (host === activity) cancel()
        }

        override fun onActivityCreated(host: Activity, state: Bundle?) = Unit
        override fun onActivityStarted(host: Activity) = Unit
        override fun onActivityResumed(host: Activity) = Unit
        override fun onActivityPaused(host: Activity) = Unit
        override fun onActivitySaveInstanceState(host: Activity, state: Bundle) = Unit
    }

    fun confirmAndBegin(
        activity: Activity,
        minutes: Int,
        features: Set<Feature>,
        onResult: (Boolean) -> Unit,
    ) {
        val selected = features.toSet()
        if (minutes !in 1..60 || selected.isEmpty() || activity.isFinishing || activity.isDestroyed ||
            !runCatching { Auth.available(activity) }.getOrDefault(false)
        ) {
            onResult(false)
            return
        }
        val request = synchronized(monitor) {
            pendingConfirmation?.cancel()
            Confirmation(activity).also { pendingConfirmation = it }
        }
        val confirmed = {
            val started = synchronized(monitor) {
                val valid = request.valid && pendingConfirmation === request
                request.cancel()
                if (!valid || activity.isFinishing || activity.isDestroyed ||
                    !runCatching { Auth.available(activity) }.getOrDefault(false)
                ) {
                    false
                } else {
                    authorization.set(Authorization(activity.applicationContext, minutes, selected))
                    try {
                        begin(activity, minutes, selected)
                    } finally {
                        authorization.remove()
                    }
                }
            }
            onResult(started)
        }
        Auth.confirm(
            activity,
            "Start or extend a breather",
            fallback = {
                if (request.valid && Lock(activity).isSet) Auth.passwordDialog(activity, onSuccess = confirmed)
                else {
                    request.cancel()
                    onResult(false)
                }
            },
            onSuccess = confirmed,
        )
    }

    fun begin(context: Context, minutes: Int, features: Set<Feature>): Boolean = synchronized(monitor) {
        val approved = authorization.get()
        authorization.remove()
        if (approved == null || approved.context !== context.applicationContext ||
            approved.minutes != minutes || approved.features != features || minutes !in 1..60 || features.isEmpty()
        ) return@synchronized false
        val saved = runCatching {
            val elapsed = SystemClock.elapsedRealtime()
            val wall = System.currentTimeMillis()
            val boot = SystemSettings.Global.getInt(context.contentResolver, SystemSettings.Global.BOOT_COUNT, -1)
            val duration = minutes * MINUTE_MS
            if (elapsed < 0 || wall <= 0 || wall > Long.MAX_VALUE - duration || boot < 0) return@runCatching false
            preferences(context).edit().clear()
                .putInt("version", 1)
                .putString("process", process)
                .putInt("boot", boot)
                .putLong("elapsed", elapsed)
                .putLong("wall", wall)
                .putLong("duration", duration)
                .putStringSet("features", features.mapTo(mutableSetOf()) { it.name })
                .commit()
        }.getOrDefault(false)
        if (!saved) {
            invalidate(context)
            return@synchronized false
        }
        storageFailed = false
        session(context) != null
    }

    fun allows(context: Context, feature: Feature): Boolean = synchronized(monitor) {
        feature in (session(context)?.features ?: emptySet())
    }

    fun remainingMs(context: Context): Long = synchronized(monitor) {
        session(context)?.remaining ?: 0L
    }

    fun activeFeatures(context: Context): Set<Feature> = synchronized(monitor) {
        session(context)?.features ?: emptySet()
    }

    fun end(context: Context) = synchronized(monitor) {
        invalidate(context)
    }

    private fun preferences(context: Context) = context.getSharedPreferences("breather", Context.MODE_PRIVATE)

    private fun session(context: Context): Session? {
        if (storageFailed) return null
        val current = runCatching {
            val stored = preferences(context)
            if (!stored.contains("version")) {
                if (stored.all.isNotEmpty()) invalidate(context)
                return@runCatching null
            }
            val elapsed = SystemClock.elapsedRealtime()
            val wall = System.currentTimeMillis()
            val startedElapsed = stored.getLong("elapsed", -1L)
            val startedWall = stored.getLong("wall", -1L)
            val duration = stored.getLong("duration", -1L)
            val boot = SystemSettings.Global.getInt(context.contentResolver, SystemSettings.Global.BOOT_COUNT, -1)
            val selected = stored.getStringSet("features", null).orEmpty().map { Feature.valueOf(it) }.toSet()
            if (stored.getInt("version", -1) != 1 || stored.getString("process", null) != process ||
                boot < 0 || stored.getInt("boot", -1) != boot ||
                duration !in MINUTE_MS..(60 * MINUTE_MS) || duration % MINUTE_MS != 0L ||
                startedElapsed < 0 || startedWall <= 0 || startedWall > Long.MAX_VALUE - duration ||
                elapsed < startedElapsed || wall < startedWall || selected.isEmpty()
            ) return@runCatching null
            val elapsedDelta = elapsed - startedElapsed
            val wallDelta = wall - startedWall
            if (elapsedDelta >= duration || wallDelta >= duration ||
                wallDelta - elapsedDelta !in -CLOCK_TOLERANCE_MS..CLOCK_TOLERANCE_MS
            ) return@runCatching null
            Session(minOf(duration - elapsedDelta, duration - wallDelta), selected)
        }.getOrNull()
        if (current == null) invalidate(context)
        return current
    }

    private fun invalidate(context: Context) {
        storageFailed = true
        runCatching { preferences(context).edit().clear().commit() }
    }
}

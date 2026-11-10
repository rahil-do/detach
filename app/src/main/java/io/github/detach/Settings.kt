package io.github.detach

import android.content.Context
import android.content.SharedPreferences
import kotlin.properties.ReadWriteProperty
import kotlin.reflect.KProperty

/** User toggles. Stored only in this app's private storage on the phone. */
class Settings(context: Context) {
    private val p: SharedPreferences = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    // Instagram: master switch + individual blocks
    var igEnabled by flag("ig_enabled")
    var igFeed by flag("ig_feed")
    var igReelsTab by flag("ig_reels_tab")
    var igExplore by flag("ig_explore")
    var igOtherProfiles by flag("ig_other_profiles")

    // YouTube: Shorts is the only thing blocked
    var ytShorts by flag("yt_shorts")

    // The ongoing "Detach is on. Tap to pause for payments" notification
    var pauseNotification by flag("pause_notification")

    /** How long a pause for payments lasts, in minutes (1 to 60). */
    var pauseMinutes: Int
        get() = p.getInt("pause_minutes", 5).coerceIn(1, 60)
        set(value) { p.edit().putInt("pause_minutes", value.coerceIn(1, 60)).apply() }

    private fun flag(key: String, default: Boolean = true) = object : ReadWriteProperty<Any?, Boolean> {
        override fun getValue(thisRef: Any?, property: KProperty<*>) = p.getBoolean(key, default)
        override fun setValue(thisRef: Any?, property: KProperty<*>, value: Boolean) {
            p.edit().putBoolean(key, value).apply()
        }
    }
}

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
    /** Parts of Instagram that remain deliberately usable by default. */
    var igStories by flag("ig_stories")
    var igMessages by flag("ig_messages")
    var igSharedReels by flag("ig_shared_reels")

    // YouTube: Shorts is the only thing blocked
    var ytShorts by flag("yt_shorts")

    // The ongoing "Detach is on. Tap to pause for payments" notification
    var pauseNotification by flag("pause_notification")

    var autoPausePayments by flag("auto_pause_payments")

    var resumeOnSocialForeground: Boolean
        get() = runCatching { p.getBoolean("resume_on_social_foreground", false) }.getOrDefault(false)
        set(value) {
            p.edit().putBoolean("resume_on_social_foreground", value).apply()
            if (!value) PauseForegroundMonitor.stop(app)
        }

    private val app = context.applicationContext

    var paymentPackages: Set<String>
        get() = runCatching { p.getStringSet("payment_packages", Targets.PAYMENT_DEFAULTS) }
            .getOrDefault(Targets.PAYMENT_DEFAULTS).orEmpty().filterTo(mutableSetOf()) { validPaymentPackage(it) }
        set(value) { p.edit().putStringSet("payment_packages", value.filterTo(mutableSetOf()) { validPaymentPackage(it) }).apply() }

    fun eventPackages(): Set<String> = Targets.READABLE + Targets.SYSTEM_UI +
        if (autoPausePayments) paymentPackages else emptySet()

    private fun validPaymentPackage(value: String): Boolean =
        value.length <= 255 && value.matches(PACKAGE_NAME) &&
            value !in Targets.READABLE && value !in Targets.SYSTEM_UI && value != "io.github.detach"

    /** How long a pause for payments lasts, in minutes (1 to 60). */
    var pauseMinutes: Int
        get() = runCatching { p.getInt("pause_minutes", 5) }.getOrDefault(5).coerceIn(1, 60)
        set(value) { p.edit().putInt("pause_minutes", value.coerceIn(1, 60)).apply() }

    private fun flag(key: String, default: Boolean = true) = object : ReadWriteProperty<Any?, Boolean> {
        override fun getValue(thisRef: Any?, property: KProperty<*>) =
            runCatching { p.getBoolean(key, default) }.getOrDefault(default)
        override fun setValue(thisRef: Any?, property: KProperty<*>, value: Boolean) {
            p.edit().putBoolean(key, value).apply()
        }
    }

    private companion object {
        val PACKAGE_NAME = Regex("[a-zA-Z][a-zA-Z0-9_]*(\\.[a-zA-Z][a-zA-Z0-9_]*)+")
    }
}

package io.github.detach

import android.content.ComponentName
import android.content.Context
import android.content.pm.ApplicationInfo
import android.provider.Settings as SysSettings
import java.text.Collator

/**
 * Other installed (non-system) apps whose accessibility service is switched on. Payment apps
 * can refuse to work while any of them is on, and Detach can only switch itself off.
 *
 * Reads only the component names in Android's enabled-services setting and each app's label,
 * for display. Nothing is stored, logged or sent, and other apps' services are never changed.
 * System apps (for example Android's own Accessibility Menu) are left out.
 */
object OtherAccessibilityApps {
    fun labels(context: Context): List<String> {
        val enabled = runCatching {
            SysSettings.Secure.getString(context.contentResolver, SysSettings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
        }.getOrNull().orEmpty()
        val manager = context.packageManager
        val collator = Collator.getInstance()
        return enabled.split(':').asSequence()
            .mapNotNull { ComponentName.unflattenFromString(it.trim())?.packageName }
            .filter { it != context.packageName }
            .distinct()
            .mapNotNull { packageName ->
                runCatching {
                    val info = manager.getApplicationInfo(packageName, 0)
                    if (info.flags and (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0) null
                    else manager.getApplicationLabel(info).toString().trim().ifBlank { packageName }
                }.getOrNull() // Not installed (a stale entry): nothing is running.
            }
            .distinct()
            .sortedWith(collator)
            .toList()
    }
}

package io.github.detach

object Targets {
    const val INSTAGRAM = "com.instagram.android"
    const val YOUTUBE = "com.google.android.youtube"

    /** Apps whose content is blocked. */
    val PACKAGES = setOf(INSTAGRAM, YOUTUBE)

    /**
     * Android's notification shade and Quick Settings surface. Its events are used only to
     * remove Detach's overlays while a system panel is in front; its view hierarchy is never
     * inspected or stored.
     */
    val SYSTEM_UI = setOf("com.android.systemui")

    /**
     * System screens where Detach could be switched off, force-stopped, cleared or uninstalled.
     * Detach only looks for its own name on them, to put up the "protected" screen.
     */
    val GUARDED = setOf(
        "com.android.settings",
        "com.google.android.packageinstaller",
        "com.android.packageinstaller",
        "com.samsung.android.packageinstaller",
        "com.miui.packageinstaller",
    )

    /** Everything the service receives events from (must match accessibility_config.xml). */
    val ALL = PACKAGES + GUARDED + SYSTEM_UI
}

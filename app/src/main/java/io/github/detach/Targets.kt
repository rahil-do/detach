package io.github.detach

object Targets {
    const val INSTAGRAM = "com.instagram.android"
    const val YOUTUBE = "com.google.android.youtube"

    /** Apps whose content is blocked. */
    val PACKAGES = setOf(INSTAGRAM, YOUTUBE)

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

    /** Everything the service is allowed to see (must match res/xml/accessibility_config.xml). */
    val ALL = PACKAGES + GUARDED
}

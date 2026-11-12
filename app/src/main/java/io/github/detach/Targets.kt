package io.github.detach

object Targets {
    const val INSTAGRAM = "com.instagram.android"
    const val YOUTUBE = "com.google.android.youtube"

    /** Apps whose content is blocked. */
    val PACKAGES = setOf(INSTAGRAM, YOUTUBE)

    val SYSTEM_UI = setOf("com.android.systemui")

    val PAYMENT_DEFAULTS = setOf(
        "com.google.android.apps.nbu.paisa.user",
        "com.phonepe.app",
        "net.one97.paytm",
        "in.org.npci.upiapp",
    )

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

    /** Packages whose window content may be read for blocking or protecting Detach. */
    val READABLE = PACKAGES + GUARDED

    /** Default event routing only; SystemUI and payment events never grant content access. */
    val ALL = READABLE + SYSTEM_UI + PAYMENT_DEFAULTS
}

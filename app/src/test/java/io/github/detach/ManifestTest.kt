package io.github.detach

import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.xmlpull.v1.XmlPullParser

/** Checks the promises the docs make about what the installed app exposes. */
@RunWith(RobolectricTestRunner::class)
class ManifestTest {
    private val ctx get() = RuntimeEnvironment.getApplication()
    private val info get() = ctx.packageManager.getPackageInfo(
        ctx.packageName,
        PackageManager.GET_PERMISSIONS or PackageManager.GET_ACTIVITIES or PackageManager.GET_SERVICES or
            PackageManager.GET_RECEIVERS or PackageManager.GET_PROVIDERS,
    )

    @Test fun `no internet or network permission is requested`() {
        val perms = info.requestedPermissions.orEmpty().toList()
        assertFalse(perms.any { it.contains("INTERNET") || it.contains("NETWORK") || it.contains("WIFI") })
    }

    @Test fun `only the launcher screen can be started by other apps`() {
        val exported = buildList {
            info.activities.orEmpty().filter { it.exported }.forEach { add(it.name) }
            info.services.orEmpty().filter { it.exported }.forEach { add(it.name) }
            info.receivers.orEmpty().filter { it.exported }.forEach { add(it.name) }
            info.providers.orEmpty().forEach { add(it.name) }
        }
        assertEquals(listOf("io.github.detach.MainActivity"), exported)
    }

    @Test fun `the accessibility service can only be bound by Android`() {
        val svc = info.services.orEmpty().first { it.name == "io.github.detach.DetachService" }
        assertEquals("android.permission.BIND_ACCESSIBILITY_SERVICE", svc.permission)
    }

    @Test fun `cloud backup of Detach's data is off`() {
        assertEquals(0, ctx.applicationInfo.flags and ApplicationInfo.FLAG_ALLOW_BACKUP)
    }

    @Test fun `Android only sends Detach events from the apps the code expects`() {
        val xml = ctx.resources.getXml(R.xml.accessibility_config)
        var packages: Set<String>? = null
        var flags: String? = null
        while (xml.eventType != XmlPullParser.END_DOCUMENT) {
            if (xml.eventType == XmlPullParser.START_TAG && xml.name == "accessibility-service") {
                val ns = "http://schemas.android.com/apk/res/android"
                packages = xml.getAttributeValue(ns, "packageNames").split(',').map { it.trim() }.toSet()
                flags = xml.getAttributeValue(ns, "accessibilityFlags")
            }
            xml.next()
        }
        assertEquals(Targets.ALL, packages)
        // No banking, UPI or wallet app may ever be on the list.
        assertTrue(packages!!.none { p -> listOf("pay", "upi", "bank", "phonepe", "paytm", "bhim").any { p.contains(it) } })
        // flagRetrieveInteractiveWindows (0x40) would let it inspect other apps' windows.
        val f = flags.orEmpty()
        val value = f.removePrefix("0x").toIntOrNull(16) ?: f.toIntOrNull() ?: 0
        assertEquals(0, value and 0x40)
    }
}

package io.github.detach

import android.accessibilityservice.AccessibilityServiceInfo
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

    @Test fun `backup and device transfer rules exclude every private storage domain`() {
        ctx.assets.openXmlResourceParser("AndroidManifest.xml").use { manifest ->
            while (manifest.eventType != XmlPullParser.END_DOCUMENT) {
                if (manifest.eventType == XmlPullParser.START_TAG && manifest.name == "application") {
                    assertEquals(R.xml.data_extraction_rules, manifest.getAttributeResourceValue(
                        "http://schemas.android.com/apk/res/android", "dataExtractionRules", 0))
                    assertEquals("false", manifest.getAttributeValue(
                        "http://schemas.android.com/apk/res/android", "fullBackupContent"))
                    break
                }
                manifest.next()
            }
        }
        val rules = ctx.resources.getXml(R.xml.data_extraction_rules)
        val exclusions = mutableMapOf<String, MutableSet<String>>()
        var mode = ""
        while (rules.eventType != XmlPullParser.END_DOCUMENT) {
            if (rules.eventType == XmlPullParser.START_TAG) {
                when (rules.name) {
                    "cloud-backup", "device-transfer" -> {
                        mode = rules.name
                        exclusions[mode] = mutableSetOf()
                    }
                    "include" -> throw AssertionError("Backup includes must not be enabled")
                    "exclude" -> {
                        assertEquals(".", rules.getAttributeValue(null, "path"))
                        exclusions.getValue(mode).add(rules.getAttributeValue(null, "domain"))
                    }
                }
            }
            rules.next()
        }
        val domains = setOf("root", "file", "database", "sharedpref", "external", "device_root",
            "device_file", "device_database", "device_sharedpref")
        assertEquals(mapOf("cloud-backup" to domains, "device-transfer" to domains), exclusions)
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
        assertEquals(Targets.ALL, Settings(ctx).eventPackages())
        assertTrue(Targets.READABLE.intersect(Targets.PAYMENT_DEFAULTS + Targets.SYSTEM_UI).isEmpty())
        // flagRetrieveInteractiveWindows (0x40) would let it inspect other apps' windows.
        val f = flags.orEmpty()
        val value = f.removePrefix("0x").toIntOrNull(16) ?: f.toIntOrNull() ?: 0
        assertEquals(0, value and 0x40)
        assertTrue(f == "flagReportViewIds" || value == AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS)
    }

    @Test fun `optional usage monitor is private and requests only its declared foreground type`() {
        val permissions = info.requestedPermissions.orEmpty().toSet()
        assertTrue("android.permission.PACKAGE_USAGE_STATS" in permissions)
        assertEquals(setOf("android.permission.FOREGROUND_SERVICE", "android.permission.FOREGROUND_SERVICE_SPECIAL_USE"),
            permissions.filter { it.startsWith("android.permission.FOREGROUND_SERVICE") }.toSet())
        assertFalse("android.permission.QUERY_ALL_PACKAGES" in permissions)
        assertEquals(setOf("io.github.detach.DetachService", "io.github.detach.PauseForegroundMonitor"),
            info.services.orEmpty().map { it.name }.toSet())
        val monitor = info.services.orEmpty().single { it.name.endsWith(".PauseForegroundMonitor") }
        assertFalse(monitor.exported)
    }

    @Test fun `permission surface is limited to local authentication reminders and explicit resume`() {
        assertEquals(
            setOf(
                "android.permission.POST_NOTIFICATIONS", "android.permission.USE_BIOMETRIC",
                "android.permission.RECEIVE_BOOT_COMPLETED", "android.permission.WRITE_SECURE_SETTINGS",
                "android.permission.PACKAGE_USAGE_STATS", "android.permission.FOREGROUND_SERVICE",
                "android.permission.FOREGROUND_SERVICE_SPECIAL_USE",
            ),
            info.requestedPermissions.orEmpty().toSet(),
        )
        assertEquals(0, ctx.applicationInfo.flags and ApplicationInfo.FLAG_USES_CLEARTEXT_TRAFFIC)
        assertTrue(info.providers.orEmpty().isEmpty())
        val receiver = info.receivers.orEmpty().single()
        assertEquals("io.github.detach.PauseReceiver", receiver.name)
        assertFalse(receiver.exported)
    }
}

package io.github.detach

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.hardware.biometrics.BiometricManager
import android.os.Looper
import android.provider.Settings as SystemSettings
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowBiometricManager
import java.time.Duration

/** "Pause and open": a UPI app opened directly must start with Detach already out of the enabled list. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], shadows = [ForegroundServiceShadow::class])
class PauseActivityTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val gpay = "com.google.android.apps.nbu.paisa.user"
    private val detach get() = ComponentName(context, DetachService::class.java).flattenToString()

    @Before fun reset() {
        for (name in listOf("settings", "lock", "pause")) context.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear().commit()
        Shadow.extract<ShadowBiometricManager>(context.getSystemService(BiometricManager::class.java)).setCanAuthenticate(false)
        ForegroundServiceShadow.reset()
        setRunning(true)
        enabled("com.example/.Other:$detach")
        install(gpay, "Google Pay")
        install("com.example.unselected", "Unselected")
    }

    @After fun cleanup() = setRunning(false)

    private fun setRunning(running: Boolean) {
        DetachService::class.java.getDeclaredField("instance").apply { isAccessible = true }
            .set(null, if (running) Robolectric.buildService(DetachService::class.java).get() else null)
    }

    private fun enabled(value: String) =
        SystemSettings.Secure.putString(context.contentResolver, SystemSettings.Secure.ENABLED_ACCESSIBILITY_SERVICES, value)

    private fun install(packageName: String, label: String) {
        val manager = shadowOf(context.packageManager)
        manager.installPackage(PackageInfo().apply {
            this.packageName = packageName
            applicationInfo = ApplicationInfo().apply {
                this.packageName = packageName
                nonLocalizedLabel = label
                flags = ApplicationInfo.FLAG_INSTALLED
            }
        })
        val main = ComponentName(packageName, "$packageName.Main")
        manager.addActivityIfNotPresent(main)
        manager.addIntentFilterForActivity(main, IntentFilter(Intent.ACTION_MAIN).apply { addCategory(Intent.CATEGORY_LAUNCHER) })
    }

    private fun buttons(root: View): List<Button> = when (root) {
        is Button -> listOf(root)
        is ViewGroup -> (0 until root.childCount).flatMap { buttons(root.getChildAt(it)) }
        else -> emptyList()
    }

    private fun open() = Robolectric.buildActivity(PauseActivity::class.java, Intent(context, PauseActivity::class.java)).setup().get()

    private fun click(activity: PauseActivity, label: String) =
        buttons(activity.window.decorView).single { it.text == label }.performClick()

    @Test fun `only selected apps with a launcher entry are offered`() {
        val labels = buttons(open().window.decorView).map { it.text.toString() }
        assertTrue(labels.toString(), "Pause and open Google Pay" in labels)
        assertFalse(labels.any { it.contains("Unselected") })
        assertNull(PauseActivity.launchIntent(context, "com.example.unselected"))
        assertNull(PauseActivity.launchIntent(context, context.packageName))
    }

    @Test fun `the payment app opens only after Detach has left the enabled services`() {
        val activity = open()
        click(activity, "Pause and open Google Pay")
        assertTrue(ForegroundServiceShadow.disabled)
        assertTrue(Pause.isPending(context))
        assertEquals(gpay, Pause.startingPaymentPackage(context))
        // Android has not dropped Detach from the list yet: nothing is opened.
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(PauseActivity.OFF_POLL_MS * 3))
        assertNull(shadowOf(activity).nextStartedActivity)
        enabled("com.example/.Other")
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(PauseActivity.OFF_POLL_MS))
        val started = shadowOf(activity).nextStartedActivity
        assertNotNull(started)
        assertEquals(gpay, started.component?.packageName ?: started.`package`)
        assertNull(started.data)
        assertTrue(started.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
        assertTrue(activity.isFinishing)
    }

    @Test fun `if Detach stays listed the payment app is never opened`() {
        val activity = open()
        click(activity, "Pause and open Google Pay")
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(PauseActivity.OFF_WAIT_MS + PauseActivity.OFF_POLL_MS))
        assertNull(shadowOf(activity).nextStartedActivity)
        assertTrue(activity.isFinishing)
        assertTrue(Pause.isPending(context))
    }

    @Test fun `opening an app still needs the Detach password`() {
        Lock(context).set("secret")
        val activity = open()
        click(activity, "Pause and open Google Pay")
        assertFalse(Pause.isPending(context))
        assertFalse(ForegroundServiceShadow.disabled)
        assertNull(shadowOf(activity).nextStartedActivity)
    }
}

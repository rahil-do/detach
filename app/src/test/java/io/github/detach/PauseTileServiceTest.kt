package io.github.detach

import android.app.AlertDialog
import android.content.Context
import android.hardware.biometrics.BiometricManager
import android.service.quicksettings.Tile
import android.widget.Button
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlertDialog
import org.robolectric.shadows.ShadowBiometricManager
import org.robolectric.shadows.ShadowTileService
import org.robolectric.shadow.api.Shadow

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class PauseTileServiceTest {
    private val context get() = RuntimeEnvironment.getApplication()

    @Before fun reset() {
        for (name in listOf("settings", "lock", "pause")) context.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear().commit()
        setRunning(false)
        Shadow.extract<ShadowBiometricManager>(context.getSystemService(BiometricManager::class.java)).setCanAuthenticate(false)
    }

    @After fun cleanup() = setRunning(false)

    private fun setRunning(running: Boolean) {
        DetachService::class.java.getDeclaredField("instance").apply { isAccessible = true }
            .set(null, if (running) Robolectric.buildService(DetachService::class.java).get() else null)
    }

    private fun tile() = Robolectric.buildService(PauseTileService::class.java).create().get()

    private fun pause() {
        context.getSharedPreferences("pause", Context.MODE_PRIVATE).edit()
            .putLong("until", System.currentTimeMillis() + 60_000L).commit()
    }

    private fun tap(service: PauseTileService): String? {
        service.onClick()
        return shadowOf(context).nextStartedActivity?.component?.className
    }

    @Test fun `a tap opens the same confirmation as the notification and never pauses by itself`() {
        setRunning(true)
        assertEquals(PauseActivity::class.java.name, tap(tile()))
        assertFalse(Pause.isPending(context))
        assertFalse(context.packageManager.getActivityInfo(
            android.content.ComponentName(context, PauseActivity::class.java), 0).exported)
    }

    @Test fun `on the lock screen the phone is unlocked before the confirmation opens`() {
        setRunning(true)
        val service = tile()
        val shadow = Shadow.extract<ShadowTileService>(service)
        shadow.setLocked(true)
        assertEquals(PauseActivity::class.java.name, tap(service))
        assertFalse(service.isLocked)
        assertFalse(Pause.isPending(context))
    }

    @Test fun `while paused or switched off a tap opens Detach instead of another pause`() {
        assertEquals(MainActivity::class.java.name, tap(tile()))
        setRunning(true)
        pause()
        assertEquals(MainActivity::class.java.name, tap(tile()))
    }

    @Config(sdk = [35])
    @Test fun `Android 14 and later still reach only the confirmation screen`() {
        setRunning(true)
        assertEquals(PauseActivity::class.java.name, tap(tile()))
        assertFalse(Pause.isPending(context))
    }

    @Test fun `the tile shows whether protection is paused`() {
        setRunning(true)
        val service = tile()
        service.onStartListening()
        assertEquals(Tile.STATE_INACTIVE, service.qsTile.state)
        assertEquals("Pause for payment", service.qsTile.label.toString())
        assertEquals("Before you pay", service.qsTile.subtitle.toString())
        pause()
        service.onStartListening()
        assertEquals(Tile.STATE_ACTIVE, service.qsTile.state)
        assertEquals("Paused", service.qsTile.subtitle.toString())
    }

    @Test fun `the confirmation the tile opens still requires the Detach password`() {
        Lock(context).set("secret")
        setRunning(true)
        val activity = Robolectric.buildActivity(PauseActivity::class.java, PauseTileService.target(context)).setup().get()
        val button = activity.window.decorView.let { root ->
            fun find(view: android.view.View): Button? = when (view) {
                is Button -> view.takeIf { it.text == "Pause protection" }
                is android.view.ViewGroup -> (0 until view.childCount).firstNotNullOfOrNull { find(view.getChildAt(it)) }
                else -> null
            }
            find(root)!!
        }
        button.performClick()
        val dialog = ShadowAlertDialog.getLatestAlertDialog()
        assertEquals("Enter password", shadowOf(dialog).title.toString())
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
        assertFalse(Pause.isPending(context))
    }
}

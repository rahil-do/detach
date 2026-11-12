package io.github.detach

import android.app.Activity
import android.app.AlertDialog
import android.app.AppOpsManager
import android.content.Context
import android.content.Intent
import android.hardware.biometrics.BiometricManager
import android.os.CountDownTimer
import android.provider.Settings as SystemSettings
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Button
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
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
import org.robolectric.shadow.api.Shadow
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@Suppress("DEPRECATION")
class UiTest {
    private val context get() = RuntimeEnvironment.getApplication()

    @Before fun reset() {
        for (name in listOf("settings", "lock", "pause")) context.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear().commit()
        SystemSettings.Global.putFloat(context.contentResolver, SystemSettings.Global.ANIMATOR_DURATION_SCALE, 0f)
        SystemSettings.Secure.putString(context.contentResolver, SystemSettings.Secure.ENABLED_ACCESSIBILITY_SERVICES, "")
        Shadow.extract<ShadowBiometricManager>(context.getSystemService(BiometricManager::class.java)).setCanAuthenticate(false)
    }

    @Test fun `all three Instagram allowances remain independently editable`() {
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        disclosure(activity, "instagram-options").performClick()
        val settings = Settings(activity)
        toggle(activity, "Allow Stories").performClick()
        assertFalse(settings.igStories)
        assertTrue(settings.igMessages)
        assertTrue(settings.igSharedReels)
        toggle(activity, "Allow DMs").performClick()
        assertFalse(settings.igMessages)
        assertTrue(settings.igSharedReels)
        toggle(activity, "Allow reels sent in DMs").performClick()
        assertFalse(settings.igSharedReels)
    }

    @Test fun `master protection disables its child controls without changing stored allowances`() {
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        disclosure(activity, "instagram-options").performClick()
        toggle(activity, "Instagram protection").performClick()
        assertFalse(Settings(activity).igEnabled)
        assertFalse(toggle(activity, "Allow Stories").isEnabled)
        assertTrue(Settings(activity).igStories)
        toggle(activity, "Instagram protection").performClick()
        assertTrue(toggle(activity, "Allow Stories").isEnabled)
    }

    @Test fun `protected switches revert until authentication succeeds`() {
        Lock(context).set("protected")
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        val control = toggle(activity, "YouTube protection")
        control.performClick()
        assertTrue(control.isChecked)
        assertTrue(Settings(activity).ytShorts)
        val dialog = ShadowAlertDialog.getLatestAlertDialog()
        assertNotNull(dialog)
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
        assertTrue(Settings(activity).ytShorts)
    }

    @Test fun `payment selection and reminder adjustments are also password protected`() {
        Lock(context).set("protected")
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        disclosure(activity, "payment-options").performClick()
        button(activity, "Choose payment & banking apps").performClick()
        var dialog = ShadowAlertDialog.getLatestAlertDialog()
        assertEquals("Enter password", shadowOf(dialog).title.toString())
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
        val before = Settings(activity).pauseMinutes
        views(activity).first { it.contentDescription == "Increase payment pause by one minute" }.performClick()
        assertEquals(before, Settings(activity).pauseMinutes)
        dialog = ShadowAlertDialog.getLatestAlertDialog()
        assertEquals("Enter password", shadowOf(dialog).title.toString())
    }

    @Test fun `disclosures keep the same accessible control when toggled`() {
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        val header = disclosure(activity, "instagram-options")
        assertFalse(header.isSelected)
        header.performClick()
        assertTrue(header.isSelected)
        assertSame(header, disclosure(activity, "instagram-options"))
        header.performClick()
        assertFalse(header.isSelected)
        assertSame(header, disclosure(activity, "instagram-options"))
    }

    @Test fun `disclosures expose working accessibility expand and collapse actions`() {
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        val header = disclosure(activity, "instagram-options")
        assertTrue(header.performAccessibilityAction(AccessibilityNodeInfo.ACTION_EXPAND, null))
        assertTrue(header.isSelected)
        assertTrue(header.createAccessibilityNodeInfo().actionList.contains(AccessibilityNodeInfo.AccessibilityAction.ACTION_COLLAPSE))
        assertFalse(header.performAccessibilityAction(AccessibilityNodeInfo.ACTION_EXPAND, null))
        assertTrue(header.isSelected)
        assertTrue(header.performAccessibilityAction(AccessibilityNodeInfo.ACTION_COLLAPSE, null))
        assertFalse(header.isSelected)
    }

    @Test fun `expanded sections and scroll survive a settings rerender and recreation`() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        var activity = controller.get()
        disclosure(activity, "instagram-options").performClick()
        disclosure(activity, "payment-options").performClick()
        layout(activity)
        var scroll = views(activity).filterIsInstance<ScrollView>().first()
        scroll.scrollTo(0, 400)
        assertEquals(400, scroll.scrollY)
        views(activity).first { it.contentDescription == "Increase payment pause by one minute" }.performClick()
        layout(activity)
        assertTrue(disclosure(activity, "instagram-options").isSelected)
        assertTrue(disclosure(activity, "payment-options").isSelected)
        assertEquals(400, scroll.scrollY)
        controller.recreate()
        activity = controller.get()
        layout(activity)
        scroll = views(activity).filterIsInstance<ScrollView>().first()
        assertTrue(disclosure(activity, "instagram-options").isSelected)
        assertTrue(disclosure(activity, "payment-options").isSelected)
        assertEquals(400, scroll.scrollY)
    }

    @Test fun `reminder stepper exposes its bounds and never wraps`() {
        Settings(context).pauseMinutes = 1
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        disclosure(activity, "payment-options").performClick()
        assertFalse(views(activity).first { it.contentDescription == "Decrease payment pause by one minute" }.isEnabled)
        views(activity).first { it.contentDescription == "Increase payment pause by one minute" }.performClick()
        assertEquals(2, Settings(activity).pauseMinutes)
        assertTrue(disclosure(activity, "payment-options").isSelected)
    }

    @Test fun `auto pause can be changed without modifying selected payment apps`() {
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        disclosure(activity, "payment-options").performClick()
        val before = Settings(activity).paymentPackages
        toggle(activity, "Auto-pause for payment apps").performClick()
        assertFalse(Settings(activity).autoPausePayments)
        assertEquals(before, Settings(activity).paymentPackages)
    }

    @Test fun `foreground monitoring stays opt in until disclosure is accepted with both grants`() {
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        disclosure(activity, "payment-options").performClick()
        toggle(activity, "Resume when Instagram or YouTube opens").performClick()
        assertFalse(Settings(activity).resumeOnSocialForeground)
        val dialog = ShadowAlertDialog.getLatestAlertDialog()
        assertEquals("Enable local app detection?", shadowOf(dialog).title.toString())
        assertEquals("Review setup", dialog.getButton(AlertDialog.BUTTON_POSITIVE).text.toString())
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
        assertFalse(Settings(activity).resumeOnSocialForeground)
    }

    @Test fun `monitor consent is revoked when the settings visit ends`() {
        shadowOf(context).grantPermissions(android.Manifest.permission.WRITE_SECURE_SETTINGS)
        shadowOf(context.getSystemService(AppOpsManager::class.java)).setMode(
            AppOpsManager.OPSTR_GET_USAGE_STATS, context.applicationInfo.uid, context.packageName, AppOpsManager.MODE_ALLOWED,
        )
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        val activity = controller.get()
        disclosure(activity, "payment-options").performClick()
        toggle(activity, "Resume when Instagram or YouTube opens").performClick()
        val dialog = ShadowAlertDialog.getLatestAlertDialog()
        val enable = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
        assertEquals("Enable", enable.text.toString())
        controller.pause().stop()
        controller.start().resume().visible()
        enable.performClick()
        assertFalse(dialog.isShowing)
        assertFalse(Settings(activity).resumeOnSocialForeground)
    }

    @Test fun `accepted monitor consent changes only the opt in without starting a watcher`() {
        shadowOf(context).grantPermissions(android.Manifest.permission.WRITE_SECURE_SETTINGS)
        shadowOf(context.getSystemService(AppOpsManager::class.java)).setMode(
            AppOpsManager.OPSTR_GET_USAGE_STATS, context.applicationInfo.uid, context.packageName, AppOpsManager.MODE_ALLOWED,
        )
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        disclosure(activity, "payment-options").performClick()
        toggle(activity, "Resume when Instagram or YouTube opens").performClick()
        ShadowAlertDialog.getLatestAlertDialog().getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        shadowOf(android.os.Looper.getMainLooper()).idle()
        assertTrue(Settings(activity).resumeOnSocialForeground)
        assertFalse(Pause.isPending(activity))
        assertNull(shadowOf(context).nextStartedService)
    }

    @Test fun `paused status offers explicit resume and does not show setup as an error`() {
        context.getSharedPreferences("pause", Context.MODE_PRIVATE).edit()
            .putLong("until", System.currentTimeMillis() + 60_000).commit()
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        assertTrue(views(activity).filterIsInstance<TextView>().any { it.text == "Protection is paused" })
        assertFalse(views(activity).filterIsInstance<TextView>().any { it.text == "Set up protection" })
        button(activity, "Resume protection").performClick()
        assertEquals(SystemSettings.ACTION_ACCESSIBILITY_SETTINGS, shadowOf(activity).nextStartedActivity.action)
        assertTrue(Pause.isPending(activity))
        assertTrue(context.getSharedPreferences("pause", Context.MODE_PRIVATE).getLong("resume_authorized_at", 0) > 0)
    }

    @Test fun `diagnostics notification cold launch reveals its report without resuming`() {
        context.getSharedPreferences("pause", Context.MODE_PRIVATE).edit()
            .putLong("until", System.currentTimeMillis() + 60_000).commit()
        val intent = Intent(context, MainActivity::class.java).setAction(Health.ACTION_DIAGNOSTICS)
        val activity = Robolectric.buildActivity(MainActivity::class.java, intent).setup().get()
        layout(activity)
        assertTrue(disclosure(activity, "diagnostic-info").isSelected)
        assertTrue(views(activity).filterIsInstance<ScrollView>().first().scrollY > 0)
        assertTrue(Pause.isPending(activity))
        assertEquals(0L, context.getSharedPreferences("pause", Context.MODE_PRIVATE).getLong("resume_authorized_at", 0))
        assertEquals("", SystemSettings.Secure.getString(context.contentResolver, SystemSettings.Secure.ENABLED_ACCESSIBILITY_SERVICES))
    }

    @Test fun `diagnostics notification to an existing activity reveals its report without resuming`() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        val activity = controller.get()
        layout(activity)
        assertFalse(disclosure(activity, "diagnostic-info").isSelected)
        controller.pause().newIntent(Intent(activity, MainActivity::class.java).setAction(Health.ACTION_DIAGNOSTICS)).resume()
        layout(activity)
        assertTrue(disclosure(activity, "diagnostic-info").isSelected)
        assertTrue(views(activity).filterIsInstance<ScrollView>().first().scrollY > 0)
        assertEquals("", SystemSettings.Secure.getString(context.contentResolver, SystemSettings.Secure.ENABLED_ACCESSIBILITY_SERVICES))
    }

    @Test fun `unlock without credentials retains the full wait and enables only on completion`() {
        val activity = Robolectric.buildActivity(UnlockActivity::class.java).setup().get()
        val action = views(activity).filterIsInstance<Button>().first { it.text.toString().startsWith("Wait") }
        val timer = shadowOf(ReflectionHelpers.getField<CountDownTimer>(activity, "timer"))
        assertTrue(timer.hasStarted())
        assertEquals(30_000L, timer.millisInFuture)
        assertEquals(1_000L, timer.countDownInterval)
        assertFalse(action.isEnabled)
        timer.invokeTick(1_000)
        assertFalse(action.isEnabled)
        assertEquals("Wait 1", action.text.toString())
        timer.invokeFinish()
        assertTrue(action.isEnabled)
        assertEquals("Continue", action.text.toString())
    }

    @Test fun `leaving unlock cancels the wait instead of completing in the background`() {
        val controller = Robolectric.buildActivity(UnlockActivity::class.java).setup()
        val activity = controller.get()
        val timer = shadowOf(ReflectionHelpers.getField<CountDownTimer>(activity, "timer"))
        assertTrue(timer.hasStarted())
        controller.pause().stop()
        assertFalse(timer.hasStarted())
        assertTrue(activity.isFinishing)
    }

    @Test fun `password field remains masked and excluded from autofill after styling`() {
        val field = passwordEditText(context, "Password")
        field.setText("secret")
        assertSame(Asterisks, field.transformationMethod)
        assertEquals("******", field.transformationMethod.getTransformation(field.text, field).toString())
        assertEquals(View.IMPORTANT_FOR_AUTOFILL_NO, field.importantForAutofill)
        assertTrue(field.minimumHeight >= Ui(context).dp(48))
    }

    @Test fun `disabled system animations leave entrance content immediately visible`() {
        val view = View(context).apply { alpha = 0.2f; translationY = 50f }
        assertFalse(Motion.enabled(context))
        Motion.enter(view, 1000)
        assertEquals(1f, view.alpha, 0f)
        assertEquals(0f, view.translationY, 0f)
    }

    @Test fun `enabled entrance motion is short and its stagger is bounded`() {
        SystemSettings.Global.putFloat(context.contentResolver, SystemSettings.Global.ANIMATOR_DURATION_SCALE, 0.5f)
        val view = View(context)
        assertTrue(Motion.enabled(context))
        Motion.enter(view, 5000)
        assertEquals(220L, view.animate().duration)
        assertEquals(80L, view.animate().startDelay)
        assertTrue(view.translationY <= 8 * context.resources.displayMetrics.density)
        SystemSettings.Global.putFloat(context.contentResolver, SystemSettings.Global.ANIMATOR_DURATION_SCALE, 0f)
        Motion.enter(view)
        assertEquals(1f, view.alpha, 0f)
        assertEquals(0f, view.translationY, 0f)
    }

    @Test fun `controls maintain accessible touch targets and descriptive labels`() {
        val ui = Ui(context)
        assertTrue(ui.primary("Continue") { }.minimumHeight >= ui.dp(48))
        assertTrue(ui.secondary("Cancel") { }.minimumHeight >= ui.dp(48))
        assertTrue(ui.quiet("Details") { }.minimumHeight >= ui.dp(48))
        assertTrue(ui.disclosure("Details", false) { }.minimumHeight >= ui.dp(48))
        val toggle = ui.toggle(true, "Block Shorts")
        assertTrue(toggle.minimumHeight >= ui.dp(48))
        assertEquals("Block Shorts", toggle.contentDescription)
    }

    @Test @Config(qualifiers = "night") fun `dark palette is deliberate and readable`() {
        val ui = Ui(context)
        assertNotEquals(ui.bg, ui.fg)
        assertNotEquals(ui.bg, ui.accent)
        assertTrue(android.graphics.Color.luminance(ui.fg) > android.graphics.Color.luminance(ui.bg))
        assertTrue(android.graphics.Color.luminance(ui.sub) > 0.3f)
    }

    @Test fun `payment preferences disclose the password bypass and manual resume tradeoff`() {
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        disclosure(activity, "payment-options").performClick()
        val notice = views(activity).filterIsInstance<TextView>().first { it.text.contains("bypasses your password") }
        assertEquals(View.VISIBLE, notice.visibility)
        assertTrue(notice.text.contains("until protection resumes"))
        assertTrue(notice.text.contains("embedded payments are not"))
    }

    @Test fun `enabled accessibility without a connected service never claims active protection`() {
        SystemSettings.Secure.putString(context.contentResolver, SystemSettings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            "io.github.detach/io.github.detach.DetachService")
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        val labels = views(activity).filterIsInstance<TextView>().map { it.text.toString() }
        assertTrue(labels.contains("Waiting for Android"))
        assertFalse(labels.contains("Protection is on"))
        assertFalse(labels.contains("Pause protection for payment"))
    }

    private fun views(activity: Activity): List<View> {
        fun descend(view: View): List<View> = listOf(view) + if (view is ViewGroup) {
            (0 until view.childCount).flatMap { descend(view.getChildAt(it)) }
        } else emptyList()
        return descend(activity.window.decorView)
    }

    private fun toggle(activity: Activity, label: String) = views(activity).filterIsInstance<Switch>().first { it.contentDescription == label }
    private fun button(activity: Activity, label: String) = views(activity).filterIsInstance<Button>().first { it.text == label }
    private fun disclosure(activity: Activity, key: String) = views(activity).first { it.tag == key }

    private fun layout(activity: Activity) {
        val decor = activity.window.decorView
        decor.measure(View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(1600, View.MeasureSpec.EXACTLY))
        decor.layout(0, 0, 1080, 1600)
        decor.viewTreeObserver.dispatchOnPreDraw()
    }
}

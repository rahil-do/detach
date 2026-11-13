package io.github.detach

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.ResolveInfo
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.BitmapDrawable
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.CheckBox
import android.widget.Checkable
import android.widget.EditText
import android.widget.ImageView
import android.widget.ListView
import android.widget.TextView
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
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowAlertDialog
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@Suppress("DEPRECATION")
class PaymentAppPickerTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val manager get() = context.packageManager
    private val launcher get() = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)

    @Before fun reset() {
        context.getSharedPreferences("settings", Context.MODE_PRIVATE).edit().clear().commit()
        shadowOf(manager).setResolveInfosForIntent(launcher, emptyList())
    }

    @Test fun `catalog contains real installed application names once in alphabetical order`() {
        install("bank.zebra", "Zebra Bank")
        install("bank.alpha", "Alpha Bank")
        addLauncher("bank.alpha", "Duplicate launcher label")
        val apps = PaymentAppPicker.loadApps(manager, context.packageName, emptySet())
        assertEquals(listOf("Alpha Bank", "Zebra Bank"), apps.map { it.name })
        assertEquals(2, apps.size)
    }

    @Test fun `uninstalled defaults and saved packages never become package label rows`() {
        install("bank.actual", "Actual Bank")
        val apps = PaymentAppPicker.loadApps(manager, context.packageName, setOf("bank.uninstalled"))
        assertEquals(listOf("Actual Bank"), apps.map { it.name })
        assertTrue(apps.none { it.name == it.info.packageName })
    }

    @Test fun `installed saved apps without launcher activities can still be selected`() {
        install("bank.hidden", "Hidden Bank", launchable = false)
        val apps = PaymentAppPicker.loadApps(manager, context.packageName, setOf("bank.hidden"))
        assertEquals(listOf("Hidden Bank"), apps.map { it.name })
    }

    @Test fun `blocked packages blank names package fallback labels and absent icons are omitted`() {
        install(Targets.INSTAGRAM, "Instagram")
        install(Targets.SYSTEM_UI.first(), "System UI")
        install("bank.blank", " ")
        install("bank.fallback", "bank.fallback")
        install("bank.packageish", "different.package.label")
        install("bank.noicon", "No Icon", icon = 0)
        install("bank.removed", "Removed", installed = false)
        addLauncher(context.packageName, "Detach")
        assertEquals(emptyList<String>(), PaymentAppPicker.loadApps(manager, context.packageName, emptySet()).map { it.name })
    }

    @Test @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun `icon raster preserves authentic local colors and aspect ratio`() {
        val app = install("bank.colors", "Color Bank")
        val bitmap = PaymentAppPicker.loadIcon(manager, app, 48)
        assertNotNull(bitmap)
        assertEquals(48, bitmap!!.width)
        assertEquals(48, bitmap.height)
        assertEquals(Color.RED, bitmap.getPixel(12, 24))
        assertEquals(Color.BLUE, bitmap.getPixel(36, 24))
        assertEquals(Color.TRANSPARENT, bitmap.getPixel(12, 2))
    }

    @Test fun `missing authentic icon is not replaced with a generic app logo`() {
        val app = install("bank.badicon", "Bad Icon", icon = 12345)
        assertNull(PaymentAppPicker.loadIcon(manager, app, 48))
    }

    @Test fun `save keeps unresolved selections and adds only explicitly selected installed apps`() {
        install("bank.actual", "Actual Bank")
        val saved = setOf("bank.uninstalled", Targets.PAYMENT_DEFAULTS.first())
        Settings(context).paymentPackages = saved
        val activity = activity()
        var savedCalls = 0
        PaymentAppPicker.show(activity) { savedCalls++ }
        val dialog = loadedDialog()
        val list = list(dialog)
        click(list, 0)
        assertEquals(saved, Settings(context).paymentPackages)
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        assertEquals(saved + "bank.actual", Settings(context).paymentPackages)
        assertEquals(1, savedCalls)
        assertFalse(dialog.isShowing)
    }

    @Test fun `cancel leaves all original selections untouched and does not call onSaved`() {
        install("bank.actual", "Actual Bank")
        Settings(context).paymentPackages = setOf("bank.uninstalled")
        val activity = activity()
        var savedCalls = 0
        PaymentAppPicker.show(activity) { savedCalls++ }
        val dialog = loadedDialog()
        click(list(dialog), 0)
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
        assertEquals(setOf("bank.uninstalled"), Settings(context).paymentPackages)
        assertEquals(0, savedCalls)
    }

    @Test fun `name search is case insensitive and never searches package identifiers`() {
        install("secret.identifier.alpha", "Alpha Bank")
        install("secret.identifier.beta", "Beta Wallet")
        val activity = activity()
        PaymentAppPicker.show(activity) {}
        val dialog = loadedDialog()
        val list = list(dialog)
        val search = dialog.window!!.decorView.findViewWithTag<EditText>("payment-app-search")
        search.setText("  ALPHA  ")
        assertEquals(1, list.adapter.count)
        assertEquals("Alpha Bank", row(list, 0).contentDescription)
        search.setText("secret.identifier")
        assertEquals(0, list.adapter.count)
        assertEquals("No matching apps", status(dialog).text)
        search.setText("")
        assertEquals(2, list.adapter.count)
        dialog.dismiss()
    }

    @Test fun `filter changes and recycled rows retain checkbox selection by package`() {
        install("bank.alpha", "Alpha Bank")
        install("bank.beta", "Beta Bank")
        Settings(context).paymentPackages = setOf("bank.alpha")
        val activity = activity()
        PaymentAppPicker.show(activity) {}
        val dialog = loadedDialog()
        val list = list(dialog)
        val recycled = row(list, 0)
        assertTrue((recycled as Checkable).isChecked)
        val rebound = list.adapter.getView(1, recycled, list)
        assertFalse((rebound as Checkable).isChecked)
        val search = dialog.window!!.decorView.findViewWithTag<EditText>("payment-app-search")
        search.setText("Beta")
        click(list, 0)
        search.setText("Alpha")
        click(list, 0)
        search.setText("")
        assertFalse((row(list, 0) as Checkable).isChecked)
        assertTrue((row(list, 1) as Checkable).isChecked)
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        assertEquals(setOf("bank.beta"), Settings(context).paymentPackages)
    }

    @Test fun `rows expose app name and checkbox state with native minimum touch sizes`() {
        install("bank.actual", "Actual Bank")
        Settings(context).paymentPackages = setOf("bank.actual")
        val activity = activity()
        PaymentAppPicker.show(activity) {}
        val dialog = loadedDialog()
        val decor = dialog.window!!.decorView
        decor.measure(
            View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(1800, View.MeasureSpec.EXACTLY),
        )
        decor.layout(0, 0, 1080, 1800)
        val row = list(dialog).getChildAt(0)
        val node = row.createAccessibilityNodeInfo()
        assertEquals("Actual Bank", node.contentDescription)
        assertTrue(node.isCheckable)
        assertTrue(node.isChecked)
        assertEquals(CheckBox::class.java.name, node.className)
        val minimum = (48 * activity.resources.displayMetrics.density).toInt()
        assertTrue(row.minimumHeight >= minimum)
        val checkbox = descendants(row).filterIsInstance<CheckBox>().single()
        assertTrue(checkbox.minimumWidth >= minimum)
        assertTrue(checkbox.minimumHeight >= minimum)
        assertFalse(checkbox.isFocusable)
        assertFalse(checkbox.isClickable)
        assertTrue(descendants(row).filterIsInstance<TextView>().none { "bank.actual" in it.text })
        assertNull(descendants(row).filterIsInstance<ImageView>().single().imageTintList)
        assertTrue(dialog.getButton(AlertDialog.BUTTON_POSITIVE).minimumHeight >= minimum)
        assertTrue(row.performAccessibilityAction(AccessibilityNodeInfo.ACTION_CLICK, null))
        assertFalse((row as Checkable).isChecked)
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        assertEquals(emptySet<String>(), Settings(activity).paymentPackages)
    }

    @Test fun `failed icon loading removes its row without deleting the saved selection`() {
        install("bank.badicon", "Bad Icon", icon = 12345)
        Settings(context).paymentPackages = setOf("bank.badicon")
        val activity = activity()
        PaymentAppPicker.show(activity) {}
        val dialog = loadedDialog()
        val list = list(dialog)
        if (list.adapter.count > 0) row(list, 0)
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (list.adapter.count != 0 && System.nanoTime() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.yield()
        }
        assertEquals(0, list.adapter.count)
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        assertEquals(setOf("bank.badicon"), Settings(activity).paymentPackages)
    }

    @Test fun `empty catalog may save without deleting unavailable saved selections`() {
        Settings(context).paymentPackages = setOf("bank.uninstalled")
        val activity = activity()
        PaymentAppPicker.show(activity) {}
        val dialog = loadedDialog()
        assertEquals(0, list(dialog).adapter.count)
        assertEquals("No installed apps available", status(dialog).text)
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        assertEquals(setOf("bank.uninstalled"), Settings(context).paymentPackages)
    }

    @Test fun `destroyed host dismisses picker without saving or delivering callbacks`() {
        install("bank.actual", "Actual Bank")
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        controller.get().setTheme(R.style.Theme_Detach)
        var savedCalls = 0
        PaymentAppPicker.show(controller.get()) { savedCalls++ }
        val dialog = ShadowAlertDialog.getLatestAlertDialog()
        controller.pause().stop().destroy()
        shadowOf(Looper.getMainLooper()).idle()
        assertFalse(dialog.isShowing)
        assertEquals(0, savedCalls)
        assertNull(list(dialog).adapter)
    }

    @Test fun `stopping host revokes a loaded picker and stale Save cannot change selections`() {
        install("bank.actual", "Actual Bank")
        val before = Settings(context).paymentPackages
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        controller.get().setTheme(R.style.Theme_Detach)
        var savedCalls = 0
        PaymentAppPicker.show(controller.get()) { savedCalls++ }
        val dialog = loadedDialog()
        val save = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
        val appList = list(dialog)
        appList.performItemClick(appList.adapter.getView(0, null, appList), 0, 0L)
        controller.pause().stop()
        controller.start().resume().visible()
        save.performClick()
        assertFalse(dialog.isShowing)
        assertEquals(before, Settings(context).paymentPackages)
        assertEquals(0, savedCalls)
    }

    @Test fun `dismiss during loading drops late results and does not save`() {
        repeat(40) { index -> install("bank.item$index", "Bank $index") }
        val activity = activity()
        var savedCalls = 0
        PaymentAppPicker.show(activity) { savedCalls++ }
        val dialog = ShadowAlertDialog.getLatestAlertDialog()
        assertFalse(dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled)
        dialog.cancel()
        shadowOf(Looper.getMainLooper()).idle()
        assertNull(list(dialog).adapter)
        assertEquals(0, savedCalls)
    }

    @Test @Config(qualifiers = "night")
    fun `night theme keeps native controls readable without tinting app logos`() {
        install("bank.actual", "Actual Bank")
        val activity = activity()
        PaymentAppPicker.show(activity) {}
        val dialog = loadedDialog()
        val row = row(list(dialog), 0)
        val label = descendants(row).filterIsInstance<TextView>().first { it.text == "Actual Bank" }
        assertEquals(activity.getColor(R.color.ink), label.currentTextColor)
        assertEquals(activity.getColor(R.color.on_accent), dialog.getButton(AlertDialog.BUTTON_POSITIVE).currentTextColor)
        assertEquals(activity.getColor(R.color.accent), dialog.getButton(AlertDialog.BUTTON_NEGATIVE).currentTextColor)
        assertNull(descendants(row).filterIsInstance<ImageView>().single().imageTintList)
        dialog.dismiss()
    }

    @Test fun `the picker asks for the apps you pay from and saves them into the event filter`() {
        install("com.example.checkout", "Checkout Example")
        PaymentAppPicker.show(activity()) {}
        val dialog = loadedDialog()
        assertEquals("Payment apps", shadowOf(dialog).title.toString())
        val hint = dialog.window!!.decorView.findViewWithTag<TextView>("payment-app-hint")
        assertEquals(View.VISIBLE, hint.visibility)
        assertTrue(hint.text.contains("apps you pay from"))
        click(list(dialog), 0)
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        assertTrue("com.example.checkout" in Settings(context).paymentPackages)
        assertTrue("com.example.checkout" in Settings(context).eventPackages())
        assertTrue(Pause.shouldAutoPausePayment(context, "com.example.checkout"))
    }

    private fun activity() = Robolectric.buildActivity(Activity::class.java).setup().get().apply {
        setTheme(R.style.Theme_Detach)
    }

    private fun loadedDialog(): AlertDialog {
        val dialog = ShadowAlertDialog.getLatestAlertDialog()
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (!dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled && System.nanoTime() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.yield()
        }
        assertTrue("Picker did not finish loading: ${status(dialog).text}", dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled)
        return dialog
    }

    private fun list(dialog: AlertDialog) = dialog.window!!.decorView.findViewWithTag<ListView>("payment-app-list")
    private fun status(dialog: AlertDialog) = dialog.window!!.decorView.findViewWithTag<TextView>("payment-app-status")
    private fun row(list: ListView, position: Int) = list.adapter.getView(position, null, list)
    private fun click(list: ListView, position: Int) {
        list.performItemClick(row(list, position), position, list.adapter.getItemId(position))
    }

    private fun descendants(view: View): List<View> = listOf(view) + if (view is ViewGroup) {
        (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) }
    } else emptyList()

    private fun install(
        packageName: String,
        name: String,
        launchable: Boolean = true,
        icon: Int = R.drawable.ic_mark,
        installed: Boolean = true,
    ): PaymentAppPicker.App {
        val info = ApplicationInfo().apply {
            this.packageName = packageName
            nonLocalizedLabel = name
            this.icon = icon
            flags = if (installed) ApplicationInfo.FLAG_INSTALLED else 0
            sourceDir = context.applicationInfo.sourceDir
            publicSourceDir = context.applicationInfo.publicSourceDir
        }
        shadowOf(manager).installPackage(PackageInfo().apply {
            this.packageName = packageName
            applicationInfo = info
        })
        if (icon == R.drawable.ic_mark) {
            val pixels = IntArray(48 * 24) { index -> if (index % 48 < 24) Color.RED else Color.BLUE }
            val bitmap = Bitmap.createBitmap(pixels, 48, 24, Bitmap.Config.ARGB_8888)
            shadowOf(manager).addDrawableResolution(packageName, icon, BitmapDrawable(context.resources, bitmap))
        }
        if (launchable) addLauncher(packageName, name)
        if (!installed) {
            shadowOf(manager).getInternalMutablePackageInfo(packageName).applicationInfo!!.flags = 0
        }
        return PaymentAppPicker.App(info, name)
    }

    private fun addLauncher(packageName: String, name: String) {
        shadowOf(manager).addResolveInfoForIntent(launcher, ResolveInfo().apply {
            nonLocalizedLabel = name
            activityInfo = ActivityInfo().apply {
                this.packageName = packageName
                this.name = "$packageName.Launcher"
                applicationInfo = manager.getApplicationInfo(packageName, 0)
            }
        })
    }
}

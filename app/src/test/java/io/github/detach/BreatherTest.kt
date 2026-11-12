package io.github.detach

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.hardware.biometrics.BiometricManager
import android.hardware.biometrics.BiometricPrompt
import android.os.CancellationSignal
import android.os.Build
import android.os.Looper
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowAlertDialog
import org.robolectric.shadows.ShadowBiometricManager
import org.robolectric.shadows.ShadowSystemClock
import org.robolectric.util.ReflectionHelpers
import java.lang.reflect.Proxy
import java.time.Duration
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import android.provider.Settings as SystemSettings

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class BreatherTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val stored get() = context.getSharedPreferences("breather", Context.MODE_PRIVATE)
    private val feed = setOf(Breather.Feature.IG_FEED)

    @Before fun reset() = BreatherTestSupport.reset()

    @Test fun `a configured password alone never authorizes a synchronous start`() {
        Lock(context).set(BreatherTestSupport.PASSWORD)
        assertFalse(Breather.begin(context, 5, feed))
        assertEquals(0L, Breather.remainingMs(context))
        assertFalse(Breather.allows(context, Breather.Feature.IG_FEED))
    }

    @Test fun `missing authentication cannot exploit Auth silent success`() {
        var result: Boolean? = null
        Breather.confirmAndBegin(BreatherTestSupport.activity(), 5, feed) { result = it }
        assertEquals(false, result)
        assertFalse(Breather.begin(context, 5, feed))
        assertEquals(0L, Breather.remainingMs(context))
        assertTrue(stored.all.isEmpty())
    }

    @Test @Config(sdk = [28])
    fun `Android 9 requires and accepts the Detach password`() {
        var unprotected: Boolean? = null
        val activity = BreatherTestSupport.activity()
        Breather.confirmAndBegin(activity, 5, feed) { unprotected = it }
        assertEquals(false, unprotected)
        Lock(context).set(BreatherTestSupport.PASSWORD)
        assertEquals(true, BreatherTestSupport.start(activity, 5, feed))
        assertTrue(Breather.allows(context, Breather.Feature.IG_FEED))
    }

    @Test fun `only successfully entering the password starts a breather`() {
        Lock(context).set(BreatherTestSupport.PASSWORD)
        val activity = BreatherTestSupport.activity()
        var result: Boolean? = null
        Breather.confirmAndBegin(activity, 5, feed) { result = it }
        assertNull(result)
        assertFalse(Breather.allows(context, Breather.Feature.IG_FEED))
        BreatherTestSupport.enterPassword("wrong")
        assertNull(result)
        assertFalse(Breather.allows(context, Breather.Feature.IG_FEED))
        BreatherTestSupport.enterPassword(BreatherTestSupport.PASSWORD)
        assertEquals(true, result)
        assertTrue(Breather.remainingMs(context) in 299_000L..300_000L)
        assertTrue(Breather.allows(context, Breather.Feature.IG_FEED))
        assertFalse(Breather.begin(context, 60, Breather.Feature.entries.toSet()))
    }

    @Test fun `canceling authentication never opens a bypass`() {
        Lock(context).set(BreatherTestSupport.PASSWORD)
        Breather.confirmAndBegin(BreatherTestSupport.activity(), 5, feed) {}
        BreatherTestSupport.dialog().getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
        assertEquals(0L, Breather.remainingMs(context))
    }

    @Test @Config(shadows = [BreatherBiometricPrompt::class])
    fun `available biometrics alone cannot start until Android confirms success`() {
        val manager = Shadow.extract<ShadowBiometricManager>(context.getSystemService(BiometricManager::class.java))
        manager.setCanAuthenticate(true)
        manager.setAuthenticatorType(BiometricManager.Authenticators.BIOMETRIC_STRONG)
        var result: Boolean? = null
        Breather.confirmAndBegin(BreatherTestSupport.activity(), 5, feed) { result = it }
        assertNull(result)
        assertEquals(0L, Breather.remainingMs(context))
        BreatherBiometricPrompt.callback.onAuthenticationFailed()
        assertNull(result)
        assertEquals(0L, Breather.remainingMs(context))
        BreatherBiometricPrompt.callback.onAuthenticationSucceeded(null)
        assertEquals(true, result)
        assertTrue(Breather.allows(context, Breather.Feature.IG_FEED))
        assertFalse(Lock(context).isSet)
    }

    @Test @Config(shadows = [BreatherBiometricPrompt::class])
    fun `biometric success queued after host stop cannot start a breather on return`() {
        val manager = Shadow.extract<ShadowBiometricManager>(context.getSystemService(BiometricManager::class.java))
        manager.setCanAuthenticate(true)
        manager.setAuthenticatorType(BiometricManager.Authenticators.BIOMETRIC_STRONG)
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        var result: Boolean? = null
        Breather.confirmAndBegin(controller.get(), 5, feed) { result = it }
        controller.pause().stop()
        controller.start().resume().visible()
        BreatherBiometricPrompt.callback.onAuthenticationSucceeded(null)
        assertEquals(false, result)
        assertEquals(0L, Breather.remainingMs(context))
    }

    @Test @Config(shadows = [BreatherBiometricPrompt::class])
    fun `biometric failure without a password cannot silently authorize`() {
        val manager = Shadow.extract<ShadowBiometricManager>(context.getSystemService(BiometricManager::class.java))
        manager.setCanAuthenticate(true)
        manager.setAuthenticatorType(BiometricManager.Authenticators.BIOMETRIC_STRONG)
        var result: Boolean? = null
        Breather.confirmAndBegin(BreatherTestSupport.activity(), 5, feed) { result = it }
        BreatherBiometricPrompt.callback.onAuthenticationError(BiometricPrompt.BIOMETRIC_ERROR_LOCKOUT, "Locked out")
        assertNull(result)
        assertEquals(0L, Breather.remainingMs(context))
    }

    @Test fun `every supported feature can be selected independently`() {
        Lock(context).set(BreatherTestSupport.PASSWORD)
        val activity = BreatherTestSupport.activity()
        for (feature in Breather.Feature.entries) {
            assertEquals(true, BreatherTestSupport.start(activity, 5, setOf(feature)))
            assertEquals(setOf(feature), Breather.activeFeatures(context))
            for (candidate in Breather.Feature.entries) {
                assertEquals(candidate == feature, Breather.allows(context, candidate))
            }
        }
    }

    @Test fun `invalid durations and empty selections do not start or change a session`() {
        Lock(context).set(BreatherTestSupport.PASSWORD)
        val activity = BreatherTestSupport.activity()
        assertEquals(true, BreatherTestSupport.start(activity, 1, feed))
        for (minutes in listOf(Int.MIN_VALUE, -1, 0, 61, Int.MAX_VALUE)) {
            var result: Boolean? = null
            Breather.confirmAndBegin(activity, minutes, feed) { result = it }
            assertEquals(false, result)
            assertEquals(60_000L, stored.getLong("duration", 0))
            assertTrue(Breather.remainingMs(context) in 59_000L..60_000L)
        }
        var emptyResult: Boolean? = null
        Breather.confirmAndBegin(activity, 5, emptySet()) { emptyResult = it }
        assertEquals(false, emptyResult)
        assertEquals(feed, Breather.activeFeatures(context))
    }

    @Test fun `maximum duration expires exactly and never restarts itself`() {
        start(60)
        assertTrue(Breather.remainingMs(context) in 3_599_000L..3_600_000L)
        advance(Duration.ofMillis(3_599_000L))
        assertTrue(Breather.remainingMs(context) in 1L..1_000L)
        advance(Duration.ofMillis(1_000L))
        assertEquals(0L, Breather.remainingMs(context))
        assertFalse(Breather.allows(context, Breather.Feature.IG_FEED))
        assertTrue(stored.all.isEmpty())
    }

    @Test fun `deep sleep counts toward expiry`() {
        start(1)
        stored.edit().putLong("wall", stored.getLong("wall", 0) - 60_000L).commit()
        ShadowSystemClock.simulateDeepSleep(Duration.ofMinutes(1))
        assertEquals(0L, Breather.remainingMs(context))
    }

    @Test fun `extension requires new authentication and replaces rather than accumulates time`() {
        val activity = start(60)
        advance(Duration.ofMinutes(1))
        Breather.confirmAndBegin(activity, 60, setOf(Breather.Feature.YT_SHORTS)) {}
        assertTrue(Breather.remainingMs(context) in 3_539_000L..3_540_000L)
        assertFalse(Breather.allows(context, Breather.Feature.YT_SHORTS))
        BreatherTestSupport.enterPassword(BreatherTestSupport.PASSWORD)
        assertTrue(Breather.remainingMs(context) in 3_599_000L..3_600_000L)
        assertTrue(Breather.allows(context, Breather.Feature.YT_SHORTS))
        assertFalse(Breather.allows(context, Breather.Feature.IG_FEED))
    }

    @Test fun `end immediately revokes every selected feature`() {
        start(5, Breather.Feature.entries.toSet())
        Breather.end(context)
        assertEquals(0L, Breather.remainingMs(context))
        assertTrue(Breather.activeFeatures(context).isEmpty())
        assertTrue(Breather.Feature.entries.none { Breather.allows(context, it) })
        assertTrue(stored.all.isEmpty())
    }

    @Test fun `a changed boot count revokes access even before boot receiver delivery`() {
        start(5)
        SystemSettings.Global.putInt(context.contentResolver, SystemSettings.Global.BOOT_COUNT, 10)
        assertEquals(0L, Breather.remainingMs(context))
        assertFalse(Breather.allows(context, Breather.Feature.IG_FEED))
    }

    @Test fun `unavailable boot count cannot create a session`() {
        Lock(context).set(BreatherTestSupport.PASSWORD)
        SystemSettings.Global.putString(context.contentResolver, SystemSettings.Global.BOOT_COUNT, null)
        assertEquals(false, BreatherTestSupport.start(BreatherTestSupport.activity(), 5, feed))
        assertEquals(0L, Breather.remainingMs(context))
    }

    @Test fun `a session belonging to another process is rejected`() {
        start(5)
        stored.edit().putString("process", "previous-process").commit()
        assertEquals(0L, Breather.remainingMs(context))
    }

    @Test fun `wall clock jumps forward and backward fail closed`() {
        val activity = start(5)
        advance(Duration.ofMinutes(1))
        stored.edit().putLong("wall", stored.getLong("wall", 0) + 3_000L).commit()
        assertEquals(0L, Breather.remainingMs(context))
        assertEquals(true, BreatherTestSupport.start(activity, 5, feed))
        stored.edit().putLong("wall", stored.getLong("wall", 0) - 3_000L).commit()
        assertEquals(0L, Breather.remainingMs(context))
    }

    @Test fun `a tolerated wall adjustment cannot extend monotonic expiry`() {
        start(1)
        advance(Duration.ofSeconds(10))
        stored.edit().putLong("wall", stored.getLong("wall", 0) + 1_000L).commit()
        assertTrue(Breather.remainingMs(context) in 49_000L..50_000L)
        advance(Duration.ofSeconds(50))
        assertEquals(0L, Breather.remainingMs(context))
    }

    @Test fun `malformed missing and overflowing persisted fields fail closed`() {
        val activity = start(5)
        val corruptions: List<(SharedPreferences.Editor) -> Unit> = listOf(
            { it.remove("version") },
            { it.putInt("version", 2) },
            { it.putString("elapsed", "corrupt") },
            { it.putLong("elapsed", -1L) },
            { it.putLong("elapsed", SystemClock.elapsedRealtime() + 1) },
            { it.putLong("wall", Long.MIN_VALUE) },
            { it.putLong("wall", Long.MAX_VALUE) },
            { it.putLong("duration", Long.MAX_VALUE) },
            { it.putLong("duration", 60_001L) },
            { it.putString("boot", "corrupt") },
            { it.remove("boot") },
            { it.remove("process") },
            { it.putStringSet("features", setOf("UNKNOWN")) },
            { it.putStringSet("features", emptySet()) },
        )
        for (corrupt in corruptions) {
            assertEquals(true, BreatherTestSupport.start(activity, 5, feed))
            stored.edit().also(corrupt).commit()
            assertFalse(Breather.allows(context, Breather.Feature.IG_FEED))
            assertEquals(0L, Breather.remainingMs(context))
        }
    }

    @Test fun `starting and ending never change settings credentials grants or protection`() {
        Settings(context).apply {
            igStories = false
            igMessages = false
            igSharedReels = false
        }
        Lock(context).set(BreatherTestSupport.PASSWORD)
        context.getSharedPreferences("guard", Context.MODE_PRIVATE).edit().putLong("until", 123L).commit()
        val names = listOf("settings", "lock", "guard", "pause")
        val before = names.associateWith { context.getSharedPreferences(it, Context.MODE_PRIVATE).all.toMap() }
        val secure = "another.app/.Reader:io.github.detach/.DetachService"
        SystemSettings.Secure.putString(context.contentResolver, SystemSettings.Secure.ENABLED_ACCESSIBILITY_SERVICES, secure)
        val protectedPermissions = listOf(android.Manifest.permission.WRITE_SECURE_SETTINGS, android.Manifest.permission.USE_BIOMETRIC)
        val permissions = protectedPermissions.associateWith { context.checkSelfPermission(it) }
        assertEquals(true, BreatherTestSupport.start(BreatherTestSupport.activity(), 5, Breather.Feature.entries.toSet()))
        Breather.end(context)
        assertEquals(before, names.associateWith { context.getSharedPreferences(it, Context.MODE_PRIVATE).all.toMap() })
        assertEquals(secure, SystemSettings.Secure.getString(context.contentResolver, SystemSettings.Secure.ENABLED_ACCESSIBILITY_SERVICES))
        assertEquals(permissions, protectedPermissions.associateWith { context.checkSelfPermission(it) })
        assertNull(shadowOf(context).nextStartedService)
    }

    @Test fun `commit failure after in-memory writes revokes an existing session`() {
        Lock(context).set(BreatherTestSupport.PASSWORD)
        val activity = Robolectric.buildActivity(BreatherStorageActivity::class.java).setup().get()
        assertEquals(true, BreatherTestSupport.start(activity, 5, feed))
        activity.failWrites = true
        assertEquals(false, BreatherTestSupport.start(activity, 60, Breather.Feature.entries.toSet()))
        assertEquals(0L, Breather.remainingMs(context))
        assertFalse(Breather.allows(context, Breather.Feature.IG_FEED))
        activity.failWrites = false
        assertEquals(true, BreatherTestSupport.start(activity, 1, feed))
    }

    @Test fun `failed end commit remains closed even if stale preferences are visible`() {
        start(5)
        val failing = object : ContextWrapper(context) {
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
                val original = super.getSharedPreferences(name, mode)
                if (name != "breather") return original
                return object : SharedPreferences by original {
                    override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor by original.edit() {
                        override fun clear(): SharedPreferences.Editor = this
                        override fun commit(): Boolean = false
                    }
                }
            }
        }
        Breather.end(failing)
        assertFalse(stored.all.isEmpty())
        assertEquals(0L, Breather.remainingMs(context))
        assertFalse(Breather.allows(context, Breather.Feature.IG_FEED))
    }

    @Test fun `a storage read exception closes all later ordinary reads`() {
        start(5)
        val failing = object : ContextWrapper(context) {
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
                if (name == "breather") error("Unavailable storage")
                return super.getSharedPreferences(name, mode)
            }
        }
        assertEquals(0L, Breather.remainingMs(failing))
        assertEquals(0L, Breather.remainingMs(context))
    }

    @Test fun `concurrent readers cannot keep a grant after end returns`() {
        start(5)
        val executor = Executors.newFixedThreadPool(4)
        try {
            val before = (1..8).map { executor.submit<Boolean> { Breather.allows(context, Breather.Feature.IG_FEED) } }
            assertTrue(before.all { it.get(5, TimeUnit.SECONDS) })
            Breather.end(context)
            val after = (1..16).map { executor.submit<Boolean> { Breather.allows(context, Breather.Feature.IG_FEED) } }
            assertTrue(after.none { it.get(5, TimeUnit.SECONDS) })
        } finally {
            executor.shutdownNow()
        }
    }

    private fun start(minutes: Int, features: Set<Breather.Feature> = feed): Activity {
        Lock(context).set(BreatherTestSupport.PASSWORD)
        return BreatherTestSupport.activity().also {
            assertEquals(true, BreatherTestSupport.start(it, minutes, features))
        }
    }

    private fun advance(duration: Duration) {
        stored.edit().putLong("wall", stored.getLong("wall", 0) - duration.toMillis()).commit()
        ShadowSystemClock.advanceBy(duration)
    }
}

@Implements(BiometricPrompt::class)
class BreatherBiometricPrompt {
    @Implementation
    protected fun authenticate(signal: CancellationSignal, executor: Executor, callback: BiometricPrompt.AuthenticationCallback) {
        Companion.callback = callback
    }

    companion object {
        lateinit var callback: BiometricPrompt.AuthenticationCallback
    }
}

class BreatherStorageActivity : Activity() {
    var failWrites = false

    override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
        val original = super.getSharedPreferences(name, mode)
        if (name != "breather" || !failWrites) return original
        return object : SharedPreferences by original {
            override fun edit(): SharedPreferences.Editor {
                val editor = original.edit()
                return Proxy.newProxyInstance(
                    SharedPreferences.Editor::class.java.classLoader,
                    arrayOf(SharedPreferences.Editor::class.java),
                ) { proxy, method, arguments ->
                    val result = method.invoke(editor, *(arguments ?: emptyArray()))
                    when {
                        method.name == "commit" -> false
                        result is SharedPreferences.Editor -> proxy
                        else -> result
                    }
                } as SharedPreferences.Editor
            }
        }
    }
}

internal object BreatherTestSupport {
    const val PASSWORD = "breather-password"

    fun reset() {
        val context = RuntimeEnvironment.getApplication()
        Breather.end(context)
        for (name in listOf("settings", "lock", "pause", "guard", "breather")) {
            context.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear().commit()
        }
        SystemSettings.Global.putInt(context.contentResolver, SystemSettings.Global.BOOT_COUNT, 9)
        if (Build.VERSION.SDK_INT >= 29) {
            Shadow.extract<ShadowBiometricManager>(context.getSystemService(BiometricManager::class.java)).apply {
                setCanAuthenticate(false)
                setAuthenticatorType(0)
            }
        }
    }

    fun activity(): Activity = Robolectric.buildActivity(Activity::class.java).setup().get()

    fun start(activity: Activity, minutes: Int, features: Set<Breather.Feature>): Boolean? {
        var result: Boolean? = null
        Breather.confirmAndBegin(activity, minutes, features) { result = it }
        shadowOf(Looper.getMainLooper()).idle()
        val dialog = ShadowAlertDialog.getLatestAlertDialog()
        if (dialog?.isShowing == true && shadowOf(dialog).title.toString() == "Enter password") enterPassword(PASSWORD)
        return result
    }

    fun enterPassword(password: String) {
        val dialog = dialog()
        views(dialog.window!!.decorView).filterIsInstance<EditText>().single().setText(password)
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        ReflectionHelpers.getField<ExecutorService>(HashWorker, "pool").submit {}.get(10, TimeUnit.SECONDS)
        shadowOf(Looper.getMainLooper()).idle()
    }

    fun views(root: View): List<View> = listOf(root) + if (root is ViewGroup) {
        (0 until root.childCount).flatMap { views(root.getChildAt(it)) }
    } else emptyList()

    fun dialog(): AlertDialog {
        shadowOf(Looper.getMainLooper()).idle()
        return ShadowAlertDialog.getLatestAlertDialog()
    }
}

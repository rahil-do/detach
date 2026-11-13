package io.github.detach

import android.accessibilityservice.AccessibilityService
import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.view.Surface
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
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
import org.robolectric.shadows.ShadowAccessibilityService
import org.robolectric.shadows.ShadowLog
import java.time.Duration

@Implements(AccessibilityService::class)
class ForegroundServiceShadow : ShadowAccessibilityService() {
    companion object {
        var activeRoot: AccessibilityNodeInfo? = null
        var disabled = false
        var rootReads = 0
        /** What had happened when the service switched itself off. */
        var pendingAtDisable = false
        var alarmsAtDisable = -1
        var notifiedAtDisable = false

        fun reset(root: AccessibilityNodeInfo? = null) {
            activeRoot = root
            disabled = false
            rootReads = 0
            pendingAtDisable = false
            alarmsAtDisable = -1
            notifiedAtDisable = false
        }
    }

    @Implementation
    protected fun getRootInActiveWindow(): AccessibilityNodeInfo? {
        rootReads++
        return activeRoot
    }

    @Implementation
    protected fun disableSelf() {
        val app = RuntimeEnvironment.getApplication()
        pendingAtDisable = Pause.isPending(app)
        alarmsAtDisable = shadowOf(app.getSystemService(AlarmManager::class.java)).scheduledAlarms.size
        notifiedAtDisable = shadowOf(app.getSystemService(NotificationManager::class.java)).getNotification(Pause.NOTIFICATION_ID)
            ?.extras?.getString(Notification.EXTRA_TITLE) == StatusNotifications.PAUSED_TITLE
        disabled = true
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(shadows = [ForegroundServiceShadow::class])
class DetachServiceTest {
    private lateinit var service: DetachService
    private lateinit var cover: FeedCover

    @Before fun setup() {
        MonitorTestSupport.reset()
        ForegroundServiceShadow.reset()
        service = Robolectric.buildService(DetachService::class.java).create().get()
        val settings = Settings(service)
        val health = Health(service)
        cover = FeedCover(service, {}, {})
        set("settings", settings)
        set("health", health)
        set("ig", InstagramRules(settings, health))
        set("yt", YouTubeRules(settings, health))
        set("cover", cover)
        set("guard", GuardOverlay(service, {}, {}))
        set("lock", Lock(service))
        set("worker", Handler(Looper.getMainLooper()))
        set("thread", HandlerThread("test-worker"))
    }

    @After fun cleanup() {
        Inspector.tracing = false
        service.onDestroy()
        shadowOf(Looper.getMainLooper()).idle()
        ForegroundServiceShadow.activeRoot = null
    }

    private fun set(name: String, value: Any) {
        DetachService::class.java.getDeclaredField(name).apply { isAccessible = true }.set(service, value)
    }

    private fun value(name: String): Any? =
        DetachService::class.java.getDeclaredField(name).apply { isAccessible = true }.get(service)

    private fun foreground(screen: Screen) {
        ForegroundServiceShadow.activeRoot = screen.snap().all.first().node
    }

    private fun event(pkg: String, type: Int = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
        service.onAccessibilityEvent(AccessibilityEvent.obtain(type).apply { packageName = pkg })
    }

    private fun evaluate() {
        DetachService::class.java.getDeclaredMethod("evaluate").apply { isAccessible = true }.invoke(service)
    }

    @Test fun `YouTube Shorts blocking recovers after notification shade closes`() {
        foreground(Screen("com.android.systemui"))
        event("com.android.systemui")
        foreground(Screen(Targets.YOUTUBE).node(id = "shorts_player_container"))
        event(Targets.YOUTUBE)
        evaluate()
        assertTrue(value("pending") is Action.Back)
    }

    @Test fun `Settings protection recovers after notification shade closes`() {
        foreground(Screen("com.android.systemui"))
        event("com.android.systemui")
        foreground(Screen("com.android.settings").node(text = "Detach").node(text = "Uninstall"))
        event("com.android.settings")
        evaluate()
        assertTrue(value("guardOn") as Boolean)
    }

    @Test fun `opening a selected payment app disables the service without tracing its content`() {
        foreground(Screen("com.phonepe.app").node(text = "Private payment screen"))
        ShadowLog.clear()
        Inspector.tracing = true
        event("com.phonepe.app")
        assertTrue(ForegroundServiceShadow.disabled)
        assertTrue(Pause.isPending(service))
        assertTrue(value("pausing") as Boolean)
        assertFalse(cover.isShowing)
        assertFalse(ShadowLog.getLogsForTag(Inspector.TAG).any { it.msg.startsWith("EV ") })
        assertFalse(shadowOf(service.application).nextStartedService != null)
    }

    @Test fun `a background payment event cannot pause a foreground target`() {
        foreground(Screen(Targets.YOUTUBE))
        event("com.phonepe.app")
        assertFalse(ForegroundServiceShadow.disabled)
        assertFalse(Pause.isPending(service))
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2))
        assertFalse(ForegroundServiceShadow.disabled)
        assertFalse(Pause.isPending(service))
    }

    @Test fun `opening a selected checkout app pauses before it can launch a UPI app`() {
        val checkout = "com.example.checkout"
        Settings(service).paymentPackages = Targets.PAYMENT_DEFAULTS + checkout
        assertTrue(checkout in Settings(service).eventPackages())
        foreground(Screen(checkout).node(text = "Order summary"))
        Inspector.tracing = true
        ShadowLog.clear()
        event(checkout)
        assertTrue(ForegroundServiceShadow.disabled)
        assertTrue(Pause.isPending(service))
        assertEquals(checkout, Pause.startingPaymentPackage(service))
        assertFalse(ShadowLog.getLogsForTag(Inspector.TAG).any { it.msg.startsWith("EV ") })
    }

    @Test fun `a background selected app event never pauses while any other app is in front`() {
        val shop = "com.example.selected.shop"
        Settings(service).paymentPackages = Targets.PAYMENT_DEFAULTS + shop
        // Home screen, an unselected app, and Detach itself straight after the owner taps Resume.
        for (front in listOf("com.example.launcher", "com.example.unselected.app", service.packageName)) {
            foreground(Screen(front))
            event(shop)
            event("com.google.android.apps.nbu.paisa.user")
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2))
            assertFalse(front, ForegroundServiceShadow.disabled)
            assertFalse(front, Pause.isPending(service))
        }
    }

    @Test fun `a payment event behind the launching checkout pauses once the payment window takes focus`() {
        val gpay = "com.google.android.apps.nbu.paisa.user"
        foreground(Screen("com.example.unselected.shop").node(text = "Checkout"))
        event(gpay)
        assertFalse(ForegroundServiceShadow.disabled)
        foreground(Screen(gpay))
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(DetachService.FOREGROUND_CHECKS_MS[0]))
        assertTrue(ForegroundServiceShadow.disabled)
        assertEquals(gpay, Pause.startingPaymentPackage(service))
        assertTrue(Pause.startingPaymentWasInFront(service))
    }

    @Test fun `a selected payment event pauses before Android reports any active window`() {
        ForegroundServiceShadow.activeRoot = null
        event("com.phonepe.app", AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED)
        assertTrue(ForegroundServiceShadow.disabled)
        assertTrue(Pause.isPending(service))
        // Only an event: the optional monitor must not treat the app as confirmed in front.
        assertEquals("com.phonepe.app", Pause.startingPaymentPackage(service))
        assertFalse(Pause.startingPaymentWasInFront(service))
    }

    @Test fun `a pause from the selected app's own window is recorded as in front`() {
        foreground(Screen("com.phonepe.app"))
        event("com.phonepe.app")
        assertTrue(ForegroundServiceShadow.disabled)
        assertTrue(Pause.startingPaymentWasInFront(service))
    }

    @Test fun `a selected payment event behind a social app pauses once its window takes focus`() {
        foreground(Screen(Targets.INSTAGRAM))
        event("com.phonepe.app")
        assertFalse(ForegroundServiceShadow.disabled)
        foreground(Screen("com.phonepe.app"))
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(DetachService.FOREGROUND_CHECKS_MS[1]))
        assertTrue(ForegroundServiceShadow.disabled)
        assertTrue(Pause.isPending(service))
    }

    @Test fun `focus re-checks are bounded to about a second`() {
        foreground(Screen(Targets.INSTAGRAM))
        event("com.phonepe.app")
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(DetachService.FOREGROUND_CHECKS_MS.last() + 1))
        foreground(Screen("com.phonepe.app"))
        val reads = ForegroundServiceShadow.rootReads
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(5))
        assertEquals(reads, ForegroundServiceShadow.rootReads)
        assertFalse(ForegroundServiceShadow.disabled)
    }

    @Test fun `a payment event reads the active window once and disables before alarm and notification work`() {
        foreground(Screen("com.phonepe.app"))
        ForegroundServiceShadow.rootReads = 0
        event("com.phonepe.app")
        assertEquals(1, ForegroundServiceShadow.rootReads)
        assertTrue(ForegroundServiceShadow.disabled)
        assertTrue(ForegroundServiceShadow.pendingAtDisable)
        assertEquals(0, ForegroundServiceShadow.alarmsAtDisable)
        assertFalse(ForegroundServiceShadow.notifiedAtDisable)
        assertEquals(1, shadowOf(service.getSystemService(AlarmManager::class.java)).scheduledAlarms.size)
        // The check above compares against the title that is actually posted, so it can fail.
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(StatusNotifications.PAUSED_TITLE, shadowOf(service.getSystemService(NotificationManager::class.java))
            .getNotification(Pause.NOTIFICATION_ID)?.extras?.getString(Notification.EXTRA_TITLE))
    }

    @Test fun `a queued cover cannot appear after the user changes apps`() {
        foreground(Screen(Targets.YOUTUBE).node(id = "shorts_player_container"))
        evaluate()
        foreground(Screen("com.example.bank").node(text = "Private bank screen"))
        shadowOf(Looper.getMainLooper()).idle()
        assertFalse(cover.isShowing)
    }

    @Test fun `content changes from a selected foreground payment app pause without a window event`() {
        foreground(Screen("com.phonepe.app").node(text = "Private payment screen"))
        Inspector.tracing = true
        ShadowLog.clear()
        event("com.phonepe.app", AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED)
        assertTrue(ForegroundServiceShadow.disabled)
        assertTrue(Pause.isPending(service))
        assertFalse(ShadowLog.getLogsForTag(Inspector.TAG).any { it.msg.startsWith("EV ") })
    }

    @Test fun `a delayed social event cannot inspect anything once a payment app is foreground`() {
        foreground(Screen("com.phonepe.app").node(text = "Private payment screen"))
        Inspector.tracing = true
        ShadowLog.clear()
        event(Targets.INSTAGRAM, AccessibilityEvent.TYPE_VIEW_CLICKED)
        assertTrue(ForegroundServiceShadow.disabled)
        assertFalse(ShadowLog.getLogsForTag(Inspector.TAG).any { it.msg.startsWith("EV ") })
    }

    @Test fun `a background social event is rejected before inspector tracing`() {
        foreground(Screen("com.example.bank").node(text = "Private bank screen"))
        Inspector.tracing = true
        ShadowLog.clear()
        event(Targets.INSTAGRAM, AccessibilityEvent.TYPE_VIEW_CLICKED)
        assertFalse(ShadowLog.getLogsForTag(Inspector.TAG).any { it.msg.startsWith("EV ") })
    }

    @Test fun `a payment pause cancels covers that were already queued on the main thread`() {
        foreground(Screen(Targets.YOUTUBE).node(id = "shorts_player_container"))
        evaluate()
        foreground(Screen("com.phonepe.app"))
        event("com.phonepe.app")
        shadowOf(Looper.getMainLooper()).idle()
        assertFalse(cover.isShowing)
        assertFalse(value("coverOn") as Boolean)
    }

    @Test fun `Back is confirmed on a second snapshot rather than executed with the first cover`() {
        foreground(Screen(Targets.YOUTUBE).node(id = "shorts_player_container"))
        evaluate()
        assertTrue(value("pending") is Action.Back)
        assertTrue(shadowOf(service).globalActionsPerformed.isEmpty())
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(cover.isShowing)
    }

    @Test fun `leaving a blocked surface clears its pending action immediately`() {
        foreground(Screen(Targets.YOUTUBE).node(id = "shorts_player_container"))
        evaluate()
        foreground(Screen(Targets.YOUTUBE).node(id = "watch_player"))
        evaluate()
        shadowOf(Looper.getMainLooper()).idle()
        assertNull(value("pending"))
        assertFalse(cover.isShowing)
        assertTrue(shadowOf(service).globalActionsPerformed.isEmpty())
    }

    @Test fun `a transient empty Instagram tree cannot erase a shared reels chat context`() {
        foreground(instagram().igDmThread())
        evaluate()
        foreground(instagram())
        evaluate()
        foreground(instagram().igReelViewer("friend"))
        evaluate()
        assertNull(value("pending"))
    }

    @Test fun `leaving Instagram clears permission inherited from a previous chat`() {
        foreground(instagram().igDmThread())
        evaluate()
        foreground(Screen("com.example.bank"))
        evaluate()
        foreground(instagram().igReelViewer("external"))
        evaluate()
        assertTrue(value("pending") is Action.Back)
    }

    @Test fun `an empty active window never gets a screen sized cover`() {
        foreground(Screen(Targets.YOUTUBE).window(0, 0, 0, 0).node(id = "shorts_player_container"))
        evaluate()
        shadowOf(Looper.getMainLooper()).idle()
        assertFalse(cover.isShowing)
        assertNull(value("pending"))
    }

    @Test fun `a queued cover is clipped again if the same apps window shrinks`() {
        foreground(Screen(Targets.YOUTUBE).node(id = "shorts_player_container"))
        evaluate()
        foreground(Screen(Targets.YOUTUBE).window(100, 100, 220, 300))
        shadowOf(Looper.getMainLooper()).idle()
        val shown = FeedCover::class.java.getDeclaredField("shown").apply { isAccessible = true }.get(cover) as android.graphics.Rect
        assertTrue(android.graphics.Rect(100, 100, 220, 300).contains(shown))
    }

    @Test fun `a delayed notification shade cleanup cannot hide a newer target cover`() {
        foreground(Screen("com.android.systemui"))
        event("com.android.systemui")
        foreground(Screen(Targets.YOUTUBE).node(id = "shorts_player_container"))
        evaluate()
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(cover.isShowing)
    }

    @Test fun `an old Explore button cannot press Back in a newly opened conversation`() {
        foreground(instagram().igDmThread())
        DetachService::class.java.getDeclaredMethod("backToSearch").apply { isAccessible = true }.invoke(service)
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(shadowOf(service).globalActionsPerformed.isEmpty())
    }

    @Test fun `a failed inbox click cannot pull Instagram back over a newly foreground bank`() {
        val snap = instagram().igTabs("clips_tab").snap()
        ForegroundServiceShadow.activeRoot = snap.all.first().node
        val messages = snap.visibleId("direct_tab")!!
        shadowOf(messages.node).setOnPerformActionListener { _, _ ->
            foreground(Screen("com.example.bank"))
            false
        }
        DetachService::class.java.getDeclaredMethod("goToDms", Snap::class.java)
            .apply { isAccessible = true }.invoke(service, snap)
        assertNull(shadowOf(service).nextStartedActivity)
        assertTrue(shadowOf(service).globalActionsPerformed.isEmpty())
    }

    @Test fun `a failed click cannot send a fallback Back to another foreground app`() {
        val snap = Screen(Targets.YOUTUBE).node(id = "home", desc = "Home", clickable = true).snap()
        ForegroundServiceShadow.activeRoot = snap.all.first().node
        val home = snap.visibleId("home")!!
        shadowOf(home.node).setOnPerformActionListener { _, _ ->
            foreground(Screen("com.example.bank"))
            false
        }
        DetachService::class.java.getDeclaredMethod("execute", Action::class.java, Snap::class.java, Long::class.javaPrimitiveType)
            .apply { isAccessible = true }.invoke(service, Action.Click(home, "YouTube Shorts tab"), snap, 1000L)
        assertTrue(shadowOf(service).globalActionsPerformed.isEmpty())
    }

    @Config(sdk = [28])
    @Test fun `legacy window clipping reserves status and bottom navigation bars`() {
        val display = shadowOf(service.getSystemService(WindowManager::class.java).defaultDisplay)
        display.setRealWidth(1080)
        display.setRealHeight(2400)
        display.setWidth(1080)
        display.setHeight(2250)
        val bounds = DetachService::class.java.getDeclaredMethod("safeScreenBounds", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
            .apply { isAccessible = true }.invoke(service, 1080, 2400) as android.graphics.Rect
        assertTrue(bounds.top > 0)
        assertEquals(2250, bounds.bottom)
        assertEquals(1080, bounds.right)
    }

    @Config(sdk = [28])
    @Test fun `legacy landscape clipping keeps navigation controls on the left unobscured`() {
        val display = shadowOf(service.getSystemService(WindowManager::class.java).defaultDisplay)
        display.setRealWidth(2400)
        display.setRealHeight(1080)
        display.setWidth(2250)
        display.setHeight(1080)
        display.setRotation(Surface.ROTATION_270)
        val bounds = DetachService::class.java.getDeclaredMethod("safeScreenBounds", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
            .apply { isAccessible = true }.invoke(service, 2400, 1080) as android.graphics.Rect
        assertEquals(150, bounds.left)
        assertEquals(2400, bounds.right)
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(shadows = [ForegroundServiceShadow::class])
class DetachServiceConnectionTest {
    private lateinit var service: DetachService

    @Before fun setup() {
        MonitorTestSupport.reset()
        ForegroundServiceShadow.reset(AccessibilityNodeInfo.obtain().apply { packageName = Targets.INSTAGRAM })
        service = Robolectric.buildService(DetachService::class.java).create().get()
    }

    @After fun cleanup() {
        service.onDestroy()
        (value("thread") as? HandlerThread)?.join(1000)
        shadowOf(Looper.getMainLooper()).idle()
        ForegroundServiceShadow.activeRoot = null
    }

    private fun value(name: String): Any? = DetachService::class.java.getDeclaredField(name)
        .apply { isAccessible = true }.get(service)

    private fun assertUninitialized() {
        assertNull(value("worker"))
        assertNull(value("thread"))
        assertNull(value("cover"))
        assertNull(value("guard"))
        assertNull(value("health"))
        assertFalse(DetachService.isRunning)
    }

    private fun connect() {
        DetachService::class.java.getDeclaredMethod("onServiceConnected").apply { isAccessible = true }.invoke(service)
    }

    private fun authorizeResume() {
        shadowOf(service.application).denyPermissions(android.Manifest.permission.WRITE_SECURE_SETTINGS)
        assertFalse(Pause.resume(service))
    }

    @Test fun `reconnecting during a recorded pause refuses startup and preserves its reminder`() {
        Pause.begin(service)
        val until = Pause.until(service)
        val started = Pause.startedAt(service)
        connect()
        assertTrue(ForegroundServiceShadow.disabled)
        assertTrue(Pause.isPending(service))
        assertEquals(until, Pause.until(service))
        assertEquals(started, Pause.startedAt(service))
        assertUninitialized()
    }

    @Test fun `an expired reminder is not permission to reconnect and start protection`() {
        val until = System.currentTimeMillis() - 60_000
        service.getSharedPreferences("pause", Context.MODE_PRIVATE).edit().putLong("until", until).commit()
        connect()
        assertTrue(ForegroundServiceShadow.disabled)
        assertEquals(until, Pause.until(service))
        assertUninitialized()
    }

    @Test fun `corrupt pause state fails closed without creating overlays or a worker`() {
        service.getSharedPreferences("pause", Context.MODE_PRIVATE).edit().putString("until", "invalid").commit()
        connect()
        assertTrue(ForegroundServiceShadow.disabled)
        assertTrue(Pause.isPending(service))
        assertUninitialized()
    }

    @Test fun `starting over a selected payment app immediately pauses without initializing protection`() {
        ForegroundServiceShadow.activeRoot = AccessibilityNodeInfo.obtain().apply { packageName = "com.phonepe.app" }
        connect()
        assertTrue(ForegroundServiceShadow.disabled)
        assertTrue(Pause.isPending(service))
        assertUninitialized()
    }

    @Test fun `interruption unbinding and destruction are safe after startup is refused`() {
        Pause.begin(service)
        connect()
        service.onInterrupt()
        service.onUnbind(null)
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(Pause.isPending(service))
        assertUninitialized()
    }

    @Test fun `an expired explicit resume marker cannot authorize a reconnect`() {
        Pause.begin(service)
        authorizeResume()
        service.getSharedPreferences("pause", Context.MODE_PRIVATE).edit()
            .putLong("resume_authorized_at", System.currentTimeMillis() - Pause.RESUME_AUTHORIZATION_MS - 1).commit()
        connect()
        assertTrue(ForegroundServiceShadow.disabled)
        assertTrue(Pause.isPending(service))
        assertUninitialized()
    }

    @Test fun `a future resume marker cannot authorize a reconnect`() {
        Pause.begin(service)
        authorizeResume()
        service.getSharedPreferences("pause", Context.MODE_PRIVATE).edit()
            .putLong("resume_authorized_at", System.currentTimeMillis() + 60_000).commit()
        connect()
        assertTrue(ForegroundServiceShadow.disabled)
        assertTrue(Pause.isPending(service))
        assertUninitialized()
    }

    @Test fun `a previously consumed explicit resume marker cannot be replayed on reconnect`() {
        Pause.begin(service)
        authorizeResume()
        assertTrue(Pause.consumeResumeAuthorization(service))
        connect()
        assertTrue(ForegroundServiceShadow.disabled)
        assertTrue(Pause.isPending(service))
        assertUninitialized()
    }

    @Test fun `a recent explicit resume permits initialization and clears the pending reminder`() {
        Pause.begin(service)
        authorizeResume()
        connect()
        assertFalse(ForegroundServiceShadow.disabled)
        assertFalse(Pause.isPending(service))
        assertTrue(DetachService.isRunning)
        assertFalse(Pause.consumeResumeAuthorization(service))
    }

    @Test fun `even explicit resume cannot initialize protection over a selected payment app`() {
        Pause.begin(service)
        authorizeResume()
        val until = Pause.until(service)
        ForegroundServiceShadow.activeRoot = AccessibilityNodeInfo.obtain().apply { packageName = "com.phonepe.app" }
        connect()
        assertTrue(ForegroundServiceShadow.disabled)
        // Reconnecting over a selected bank starts a fresh payment pause instead of protecting it.
        assertTrue(Pause.isPending(service))
        assertTrue(Pause.until(service) >= until)
        assertUninitialized()
        assertFalse(Pause.consumeResumeAuthorization(service))
    }

    private fun idleWorker() {
        (value("thread") as? HandlerThread)?.looper?.let { shadowOf(it).idle() }
    }

    private fun root(packageName: String?) {
        ForegroundServiceShadow.activeRoot = packageName?.let { name -> AccessibilityNodeInfo.obtain().apply { this.packageName = name } }
    }

    @Test fun `startup re-checks pause for a selected app whose window was not available at binding`() {
        root(null)
        connect()
        idleWorker()
        assertTrue(DetachService.isRunning)
        assertFalse(ForegroundServiceShadow.disabled)
        root("com.phonepe.app")
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(300))
        assertTrue(ForegroundServiceShadow.disabled)
        assertTrue(Pause.isPending(service))
        assertFalse(DetachService.isRunning)
    }

    @Test fun `a selected app event with no active window right after connecting waits for its own window`() {
        root(null)
        connect()
        idleWorker()
        // Typically the exit transition of the payment app the owner just left.
        service.onAccessibilityEvent(AccessibilityEvent.obtain(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED).apply { packageName = "com.phonepe.app" })
        assertFalse(ForegroundServiceShadow.disabled)
        root(Targets.INSTAGRAM)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(DetachService.STARTUP_GRACE_MS + 1))
        assertFalse(ForegroundServiceShadow.disabled)
        assertTrue(DetachService.isRunning)
        // After the grace an event with no active window pauses at once, as before.
        root(null)
        service.onAccessibilityEvent(AccessibilityEvent.obtain(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED).apply { packageName = "com.phonepe.app" })
        assertTrue(ForegroundServiceShadow.disabled)
    }

    private fun markSocialResume() {
        Pause::class.java.getDeclaredField("socialResumeAt").apply { isAccessible = true }
            .setLong(null, android.os.SystemClock.elapsedRealtime())
    }

    @Test fun `right after a resume for Instagram a stale payment window is read again before pausing`() {
        Pause.begin(service)
        authorizeResume()
        markSocialResume()
        root("com.phonepe.app")
        connect()
        assertFalse(ForegroundServiceShadow.disabled)
        assertTrue(DetachService.isRunning)
        service.onAccessibilityEvent(AccessibilityEvent.obtain(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED).apply { packageName = "com.phonepe.app" })
        assertFalse(ForegroundServiceShadow.disabled)
        // Instagram takes focus: the payment app was only the window being left.
        root(Targets.INSTAGRAM)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2))
        assertFalse(ForegroundServiceShadow.disabled)
        assertFalse(Pause.isPending(service))
    }

    @Test fun `right after a resume for Instagram a payment app really in front still pauses`() {
        Pause.begin(service)
        authorizeResume()
        markSocialResume()
        root("com.phonepe.app")
        connect()
        assertFalse(ForegroundServiceShadow.disabled)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(DetachService.SOCIAL_RESUME_CHECKS_MS.first()))
        assertTrue(ForegroundServiceShadow.disabled)
        assertTrue(Pause.isPending(service))
        assertTrue(Pause.startingPaymentWasInFront(service))
    }

    @Test fun `startup re-checks stop after about a second and never pause a social app`() {
        root(Targets.INSTAGRAM)
        connect()
        idleWorker()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(DetachService.FOREGROUND_CHECKS_MS.last() + 1))
        idleWorker()
        assertFalse(ForegroundServiceShadow.disabled)
        root("com.phonepe.app")
        val reads = ForegroundServiceShadow.rootReads
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(5))
        assertFalse(ForegroundServiceShadow.disabled)
        assertTrue(ForegroundServiceShadow.rootReads - reads <= 1)
        assertTrue(DetachService.isRunning)
    }

    @Test fun `a timer resume while the same selected app is still open pauses again at once`() {
        val bank = "com.phonepe.app"
        shadowOf(service.application).grantPermissions(android.Manifest.permission.WRITE_SECURE_SETTINGS)
        android.provider.Settings.Secure.putString(service.contentResolver,
            android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, "com.example/.Other")
        Settings(service).pauseMinutes = 1
        assertTrue(Pause.begin(service, bank))
        val stored = service.getSharedPreferences("pause", Context.MODE_PRIVATE)
        stored.edit().putLong("started", stored.getLong("started", 0L) - 60_001L)
            .putLong("until", stored.getLong("until", 0L) - 60_001L).commit()
        org.robolectric.shadows.ShadowSystemClock.advanceBy(Duration.ofMillis(60_001L))
        Pause.onTimer(service)
        val previous = Pause.startedAt(service)
        root(bank)
        connect()
        assertTrue(ForegroundServiceShadow.disabled)
        assertTrue(Pause.isPending(service))
        assertEquals(bank, Pause.startingPaymentPackage(service))
        assertTrue(Pause.startedAt(service) >= previous)
        assertFalse(Pause.timerDue(service))
        assertUninitialized()
    }
}

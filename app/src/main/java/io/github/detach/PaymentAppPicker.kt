package io.github.detach

import android.app.Activity
import android.app.AlertDialog
import android.app.Application
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.util.LruCache
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.BaseAdapter
import android.widget.CheckBox
import android.widget.Checkable
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import java.lang.ref.WeakReference
import java.text.Collator
import java.util.Locale
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

@Suppress("DEPRECATION")
object PaymentAppPicker {
    fun show(activity: Activity, onSaved: () -> Unit) {
        if (!activity.isFinishing && !activity.isDestroyed) Session(activity, onSaved).show()
    }

    internal data class App(val info: ApplicationInfo, val name: String) {
        val searchName = name.lowercase(Locale.ROOT)
    }

    internal fun loadApps(manager: PackageManager, ownPackage: String, saved: Set<String>): List<App> {
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val packages = manager.queryIntentActivities(launcher, 0).mapNotNull { it.activityInfo?.packageName }.toSet() +
            saved + Targets.PAYMENT_DEFAULTS
        val collator = Collator.getInstance()
        return packages.asSequence().filter {
            it != ownPackage && it !in Targets.READABLE && it !in Targets.SYSTEM_UI
        }.mapNotNull { packageName ->
            if (Thread.currentThread().isInterrupted) throw InterruptedException()
            runCatching {
                val info = manager.getApplicationInfo(packageName, 0)
                val name = manager.getApplicationLabel(info).toString().trim()
                if (info.flags and ApplicationInfo.FLAG_INSTALLED == 0 || info.icon == 0 ||
                    name.isBlank() || name == packageName || PACKAGE_LABEL.matches(name)) null
                else App(info, name)
            }.getOrNull()
        }.toList().sortedWith { first, second ->
            collator.compare(first.name, second.name).takeIf { it != 0 }
                ?: first.info.packageName.compareTo(second.info.packageName)
        }
    }

    internal fun loadIcon(manager: PackageManager, app: App, size: Int): Bitmap? = runCatching {
        val drawable = manager.getDrawable(app.info.packageName, app.info.icon, app.info) ?: return null
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val width = drawable.intrinsicWidth.takeIf { it > 0 } ?: size
        val height = drawable.intrinsicHeight.takeIf { it > 0 } ?: size
        val scale = minOf(size.toFloat() / width, size.toFloat() / height)
        val targetWidth = (width * scale).toInt().coerceAtLeast(1)
        val targetHeight = (height * scale).toInt().coerceAtLeast(1)
        val left = (size - targetWidth) / 2
        val top = (size - targetHeight) / 2
        drawable.setBounds(left, top, left + targetWidth, top + targetHeight)
        drawable.draw(Canvas(bitmap))
        bitmap
    }.getOrNull()

    internal const val HINT = "Detach pauses while any selected app is open. Include the apps you pay from, " +
        "e.g. investing or shopping apps, so it is already off when they open a UPI app."

    private val PACKAGE_LABEL = Regex("[a-zA-Z][a-zA-Z0-9_]*(\\.[a-zA-Z][a-zA-Z0-9_]*)+")

    private class Session(private val activity: Activity, private var onSaved: (() -> Unit)?) :
        Application.ActivityLifecycleCallbacks {
        private val ui = Ui(activity)
        private val selected = Settings(activity).paymentPackages.toMutableSet()
        private val handler = Handler(Looper.getMainLooper())
        private val executor = ThreadPoolExecutor(1, 1, 10, TimeUnit.SECONDS, ArrayBlockingQueue(24)) { task ->
            Thread(task, "payment-app-picker").apply { isDaemon = true }
        }
        private val icons = object : LruCache<String, Bitmap>(4 * 1024 * 1024) {
            override fun sizeOf(key: String, value: Bitmap) = value.byteCount
        }
        private val pendingIcons = mutableSetOf<String>()
        private var apps = emptyList<App>()
        private var visibleApps = emptyList<App>()
        private var closed = false
        private var loaded = false
        private val iconSize = ui.dp(40).coerceIn(1, 192)
        private val search = ui.style(EditText(activity)).apply {
            tag = "payment-app-search"
            hint = "Search installed apps"
            contentDescription = "Search installed apps"
            isSingleLine = true
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_CAP_WORDS
            imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_DONE
        }
        private val summary = ui.note("").apply {
            tag = "payment-app-summary"
            setPadding(0, ui.dp(8), 0, ui.dp(8))
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        private val status = ui.text("Loading installed apps…", 15f, ui.sub).apply {
            tag = "payment-app-status"
            gravity = Gravity.CENTER
            setPadding(ui.dp(16), ui.dp(16), ui.dp(16), ui.dp(16))
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        private val adapter = object : BaseAdapter() {
            override fun getCount() = visibleApps.size
            override fun getItem(position: Int) = visibleApps[position]
            override fun getItemId(position: Int) = position.toLong()
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val app = getItem(position)
                val row = convertView as? AppRow ?: AppRow(activity, ui)
                row.bind(app.name, app.info.packageName in selected, icons.get(app.info.packageName))
                requestIcon(app)
                return row
            }
        }
        private val list = ListView(activity).apply {
            tag = "payment-app-list"
            divider = null
            adapter = this@Session.adapter
            emptyView = status
            setOnItemClickListener { _, view, position, _ ->
                val packageName = visibleApps[position].info.packageName
                if (!selected.add(packageName)) selected.remove(packageName)
                (view as Checkable).isChecked = packageName in selected
                updateSummary()
            }
        }
        private val watcher = object : TextWatcher {
            override fun beforeTextChanged(text: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(text: CharSequence?, start: Int, before: Int, count: Int) = filter()
            override fun afterTextChanged(text: Editable?) = Unit
        }
        private val dialog = AlertDialog.Builder(activity)
            .setTitle("UPI, bank & checkout apps")
            .setView(LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(ui.dp(20), ui.dp(8), ui.dp(20), 0)
                addView(ui.note(HINT).apply {
                    tag = "payment-app-hint"
                    setPadding(0, 0, 0, ui.dp(10))
                })
                addView(search)
                addView(summary)
                addView(android.widget.FrameLayout(activity).apply {
                    addView(list, ViewGroup.LayoutParams(-1, -1))
                    addView(status, ViewGroup.LayoutParams(-1, -1))
                }, LinearLayout.LayoutParams(-1, (activity.resources.displayMetrics.heightPixels * 0.42f).toInt(), 1f))
            })
            .setPositiveButton("Save", null)
            .setNegativeButton("Cancel", null)
            .create()

        fun show() {
            activity.application.registerActivityLifecycleCallbacks(this)
            dialog.setOnDismissListener { close() }
            dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN or
                WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
            dialog.show()
            ui.style(dialog)
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).apply {
                isEnabled = false
                setOnClickListener {
                    if (closed || !loaded || activity.isFinishing || activity.isDestroyed) return@setOnClickListener
                    Settings(activity).paymentPackages = selected
                    val callback = onSaved
                    dialog.dismiss()
                    callback?.invoke()
                }
            }
            search.addTextChangedListener(watcher)
            updateSummary()
            val owner = WeakReference(this)
            val main = handler
            val manager = activity.applicationContext.packageManager
            val ownPackage = activity.packageName
            val saved = selected.toSet()
            executor.execute {
                val result = runCatching { loadApps(manager, ownPackage, saved) }
                main.post { owner.get()?.receiveApps(result) }
            }
        }

        private fun receiveApps(result: Result<List<App>>) {
            if (closed) return
            loaded = result.isSuccess
            apps = result.getOrDefault(emptyList())
            filter()
            if (!loaded) status.text = "Couldn’t load installed apps. Please try again."
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = loaded
        }

        private fun filter() {
            val query = search.text.toString().trim().lowercase(Locale.ROOT)
            visibleApps = if (query.isEmpty()) apps else apps.filter { query in it.searchName }
            if (loaded) status.text = if (query.isEmpty()) "No installed apps available" else "No matching apps"
            adapter.notifyDataSetChanged()
        }

        private fun updateSummary() {
            summary.text = "${selected.size} selected. Saved choices for unavailable apps are kept."
        }

        private fun requestIcon(app: App) {
            val packageName = app.info.packageName
            if (closed || icons.get(packageName) != null || !pendingIcons.add(packageName)) return
            val owner = WeakReference(this)
            val main = handler
            val manager = activity.applicationContext.packageManager
            val size = iconSize
            try {
                executor.execute {
                    val bitmap = if (Thread.currentThread().isInterrupted) null else loadIcon(manager, app, size)
                    main.post { owner.get()?.receiveIcon(packageName, bitmap) }
                }
            } catch (_: RejectedExecutionException) {
                pendingIcons.remove(packageName)
            }
        }

        private fun receiveIcon(packageName: String, bitmap: Bitmap?) {
            if (closed) return
            pendingIcons.remove(packageName)
            if (bitmap != null) {
                icons.put(packageName, bitmap)
                adapter.notifyDataSetChanged()
            } else {
                apps = apps.filterNot { it.info.packageName == packageName }
                filter()
            }
        }

        private fun close() {
            if (closed) return
            closed = true
            activity.application.unregisterActivityLifecycleCallbacks(this)
            search.removeTextChangedListener(watcher)
            list.adapter = null
            list.onItemClickListener = null
            executor.shutdownNow()
            handler.removeCallbacksAndMessages(null)
            pendingIcons.clear()
            icons.evictAll()
            onSaved = null
        }

        override fun onActivityDestroyed(host: Activity) {
            if (host === activity) {
                dialog.dismiss()
                close()
            }
        }

        override fun onActivityCreated(host: Activity, state: Bundle?) = Unit
        override fun onActivityStarted(host: Activity) = Unit
        override fun onActivityResumed(host: Activity) = Unit
        override fun onActivityPaused(host: Activity) = Unit
        override fun onActivityStopped(host: Activity) {
            if (host === activity) {
                dialog.dismiss()
                close()
            }
        }
        override fun onActivitySaveInstanceState(host: Activity, state: Bundle) = Unit
    }

    private class AppRow(activity: Activity, ui: Ui) : LinearLayout(activity), Checkable {
        private val icon = ImageView(activity).apply {
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            imageTintList = null
            scaleType = ImageView.ScaleType.FIT_CENTER
        }
        private val name = ui.text("", 16f).apply {
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            setPadding(ui.dp(12), 0, ui.dp(8), 0)
        }
        private val checkbox = CheckBox(activity).apply {
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            isClickable = false
            isFocusable = false
            minimumWidth = ui.dp(48)
            minimumHeight = ui.dp(48)
        }

        init {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
            minimumHeight = ui.dp(64)
            setPadding(ui.dp(4), ui.dp(8), 0, ui.dp(8))
            addView(icon, LayoutParams(ui.dp(40), ui.dp(40)))
            addView(name, LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(checkbox, LayoutParams(ui.dp(48), ui.dp(48)))
        }

        fun bind(label: String, checked: Boolean, bitmap: Bitmap?) {
            name.text = label
            contentDescription = label
            isChecked = checked
            icon.setImageBitmap(bitmap)
        }

        override fun isChecked() = checkbox.isChecked
        override fun setChecked(checked: Boolean) { checkbox.isChecked = checked }
        override fun toggle() { isChecked = !isChecked }

        override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
            super.onInitializeAccessibilityNodeInfo(info)
            info.className = CheckBox::class.java.name
            info.isCheckable = true
            info.isChecked = isChecked
            info.isClickable = isEnabled
            if (isEnabled) info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_CLICK)
        }

        override fun performAccessibilityAction(action: Int, arguments: Bundle?): Boolean {
            if (action == AccessibilityNodeInfo.ACTION_CLICK && isEnabled) {
                val list = parent as? ListView
                val rowAdapter = list?.adapter
                val position = list?.getPositionForView(this) ?: ListView.INVALID_POSITION
                if (list != null && rowAdapter != null && position in 0 until rowAdapter.count) {
                    return list.performItemClick(this, position, rowAdapter.getItemId(position))
                }
            }
            return super.performAccessibilityAction(action, arguments)
        }
    }
}

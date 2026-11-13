package io.github.detach

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Bundle
import android.view.View
import android.widget.RemoteViews

/** A compact home-screen launcher for selected payment apps. */
class PaymentWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        ids.forEach { update(context, manager, it) }
    }

    override fun onAppWidgetOptionsChanged(context: Context, manager: AppWidgetManager, id: Int, options: Bundle) {
        update(context, manager, id, options)
    }

    private fun update(context: Context, manager: AppWidgetManager, id: Int, options: Bundle = manager.getAppWidgetOptions(id)) {
        val shape = shapeFor(options)
        val views = RemoteViews(context.packageName, when (shape.kind) {
            Kind.STRIP -> R.layout.widget_payment_single
            Kind.GRID_3 -> R.layout.widget_payment_double
            Kind.GRID_4 -> R.layout.widget_payment_2x3
            Kind.GRID_5 -> R.layout.widget_payment_2x4
        })
        if (shape.kind == Kind.GRID_3) {
            views.setViewVisibility(R.id.payment_row_1, View.VISIBLE)
            views.setViewVisibility(R.id.payment_row_2, View.VISIBLE)
        }
        val apps = paymentApps(context).take(shape.capacity)
        // A strip always uses the same five-cell layout. Reset every cell when the widget
        // shrinks so slots that were visible at a wider size cannot remain stale.
        val slots = if (shape.kind == Kind.STRIP) 5 else shape.capacity
        repeat(slots) { index ->
            val tile = TILE_IDS[index]
            val app = apps.getOrNull(index)
            if (app == null) {
                // Grids keep empty cells to preserve their columns. Strips remove unused cells
                // so the visible apps share the full widget width without a blank tail.
                val emptyVisibility = if (shape.kind == Kind.STRIP) View.GONE else View.INVISIBLE
                views.setViewVisibility(tile, if (apps.isEmpty() && index == 0) View.VISIBLE else emptyVisibility)
                if (apps.isEmpty() && index == 0) {
                    views.setTextViewText(LABEL_IDS[index], "Choose apps")
                    views.setImageViewResource(ICON_IDS[index], R.drawable.ic_mark)
                    views.setOnClickPendingIntent(tile, openDetach(context, id))
                    views.setContentDescription(tile, "Choose payment apps in Detach")
                }
            } else {
                views.setViewVisibility(tile, View.VISIBLE)
                views.setTextViewText(LABEL_IDS[index], app.label)
                views.setTextViewTextSize(
                    LABEL_IDS[index], android.util.TypedValue.COMPLEX_UNIT_SP,
                    if (shape.kind == Kind.GRID_3) 8f else 10f,
                )
                app.icon?.let { views.setImageViewBitmap(ICON_IDS[index], it) }
                    ?: views.setImageViewResource(ICON_IDS[index], R.drawable.ic_mark)
                views.setOnClickPendingIntent(tile, launchPayment(context, id, app.packageName))
                views.setContentDescription(tile, "Open ${app.label} after pausing Detach")
            }
        }
        manager.updateAppWidget(id, views)
    }

    private fun paymentApps(context: Context): List<PaymentApp> {
        val pm = context.packageManager
        return Settings(context).paymentPackages.mapNotNull { packageName ->
            val launch = pm.getLaunchIntentForPackage(packageName) ?: return@mapNotNull null
            runCatching {
                val info = pm.getApplicationInfo(packageName, 0)
                PaymentApp(packageName, pm.getApplicationLabel(info).toString(), icon(pm, info))
            }.getOrNull()
        }.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.label })
    }

    private fun icon(pm: android.content.pm.PackageManager, info: android.content.pm.ApplicationInfo): Bitmap? = runCatching {
        val size = 72
        val drawable = pm.getApplicationIcon(info)
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val scale = minOf(size.toFloat() / drawable.intrinsicWidth.coerceAtLeast(1), size.toFloat() / drawable.intrinsicHeight.coerceAtLeast(1))
        val width = (drawable.intrinsicWidth * scale).toInt().coerceAtLeast(1)
        val height = (drawable.intrinsicHeight * scale).toInt().coerceAtLeast(1)
        drawable.setBounds((size - width) / 2, (size - height) / 2, (size + width) / 2, (size + height) / 2)
        drawable.draw(Canvas(bitmap))
        bitmap
    }.getOrNull()

    private fun openDetach(context: Context, id: Int) = PendingIntent.getActivity(
        context, id, Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun launchPayment(context: Context, id: Int, packageName: String) = PendingIntent.getActivity(
        context, id + packageName.hashCode(), Intent(context, PaymentLaunchActivity::class.java)
            .putExtra(PaymentLaunchActivity.EXTRA_PACKAGE, packageName).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private data class PaymentApp(val packageName: String, val label: String, val icon: Bitmap?)

    internal enum class Kind { STRIP, GRID_3, GRID_4, GRID_5 }
    internal data class Shape(val kind: Kind, val columns: Int, val rows: Int, val capacity: Int)

    internal fun shapeFor(options: Bundle): Shape {
        return shapeFor(
            options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 72),
            options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 110),
        )
    }

    internal fun shapeFor(width: Int, height: Int): Shape {
        if (height < 130) {
            val count = when {
                width < 130 -> 1 // 1x1
                width < 230 -> 3 // 1x2
                width < 330 -> 4 // 1x3
                else -> 5         // 1x4 (and wider)
            }
            return Shape(Kind.STRIP, count, 1, count)
        }
        return when {
            width >= 300 -> Shape(Kind.GRID_5, 5, 2, 10) // 2x4
            width >= 220 -> Shape(Kind.GRID_4, 4, 2, 8)  // 2x3
            else -> Shape(Kind.GRID_3, 3, 3, 9)          // 2x2
        }
    }

    companion object {
        private val TILE_IDS = intArrayOf(R.id.payment_app_0,R.id.payment_app_1,R.id.payment_app_2,R.id.payment_app_3,R.id.payment_app_4,R.id.payment_app_5,R.id.payment_app_6,R.id.payment_app_7,R.id.payment_app_8,R.id.payment_app_9)
        private val ICON_IDS = intArrayOf(R.id.payment_icon_0,R.id.payment_icon_1,R.id.payment_icon_2,R.id.payment_icon_3,R.id.payment_icon_4,R.id.payment_icon_5,R.id.payment_icon_6,R.id.payment_icon_7,R.id.payment_icon_8,R.id.payment_icon_9)
        private val LABEL_IDS = intArrayOf(R.id.payment_label_0,R.id.payment_label_1,R.id.payment_label_2,R.id.payment_label_3,R.id.payment_label_4,R.id.payment_label_5,R.id.payment_label_6,R.id.payment_label_7,R.id.payment_label_8,R.id.payment_label_9)

        fun updateAll(context: Context) {
            val app = context.applicationContext
            val manager = AppWidgetManager.getInstance(app)
            val ids = manager.getAppWidgetIds(ComponentName(app, PaymentWidgetProvider::class.java))
            if (ids.isNotEmpty()) PaymentWidgetProvider().onUpdate(app, manager, ids)
        }
    }
}

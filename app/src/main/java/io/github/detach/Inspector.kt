package io.github.detach

import android.graphics.Rect
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Developer tool: prints the accessibility tree of Instagram / YouTube to logcat so the
 * detection rules can be updated when those apps change their layout.
 *
 * It can only be triggered from a computer over USB (adb), because the broadcast that
 * starts it requires android.permission.DUMP, which ordinary apps cannot hold.
 * Output stays in the phone's local log; nothing is written to storage or sent anywhere.
 */
object Inspector {
    const val TAG = "DetachInspect"
    const val ACTION_DUMP = "io.github.detach.DUMP"
    const val ACTION_TRACE = "io.github.detach.TRACE"

    @Volatile
    var tracing = false

    fun dump(root: AccessibilityNodeInfo?) {
        if (root == null) {
            Log.i(TAG, "dump: no active window")
            return
        }
        val pkg = root.packageName?.toString()
        if (pkg !in Targets.ALL) {
            // Never print other apps' screens.
            Log.i(TAG, "dump: active window is not one Detach watches")
            return
        }
        Log.i(TAG, "===== DUMP BEGIN $pkg =====")
        walk(root, 0)
        Log.i(TAG, "===== DUMP END =====")
    }

    private fun walk(node: AccessibilityNodeInfo, depth: Int) {
        if (depth > 60) return
        Log.i(TAG, describe(node, depth))
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            walk(child, depth + 1)
        }
    }

    fun describe(node: AccessibilityNodeInfo, depth: Int = 0): String {
        val r = Rect()
        node.getBoundsInScreen(r)
        val id = node.viewIdResourceName?.substringAfter(":id/") ?: "-"
        val cls = node.className?.toString()?.substringAfterLast('.') ?: "?"
        val flags = buildString {
            if (node.isClickable) append('C')
            if (node.isSelected) append('S')
            if (node.isScrollable) append('R')
            if (node.isCheckable) append('K')
            if (node.isChecked) append('X')
            if (!node.isVisibleToUser) append('h')
        }
        return "${"  ".repeat(depth.coerceAtMost(30))}$cls #$id [${r.left},${r.top},${r.right},${r.bottom}] $flags" +
            " t=${clip(node.text)} d=${clip(node.contentDescription)}"
    }

    fun trace(event: AccessibilityEvent) {
        if (!tracing) return
        val src = event.source
        Log.i(
            TAG,
            "EV ${AccessibilityEvent.eventTypeToString(event.eventType)} pkg=${event.packageName} " +
                "cls=${event.className} ch=${event.contentChangeTypes} " +
                "src=${src?.let { describe(it) } ?: "-"}"
        )
    }

    private fun clip(s: CharSequence?): String {
        if (s.isNullOrEmpty()) return "\"\""
        val t = s.toString().replace('\n', ' ')
        return "\"" + (if (t.length > 40) t.take(40) + "…" else t) + "\""
    }
}

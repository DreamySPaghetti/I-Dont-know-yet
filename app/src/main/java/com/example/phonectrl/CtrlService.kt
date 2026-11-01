package com.example.phonectrl

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.graphics.Path
import android.graphics.Rect
import android.os.Bundle
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import kotlin.concurrent.thread

class CtrlService : AccessibilityService() {
    companion object { var inst: CtrlService? = null }

    override fun onServiceConnected() { inst = this }
    override fun onUnbind(i: Intent?): Boolean { inst = null; return super.onUnbind(i) }
    override fun onAccessibilityEvent(e: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    fun run(script: String) = thread {
        Thread.sleep(600)
        for (raw in script.split(Regex("\\n|;|\\s+then\\s+", RegexOption.IGNORE_CASE))) {
            val l = raw.trim()
            if (l.isEmpty()) continue
            val c = l.substringBefore(' ').lowercase()
            val a = l.substringAfter(' ', "").trim()
            val n = a.split(Regex("\\s+")).mapNotNull { it.toFloatOrNull() }
            val w = resources.displayMetrics.widthPixels / 2f
            val h = resources.displayMetrics.heightPixels.toFloat()
            when (c) {
                "open" -> openApp(a)
                "tap" -> if (n.size >= 2) swipe(n[0], n[1], n[0], n[1], 50) else clickText(a)
                "swipe" -> if (n.size >= 4) swipe(n[0], n[1], n[2], n[3], n.getOrElse(4) { 300f }.toLong())
                "scroll" -> if (a.startsWith("up", true)) swipe(w, h * .3f, w, h * .7f, 300) else swipe(w, h * .7f, w, h * .3f, 300)
                "type" -> focused()?.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
                    putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, a)
                })
                "enter" -> focused()?.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)
                "back" -> performGlobalAction(GLOBAL_ACTION_BACK)
                "home" -> performGlobalAction(GLOBAL_ACTION_HOME)
                "recents" -> performGlobalAction(GLOBAL_ACTION_RECENTS)
                "notifications" -> performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS)
                "wait" -> Thread.sleep(((n.firstOrNull() ?: 1f) * 1000).toLong())
            }
            Thread.sleep(700)
        }
    }

    private fun focused() = rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)

    private fun swipe(x1: Float, y1: Float, x2: Float, y2: Float, ms: Long) {
        val p = Path().apply { moveTo(x1, y1); lineTo(x2, y2) }
        dispatchGesture(GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(p, 0, ms)).build(), null, null)
        Thread.sleep(ms + 150)
    }

    private fun openApp(name: String) {
        val pm = packageManager
        val apps = pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
        val ri = apps.firstOrNull { it.loadLabel(pm).toString().equals(name, true) }
            ?: apps.firstOrNull { it.loadLabel(pm).toString().contains(name, true) } ?: return
        startActivity(pm.getLaunchIntentForPackage(ri.activityInfo.packageName)!!.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        Thread.sleep(1500)
    }

    private fun clickText(t: String) {
        val node = rootInActiveWindow?.findAccessibilityNodeInfosByText(t)?.firstOrNull() ?: return
        var x: AccessibilityNodeInfo? = node
        while (x != null && !x.isClickable) x = x.parent
        if (x != null) x.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        else Rect().also { node.getBoundsInScreen(it); swipe(it.exactCenterX(), it.exactCenterY(), it.exactCenterX(), it.exactCenterY(), 50) }
    }
}

class Fire : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        CtrlService.inst?.run(c.getSharedPreferences("p", 0).getString("s", "")!!)
    }
}

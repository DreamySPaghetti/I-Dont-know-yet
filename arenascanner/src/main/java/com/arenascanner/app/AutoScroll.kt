package com.arenascanner.app

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.view.accessibility.AccessibilityEvent

/** Swipes the Arena collection for the scanner. Android only lets apps swipe through an accessibility service. */
class AutoScroll : AccessibilityService() {
    companion object {
        @Volatile var instance: AutoScroll? = null
            private set
    }

    override fun onServiceConnected() { instance = this }
    override fun onUnbind(intent: android.content.Intent?): Boolean { instance = null; return super.onUnbind(intent) }
    override fun onDestroy() { instance = null; super.onDestroy() }
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    /**
     * Slow horizontal drag from (x1, y) to (x2, y), then a short hold before lifting so Arena
     * doesn't fling the list further than we can read. [done] gets true if the gesture ran.
     */
    fun swipe(x1: Float, y: Float, x2: Float, moveMs: Long, done: (Boolean) -> Unit) {
        val move = GestureDescription.StrokeDescription(Path().apply { moveTo(x1, y); lineTo(x2, y) }, 0, moveMs, true)
        val hold = move.continueStroke(Path().apply { moveTo(x2, y); lineTo(x2 + 1f, y) }, 0, 350, false)
        val ok = dispatchGesture(GestureDescription.Builder().addStroke(move).build(), object : GestureResultCallback() {
            override fun onCompleted(g: GestureDescription?) {
                dispatchGesture(GestureDescription.Builder().addStroke(hold).build(), object : GestureResultCallback() {
                    override fun onCompleted(g: GestureDescription?) = done(true)
                    override fun onCancelled(g: GestureDescription?) = done(false)
                }, null)
            }
            override fun onCancelled(g: GestureDescription?) = done(false)
        }, null)
        if (!ok) done(false)
    }
}

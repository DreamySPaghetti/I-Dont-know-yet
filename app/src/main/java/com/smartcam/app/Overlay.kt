package com.smartcam.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.View

/** Draws the rule-of-thirds grid and the tap-to-focus ring on top of the preview. */
class Overlay(ctx: Context) : View(ctx) {
    var grid = true
        set(v) { field = v; invalidate() }
    private var fx = -1f
    private var fy = -1f
    private val d = resources.displayMetrics.density
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(90, 255, 255, 255); strokeWidth = d }
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(255, 214, 0); style = Paint.Style.STROKE; strokeWidth = 2 * d }

    fun focus(x: Float, y: Float) { fx = x; fy = y; invalidate() }
    fun clearFocus() { fx = -1f; invalidate() }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        if (grid) for (i in 1..2) {
            c.drawLine(w * i / 3, 0f, w * i / 3, h, line)
            c.drawLine(0f, h * i / 3, w, h * i / 3, line)
        }
        if (fx >= 0) c.drawCircle(fx, fy, 32 * d, ring)
    }
}

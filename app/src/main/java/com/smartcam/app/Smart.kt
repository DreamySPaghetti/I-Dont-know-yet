package com.smartcam.app

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

data class Exposure(val iso: Int, val timeNs: Long)

/** Pure decision logic, kept free of Android types so it can be unit-tested. */
object Smart {
    /** Longest shutter (ns) that avoids blur from hand shake (rad/s) and subject motion (mean luma change). */
    fun shutterLimitNs(gyro: Float, subject: Float, zoom: Float, ois: Boolean): Long {
        var t = when {
            gyro < 0.03f -> 1.0 / 15
            gyro < 0.15f -> 1.0 / 50
            else -> 1.0 / 125
        }
        if (ois) t *= 2
        if (subject > 15f) t = min(t, 1.0 / 500) else if (subject > 6f) t = min(t, 1.0 / 250)
        t /= max(1f, zoom)
        return (t * 1e9).toLong()
    }

    /**
     * Keep the brightness the auto-exposure metered (iso × time), shifted by [biasEv],
     * but split it into the lowest ISO (least noise) whose shutter stays under [limitNs].
     */
    fun plan(meterIso: Int, meterNs: Long, biasEv: Float, limitNs: Long,
             isoMin: Int, isoMax: Int, tMin: Long, tMax: Long): Exposure {
        val total = meterIso.toDouble() * meterNs * 2.0.pow(biasEv.toDouble())
        val lo = tMin.toDouble(); val hi = tMax.toDouble()
        var t = min(total / isoMin, limitNs.toDouble()).coerceIn(lo, hi)
        if (total / t > isoMax) t = min(total / isoMax, limitNs * 2.0).coerceIn(lo, hi)
        val iso = (total / t).coerceIn(isoMin.toDouble(), isoMax.toDouble())
        return Exposure(iso.roundToInt(), t.toLong())
    }

    /** Darken when more than 2% of the frame is blown out, up to -1.5 EV. */
    fun highlightBias(clipFrac: Float): Float = if (clipFrac > 0.02f) -min(1.5f, clipFrac * 15f) else 0f

    /** Zoom so the faces' bounding box fills ~45% of the frame, while keeping it inside the centred crop. */
    fun targetZoom(l: Int, t: Int, r: Int, b: Int, w: Int, h: Int, zMin: Float, zMax: Float): Float {
        val frac = max((r - l).toFloat() / w, (b - t).toFloat() / h).coerceAtLeast(0.01f)
        val cx = w / 2f; val cy = h / 2f
        val reach = max(max(abs(l - cx), abs(r - cx)) / cx, max(abs(t - cy), abs(b - cy)) / cy)
        val z = min(0.45f / frac, 0.9f / reach.coerceAtLeast(0.01f))
        return z.coerceIn(zMin, min(zMax, 4f))
    }

    /**
     * Finds the object under a tap in a coarse luma grid by region growing from the tapped cell.
     * Tries looser and looser tolerances and keeps the largest region that hasn't leaked into the
     * background (≤35% of the frame). Returns [l, t, r, b] as 0..1 fractions of the grid.
     */
    fun objectBox(g: FloatArray, gw: Int, gh: Int, sx: Float, sy: Float): FloatArray {
        val x0 = (sx * gw).toInt().coerceIn(0, gw - 1)
        val y0 = (sy * gh).toInt().coerceIn(0, gh - 1)
        var seed = 0f; var sn = 0
        for (y in y0 - 1..y0 + 1) for (x in x0 - 1..x0 + 1) if (x in 0 until gw && y in 0 until gh) { seed += g[y * gw + x]; sn++ }
        seed /= sn
        var best: FloatArray? = null
        for (tol in floatArrayOf(8f, 14f, 22f, 32f)) {
            val seen = BooleanArray(gw * gh)
            val stack = ArrayDeque<Int>().apply { add(y0 * gw + x0) }
            seen[y0 * gw + x0] = true
            var n = 0; var l = x0; var r = x0; var t = y0; var b = y0
            while (stack.isNotEmpty()) {
                val i = stack.removeLast(); val x = i % gw; val y = i / gw
                n++; l = min(l, x); r = max(r, x); t = min(t, y); b = max(b, y)
                for ((nx, ny) in arrayOf(x - 1 to y, x + 1 to y, x to y - 1, x to y + 1)) {
                    if (nx !in 0 until gw || ny !in 0 until gh) continue
                    val j = ny * gw + nx
                    if (!seen[j] && abs(g[j] - seed) <= tol) { seen[j] = true; stack.add(j) }
                }
            }
            val area = n.toFloat() / (gw * gh)
            if (area > 0.35f) break
            if (area >= 0.01f) best = floatArrayOf(l.toFloat() / gw, t.toFloat() / gh, (r + 1f) / gw, (b + 1f) / gh)
        }
        // Nothing distinct found: frame a quarter of the view around the tap.
        return best ?: floatArrayOf(sx - 0.125f, sy - 0.125f, sx + 0.125f, sy + 0.125f)
    }

    /**
     * Crop that makes a box (fractions of the full sensor) fill ~60% of the frame, centred on it.
     * Zoom is capped at [zMax] so the digital crop keeps enough resolution. Returns [cx, cy, zoom].
     */
    fun frameObject(box: FloatArray, zMax: Float): FloatArray {
        val (l, t, r, b) = box
        val z = (0.6f / max(r - l, b - t).coerceAtLeast(0.01f)).coerceIn(1f, max(1f, zMax))
        val cx = ((l + r) / 2).coerceIn(0.5f / z, 1 - 0.5f / z)
        val cy = ((t + b) / 2).coerceIn(0.5f / z, 1 - 0.5f / z)
        return floatArrayOf(cx, cy, z)
    }

    /** Variance of the Laplacian: higher means sharper (less blur / better focus). */
    fun sharpness(lum: IntArray, w: Int, h: Int): Double {
        var sum = 0.0; var sq = 0.0; var n = 0
        for (y in 1 until h - 1) for (x in 1 until w - 1) {
            val i = y * w + x
            val v = (4 * lum[i] - lum[i - 1] - lum[i + 1] - lum[i - w] - lum[i + w]).toDouble()
            sum += v; sq += v * v; n++
        }
        if (n == 0) return 0.0
        val m = sum / n
        return sq / n - m * m
    }
}

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

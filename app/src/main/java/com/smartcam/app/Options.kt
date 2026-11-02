package com.smartcam.app

import android.content.Context

/** User settings, persisted so they survive restarts. Read from the camera thread, so keep reads cheap. */
class Options(ctx: Context) {
    private val p = ctx.getSharedPreferences("options", Context.MODE_PRIVATE)
    private fun b(k: String, d: Boolean) = p.getBoolean(k, d)
    private fun sb(k: String, v: Boolean) = p.edit().putBoolean(k, v).apply()
    private fun i(k: String, d: Int) = p.getInt(k, d)
    private fun si(k: String, v: Int) = p.edit().putInt(k, v).apply()

    var smartExposure: Boolean get() = b("smartExposure", true); set(v) = sb("smartExposure", v)
    var protectHighlights: Boolean get() = b("protectHighlights", true); set(v) = sb("protectHighlights", v)
    var autoZoom: Boolean get() = b("autoZoom", true); set(v) = sb("autoZoom", v)
    var grid: Boolean get() = b("grid", true); set(v) = sb("grid", v)
    var volumeShutter: Boolean get() = b("volumeShutter", true); set(v) = sb("volumeShutter", v)
    /** Number of frames shot per press; the sharpest is kept. */
    var burst: Int get() = i("burst", 3); set(v) = si("burst", v)
    /** 0 = no cap beyond the sensor's own maximum. */
    var maxIso: Int get() = i("maxIso", 0); set(v) = si("maxIso", v)
    var timer: Int get() = i("timer", 0); set(v) = si("timer", v)
    /** Exposure compensation in half-stops, -4..4 (= -2..+2 EV). */
    var evHalfStops: Int get() = i("ev", 0); set(v) = si("ev", v)
    val ev: Float get() = evHalfStops / 2f

    companion object { const val MAX_BURST = 5 }
}

package com.smartcam.app

import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.util.Range
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.roundToInt

/** One lens button: zoom [ratio] on camera [id] (relative to the main lens' field of view). */
data class Stop(val id: String, val ratio: Float) {
    val label: String get() = when {
        ratio < 0.95f -> "%.1f".format(ratio).removePrefix("0")
        abs(ratio - ratio.roundToInt()) < 0.1f -> "${ratio.roundToInt()}x"
        else -> "%.1fx".format(ratio)
    }
}

class LensSet(
    val backId: String?,
    val frontId: String?,
    val backStops: List<Stop>,
    /** Physical lens id → zoom ratio, to show which lens the phone is actually using. */
    val physRatio: Map<String, Float>,
)

/**
 * Finds every camera the phone lets apps use. Phones like Samsung's expose either one "logical"
 * back camera that switches lenses itself as the zoom ratio changes (preferred: smooth, and the
 * phone picks the best lens), or separate camera IDs per lens. Both are collected.
 */
object Lenses {
    /** Field-of-view proxy: focal length over sensor diagonal. Ratios of this are zoom factors. */
    private fun eq(c: CameraCharacteristics): Float? {
        val f = c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.firstOrNull() ?: return null
        val s = c.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE) ?: return null
        return f / hypot(s.width, s.height)
    }

    fun discover(mgr: CameraManager): LensSet {
        val all = mgr.cameraIdList.mapNotNull { id -> runCatching { id to mgr.getCameraCharacteristics(id) }.getOrNull() }
        fun facing(f: Int) = all.filter { it.second.get(CameraCharacteristics.LENS_FACING) == f }
        val backs = facing(CameraMetadata.LENS_FACING_BACK)
        val frontId = facing(CameraMetadata.LENS_FACING_FRONT).firstOrNull()?.first
        fun span(c: CameraCharacteristics) = c.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE)?.let { it.upper / it.lower } ?: 1f
        // Widest zoom span = the logical camera that covers the most lenses; ties go to the lowest id (usually "0").
        val main = backs.maxWithOrNull(compareBy({ span(it.second) }, { -(it.first.toIntOrNull() ?: 999) }))
            ?: return LensSet(null, frontId, emptyList(), emptyMap())
        val mc = main.second
        val base = eq(mc)
        val zr = mc.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE) ?: Range(1f, 1f)
        fun ratio(c: CameraCharacteristics): Float? { val e = eq(c) ?: return null; return if (base == null) null else e / base }

        val phys = HashMap<String, Float>()
        val stops = mutableListOf(Stop(main.first, 1f))
        for (pid in mc.physicalCameraIds) {
            val r = runCatching { ratio(mgr.getCameraCharacteristics(pid)) }.getOrNull() ?: continue
            phys[pid] = r
            if (r >= zr.lower * 0.97f && r <= zr.upper * 1.03f) stops += Stop(main.first, r.coerceIn(zr.lower, zr.upper))
        }
        if (zr.lower < 0.95f && stops.none { it.ratio < 0.95f }) stops += Stop(main.first, zr.lower)
        // Lenses only reachable as their own camera id.
        for ((id, c) in backs) {
            if (id == main.first || id in mc.physicalCameraIds) continue
            val r = ratio(c) ?: continue
            if (stops.none { abs(it.ratio - r) < 0.15f * r }) stops += Stop(id, r)
        }
        val dedup = mutableListOf<Stop>()
        for (s in stops.sortedBy { it.ratio }) if (dedup.none { abs(it.ratio - s.ratio) < 0.15f * s.ratio }) dedup += s
        return LensSet(main.first, frontId, dedup, phys)
    }
}

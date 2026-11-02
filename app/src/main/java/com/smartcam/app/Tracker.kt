package com.smartcam.app

import android.media.Image
import android.os.SystemClock
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.objects.ObjectDetection
import com.google.mlkit.vision.objects.defaults.ObjectDetectorOptions
import java.util.concurrent.Executor
import kotlin.math.max
import kotlin.math.min

/**
 * On-device object detection + tracking (Google ML Kit, bundled model, no network).
 * Stream mode gives each object a tracking id that stays the same across frames.
 */
class Tracker(private val exec: Executor) {
    /** [box] is l,t,r,b as fractions of the analysed frame in sensor orientation; [geo] is the crop when it was taken. */
    class Obj(val id: Int?, val box: FloatArray, val label: String?, val geo: FloatArray)

    private val det = ObjectDetection.getClient(
        ObjectDetectorOptions.Builder()
            .setDetectorMode(ObjectDetectorOptions.STREAM_MODE)
            .enableMultipleObjects()
            .enableClassification()
            .build()
    )
    @Volatile private var busy = false
    private var lastMs = 0L
    @Volatile var objects: List<Obj> = emptyList(); private set
    @Volatile var stampMs = 0L; private set

    /**
     * Runs detection on at most ~10 frames a second. [rotation] makes the image upright for the model;
     * [toFrame] maps upright 0..1 coordinates back to sensor orientation.
     */
    fun feed(img: Image, rotation: Int, toFrame: (Float, Float) -> Pair<Float, Float>, geo: FloatArray, done: () -> Unit) {
        val now = SystemClock.uptimeMillis()
        if (busy || now - lastMs < 100) return
        busy = true; lastMs = now
        val w = img.width; val h = img.height
        val input = InputImage.fromByteArray(nv21(img), w, h, rotation, InputImage.IMAGE_FORMAT_NV21)
        val uw = (if (rotation % 180 == 0) w else h).toFloat()
        val uh = (if (rotation % 180 == 0) h else w).toFloat()
        det.process(input).addOnSuccessListener(exec) { list ->
            objects = list.map { o ->
                val b = o.boundingBox
                val a = toFrame(b.left / uw, b.top / uh)
                val c = toFrame(b.right / uw, b.bottom / uh)
                Obj(o.trackingId, floatArrayOf(min(a.first, c.first), min(a.second, c.second), max(a.first, c.first), max(a.second, c.second)),
                    o.labels.maxByOrNull { it.confidence }?.text, geo)
            }
            stampMs = SystemClock.uptimeMillis()
            busy = false
            done()
        }.addOnFailureListener(exec) { busy = false }
    }

    fun close() = det.close()

    private fun nv21(img: Image): ByteArray {
        val w = img.width; val h = img.height
        val out = ByteArray(w * h * 3 / 2)
        val y = img.planes[0]; val u = img.planes[1]; val v = img.planes[2]
        val yb = y.buffer
        for (r in 0 until h) { yb.position(r * y.rowStride); yb.get(out, r * w, w) }
        val ub = u.buffer; val vb = v.buffer
        var o = w * h
        for (r in 0 until h / 2) for (c in 0 until w / 2) {
            out[o++] = vb.get(r * v.rowStride + c * v.pixelStride)
            out[o++] = ub.get(r * u.rowStride + c * u.pixelStride)
        }
        return out
    }
}

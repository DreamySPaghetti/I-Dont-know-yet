package com.smartcam.app

import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.SurfaceTexture
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.TotalCaptureResult
import android.hardware.camera2.params.Face
import android.media.Image
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.provider.MediaStore
import android.util.Range
import android.view.Surface
import android.view.TextureView
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt
import android.hardware.camera2.CameraCharacteristics as C
import android.hardware.camera2.CameraMetadata as M
import android.hardware.camera2.CaptureRequest as Q
import android.hardware.camera2.CaptureResult as Res

private const val BURST = 3

@SuppressLint("MissingPermission")
class Cam(
    private val ctx: Context,
    private val texture: TextureView,
    private val onHud: (String) -> Unit,
    private val onSaved: (String) -> Unit,
) : SensorEventListener {
    private val thread = HandlerThread("cam").apply { start() }
    private val h = Handler(thread.looper)
    private val mgr = ctx.getSystemService(CameraManager::class.java)
    private val sensors = ctx.getSystemService(SensorManager::class.java)

    private lateinit var chars: C
    private var device: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var opening = false
    private lateinit var preview: Surface
    private lateinit var yuv: ImageReader
    private lateinit var jpeg: ImageReader

    private var manual = false
    private var ois = false
    private var hqNr = false
    private var hqEdge = false
    private var isoRange = Range(100, 1600)
    private var tRange = Range(100_000L, 100_000_000L)
    private var zRange = Range(1f, 1f)
    private var active = Rect()
    private var faceMode = 0

    @Volatile var autoZoom = true
    @Volatile private var zoom = 1f
    private var sentZoom = 1f
    private var zoomTarget = 1f
    private var lastFaceMs = 0L
    private var faceCount = 0

    @Volatile private var gyro = 0f
    private var subject = 0f
    private var clip = 0f
    private var bias = 0f
    private var sentEv = 0
    private var meterIso = 100
    private var meterNs = 10_000_000L
    private var lastGrid: FloatArray? = null
    private var frames = 0

    private var shooting = false
    private var burstSent = false
    private var shootStart = 0L
    private var shotHud = ""
    private val shots = ArrayList<ByteArray>()

    fun open() = h.post {
        if (device != null || opening) return@post
        opening = true
        val id = mgr.cameraIdList.first { mgr.getCameraCharacteristics(it).get(C.LENS_FACING) == M.LENS_FACING_BACK }
        chars = mgr.getCameraCharacteristics(id)
        manual = chars.get(C.REQUEST_AVAILABLE_CAPABILITIES)!!.contains(M.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR)
        chars.get(C.SENSOR_INFO_SENSITIVITY_RANGE)?.let { isoRange = it }
        chars.get(C.SENSOR_INFO_EXPOSURE_TIME_RANGE)?.let { tRange = it }
        chars.get(C.CONTROL_ZOOM_RATIO_RANGE)?.let { zRange = it }
        active = chars.get(C.SENSOR_INFO_ACTIVE_ARRAY_SIZE)!!
        ois = chars.get(C.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION)?.contains(M.LENS_OPTICAL_STABILIZATION_MODE_ON) == true
        hqNr = chars.get(C.NOISE_REDUCTION_AVAILABLE_NOISE_REDUCTION_MODES)?.contains(M.NOISE_REDUCTION_MODE_HIGH_QUALITY) == true
        hqEdge = chars.get(C.EDGE_AVAILABLE_EDGE_MODES)?.contains(M.EDGE_MODE_HIGH_QUALITY) == true
        faceMode = chars.get(C.STATISTICS_INFO_AVAILABLE_FACE_DETECT_MODES)?.maxOrNull() ?: 0

        val map = chars.get(C.SCALER_STREAM_CONFIGURATION_MAP)!!
        fun is43(w: Int, hh: Int) = w * 3 == hh * 4
        val jpegs = map.getOutputSizes(ImageFormat.JPEG)
        val js = jpegs.filter { is43(it.width, it.height) }.maxByOrNull { it.width * it.height }
            ?: jpegs.maxBy { it.width * it.height }
        val previews = map.getOutputSizes(SurfaceTexture::class.java).filter { it.width <= 1920 }
        val ps = previews.filter { is43(it.width, it.height) }.maxByOrNull { it.width * it.height }
            ?: previews.maxBy { it.width * it.height }
        val ys = map.getOutputSizes(ImageFormat.YUV_420_888).filter { it.width <= 640 }.maxBy { it.width * it.height }

        yuv = ImageReader.newInstance(ys.width, ys.height, ImageFormat.YUV_420_888, 2)
        yuv.setOnImageAvailableListener({ r -> r.acquireLatestImage()?.use { analyze(it) } }, h)
        jpeg = ImageReader.newInstance(js.width, js.height, ImageFormat.JPEG, BURST + 1)
        jpeg.setOnImageAvailableListener({ r -> r.acquireNextImage()?.use { onJpeg(it) } }, h)
        texture.surfaceTexture!!.setDefaultBufferSize(ps.width, ps.height)
        preview = Surface(texture.surfaceTexture)

        sensors.getDefaultSensor(Sensor.TYPE_GYROSCOPE)?.let { sensors.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME, h) }
        mgr.openCamera(id, object : CameraDevice.StateCallback() {
            override fun onOpened(d: CameraDevice) { device = d; opening = false; startSession() }
            override fun onDisconnected(d: CameraDevice) { d.close(); device = null; opening = false }
            override fun onError(d: CameraDevice, e: Int) { d.close(); device = null; opening = false; onHud("Camera error $e") }
        }, h)
    }

    fun close() = h.post {
        sensors.unregisterListener(this)
        session?.close(); session = null
        device?.close(); device = null
        if (::yuv.isInitialized) { yuv.close(); jpeg.close() }
        shooting = false
    }

    fun pinch(scale: Float) {
        autoZoom = false
        zoom = (zoom * scale).coerceIn(zRange.lower, zRange.upper)
    }

    fun shoot() = h.post {
        val s = session ?: return@post
        if (shooting) return@post
        shooting = true; burstSent = false; shots.clear()
        shootStart = SystemClock.uptimeMillis()
        s.capture(previewReq().apply { set(Q.CONTROL_AF_TRIGGER, M.CONTROL_AF_TRIGGER_START) }.build(), cb, h)
    }

    @Suppress("DEPRECATION")
    private fun startSession() {
        device!!.createCaptureSession(listOf(preview, yuv.surface, jpeg.surface), object : CameraCaptureSession.StateCallback() {
            override fun onConfigured(s: CameraCaptureSession) { session = s; repeat() }
            override fun onConfigureFailed(s: CameraCaptureSession) { onHud("Camera setup failed") }
        }, h)
    }

    private fun base(template: Int) = device!!.createCaptureRequest(template).apply {
        set(Q.CONTROL_MODE, M.CONTROL_MODE_AUTO)
        set(Q.CONTROL_AF_MODE, M.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
        set(Q.CONTROL_AWB_MODE, M.CONTROL_AWB_MODE_AUTO)
        set(Q.CONTROL_ZOOM_RATIO, zoom)
        if (ois) set(Q.LENS_OPTICAL_STABILIZATION_MODE, M.LENS_OPTICAL_STABILIZATION_MODE_ON)
    }

    // Preview keeps the camera's own auto-exposure running: its result is our light meter.
    private fun previewReq() = base(CameraDevice.TEMPLATE_PREVIEW).apply {
        addTarget(preview); addTarget(yuv.surface)
        set(Q.CONTROL_AE_MODE, M.CONTROL_AE_MODE_ON)
        set(Q.STATISTICS_FACE_DETECT_MODE, faceMode)
        if (!manual) set(Q.CONTROL_AE_EXPOSURE_COMPENSATION, evSteps())
    }

    private fun repeat() {
        val s = session ?: return
        sentZoom = zoom; sentEv = evSteps()
        s.setRepeatingRequest(previewReq().build(), cb, h)
    }

    private fun evSteps(): Int {
        val step = chars.get(C.CONTROL_AE_COMPENSATION_STEP)?.toFloat() ?: return 0
        val range = chars.get(C.CONTROL_AE_COMPENSATION_RANGE) ?: return 0
        return (bias / step).roundToInt().coerceIn(range.lower, range.upper)
    }

    private fun plan() = Smart.plan(
        meterIso, meterNs, bias, Smart.shutterLimitNs(gyro, subject, zoom, ois),
        isoRange.lower, isoRange.upper, tRange.lower, min(tRange.upper, 500_000_000L),
    )

    private val cb = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureCompleted(s: CameraCaptureSession, r: Q, res: TotalCaptureResult) {
            if (shooting && burstSent) return
            res.get(Res.SENSOR_SENSITIVITY)?.let { meterIso = it }
            res.get(Res.SENSOR_EXPOSURE_TIME)?.let { meterNs = it }
            updateZoom(res.get(Res.STATISTICS_FACES))
            if (abs(zoom - sentZoom) > 0.01f || (!manual && evSteps() != sentEv)) repeat()
            if (shooting && !burstSent) {
                val af = res.get(Res.CONTROL_AF_STATE)
                val locked = af == M.CONTROL_AF_STATE_FOCUSED_LOCKED || af == M.CONTROL_AF_STATE_NOT_FOCUSED_LOCKED
                if (locked || SystemClock.uptimeMillis() - shootStart > 1500) fireBurst()
            }
            if (++frames % 8 == 0) onHud(hud())
        }
    }

    private fun hud(): String {
        val e = plan()
        val shutter = if (e.timeNs >= 500_000_000) "%.1fs".format(e.timeNs / 1e9) else "1/${(1e9 / e.timeNs).roundToInt()}s"
        val hold = when { gyro < 0.03f -> "steady"; gyro < 0.15f -> "slight shake"; else -> "shaky" }
        val mode = if (manual) "" else " (auto-exposure device)"
        return "ISO ${e.iso} · $shutter · %.1fx · %+.1f EV\n$hold · motion %.0f · faces $faceCount$mode"
            .format(zoom, bias, subject)
    }

    private fun updateZoom(faces: Array<Face>?) {
        val f = faces?.filter { it.score >= 50 }.orEmpty()
        faceCount = f.size
        if (!autoZoom) return
        val now = SystemClock.uptimeMillis()
        val target = if (f.isNotEmpty()) {
            lastFaceMs = now
            Smart.targetZoom(
                f.minOf { it.bounds.left } - active.left, f.minOf { it.bounds.top } - active.top,
                f.maxOf { it.bounds.right } - active.left, f.maxOf { it.bounds.bottom } - active.top,
                active.width(), active.height(), zRange.lower, zRange.upper,
            )
        } else if (now - lastFaceMs > 1500) 1f.coerceIn(zRange.lower, zRange.upper) else zoomTarget
        if (abs(target - zoomTarget) / zoomTarget > 0.15f) zoomTarget = target
        zoom += (zoomTarget - zoom) * 0.08f
    }

    private fun analyze(img: Image) {
        val p = img.planes[0]
        val buf = p.buffer; val rs = p.rowStride
        val w = img.width; val hh = img.height
        val gw = 32; val gh = 24
        val sum = FloatArray(gw * gh); val cnt = IntArray(gw * gh)
        var clipped = 0; var n = 0
        for (y in 0 until hh step 4) for (x in 0 until w step 4) {
            val v = buf.get(y * rs + x).toInt() and 0xFF
            if (v >= 250) clipped++
            n++
            val c = (y * gh / hh) * gw + x * gw / w
            sum[c] += v.toFloat(); cnt[c]++
        }
        for (i in sum.indices) if (cnt[i] > 0) sum[i] /= cnt[i]
        val prev = lastGrid
        // Only count luma change as subject motion when the phone itself is still.
        val diff = if (prev != null && gyro < 0.1f) sum.indices.sumOf { abs(sum[it] - prev[it]).toDouble() }.toFloat() / sum.size else 0f
        lastGrid = sum
        subject = subject * 0.7f + diff * 0.3f
        clip = clip * 0.7f + clipped.toFloat() / n * 0.3f
        bias += (Smart.highlightBias(clip) - bias) * 0.2f
    }

    private fun fireBurst() {
        burstSent = true
        val e = plan()
        shotHud = hud().substringBefore('\n')
        val reqs = List(BURST) {
            base(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                addTarget(jpeg.surface)
                if (hqNr) set(Q.NOISE_REDUCTION_MODE, M.NOISE_REDUCTION_MODE_HIGH_QUALITY)
                if (hqEdge) set(Q.EDGE_MODE, M.EDGE_MODE_HIGH_QUALITY)
                set(Q.JPEG_QUALITY, 95.toByte())
                set(Q.JPEG_ORIENTATION, chars.get(C.SENSOR_ORIENTATION) ?: 90)
                if (manual) {
                    set(Q.CONTROL_AE_MODE, M.CONTROL_AE_MODE_OFF)
                    set(Q.SENSOR_SENSITIVITY, e.iso)
                    set(Q.SENSOR_EXPOSURE_TIME, e.timeNs)
                    set(Q.SENSOR_FRAME_DURATION, e.timeNs)
                } else {
                    set(Q.CONTROL_AE_MODE, M.CONTROL_AE_MODE_ON)
                    set(Q.CONTROL_AE_EXPOSURE_COMPENSATION, evSteps())
                }
            }.build()
        }
        session?.captureBurst(reqs, null, h)
    }

    private fun onJpeg(img: Image) {
        val b = img.planes[0].buffer
        val bytes = ByteArray(b.remaining()).also { b.get(it) }
        if (!shooting) return
        shots += bytes
        if (shots.size < BURST) return
        val list = shots.toList()
        shooting = false
        session?.capture(previewReq().apply { set(Q.CONTROL_AF_TRIGGER, M.CONTROL_AF_TRIGGER_CANCEL) }.build(), cb, h)
        val info = shotHud
        Thread { save(list.maxBy(::sharpness), info) }.start()
    }

    private fun sharpness(jpg: ByteArray): Double {
        val bmp = BitmapFactory.decodeByteArray(jpg, 0, jpg.size, BitmapFactory.Options().apply { inSampleSize = 8 }) ?: return 0.0
        val w = bmp.width; val hh = bmp.height
        val px = IntArray(w * hh).also { bmp.getPixels(it, 0, w, 0, 0, w, hh) }
        bmp.recycle()
        val lum = IntArray(px.size) { val c = px[it]; ((c shr 16 and 255) * 3 + (c shr 8 and 255) * 6 + (c and 255)) / 10 }
        return Smart.sharpness(lum, w, hh)
    }

    private fun save(jpg: ByteArray, info: String) {
        val v = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "SmartCam_${System.currentTimeMillis()}.jpg")
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/SmartCam")
        }
        val r = ctx.contentResolver
        val uri = r.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, v)
        if (uri == null) { onSaved("Save failed"); return }
        r.openOutputStream(uri)?.use { it.write(jpg) }
        onSaved("Saved sharpest of $BURST · $info")
    }

    override fun onSensorChanged(e: SensorEvent) {
        val (x, y, z) = e.values
        gyro = gyro * 0.8f + sqrt(x * x + y * y + z * z) * 0.2f
    }

    override fun onAccuracyChanged(s: Sensor, a: Int) {}
}

package com.smartcam.app

import android.Manifest
import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
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
import android.hardware.camera2.params.MeteringRectangle
import android.media.CamcorderProfile
import android.media.Image
import android.media.ImageReader
import android.media.MediaCodec
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.provider.MediaStore
import android.util.Range
import android.util.Size
import android.view.Surface
import android.view.TextureView
import java.io.File
import java.io.FileDescriptor
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt
import android.hardware.camera2.CameraCharacteristics as C
import android.hardware.camera2.CameraMetadata as M
import android.hardware.camera2.CaptureRequest as Q
import android.hardware.camera2.CaptureResult as Res

interface CamListener {
    fun hud(text: String, zoom: Float)
    fun busy(busy: Boolean)
    fun captured()
    fun saved(uri: Uri, msg: String)
    fun error(msg: String)
    /** Session ready: preview aspect (long side / short side), lens buttons, which camera. */
    fun configured(aspect: Float, stops: List<Stop>, front: Boolean, video: Boolean)
    fun recording(on: Boolean)
}

private const val GW = 64
private const val GH = 48

/**
 * Zoom model: the view is a centre (vx, vy, as fractions of the 1x field of view) and a total zoom vT.
 * It is split into the phone's zoom ratio (which makes the phone switch to the best physical lens) and
 * a crop on top of it for anything off-centre or beyond what the zoom ratio reaches.
 */
private class Geo(val zr: Float, val czx: Float, val czy: Float, val k: Float) {
    fun toArray() = floatArrayOf(zr, czx, czy, k)
}

@SuppressLint("MissingPermission")
class Cam(
    private val ctx: Context,
    private val texture: TextureView,
    private val opts: Options,
    private val out: CamListener,
) : SensorEventListener {
    private val thread = HandlerThread("cam").apply { start() }
    private val h = Handler(thread.looper)
    private val mgr = ctx.getSystemService(CameraManager::class.java)
    private val sensors = ctx.getSystemService(SensorManager::class.java)
    private val tracker = Tracker { h.post(it) }
    private var lenses: LensSet? = null

    @Volatile var front = false; private set
    @Volatile var video = false; private set
    @Volatile var isRecording = false; private set
    private var camId: String? = null
    private var pendingZoom = 1f

    private lateinit var chars: C
    private var device: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var opening = false
    private var preview: Surface? = null
    private var yuv: ImageReader? = null
    private var jpeg: ImageReader? = null
    private var recSurface: Surface? = null
    private var recorder: MediaRecorder? = null
    private var recUri: Uri? = null
    private var recFd: ParcelFileDescriptor? = null
    private var recStart = 0L
    private var videoSize = Size(1920, 1080)

    private var manual = false
    private var ois = false
    private var hqNr = false
    private var hqEdge = false
    private var fixedFocus = false
    private var videoStab = false
    private var fps30: Range<Int>? = null
    private var maxAf = 0
    private var maxAe = 0
    private var isoRange = Range(100, 1600)
    private var tRange = Range(100_000L, 100_000_000L)
    private var zRange = Range(1f, 1f)
    private var active = Rect()
    private var faceMode = 0
    private var sensorOrientation = 90
    private var teleRatio = 0f
    private var zMaxPan = 4f

    // Current view and the target it eases toward.
    private var vx = 0.5f; private var vy = 0.5f; private var vT = 1f
    private var tx = 0.5f; private var ty = 0.5f; private var tT = 1f
    private var ease = 0.1f
    private var sentGeo = FloatArray(4)
    private var lowLight = false
    private var activePhys: String? = null

    // Face auto zoom.
    private var lastFaceMs = 0L
    private var faceCount = 0
    // Double-tap target: pinned = framing an object; trackId = ML Kit id being followed (null = fixed spot).
    private var pinned = false
    private var trackId: Int? = null
    private var trackLabel: String? = null
    private var trackSeen = 0L
    private var trackCenter = floatArrayOf(0.5f, 0.5f)
    private var focusRect: MeteringRectangle? = null
    private var focusWhenSettled = false

    @Volatile private var gyro = 0f
    private var subject = 0f
    private var clip = 0f
    private var hiBias = 0f
    private var sentEv = 0
    private var meterIso = 100
    private var meterNs = 10_000_000L
    private var lastGrid: FloatArray? = null
    private var gridGeo: Geo? = null
    private var frames = 0

    private var shooting = false
    private var burstSent = false
    private var burstSize = 3
    private var shootStart = 0L
    private var shotInfo = ""
    private val shots = ArrayList<ByteArray>()

    private val useManualExposure get() = manual && opts.smartExposure && !video

    // ---------------------------------------------------------------- public API (any thread)

    fun open() = h.post {
        if (device != null || opening) return@post
        val l = lenses ?: runCatching { Lenses.discover(mgr) }.getOrNull()?.also { lenses = it }
        val id = camId ?: (if (front) l?.frontId else l?.backId) ?: mgr.cameraIdList.firstOrNull()
        if (id == null) { out.error("No camera found"); return@post }
        openId(id)
    }

    fun close() = h.post { closeDevice() }

    fun release() = h.post { closeDevice(); tracker.close(); thread.quitSafely() }

    fun setFront(f: Boolean) = h.post {
        if (isRecording || f == front) return@post
        front = f
        val l = lenses ?: return@post
        switchTo((if (f) l.frontId else l.backId) ?: return@post, 1f)
    }

    fun setVideo(v: Boolean) = h.post {
        if (isRecording || v == video) return@post
        video = v
        if (device != null) configure()
    }

    fun selectStop(s: Stop) = h.post {
        opts.autoZoom = false
        unpin()
        if (s.id != camId) { switchTo(s.id, if (s.id == lenses?.backId) s.ratio else 1f); return@post }
        tx = 0.5f; ty = 0.5f; tT = s.ratio.coerceIn(zRange.lower, pinchMax()); ease = 0.25f
    }

    fun pinch(scale: Float) = h.post {
        opts.autoZoom = false
        unpin()
        vT = (vT * scale).coerceIn(zRange.lower, pinchMax()); tT = vT
        if (vT <= 1f) { vx = 0.5f; vy = 0.5f } else {
            vx = vx.coerceIn(0.5f / vT, 1 - 0.5f / vT); vy = vy.coerceIn(0.5f / vT, 1 - 0.5f / vT)
        }
        tx = vx; ty = vy
    }

    /** Focus and meter on a point given as 0..1 fractions of the portrait preview. */
    fun focusAt(nx: Float, ny: Float) = h.post {
        val (sx, sy) = viewToFrame(nx, ny)
        val g = geo()
        focusOnZoomed(g.czx + (sx - 0.5f) / g.k, g.czy + (sy - 0.5f) / g.k, 0.05f / g.k)
    }

    fun clearFocus() = h.post {
        if (focusRect == null) return@post
        focusRect = null
        if (!fixedFocus) safe { session?.capture(previewReq().apply { set(Q.CONTROL_AF_TRIGGER, M.CONTROL_AF_TRIGGER_CANCEL) }.build(), cb, h) }
        repeat()
    }

    /**
     * Double-tap: if an object is being framed, let go (auto zoom takes over again). Otherwise find the
     * object under the finger — preferring ML Kit's detections, which are then followed as they move —
     * and frame it at ~60% of the view, using the longest lens that can see it.
     */
    fun zoomTo(nx: Float, ny: Float) = h.post {
        if (session == null) return@post
        if (pinned) { unpin(); focusRect = null; focusWhenSettled = false; tx = 0.5f; ty = 0.5f; tT = 1f.coerceIn(zRange.lower, zRange.upper); ease = 0.2f; return@post }
        val (sx, sy) = viewToFrame(nx, ny)
        val tap = frameToFull(sx, sy, geo().toArray())
        val fresh = SystemClock.uptimeMillis() - tracker.stampMs < 600
        val objs = if (fresh) tracker.objects.map { it to toFull(it) } else emptyList()
        val margin = 0.03f / vT
        val hit = objs.filter { (_, b) -> tap.first in b[0] - margin..b[2] + margin && tap.second in b[1] - margin..b[3] + margin }
            .minByOrNull { (_, b) -> (b[2] - b[0]) * (b[3] - b[1]) }
            ?: objs.minByOrNull { (_, b) -> dist(b, tap) }?.takeIf { (_, b) -> dist(b, tap) < 0.12f / vT }
        val box: FloatArray
        if (hit != null) {
            box = hit.second; trackId = hit.first.id; trackLabel = hit.first.label
        } else {
            val g = lastGrid ?: return@post
            val gg = gridGeo?.toArray() ?: return@post
            val b = Smart.objectBox(g, GW, GH, sx, sy)
            val a = frameToFull(b[0], b[1], gg); val c = frameToFull(b[2], b[3], gg)
            box = floatArrayOf(a.first, a.second, c.first, c.second)
            trackId = null; trackLabel = null
        }
        pinned = true
        trackSeen = SystemClock.uptimeMillis()
        frameBox(box, force = true)
        // Regions live in the zoomed view's coordinates, so focus once the glide ends and the object is centred.
        focusWhenSettled = true
    }

    fun shoot() = h.post {
        if (video) { if (isRecording) stopRecording() else startRecording(); return@post }
        val s = session ?: run { out.error("Camera not ready"); return@post }
        if (shooting) return@post
        shooting = true; burstSent = false; shots.clear()
        burstSize = opts.burst.coerceIn(1, Options.MAX_BURST)
        shootStart = SystemClock.uptimeMillis()
        out.busy(true)
        if (fixedFocus) { fireBurst(); return@post }
        if (!safe { s.capture(previewReq().apply { set(Q.CONTROL_AF_TRIGGER, M.CONTROL_AF_TRIGGER_START) }.build(), cb, h) }) {
            fail("Capture failed"); return@post
        }
        val started = shootStart
        h.postDelayed({ if (shooting && shootStart == started) fail("Capture timed out, try again") }, 8000)
    }

    // ---------------------------------------------------------------- opening / sessions

    private fun switchTo(id: String, zoom: Float) {
        closeDevice()
        pendingZoom = zoom
        openId(id)
    }

    private fun openId(id: String) {
        opening = true
        try {
            chars = mgr.getCameraCharacteristics(id)
            camId = id
            front = chars.get(C.LENS_FACING) == M.LENS_FACING_FRONT
            readCaps()
            vx = 0.5f; vy = 0.5f; vT = pendingZoom.coerceIn(zRange.lower, zRange.upper)
            tx = 0.5f; ty = 0.5f; tT = vT; pendingZoom = 1f
            unpin(); focusRect = null
            sensors.getDefaultSensor(Sensor.TYPE_GYROSCOPE)?.let { sensors.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME, h) }
            mgr.openCamera(id, object : CameraDevice.StateCallback() {
                override fun onOpened(d: CameraDevice) { device = d; opening = false; configure() }
                override fun onDisconnected(d: CameraDevice) {
                    d.close(); device = null; opening = false
                    out.error("Camera was taken by another app")
                }
                override fun onError(d: CameraDevice, e: Int) {
                    d.close(); device = null; opening = false
                    out.error(when (e) {
                        CameraDevice.StateCallback.ERROR_CAMERA_IN_USE, CameraDevice.StateCallback.ERROR_MAX_CAMERAS_IN_USE -> "Camera is in use by another app"
                        CameraDevice.StateCallback.ERROR_CAMERA_DISABLED -> "Camera is disabled on this device"
                        else -> "Camera error ($e), reopen the app"
                    })
                }
            }, h)
        } catch (e: Exception) {
            opening = false
            out.error("Can't open camera: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    private fun readCaps() {
        manual = chars.get(C.REQUEST_AVAILABLE_CAPABILITIES)?.contains(M.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR) == true
        isoRange = chars.get(C.SENSOR_INFO_SENSITIVITY_RANGE) ?: Range(100, 1600)
        tRange = chars.get(C.SENSOR_INFO_EXPOSURE_TIME_RANGE) ?: Range(100_000L, 100_000_000L)
        zRange = chars.get(C.CONTROL_ZOOM_RATIO_RANGE) ?: Range(1f, 1f)
        active = chars.get(C.SENSOR_INFO_ACTIVE_ARRAY_SIZE)!!
        sensorOrientation = chars.get(C.SENSOR_ORIENTATION) ?: 90
        ois = chars.get(C.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION)?.contains(M.LENS_OPTICAL_STABILIZATION_MODE_ON) == true
        hqNr = chars.get(C.NOISE_REDUCTION_AVAILABLE_NOISE_REDUCTION_MODES)?.contains(M.NOISE_REDUCTION_MODE_HIGH_QUALITY) == true
        hqEdge = chars.get(C.EDGE_AVAILABLE_EDGE_MODES)?.contains(M.EDGE_MODE_HIGH_QUALITY) == true
        fixedFocus = (chars.get(C.LENS_INFO_MINIMUM_FOCUS_DISTANCE) ?: 0f) == 0f
        videoStab = chars.get(C.CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES)?.contains(M.CONTROL_VIDEO_STABILIZATION_MODE_ON) == true
        fps30 = chars.get(C.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)?.firstOrNull { it.lower == 30 && it.upper == 30 }
        faceMode = chars.get(C.STATISTICS_INFO_AVAILABLE_FACE_DETECT_MODES)?.maxOrNull() ?: 0
        maxAf = chars.get(C.CONTROL_MAX_REGIONS_AF) ?: 0
        maxAe = chars.get(C.CONTROL_MAX_REGIONS_AE) ?: 0
        val stops = stopsHere()
        teleRatio = stops.map { it.ratio }.firstOrNull { it >= 1.8f } ?: 0f
        // Double-tap may crop up to 1.3x past the longest lens (or 4x on the main lens alone).
        zMaxPan = max(4f, (stops.maxOfOrNull { it.ratio } ?: 1f) * 1.3f)
    }

    private fun stopsHere(): List<Stop> {
        val l = lenses
        return if (front || l == null) listOf(Stop(camId ?: "0", 1f)) else l.backStops
    }

    private fun pinchMax() = max(zRange.upper, zMaxPan)

    private fun closeDevice() {
        sensors.unregisterListener(this)
        if (isRecording) stopRecording()
        runCatching { session?.close() }; session = null
        runCatching { device?.close() }; device = null
        releaseOutputs()
        if (shooting) { shooting = false; out.busy(false) }
        opening = false
    }

    private fun releaseOutputs() {
        yuv?.close(); jpeg?.close(); preview?.release(); recSurface?.release()
        yuv = null; jpeg = null; preview = null; recSurface = null
    }

    private fun pickSize(sizes: Array<Size>, maxW: Int, aspect: Float): Size {
        val ok = sizes.filter { it.width <= maxW }.ifEmpty { sizes.toList() }
        return ok.filter { abs(it.width.toFloat() / it.height - aspect) < 0.02f }.maxByOrNull { it.width * it.height }
            ?: ok.maxBy { it.width * it.height }
    }

    private fun configure(withYuv: Boolean = true) {
        val d = device ?: return
        runCatching { session?.close() }; session = null
        releaseOutputs()
        try {
            val map = chars.get(C.SCALER_STREAM_CONFIGURATION_MAP)!!
            val aspect = if (video) 16f / 9 else 4f / 3
            val ps = pickSize(map.getOutputSizes(SurfaceTexture::class.java), 1920, aspect)
            val st = texture.surfaceTexture ?: throw IllegalStateException("preview not ready")
            st.setDefaultBufferSize(ps.width, ps.height)
            val surfaces = mutableListOf(Surface(st).also { preview = it })
            if (withYuv) {
                val ys = pickSize(map.getOutputSizes(ImageFormat.YUV_420_888), 640, aspect)
                yuv = ImageReader.newInstance(ys.width, ys.height, ImageFormat.YUV_420_888, 2).apply {
                    setOnImageAvailableListener({ r -> r.acquireLatestImage()?.use { analyze(it) } }, h)
                }
                surfaces += yuv!!.surface
            }
            if (video) {
                videoSize = pickVideoSize(map)
                recSurface = MediaCodec.createPersistentInputSurface()
                // A recorder must be prepared once so the persistent surface gets its size.
                newRecorder(null, false).apply { prepare(); release() }
                surfaces += recSurface!!
            } else {
                val jpegs = map.getOutputSizes(ImageFormat.JPEG)
                val js = jpegs.filter { abs(it.width.toFloat() / it.height - 4f / 3) < 0.02f }.maxByOrNull { it.width * it.height }
                    ?: jpegs.maxBy { it.width * it.height }
                jpeg = ImageReader.newInstance(js.width, js.height, ImageFormat.JPEG, Options.MAX_BURST + 1).apply {
                    setOnImageAvailableListener({ r -> r.acquireNextImage()?.use { onJpeg(it) } }, h)
                }
                surfaces += jpeg!!.surface
            }
            out.configured(ps.width.toFloat() / ps.height, stopsHere(), front, video)
            @Suppress("DEPRECATION")
            d.createCaptureSession(surfaces, object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(s: CameraCaptureSession) { session = s; repeat() }
                override fun onConfigureFailed(s: CameraCaptureSession) {
                    // Some phones can't run analysis alongside 4K recording; retry without it.
                    if (withYuv && video) configure(false) else out.error("Camera setup failed on this device")
                }
            }, h)
        } catch (e: Exception) {
            out.error("Camera setup failed: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    // ---------------------------------------------------------------- requests

    /** Runs a camera call, swallowing the errors a closing camera throws. */
    private inline fun safe(block: () -> Unit): Boolean = try { block(); true } catch (e: Exception) { false }

    private fun geo(): Geo {
        var zr = vT
        val reach = max(abs(vx - 0.5f), abs(vy - 0.5f))
        // Off-centre framing: the zoom ratio must still show the whole window, the crop does the rest.
        if (reach > 1e-3f) zr = min(zr, 0.5f / (reach + 0.5f / vT))
        // In low light the main sensor (bigger, brighter lens) beats the telephoto, so crop it instead.
        if (lowLight && teleRatio > 0f && zr >= teleRatio * 0.97f) zr = teleRatio * 0.95f
        zr = zr.coerceIn(zRange.lower, zRange.upper)
        val k = max(1f, vT / zr)
        val czx = (0.5f + (vx - 0.5f) * zr).coerceIn(0.5f / k, 1 - 0.5f / k)
        val czy = (0.5f + (vy - 0.5f) * zr).coerceIn(0.5f / k, 1 - 0.5f / k)
        return Geo(zr, czx, czy, k)
    }

    private fun base(template: Int) = device!!.createCaptureRequest(template).apply {
        set(Q.CONTROL_MODE, M.CONTROL_MODE_AUTO)
        set(Q.CONTROL_AWB_MODE, M.CONTROL_AWB_MODE_AUTO)
        val g = geo()
        set(Q.CONTROL_ZOOM_RATIO, g.zr)
        // Crop is in the zoomed view's coordinates, which span the whole active array.
        val w = (active.width() / g.k).toInt(); val hh = (active.height() / g.k).toInt()
        val l = (g.czx * active.width() - w / 2f).toInt().coerceIn(0, active.width() - w)
        val t = (g.czy * active.height() - hh / 2f).toInt().coerceIn(0, active.height() - hh)
        set(Q.SCALER_CROP_REGION, Rect(l, t, l + w, t + hh))
        if (ois) set(Q.LENS_OPTICAL_STABILIZATION_MODE, M.LENS_OPTICAL_STABILIZATION_MODE_ON)
        val fr = focusRect
        val afMode = when {
            fr != null && maxAf > 0 -> M.CONTROL_AF_MODE_AUTO
            video -> M.CONTROL_AF_MODE_CONTINUOUS_VIDEO
            else -> M.CONTROL_AF_MODE_CONTINUOUS_PICTURE
        }
        if (!fixedFocus) set(Q.CONTROL_AF_MODE, afMode)
        if (fr != null && maxAf > 0) set(Q.CONTROL_AF_REGIONS, arrayOf(fr))
        if (fr != null && maxAe > 0) set(Q.CONTROL_AE_REGIONS, arrayOf(fr))
    }

    // The preview keeps the camera's own auto-exposure running: its result is our light meter.
    // The user's EV is applied here so the preview shows it; highlight protection is added at capture.
    private fun previewEv() = opts.ev + if (useManualExposure) 0f else hiBias

    private fun previewReq() = base(if (video) CameraDevice.TEMPLATE_RECORD else CameraDevice.TEMPLATE_PREVIEW).apply {
        addTarget(preview!!)
        yuv?.let { addTarget(it.surface) }
        if (isRecording) addTarget(recSurface!!)
        set(Q.CONTROL_AE_MODE, M.CONTROL_AE_MODE_ON)
        set(Q.STATISTICS_FACE_DETECT_MODE, faceMode)
        set(Q.CONTROL_AE_EXPOSURE_COMPENSATION, evSteps(previewEv()))
        if (video) {
            fps30?.let { set(Q.CONTROL_AE_TARGET_FPS_RANGE, it) }
            if (videoStab) set(Q.CONTROL_VIDEO_STABILIZATION_MODE, M.CONTROL_VIDEO_STABILIZATION_MODE_ON)
        }
    }

    private fun repeat() {
        val s = session ?: return
        sentGeo = geo().toArray(); sentEv = evSteps(previewEv())
        safe { s.setRepeatingRequest(previewReq().build(), cb, h) }
    }

    private fun evSteps(ev: Float): Int {
        val step = chars.get(C.CONTROL_AE_COMPENSATION_STEP)?.toFloat() ?: return 0
        val range = chars.get(C.CONTROL_AE_COMPENSATION_RANGE) ?: return 0
        if (step <= 0f) return 0
        return (ev / step).roundToInt().coerceIn(range.lower, range.upper)
    }

    private fun plan(): Exposure {
        val cap = opts.maxIso
        val isoMax = if (cap > 0) cap.coerceIn(isoRange.lower, isoRange.upper) else isoRange.upper
        return Smart.plan(meterIso, meterNs, hiBias, Smart.shutterLimitNs(gyro, subject, vT, ois),
            isoRange.lower, isoMax, tRange.lower, min(tRange.upper, 500_000_000L))
    }

    // ---------------------------------------------------------------- coordinates

    /** Upright 0..1 coordinates → fraction of the camera frame (sensor orientation). */
    private fun orient(x: Float, y: Float) = when (sensorOrientation) {
        90 -> y to 1 - x
        270 -> 1 - y to x
        180 -> 1 - x to 1 - y
        else -> x to y
    }

    /** Portrait preview tap → frame fraction. The front preview is mirrored, so undo that first. */
    private fun viewToFrame(nx: Float, ny: Float) = orient(if (front) 1 - nx else nx, ny)

    /** Frame fraction → fraction of the 1x field of view, given the crop [g] (zr, czx, czy, k) the frame had. */
    private fun frameToFull(sx: Float, sy: Float, g: FloatArray): Pair<Float, Float> {
        val zx = g[1] + (sx - 0.5f) / g[3]; val zy = g[2] + (sy - 0.5f) / g[3]
        return (0.5f + (zx - 0.5f) / g[0]) to (0.5f + (zy - 0.5f) / g[0])
    }

    private fun toFull(o: Tracker.Obj): FloatArray {
        val a = frameToFull(o.box[0], o.box[1], o.geo); val c = frameToFull(o.box[2], o.box[3], o.geo)
        return floatArrayOf(a.first, a.second, c.first, c.second)
    }

    private fun dist(b: FloatArray, p: Pair<Float, Float>) = hypot((b[0] + b[2]) / 2 - p.first, (b[1] + b[3]) / 2 - p.second)

    /** Focus/meter region centred on a point of the zoomed view (0..1); regions use the zoomed view's coordinates. */
    private fun focusOnZoomed(zx: Float, zy: Float, halfFrac: Float) {
        if (session == null) return
        val w = active.width(); val hh = active.height()
        val half = (max(w, hh) * halfFrac).toInt().coerceIn(8, max(w, hh) / 3)
        val l = (zx * w - half).toInt().coerceIn(0, w - 2)
        val t = (zy * hh - half).toInt().coerceIn(0, hh - 2)
        focusRect = MeteringRectangle(l, t, min(2 * half, w - l - 1).coerceAtLeast(1),
            min(2 * half, hh - t - 1).coerceAtLeast(1), MeteringRectangle.METERING_WEIGHT_MAX - 1)
        repeat()
        if (!fixedFocus) safe { session?.capture(previewReq().apply { set(Q.CONTROL_AF_TRIGGER, M.CONTROL_AF_TRIGGER_START) }.build(), cb, h) }
    }

    // ---------------------------------------------------------------- zoom behaviour

    private fun unpin() { pinned = false; trackId = null; trackLabel = null }

    /** Aim the view at a box (1x-view fractions) so it fills ~60%. Small moves are ignored to avoid jitter. */
    private fun frameBox(b: FloatArray, force: Boolean) {
        val t = Smart.frameObject(b, zMaxPan)
        val z = t[2].coerceAtLeast(zRange.lower)
        val dead = 0.04f / tT
        if (force || abs(t[0] - tx) > dead || abs(t[1] - ty) > dead || abs(z - tT) / tT > 0.15f) {
            tx = t[0]; ty = t[1]; tT = z; ease = if (force) 0.25f else 0.15f
        }
        trackCenter = floatArrayOf((b[0] + b[2]) / 2, (b[1] + b[3]) / 2)
    }

    private fun onDetections() {
        if (!pinned) return
        val id = trackId ?: return
        val now = SystemClock.uptimeMillis()
        val objs = tracker.objects
        val same = objs.firstOrNull { it.id == id }
        if (same != null) { trackSeen = now; frameBox(toFull(same), false); return }
        val lost = now - trackSeen
        if (lost > 1200) {
            // Ids can change when the view zooms; re-attach to the nearest similar object.
            val c = trackCenter[0] to trackCenter[1]
            val again = objs.filter { trackLabel == null || it.label == trackLabel || it.label == null }
                .map { it to toFull(it) }.minByOrNull { dist(it.second, c) }?.takeIf { dist(it.second, c) < 0.25f / vT }
            if (again != null) { trackId = again.first.id; trackSeen = now; frameBox(again.second, false) }
            else if (lost > 4000) trackId = null // keep the framing, stop following
        }
    }

    private fun autoFrame(faces: Array<Face>?, zrRes: Float) {
        val f = faces?.filter { it.score >= 50 }.orEmpty()
        faceCount = f.size
        if (!opts.autoZoom || pinned) return
        val now = SystemClock.uptimeMillis()
        val w = active.width(); val hh = active.height()
        // Faces come in the zoomed view's coordinates; convert to the 1x view.
        fun fx(x: Int) = ((0.5f + (x.toFloat() / w - 0.5f) / zrRes) * w).toInt()
        fun fy(y: Int) = ((0.5f + (y.toFloat() / hh - 0.5f) / zrRes) * hh).toInt()
        val target = if (f.isNotEmpty()) {
            lastFaceMs = now
            Smart.targetZoom(fx(f.minOf { it.bounds.left }), fy(f.minOf { it.bounds.top }), fx(f.maxOf { it.bounds.right }),
                fy(f.maxOf { it.bounds.bottom }), w, hh, zRange.lower, zMaxPan)
        } else if (now - lastFaceMs > 1500) 1f.coerceIn(zRange.lower, zRange.upper) else tT
        if (abs(target - tT) / tT > 0.15f || tx != 0.5f || ty != 0.5f) { tT = target; tx = 0.5f; ty = 0.5f; ease = 0.08f }
    }

    private fun stepView() {
        vx += (tx - vx) * ease; vy += (ty - vy) * ease; vT += (tT - vT) * ease
        if (abs(tT - vT) < 0.002f) vT = tT
    }

    // ---------------------------------------------------------------- per-frame

    private val cb = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureCompleted(s: CameraCaptureSession, r: Q, res: TotalCaptureResult) {
            if (shooting && burstSent) return
            res.get(Res.SENSOR_SENSITIVITY)?.let { meterIso = it }
            res.get(Res.SENSOR_EXPOSURE_TIME)?.let { meterNs = it }
            // Scene brightness (EV at ISO 100) is lens-independent once aperture is included.
            val n = res.get(Res.LENS_APERTURE) ?: 1.8f
            val ev100 = (ln(n * n / (meterNs / 1e9)) - ln(meterIso / 100.0)) / ln(2.0)
            if (lowLight && ev100 > 6) lowLight = false else if (!lowLight && ev100 < 5) lowLight = true
            activePhys = res.get(Res.LOGICAL_MULTI_CAMERA_ACTIVE_PHYSICAL_ID)
            autoFrame(res.get(Res.STATISTICS_FACES), res.get(Res.CONTROL_ZOOM_RATIO) ?: 1f)
            stepView()
            if (focusWhenSettled && abs(tT - vT) < 0.01f && abs(tx - vx) < 0.005f && abs(ty - vy) < 0.005f) {
                focusWhenSettled = false
                val gg = geo()
                focusOnZoomed(gg.czx, gg.czy, 0.08f / gg.k)
            }
            val g = geo().toArray()
            if ((0..3).any { abs(g[it] - sentGeo[it]) > 0.002f } || evSteps(previewEv()) != sentEv) repeat()
            if (shooting && !burstSent) {
                val af = res.get(Res.CONTROL_AF_STATE)
                val locked = af == M.CONTROL_AF_STATE_FOCUSED_LOCKED || af == M.CONTROL_AF_STATE_NOT_FOCUSED_LOCKED
                if (locked || SystemClock.uptimeMillis() - shootStart > 1500) fireBurst()
            }
            if (++frames % 8 == 0) out.hud(hud(), vT)
        }
    }

    private fun shutterText(ns: Long) =
        if (ns >= 500_000_000) "%.1fs".format(ns / 1e9) else "1/${(1e9 / ns).roundToInt()}s"

    private fun hud(): String {
        val first = when {
            isRecording -> {
                val s = (SystemClock.uptimeMillis() - recStart) / 1000
                "● REC %d:%02d · %s".format(s / 60, s % 60, if (videoSize.width >= 3840) "4K" else "${videoSize.height}p")
            }
            useManualExposure -> { val e = plan(); "ISO ${e.iso} · ${shutterText(e.timeNs)}" }
            video -> "Video · ${if (videoSize.width >= 3840) "4K" else "${videoSize.height}p"} 30fps"
            manual -> "Phone auto-exposure"
            else -> "Phone auto-exposure (no manual control here)"
        }
        val lens = activePhys?.let { lenses?.physRatio?.get(it) }?.let { " · ${Stop("", it).label} lens" } ?: ""
        val second = buildString {
            append("%.1fx%s · %+.1f EV".format(vT, lens, opts.ev + hiBias))
            if (lowLight && teleRatio > 0f && vT >= teleRatio * 0.97f) append(" · low light: main sensor")
            if (pinned) append(if (trackId != null) " · following ${trackLabel ?: "object"}" else " · framed")
            else if (faceCount > 0) append(" · $faceCount face${if (faceCount > 1) "s" else ""}")
            if (focusRect != null) append(" · focus locked")
            if (!video) append(when { gyro < 0.03f -> " · steady"; gyro < 0.15f -> " · slight shake"; else -> " · shaky" })
        }
        return "$first\n$second"
    }

    private fun analyze(img: Image) {
        val p = img.planes[0]
        val buf = p.buffer; val rs = p.rowStride
        val w = img.width; val hh = img.height
        val sum = FloatArray(GW * GH); val cnt = IntArray(GW * GH)
        var clipped = 0; var n = 0
        for (y in 0 until hh step 4) for (x in 0 until w step 4) {
            val v = buf.get(y * rs + x).toInt() and 0xFF
            if (v >= 250) clipped++
            n++
            val c = (y * GH / hh) * GW + x * GW / w
            sum[c] += v.toFloat(); cnt[c]++
        }
        for (i in sum.indices) if (cnt[i] > 0) sum[i] /= cnt[i]
        val prev = lastGrid
        // Only count luma change as subject motion when the phone itself is still and the view isn't zooming.
        val still = gyro < 0.1f && abs(tT - vT) < 0.01f
        val diff = if (prev != null && still) sum.indices.sumOf { abs(sum[it] - prev[it]).toDouble() }.toFloat() / sum.size else 0f
        val g = geo()
        lastGrid = sum; gridGeo = g
        subject = subject * 0.7f + diff * 0.3f
        clip = clip * 0.7f + clipped.toFloat() / n * 0.3f
        val target = if (opts.protectHighlights) Smart.highlightBias(clip) else 0f
        hiBias += (target - hiBias) * 0.2f
        tracker.feed(img, sensorOrientation, ::orient, g.toArray()) { onDetections() }
    }

    // ---------------------------------------------------------------- photos

    private fun fireBurst() {
        burstSent = true
        val e = plan()
        val manualNow = useManualExposure
        shotInfo = if (manualNow) "ISO ${e.iso} · ${shutterText(e.timeNs)}" else "auto exposure"
        val reqs = try {
            List(burstSize) {
                base(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                    addTarget(jpeg!!.surface)
                    if (hqNr) set(Q.NOISE_REDUCTION_MODE, M.NOISE_REDUCTION_MODE_HIGH_QUALITY)
                    if (hqEdge) set(Q.EDGE_MODE, M.EDGE_MODE_HIGH_QUALITY)
                    set(Q.JPEG_QUALITY, 95.toByte())
                    set(Q.JPEG_ORIENTATION, sensorOrientation)
                    if (manualNow) {
                        set(Q.CONTROL_AE_MODE, M.CONTROL_AE_MODE_OFF)
                        set(Q.SENSOR_SENSITIVITY, e.iso)
                        set(Q.SENSOR_EXPOSURE_TIME, e.timeNs)
                        set(Q.SENSOR_FRAME_DURATION, e.timeNs)
                    } else {
                        set(Q.CONTROL_AE_MODE, M.CONTROL_AE_MODE_ON)
                        set(Q.CONTROL_AE_EXPOSURE_COMPENSATION, evSteps(opts.ev + hiBias))
                    }
                }.build()
            }
        } catch (ex: Exception) { fail("Capture failed"); return }
        if (safe { session!!.captureBurst(reqs, null, h) }) out.captured() else fail("Capture failed")
    }

    private fun fail(msg: String) {
        shooting = false; shots.clear()
        out.busy(false); out.error(msg)
        if (!fixedFocus) safe { session?.capture(previewReq().apply { set(Q.CONTROL_AF_TRIGGER, M.CONTROL_AF_TRIGGER_CANCEL) }.build(), cb, h) }
    }

    private fun onJpeg(img: Image) {
        val b = img.planes[0].buffer
        val bytes = ByteArray(b.remaining()).also { b.get(it) }
        if (!shooting) return
        shots += bytes
        if (shots.size < burstSize) return
        val list = shots.toList()
        shots.clear(); shooting = false
        if (focusRect == null && !fixedFocus) safe {
            session?.capture(previewReq().apply { set(Q.CONTROL_AF_TRIGGER, M.CONTROL_AF_TRIGGER_CANCEL) }.build(), cb, h)
        }
        val info = shotInfo
        Thread {
            val best = if (list.size == 1) list[0] else list.maxBy(::sharpness)
            val uri = savePhoto(best)
            if (uri == null) out.error("Couldn't save photo (storage full?)")
            else out.saved(uri, if (list.size > 1) "Saved sharpest of ${list.size} · $info" else "Saved · $info")
            out.busy(false)
        }.start()
    }

    private fun sharpness(jpg: ByteArray): Double {
        val bmp = BitmapFactory.decodeByteArray(jpg, 0, jpg.size, BitmapFactory.Options().apply { inSampleSize = 8 }) ?: return 0.0
        val w = bmp.width; val hh = bmp.height
        val px = IntArray(w * hh).also { bmp.getPixels(it, 0, w, 0, 0, w, hh) }
        bmp.recycle()
        val lum = IntArray(px.size) { val c = px[it]; ((c shr 16 and 255) * 3 + (c shr 8 and 255) * 6 + (c and 255)) / 10 }
        return Smart.sharpness(lum, w, hh)
    }

    private fun savePhoto(jpg: ByteArray): Uri? = try {
        val v = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "SmartCam_${System.currentTimeMillis()}.jpg")
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/SmartCam")
        }
        val r = ctx.contentResolver
        r.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, v)?.also { uri ->
            r.openOutputStream(uri)?.use { it.write(jpg) } ?: run { r.delete(uri, null, null); return null }
        }
    } catch (e: Exception) { null }

    // ---------------------------------------------------------------- video

    private fun pickVideoSize(map: android.hardware.camera2.params.StreamConfigurationMap): Size {
        val sizes = map.getOutputSizes(MediaRecorder::class.java)
        val id = camId?.toIntOrNull() ?: 0
        val uhd = Size(3840, 2160)
        if (uhd in sizes && runCatching { CamcorderProfile.hasProfile(id, CamcorderProfile.QUALITY_2160P) }.getOrDefault(false) &&
            map.getOutputMinFrameDuration(MediaRecorder::class.java, uhd) <= 33_400_000L) return uhd
        return pickSize(sizes, 1920, 16f / 9)
    }

    @Suppress("DEPRECATION")
    private fun newRecorder(fd: FileDescriptor?, audio: Boolean): MediaRecorder {
        val r = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(ctx) else MediaRecorder()
        if (audio) r.setAudioSource(MediaRecorder.AudioSource.CAMCORDER)
        r.setVideoSource(MediaRecorder.VideoSource.SURFACE)
        r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
        if (fd != null) r.setOutputFile(fd) else r.setOutputFile(File(ctx.cacheDir, "probe.mp4"))
        r.setVideoEncodingBitRate(if (videoSize.width >= 3840) 45_000_000 else 17_000_000)
        r.setVideoFrameRate(30)
        r.setVideoSize(videoSize.width, videoSize.height)
        r.setVideoEncoder(MediaRecorder.VideoEncoder.H264)
        if (audio) {
            r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            r.setAudioSamplingRate(48_000)
            r.setAudioEncodingBitRate(192_000)
            r.setAudioChannels(2)
        }
        r.setInputSurface(recSurface!!)
        r.setOrientationHint(sensorOrientation)
        return r
    }

    private fun startRecording() {
        if (session == null || recSurface == null) { out.error("Camera not ready"); return }
        val res = ctx.contentResolver
        var uri: Uri? = null
        try {
            val v = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, "SmartCam_${System.currentTimeMillis()}.mp4")
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/SmartCam")
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }
            uri = res.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, v) ?: throw IllegalStateException("storage")
            val fd = res.openFileDescriptor(uri, "w") ?: throw IllegalStateException("storage")
            recFd = fd; recUri = uri
            val audio = ctx.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
            recorder = newRecorder(fd.fileDescriptor, audio).apply { prepare() }
            isRecording = true
            repeat()
            recorder!!.start()
            recStart = SystemClock.uptimeMillis()
            out.recording(true)
            if (!audio) out.error("Recording without sound (microphone permission off)")
        } catch (e: Exception) {
            isRecording = false
            recorder?.release(); recorder = null
            recFd?.close(); recFd = null
            uri?.let { runCatching { res.delete(it, null, null) } }
            repeat()
            out.error("Couldn't start recording: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    private fun stopRecording() {
        isRecording = false
        repeat()
        val ok = runCatching { recorder?.stop() }.isSuccess
        recorder?.release(); recorder = null
        recFd?.close(); recFd = null
        val uri = recUri ?: return
        recUri = null
        val res = ctx.contentResolver
        if (ok) {
            res.update(uri, ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }, null, null)
            out.saved(uri, "Video saved")
        } else {
            runCatching { res.delete(uri, null, null) }
            out.error("Video too short, not saved")
        }
        out.recording(false)
    }

    // ---------------------------------------------------------------- sensors

    override fun onSensorChanged(e: SensorEvent) {
        val (x, y, z) = e.values
        gyro = gyro * 0.8f + sqrt(x * x + y * y + z * z) * 0.2f
    }

    override fun onAccuracyChanged(s: Sensor, a: Int) {}
}

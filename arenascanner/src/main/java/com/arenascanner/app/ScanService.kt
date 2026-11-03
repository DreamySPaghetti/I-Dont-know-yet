package com.arenascanner.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.TextView
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.io.File
import java.util.concurrent.Executors

/**
 * Captures the screen while Arena is open. Whenever the screen stops moving and shows something
 * new, it reads the card names with ML Kit, counts the copy pips under each card, and records them.
 * The floating button pauses/resumes (tap) or stops (long-press).
 */
class ScanService : Service() {
    companion object {
        const val EXTRA_CODE = "code"
        const val EXTRA_DATA = "data"
        const val ACTION_STOP = "stop"
        private const val CHANNEL = "scan"
        private const val TICK_MS = 400L
        @Volatile var running = false
        fun debugFile(ctx: android.content.Context) = File(ctx.filesDir, "last_scan.png")
    }

    private val main = Handler(Looper.getMainLooper())
    private lateinit var bgThread: HandlerThread
    private lateinit var bg: Handler
    private val debugWriter = Executors.newSingleThreadExecutor()
    private var projection: MediaProjection? = null
    private var display: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private var latest: Image? = null // newest frame, only touched on the bg thread
    private var overlay: TextView? = null
    private var overlayLp: WindowManager.LayoutParams? = null
    @Volatile private var paused = false
    @Volatile private var busy = false
    private var width = 0
    private var height = 0
    private val settle = Settle()
    private var found = 0
    private val recognizer by lazy { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }
    private val matcher by lazy { Matcher(CardDb.load(this)) }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) { stopSelf(); return START_NOT_STICKY }
        if (projection != null) return START_NOT_STICKY
        startForeground(1, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        val code = intent?.getIntExtra(EXTRA_CODE, 0) ?: 0
        val data: Intent? = if (Build.VERSION.SDK_INT >= 33) intent?.getParcelableExtra(EXTRA_DATA, Intent::class.java)
            else @Suppress("DEPRECATION") intent?.getParcelableExtra(EXTRA_DATA)
        if (data == null) { stopSelf(); return START_NOT_STICKY }
        startCapture(code, data)
        showOverlay()
        running = true
        bg.postDelayed(::tick, 1500)
        return START_NOT_STICKY
    }

    private fun startCapture(code: Int, data: Intent) {
        bgThread = HandlerThread("capture").also { it.start() }
        bg = Handler(bgThread.looper)
        val mpm = getSystemService(MediaProjectionManager::class.java)
        val p = mpm.getMediaProjection(code, data)
        projection = p
        // Android 14+ requires the callback before the virtual display is created.
        p.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() { main.post { stopSelf() } }
        }, main)
        val bounds = getSystemService(WindowManager::class.java).currentWindowMetrics.bounds
        width = bounds.width(); height = bounds.height()
        val r = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 3)
        r.setOnImageAvailableListener({ rd ->
            val img = rd.acquireLatestImage() ?: return@setOnImageAvailableListener
            latest?.close()
            latest = img
        }, bg)
        reader = r
        display = p.createVirtualDisplay("ArenaScanner", width, height, resources.displayMetrics.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, r.surface, null, null)
    }

    /** Runs on the bg thread every [TICK_MS]: scan once the screen has settled on something new. */
    private fun tick() {
        if (!running) return
        val img = latest
        if (img != null && !paused && !busy) {
            val fp = fingerprint(img)
            if (settle.offer(fp)) {
                settle.scanned(fp)
                scan(toBitmap(img))
            }
        }
        bg.postDelayed(::tick, TICK_MS)
    }

    /** 18×36 grid of brightness samples, skipping the floating button so it doesn't count as change. */
    private fun fingerprint(img: Image): IntArray {
        val plane = img.planes[0]
        val buf = plane.buffer
        val lp = overlayLp
        val ov = overlay
        val cols = 18; val rows = 36
        val out = IntArray(cols * rows)
        for (r in 0 until rows) for (c in 0 until cols) {
            val x = (c + 0.5f) * img.width / cols
            val y = (r + 0.5f) * img.height / rows
            if (lp != null && ov != null && x >= lp.x && x < lp.x + ov.width && y >= lp.y && y < lp.y + ov.height) continue
            val i = y.toInt() * plane.rowStride + x.toInt() * plane.pixelStride
            val rr = buf.get(i).toInt() and 0xFF
            val gg = buf.get(i + 1).toInt() and 0xFF
            val bb = buf.get(i + 2).toInt() and 0xFF
            out[r * cols + c] = (rr * 3 + gg * 6 + bb) / 10
        }
        return out
    }

    private fun scan(bmp: Bitmap) {
        if (isBlack(bmp)) { main.post { flash("Black", hold = 3000) }; return }
        busy = true
        main.post { if (!paused) overlay?.text = "…" }
        recognizer.process(InputImage.fromBitmap(bmp, 0))
            .addOnSuccessListener { text ->
                // Read the pips on a background thread: it touches many pixels.
                bg.post {
                    val hits = mutableListOf<Pair<Card, Box>>()
                    for (block in text.textBlocks) for (line in block.lines) {
                        val b = line.boundingBox ?: continue
                        val c = matcher.match(line.text) ?: continue
                        hits.add(c to Box(b.left, b.top, b.right, b.bottom))
                    }
                    val result = countPips(bmp, hits)
                    val added = CollectionStore.recordScan(this, result.map { it.first to it.third })
                    found += added
                    saveDebug(bmp, result)
                    main.post {
                        flash(if (added > 0) "+$added" else if (hits.isEmpty()) "0" else "✓")
                        busy = false
                    }
                }
            }
            .addOnFailureListener { main.post { flash("Error"); busy = false } }
    }

    /** For each card name found, the copies read from its pips (null if unreadable). */
    private fun countPips(bmp: Bitmap, hits: List<Pair<Card, Box>>): List<Triple<Card, Box, Int?>> {
        if (hits.isEmpty()) return emptyList()
        val w = bmp.width; val h = bmp.height
        val px = IntArray(w * h)
        bmp.getPixels(px, 0, w, 0, 0, w, h)
        val luma = { x: Int, y: Int ->
            val p = px[y * w + x]
            (((p shr 16) and 0xFF) * 3 + ((p shr 8) and 0xFF) * 6 + (p and 0xFF)) / 10
        }
        val cardW = Layout.cardWidth(hits.map { it.second }, w)
        val seen = LinkedHashMap<String, Triple<Card, Box, Int?>>()
        for ((card, box) in hits) {
            val n = PipCounter.count(luma, Layout.pipRegion(box, cardW, w, h), cardW)
            val prev = seen[card.name]
            if (prev == null || (prev.third == null && n != null)) seen[card.name] = Triple(card, box, n)
        }
        return seen.values.toList()
    }

    /** Saves the last scanned screen with what was read drawn on it, for "Save last scan image". */
    private fun saveDebug(bmp: Bitmap, result: List<Triple<Card, Box, Int?>>) {
        val copy = bmp.copy(Bitmap.Config.ARGB_8888, true)
        val cardW = Layout.cardWidth(result.map { it.second }, copy.width)
        debugWriter.execute {
            val cv = Canvas(copy)
            val stroke = Paint().apply { style = Paint.Style.STROKE; strokeWidth = 4f }
            val label = Paint().apply { textSize = 40f; isFakeBoldText = true; color = Color.YELLOW; setShadowLayer(4f, 0f, 0f, Color.BLACK) }
            for ((_, box, n) in result) {
                stroke.color = Color.RED
                cv.drawRect(box.left.toFloat(), box.top.toFloat(), box.right.toFloat(), box.bottom.toFloat(), stroke)
                val r = Layout.pipRegion(box, cardW, copy.width, copy.height)
                stroke.color = if (n == null) Color.MAGENTA else Color.CYAN
                cv.drawRect(r.left.toFloat(), r.top.toFloat(), r.right.toFloat(), r.bottom.toFloat(), stroke)
                cv.drawText(n?.toString() ?: "?", r.left.toFloat(), r.bottom + 40f, label)
            }
            runCatching { debugFile(this).outputStream().use { copy.compress(Bitmap.CompressFormat.PNG, 100, it) } }
            copy.recycle()
        }
    }

    private fun showOverlay() {
        val wm = getSystemService(WindowManager::class.java)
        val d = resources.displayMetrics.density
        val btn = TextView(this).apply {
            text = "Auto"
            textSize = 14f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            val pad = (12 * d).toInt()
            setPadding(pad, pad, pad, pad)
            minWidth = (58 * d).toInt(); minHeight = (58 * d).toInt()
        }
        style(btn)
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.START; x = (8 * d).toInt(); y = (120 * d).toInt() }

        // Drag to move; tap to pause/resume; long-press to stop.
        val slop = ViewConfiguration.get(this).scaledTouchSlop
        var downX = 0f; var downY = 0f; var startX = 0; var startY = 0; var moved = false; var downAt = 0L
        btn.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> { downX = e.rawX; downY = e.rawY; startX = lp.x; startY = lp.y; moved = false; downAt = e.eventTime }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX; val dy = e.rawY - downY
                    if (moved || dx * dx + dy * dy > slop * slop) {
                        moved = true; lp.x = startX + dx.toInt(); lp.y = startY + dy.toInt(); wm.updateViewLayout(btn, lp)
                    }
                }
                MotionEvent.ACTION_UP -> if (!moved) {
                    if (e.eventTime - downAt > 600) stopSelf()
                    else { paused = !paused; style(btn); btn.text = if (paused) "Paused" else "Auto" }
                }
            }
            true
        }
        wm.addView(btn, lp)
        overlay = btn
        overlayLp = lp
    }

    private fun style(btn: TextView) {
        val d = resources.displayMetrics.density
        btn.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Color.parseColor(if (paused) "#CC5B6678" else "#E62A63B0"))
            setStroke((2 * d).toInt(), Color.WHITE)
        }
    }

    /** Shows a short result on the button, then goes back to "Auto". */
    private fun flash(label: String, hold: Long = 1000) {
        val btn = overlay ?: return
        btn.text = label
        main.removeCallbacksAndMessages("flash")
        main.postAtTime({ btn.text = if (paused) "Paused" else "Auto" }, "flash", android.os.SystemClock.uptimeMillis() + hold)
    }

    private fun toBitmap(img: Image): Bitmap {
        val plane = img.planes[0]
        val pixelStride = plane.pixelStride
        val rowPadding = plane.rowStride - pixelStride * img.width
        val padded = Bitmap.createBitmap(img.width + rowPadding / pixelStride, img.height, Bitmap.Config.ARGB_8888)
        plane.buffer.rewind()
        padded.copyPixelsFromBuffer(plane.buffer)
        return if (rowPadding == 0) padded else Bitmap.createBitmap(padded, 0, 0, img.width, img.height)
    }

    private fun isBlack(b: Bitmap): Boolean {
        for (y in 1..9) for (x in 1..9) {
            val p = b.getPixel(b.width * x / 10, b.height * y / 10)
            if (Color.red(p) + Color.green(p) + Color.blue(p) > 30) return false
        }
        return true
    }

    private fun notification(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Scanning", NotificationManager.IMPORTANCE_LOW))
        val stop = PendingIntent.getService(this, 0, Intent(this, ScanService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE)
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_search)
            .setContentTitle("Arena Scanner is reading your screen")
            .setContentText("Scroll through your collection; it scans each time you stop. Tap the button to pause.")
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(null, "Stop", stop).build())
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        running = false
        overlay?.let { runCatching { getSystemService(WindowManager::class.java).removeView(it) } }
        overlay = null
        display?.release()
        projection?.stop()
        if (::bg.isInitialized) bg.post { latest?.close(); latest = null; reader?.close(); bgThread.quitSafely() }
        runCatching { recognizer.close() }
        debugWriter.shutdown()
        super.onDestroy()
    }
}

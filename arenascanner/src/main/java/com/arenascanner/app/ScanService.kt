package com.arenascanner.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.Color
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
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.TextView
import android.widget.Toast
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions

/**
 * Captures the screen while Arena is open. A floating "Scan" button grabs the current frame,
 * reads the text on it with ML Kit, and adds every Standard card name it recognises to the collection.
 */
class ScanService : Service() {
    companion object {
        const val EXTRA_CODE = "code"
        const val EXTRA_DATA = "data"
        const val ACTION_STOP = "stop"
        private const val CHANNEL = "scan"
        @Volatile var running = false
    }

    private val main = Handler(Looper.getMainLooper())
    private lateinit var bgThread: HandlerThread
    private lateinit var bg: Handler
    private var projection: MediaProjection? = null
    private var display: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private var latest: Image? = null // newest frame, only touched on the bg thread
    private var overlay: TextView? = null
    private var busy = false
    private var width = 0
    private var height = 0
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

    private fun showOverlay() {
        val wm = getSystemService(WindowManager::class.java)
        val d = resources.displayMetrics.density
        val btn = TextView(this).apply {
            text = "Scan"
            textSize = 15f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            val pad = (14 * d).toInt()
            setPadding(pad, pad, pad, pad)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#E62A63B0"))
                setStroke((2 * d).toInt(), Color.WHITE)
            }
            minWidth = (64 * d).toInt(); minHeight = (64 * d).toInt()
        }
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.START; x = (16 * d).toInt(); y = (200 * d).toInt() }

        // Drag to move; tap to scan; long-press to stop.
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
                    if (e.eventTime - downAt > 600) stopSelf() else scan()
                }
            }
            true
        }
        wm.addView(btn, lp)
        overlay = btn
    }

    private fun scan() {
        if (busy) return
        busy = true
        val btn = overlay ?: return
        // Hide the button so it doesn't cover a card, then wait for a fresh frame.
        btn.visibility = View.INVISIBLE
        bg.postDelayed({
            val bmp = latest?.let { toBitmap(it) }
            main.post {
                btn.visibility = View.VISIBLE
                if (bmp == null) { finish("No screen yet", "Couldn't read the screen yet. Scroll a little and tap Scan again."); return@post }
                if (isBlack(bmp)) { finish("Black", "The capture came out black. Arena may block screen capture on this phone."); return@post }
                btn.text = "…"
                recognizer.process(InputImage.fromBitmap(bmp, 0))
                    .addOnSuccessListener { text ->
                        val lines = text.textBlocks.flatMap { b -> b.lines.map { it.text } }
                        val found = matcher.matchAll(lines)
                        val added = CollectionStore.addSeen(this, found)
                        finish(if (added > 0) "+$added" else "0 new",
                            if (found.isEmpty()) "No card names found on this screen."
                            else "Found ${found.size} cards, $added new.")
                    }
                    .addOnFailureListener { finish("Error", "Text reading failed: ${it.message}") }
            }
        }, 350)
    }

    private fun finish(label: String, message: String) {
        overlay?.text = label
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
        main.postDelayed({ overlay?.text = "Scan"; busy = false }, 1200)
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
            .setContentText("Tap the Scan button on each collection page. Long-press it to stop.")
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
        super.onDestroy()
    }
}

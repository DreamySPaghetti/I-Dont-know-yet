package com.arenascanner.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.content.res.Configuration
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
import android.util.DisplayMetrics
import android.view.Display
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.TextView
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.io.File
import java.util.concurrent.Executors

/**
 * Captures the screen while Arena's collection is open and scans continuously: whenever the screen
 * shows something new, it reads the card names with ML Kit, counts the copy diamonds above each card,
 * and records them. With auto-scroll on it also swipes through the collection.
 * Floating button: tap = pause/resume, long-press = stop, drag = move.
 */
class ScanService : Service() {
    companion object {
        const val EXTRA_CODE = "code"
        const val EXTRA_DATA = "data"
        const val ACTION_STOP = "stop"
        const val ACTION_TOGGLE = "toggle"
        private const val CHANNEL = "scan"
        private const val TICK_MS = 100L
        private const val DEBUG_EVERY_MS = 5000L
        @Volatile var running = false
        fun debugFile(ctx: android.content.Context) = File(ctx.filesDir, "last_scan.jpg")
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
    private var panel: TextView? = null
    @Volatile private var paused = false
    @Volatile private var busy = false
    private var busySince = 0L
    private var width = 0
    private var height = 0
    private var ticks = 0
    // Don't wait for scrolling to stop; just skip frames that look like the last scanned one.
    private val settle = Settle(stableTicks = 0, newDiff = 4.0)
    /** Every count read for each card this session; the card's count is the one read most often. */
    private val votes = HashMap<String, IntArray>()
    private var found = 0
    private var updatedTotal = 0
    private val recognizer by lazy { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }
    private val matcher by lazy { Matcher(CardDb.load(this)) }

    // Reused buffers (a scan used to allocate ~20 MB of bitmaps and pixel arrays several times a second).
    private var frameBuf: Bitmap? = null
    private var scanBmp: Bitmap? = null
    private var pxBuf: IntArray? = null
    private var lastDebugAt = 0L
    private val debugPending = java.util.concurrent.atomic.AtomicBoolean(false)

    // Nothing to read while the screen is off.
    @Volatile private var screenOn = true
    private val screenReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(c: android.content.Context?, i: Intent?) { screenOn = i?.action != Intent.ACTION_SCREEN_OFF }
    }

    // --- auto-scroll ---
    private var scrollOn = false
    @Volatile private var swiping = false
    private var swipeStartedAt = 0L
    @Volatile private var swipeEndedAt = 0L
    @Volatile private var scansAfterSwipe = 0
    private var beforeSwipe: IntArray? = null
    private var stuck = 0
    private var speed = Prefs.SPEEDS[1]
    private var scrollStartAt = 0L          // first swipe waits a moment so you can open Collection
    @Volatile private var emptyScans = 0    // scans in a row that found no cards
    private val sessionNew = LinkedHashSet<String>()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) { stopSelf(); return START_NOT_STICKY }
        if (intent?.action == ACTION_TOGGLE) { main.post { togglePause() }; return START_NOT_STICKY }
        if (projection != null) return START_NOT_STICKY
        startForeground(1, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        val code = intent?.getIntExtra(EXTRA_CODE, 0) ?: 0
        val data: Intent? = if (Build.VERSION.SDK_INT >= 33) intent?.getParcelableExtra(EXTRA_DATA, Intent::class.java)
            else @Suppress("DEPRECATION") intent?.getParcelableExtra(EXTRA_DATA)
        if (data == null) { stopSelf(); return START_NOT_STICKY }
        startCapture(code, data)
        val screenFilter = android.content.IntentFilter().apply { addAction(Intent.ACTION_SCREEN_OFF); addAction(Intent.ACTION_SCREEN_ON) }
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(screenReceiver, screenFilter, RECEIVER_NOT_EXPORTED)
        else registerReceiver(screenReceiver, screenFilter)
        showOverlay()
        running = true
        speed = Prefs.speed(this)
        scrollOn = Prefs.get(this).getBoolean("auto_scroll", false) && AutoScroll.instance != null
        scrollStartAt = System.currentTimeMillis() + 4500
        bg.postDelayed(::tick, 1500)
        return START_NOT_STICKY
    }

    private fun startCapture(code: Int, data: Intent) {
        bgThread = HandlerThread("capture").also { it.start() }
        bg = Handler(bgThread.looper)
        val p = getSystemService(MediaProjectionManager::class.java).getMediaProjection(code, data)
        projection = p
        // Android 14+ requires the callback before the virtual display is created.
        p.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() { main.post { stopSelf() } }
        }, main)
        val (w, h) = realSize()
        width = w; height = h
        val r = newReader(w, h)
        reader = r
        display = p.createVirtualDisplay("ArenaScanner", w, h, resources.displayMetrics.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, r.surface, null, null)
    }

    /** The screen's current size in pixels, as it is rotated now (Arena runs in landscape). */
    private fun realSize(): Pair<Int, Int> {
        val d = getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY)
        val m = DisplayMetrics()
        @Suppress("DEPRECATION") d.getRealMetrics(m)
        return m.widthPixels to m.heightPixels
    }

    private fun newReader(w: Int, h: Int): ImageReader {
        val r = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 3)
        r.setOnImageAvailableListener({ rd ->
            if (rd !== reader) return@setOnImageAvailableListener
            val img = rd.acquireLatestImage() ?: return@setOnImageAvailableListener
            latest?.close()
            latest = img
        }, bg)
        return r
    }

    /** bg thread: when the phone turns (this app starts in portrait, Arena is landscape), resize the capture. */
    private fun matchRotation() {
        val (w, h) = realSize()
        if (w == width && h == height) return
        val vd = display ?: return
        width = w; height = h
        val old = reader
        val r = newReader(w, h)
        reader = r
        latest?.close(); latest = null
        vd.resize(w, h, resources.displayMetrics.densityDpi)
        vd.surface = r.surface
        old?.close()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (::bg.isInitialized) bg.post(::matchRotation)
    }

    /** bg thread, every [TICK_MS]: scan the newest frame if it's new, then let auto-scroll move on. */
    private fun tick() {
        if (!running) return
        if (!screenOn) { bg.postDelayed(::tick, 1000); return }
        if (++ticks % 10 == 0) matchRotation() // backup in case a rotation callback was missed
        // Watchdog: if text reading never answered, don't stay stuck.
        if (busy && System.currentTimeMillis() - busySince > 8000) busy = false
        val img = latest
        if (img != null && !paused && !busy) {
            val fp = fingerprint(img)
            if (settle.offer(fp)) {
                settle.scanned(fp)
                scan(scanBitmap(img))
            }
        }
        if (img != null) autoScroll(img)
        bg.postDelayed(::tick, TICK_MS)
    }

    /**
     * Swipe, wait until a scan has read the new position (or 1.5 s if nothing changed), swipe again.
     * Three swipes in a row that don't move the screen = end of the list.
     */
    private fun autoScroll(img: Image) {
        if (!scrollOn || paused) return
        val now = System.currentTimeMillis()
        if (now < scrollStartAt) return
        // Safety: don't swipe around a screen that isn't the collection.
        if (emptyScans >= 4) {
            scrollOn = false
            main.post {
                paused = true; overlay?.let { style(it) }; flash("Paused", 1)
                showPanel("No cards on screen, so auto-scroll paused.\nOpen Collection in Arena, then tap the button to carry on.")
            }
            return
        }
        if (swiping) {
            // Watchdog: a gesture whose callback never came back mustn't stop scrolling for good.
            if (now - swipeStartedAt > speed.moveMs + speed.holdMs + 2500) { swiping = false; swipeEndedAt = now }
            return
        }
        if (busy) return
        val waited = now - swipeEndedAt
        if (waited < 200) return
        if (scansAfterSwipe < 1 && waited < 1500) return
        val fp = fingerprint(img)
        beforeSwipe?.let { stuck = if (Settle.diff(it, fp) < 2.0) stuck + 1 else 0 }
        if (stuck >= 3) {
            scrollOn = false
            main.post {
                paused = true; overlay?.let { style(it); it.text = "Done" }
                buzz()
                showPanel("Finished: $found new cards, $updatedTotal counts updated.\n" +
                    "Change Arena's filter and tap the button to scan more, or long-press it to stop.")
            }
            return
        }
        val svc = AutoScroll.instance ?: run {
            scrollOn = false
            main.post {
                paused = true; overlay?.let { style(it); it.text = "Paused" }
                showPanel("Auto-scroll stopped: Android turned off the swipe service.\n" +
                    "Turn \"Arena Scanner auto-scroll\" back on in Settings → Accessibility, then tap the button.")
            }
            return
        }
        beforeSwipe = fp
        swiping = true
        swipeStartedAt = now
        scansAfterSwipe = 0
        // Right to left along the gap between the two card rows (measured from a recording).
        val y = height * 0.585f
        val x1 = width * 0.80f
        val x2 = x1 - width * speed.step
        main.post {
            svc.swipe(x1, y, x2, speed.moveMs, speed.holdMs) { _ ->
                swipeEndedAt = System.currentTimeMillis()
                swiping = false
            }
        }
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
        busySince = System.currentTimeMillis()
        val afterSwipe = !swiping && System.currentTimeMillis() >= swipeEndedAt
        fun finished() { if (afterSwipe) scansAfterSwipe++; busy = false }
        recognizer.process(InputImage.fromBitmap(bmp, 0))
            .addOnSuccessListener { text ->
                // Read the diamonds on the background thread: it touches many pixels.
                bg.post {
                    try {
                        val hits = mutableListOf<Pair<Card, Box>>()
                        for (block in text.textBlocks) for (line in block.lines) {
                            val b = line.boundingBox ?: continue
                            val c = matcher.match(line.text) ?: continue
                            hits.add(c to Box(b.left, b.top, b.right, b.bottom))
                        }
                        val result = countPips(bmp, hits)
                        val counts = result.map { (card, _, read) ->
                            val v = votes.getOrPut(card.name) { IntArray(5) }
                            read.count?.let { v[it]++ }
                            // Most-read count; ties go to the higher one. null until read once.
                            val best = (1..4).filter { v[it] > 0 }.maxWithOrNull(compareBy<Int>({ v[it] }, { it }))
                            Triple(card, best, best?.let { v[it] } ?: 0)
                        }
                        emptyScans = if (hits.isEmpty()) emptyScans + 1 else 0
                        val added = CollectionStore.recordScan(this, counts)
                        sessionNew += CollectionStore.lastAdded
                        found += added
                        updatedTotal += CollectionStore.lastUpdated
                        if (added > 0 || CollectionStore.lastUpdated > 0) updateNotification()
                        saveDebug(bmp, result)
                        main.post { if (added > 0) flash("+$added") else overlay?.text = idleLabel() }
                    } finally {
                        finished()
                    }
                }
            }
            .addOnFailureListener { bg.post { finished() } }
    }

    /** For each card name found, the copies read from the diamonds above it (null if unreadable). */
    private fun countPips(bmp: Bitmap, hits: List<Pair<Card, Box>>): List<Triple<Card, Box, PipRead>> {
        if (hits.isEmpty()) return emptyList()
        val w = bmp.width; val h = bmp.height
        val px = pxBuf?.takeIf { it.size == w * h } ?: IntArray(w * h).also { pxBuf = it }
        bmp.getPixels(px, 0, w, 0, 0, w, h)
        val luma = { x: Int, y: Int ->
            val p = px[y * w + x]
            (((p shr 16) and 0xFF) * 3 + ((p shr 8) and 0xFF) * 6 + (p and 0xFF)) / 10
        }
        val cardW = Layout.cardWidth(hits.map { it.second }, w, h)
        val seen = LinkedHashMap<String, Triple<Card, Box, PipRead>>()
        for ((card, box) in hits) {
            val read = PipCounter.count(luma, w, h, box, cardW)
            val prev = seen[card.name]
            if (prev == null || (prev.third.count == null && read.count != null)) seen[card.name] = Triple(card, box, read)
        }
        return seen.values.toList()
    }

    /** The last scanned screen with what was read drawn on it (for "Save scan image"); at most every 5 s. */
    private fun saveDebug(bmp: Bitmap, result: List<Triple<Card, Box, PipRead>>) {
        val now = System.currentTimeMillis()
        if (result.isEmpty() || now - lastDebugAt < DEBUG_EVERY_MS || !debugPending.compareAndSet(false, true)) return
        lastDebugAt = now
        val copy = bmp.copy(Bitmap.Config.ARGB_8888, true)
        val cardW = Layout.cardWidth(result.map { it.second }, copy.width, copy.height)
        val r = (Layout.PIP_RADIUS * cardW).toFloat()
        debugWriter.execute {
            val cv = Canvas(copy)
            val stroke = Paint().apply { style = Paint.Style.STROKE; strokeWidth = 3f }
            val label = Paint().apply { textSize = 34f; isFakeBoldText = true; color = Color.YELLOW; setShadowLayer(4f, 0f, 0f, Color.BLACK) }
            for ((_, box, read) in result) {
                stroke.color = Color.RED
                cv.drawRect(box.left.toFloat(), box.top.toFloat(), box.right.toFloat(), box.bottom.toFloat(), stroke)
                stroke.color = if (read.count == null) Color.MAGENTA else Color.CYAN
                for ((x, y) in read.slots) cv.drawRect(x - r, y - r, x + r, y + r, stroke)
                val (x0, y0) = read.slots.first()
                cv.drawText(read.count?.toString() ?: "?", x0 - r, y0 - r - 8f, label)
            }
            runCatching { debugFile(this).outputStream().use { copy.compress(Bitmap.CompressFormat.JPEG, 90, it) } }
            copy.recycle()
            debugPending.set(false)
        }
    }

    private fun showOverlay() {
        val wm = getSystemService(WindowManager::class.java)
        val d = resources.displayMetrics.density
        val btn = TextView(this).apply {
            text = "Auto"; textSize = 14f; setTextColor(Color.WHITE); gravity = Gravity.CENTER
            val pad = (12 * d).toInt()
            setPadding(pad, pad, pad, pad)
            minWidth = (58 * d).toInt(); minHeight = (58 * d).toInt()
            contentDescription = "Collection scanner. Tap to pause or resume, long-press to stop, drag to move."
        }
        style(btn)
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            // Keep the screen on while scanning: auto-scroll gestures don't count as touches, so it would time out.
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON, PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = Prefs.get(this@ScanService).getInt("btn_x", (8 * d).toInt())
            y = Prefs.get(this@ScanService).getInt("btn_y", (120 * d).toInt())
        }
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
                MotionEvent.ACTION_UP -> if (moved) {
                    Prefs.get(this).edit().putInt("btn_x", lp.x).putInt("btn_y", lp.y).apply()
                } else if (e.eventTime - downAt > 600) stopSelf()
                else togglePause()
            }
            true
        }
        wm.addView(btn, lp)
        overlay = btn
        overlayLp = lp
    }

    private fun togglePause() {
        val btn = overlay ?: return
        paused = !paused; style(btn); btn.text = idleLabel()
        panel?.visibility = View.GONE
        // Resuming (e.g. after "Done" and a new filter) restarts auto-scroll.
        if (!paused && Prefs.get(this).getBoolean("auto_scroll", false) && AutoScroll.instance != null) {
            speed = Prefs.speed(this); scrollOn = true; stuck = 0; beforeSwipe = null; scansAfterSwipe = 1; emptyScans = 0
            scrollStartAt = System.currentTimeMillis() + 800
        }
        getSystemService(NotificationManager::class.java).notify(1, notification())
    }

    /** "Paused", or how many different cards this session has read so far. */
    private fun idleLabel() = when {
        paused -> "Paused"
        votes.isEmpty() -> "Auto"
        else -> "${votes.size}"
    }

    private fun buzz() = runCatching {
        val v = getSystemService(android.os.VibratorManager::class.java).defaultVibrator
        v.vibrate(android.os.VibrationEffect.createOneShot(120, android.os.VibrationEffect.DEFAULT_AMPLITUDE))
    }

    private fun style(btn: TextView) {
        val d = resources.displayMetrics.density
        btn.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Color.parseColor(if (paused) "#CC5B6678" else "#E62F6BDB"))
            setStroke((2 * d).toInt(), Color.WHITE)
        }
    }

    /** Short result on the button, then back to "Auto"/"Paused". */
    private fun flash(label: String, hold: Long = 1000) {
        val btn = overlay ?: return
        btn.text = label
        main.removeCallbacksAndMessages("flash")
        main.postAtTime({ btn.text = idleLabel() }, "flash", android.os.SystemClock.uptimeMillis() + hold)
    }

    /** Message panel at the top of the screen; tap to hide. */
    private fun showPanel(text: String) {
        val wm = getSystemService(WindowManager::class.java)
        val d = resources.displayMetrics.density
        val p = panel ?: TextView(this).apply {
            textSize = 15f; setTextColor(Color.WHITE); setLineSpacing(0f, 1.15f)
            val pad = (14 * d).toInt(); setPadding(pad, pad, pad, pad)
            background = GradientDrawable().apply { cornerRadius = 14 * d; setColor(Color.parseColor("#E6151A22")) }
            setOnClickListener { visibility = View.GONE }
        }.also { tv ->
            wm.addView(tv, WindowManager.LayoutParams(
                (minOf(width, height) * 1.1).toInt().coerceAtMost((width * 0.6).toInt()), WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, PixelFormat.TRANSLUCENT
            ).apply { gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL; y = (8 * d).toInt() })
            panel = tv
        }
        p.text = text
        p.visibility = View.VISIBLE
    }

    /** Copies the frame into the reused padded buffer (rows can be wider than the screen). bg thread only. */
    private fun copyFrame(img: Image): Bitmap {
        val plane = img.planes[0]
        val pw = plane.rowStride / plane.pixelStride
        val b = frameBuf?.takeIf { it.width == pw && it.height == img.height }
            ?: Bitmap.createBitmap(pw, img.height, Bitmap.Config.ARGB_8888).also { frameBuf = it }
        plane.buffer.rewind()
        b.copyPixelsFromBuffer(plane.buffer)
        return b
    }

    /** The scan's screen-sized bitmap, reused between scans (only one scan runs at a time). */
    private fun scanBitmap(img: Image): Bitmap {
        val f = copyFrame(img)
        val s = scanBmp?.takeIf { it.width == img.width && it.height == img.height }
            ?: Bitmap.createBitmap(img.width, img.height, Bitmap.Config.ARGB_8888).also { scanBmp = it }
        Canvas(s).drawBitmap(f, 0f, 0f, null)
        return s
    }

    private fun isBlack(b: Bitmap): Boolean {
        for (y in 1..9) for (x in 1..9) {
            val p = b.getPixel(b.width * x / 10, b.height * y / 10)
            if (Color.red(p) + Color.green(p) + Color.blue(p) > 30) return false
        }
        return true
    }

    private var lastNotifyAt = 0L

    /** Keeps the notification's counts current (at most once a second). */
    private fun updateNotification() {
        val now = System.currentTimeMillis()
        if (now - lastNotifyAt < 1000) return
        lastNotifyAt = now
        getSystemService(NotificationManager::class.java).notify(1, notification())
    }

    private fun notification(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Scanning", NotificationManager.IMPORTANCE_LOW))
        val stop = PendingIntent.getService(this, 0, Intent(this, ScanService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE)
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_search)
            .setContentTitle("Arena Scanner is reading your collection")
            .setContentText(if (found + updatedTotal > 0) "$found new cards, $updatedTotal counts updated so far. Tap the button to pause."
                else "Open Collection in Arena; it scans as the cards go by. Tap the button to pause.")
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(null, if (paused) "Resume" else "Pause",
                PendingIntent.getService(this, 1, Intent(this, ScanService::class.java).setAction(ACTION_TOGGLE), PendingIntent.FLAG_IMMUTABLE)).build())
            .addAction(Notification.Action.Builder(null, "Stop", stop).build())
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        if (running) {
            android.widget.Toast.makeText(this, "Scan finished: $found new cards, $updatedTotal counts updated.", android.widget.Toast.LENGTH_LONG).show()
            // Remember what this session added (main screen can show it) and back the collection up.
            if (sessionNew.isNotEmpty()) Prefs.get(this).edit()
                .putString("last_scan_new", org.json.JSONArray(sessionNew.toList()).toString())
                .putLong("last_scan_time", System.currentTimeMillis()).apply()
            if (found + updatedTotal > 0) Thread { Backup.save(applicationContext) }.start()
        }
        running = false
        runCatching { unregisterReceiver(screenReceiver) }
        val wm = getSystemService(WindowManager::class.java)
        overlay?.let { runCatching { wm.removeView(it) } }
        panel?.let { runCatching { wm.removeView(it) } }
        overlay = null; panel = null
        display?.release()
        projection?.stop()
        if (::bg.isInitialized) bg.post { latest?.close(); latest = null; reader?.close(); bgThread.quitSafely() }
        runCatching { recognizer.close() }
        debugWriter.shutdown()
        super.onDestroy()
    }
}

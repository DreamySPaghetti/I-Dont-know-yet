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
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.TextView
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.io.File
import java.util.concurrent.Executors

/**
 * Captures the screen while Arena is open and scans continuously as you scroll: as soon as one scan
 * finishes, the next frame is read if the screen has changed. Each scan reads the card names with
 * ML Kit, counts the copy pips under each card, and records them.
 * The floating button pauses/resumes (tap) or stops (long-press).
 *
 * In coach mode ([MODE_COACH]) it doesn't scan. Tapping the floating "Coach" button captures the
 * current Arena screen, reads the visible card names, and asks Gemma (on the phone) for the best play.
 */
class ScanService : Service() {
    companion object {
        const val EXTRA_CODE = "code"
        const val EXTRA_DATA = "data"
        const val ACTION_STOP = "stop"
        const val EXTRA_MODE = "mode"
        const val MODE_COACH = "coach"
        @Volatile var coaching = false
        private const val CHANNEL = "scan"
        private const val TICK_MS = 120L
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
    // stableTicks = 0: don't wait for scrolling to stop, just skip frames that look like the last scan.
    private var ticks = 0
    private val settle = Settle(stableTicks = 0, newDiff = 4.0)
    /**
     * Every count read for each card this session (index = copies). The card's count is the one read
     * most often, so a single misread frame can't change it.
     */
    private val votes = HashMap<String, IntArray>()
    private var found = 0
    private val recognizer by lazy { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }
    private val matcher by lazy { Matcher(CardDb.load(this)) }

    // --- coach mode ---
    private var coachMode = false
    private var gemma: Gemma? = null
    private val coachExec = Executors.newSingleThreadExecutor()
    @Volatile private var coachReady = false
    @Volatile private var coachBusy = false
    private var panel: TextView? = null
    private var panelBox: android.widget.LinearLayout? = null
    private var actions: android.widget.LinearLayout? = null
    private var fixBox: android.view.View? = null
    // The last question and answer, so the player can rate or correct it.
    private var lastImage: ByteArray? = null
    private var lastPrompt = ""
    private var lastBoard: CoachPrompt.Board? = null
    private var lastAnswer = ""

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) { stopSelf(); return START_NOT_STICKY }
        if (projection != null) return START_NOT_STICKY
        coachMode = intent?.getStringExtra(EXTRA_MODE) == MODE_COACH
        startForeground(1, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        val code = intent?.getIntExtra(EXTRA_CODE, 0) ?: 0
        val data: Intent? = if (Build.VERSION.SDK_INT >= 33) intent?.getParcelableExtra(EXTRA_DATA, Intent::class.java)
            else @Suppress("DEPRECATION") intent?.getParcelableExtra(EXTRA_DATA)
        if (data == null) { stopSelf(); return START_NOT_STICKY }
        startCapture(code, data)
        showOverlay()
        running = true
        if (coachMode) startCoach() else bg.postDelayed(::tick, 1500)
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

    /**
     * Runs on the bg thread. When the phone turns (the app starts in portrait, Arena is landscape),
     * resize the capture to match; otherwise Arena's picture is squeezed and the text is too small to read.
     */
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

    /** Runs on the bg thread every [TICK_MS]: whenever no scan is running, scan the frame if it's new. */
    private fun tick() {
        if (!running) return
        if (++ticks % 8 == 0) matchRotation() // backup in case a rotation callback was missed
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
                    val counts = result.map { (card, _, read) ->
                        val v = votes.getOrPut(card.name) { IntArray(5) }
                        read.count?.let { v[it]++ }
                        // Most-read count; ties go to the higher one. null until it has been read once.
                        val best = (1..4).filter { v[it] > 0 }.maxWithOrNull(compareBy<Int>({ v[it] }, { it }))
                        card to best
                    }
                    val added = CollectionStore.recordScan(this, counts)
                    found += added
                    saveDebug(bmp, result)
                    main.post {
                        if (added > 0) flash("+$added")
                        busy = false
                    }
                }
            }
            .addOnFailureListener { main.post { flash("Error"); busy = false } }
    }

    /** For each card name found, the copies read from the diamonds above it (null if unreadable). */
    private fun countPips(bmp: Bitmap, hits: List<Pair<Card, Box>>): List<Triple<Card, Box, PipRead>> {
        if (hits.isEmpty()) return emptyList()
        val w = bmp.width; val h = bmp.height
        val px = IntArray(w * h)
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

    /** Saves the last scanned screen with what was read drawn on it, for "Save last scan image". */
    private fun saveDebug(bmp: Bitmap, result: List<Triple<Card, Box, PipRead>>) {
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
            runCatching { debugFile(this).outputStream().use { copy.compress(Bitmap.CompressFormat.PNG, 100, it) } }
            copy.recycle()
        }
    }

    private fun showOverlay() {
        val wm = getSystemService(WindowManager::class.java)
        val d = resources.displayMetrics.density
        val btn = TextView(this).apply {
            text = if (coachMode) "Loading" else "Auto"
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
                    else if (coachMode) coachTap()
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
            setColor(Color.parseColor(when { coachMode -> "#E66A3FB5"; paused -> "#CC5B6678"; else -> "#E62A63B0" }))
            setStroke((2 * d).toInt(), Color.WHITE)
        }
    }

    /** Shows a short result on the button, then goes back to "Auto". */
    private fun flash(label: String, hold: Long = 1000) {
        val btn = overlay ?: return
        btn.text = label
        main.removeCallbacksAndMessages("flash")
        main.postAtTime({ btn.text = idleLabel() }, "flash", android.os.SystemClock.uptimeMillis() + hold)
    }

    private fun idleLabel() = when {
        coachMode && !coachReady -> "Loading"
        coachMode -> "Coach"
        paused -> "Paused"
        else -> "Auto"
    }

    // ---------------------------------------------------------------- coach mode

    /** Loads Gemma in the background; the button says "Loading" until it's ready (about 10 s). */
    private fun startCoach() {
        coaching = true
        coachExec.execute {
            try {
                val g = Gemma(this)
                g.load()
                gemma = g
                coachReady = true
                main.post { overlay?.text = "Coach"; showPanel("Coach ready (${g.backendName}). On your turn, tap Coach.") }
            } catch (t: Throwable) {
                main.post { showPanel("Couldn't start Gemma: ${t.message}\nLong-press the button to close."); overlay?.text = "Error" }
            }
        }
    }

    /** Coach button tapped: hide the overlay, grab a clean frame, read it, ask Gemma. */
    private fun coachTap() {
        if (!coachReady) { showPanel("Still loading the model…"); return }
        if (coachBusy) return
        coachBusy = true
        overlay?.visibility = android.view.View.INVISIBLE
        panelBox?.visibility = android.view.View.INVISIBLE
        // Wait for a frame captured after the overlay disappeared.
        bg.postDelayed({
            val img = latest
            val bmp = img?.let { toBitmap(it) }
            main.post {
                overlay?.visibility = android.view.View.VISIBLE
                if (bmp == null) { showPanel("Couldn't capture the screen. Try again."); coachBusy = false; return@post }
                showPanel("Reading the board…")
                readBoard(bmp)
            }
        }, 350)
    }

    private fun readBoard(bmp: Bitmap) {
        recognizer.process(InputImage.fromBitmap(bmp, 0))
            .addOnSuccessListener { text ->
                val seen = text.textBlocks.flatMap { it.lines }.mapNotNull { l ->
                    val r = l.boundingBox ?: return@mapNotNull null
                    CoachPrompt.Seen(l.text, r.exactCenterX() / bmp.width, r.exactCenterY() / bmp.height, r.height().toFloat() / bmp.height)
                }
                val board = CoachPrompt.analyze(seen) { matcher.match(it) }
                val cards = board.cards
                val names = board.cards.map { it.first.name }.toSet()
                val learned = LessonMatch.best(Lessons.all(this), board.phase.name, names)
                val prompt = CoachPrompt.build(board, lessons = learned)
                val image = jpeg(bmp, 1024)
                lastImage = image; lastPrompt = prompt; lastBoard = board; lastAnswer = ""
                showPanel("Thinking… (${cards.distinctBy { it.first.name }.size} cards read)")
                coachExec.execute { askGemma(image, prompt) }
            }
            .addOnFailureListener { showPanel("Couldn't read the screen: ${it.message}"); coachBusy = false }
    }

    private fun askGemma(image: ByteArray, prompt: String) {
        val g = gemma ?: run { main.post { coachBusy = false }; return }
        val started = System.currentTimeMillis()
        g.ask(image, prompt,
            onText = { t -> lastAnswer = t.trim(); main.post { showPanel(lastAnswer) } },
            onDone = {
                val secs = (System.currentTimeMillis() - started) / 1000
                val used = Regex("Lessons from my past games").containsMatchIn(prompt)
                main.post {
                    panel?.let { it.text = styled("$lastAnswer\n\n(${secs}s${if (used) " · used your past lessons" else ""})") }
                    actions?.visibility = android.view.View.VISIBLE
                    coachBusy = false
                }
            },
            onError = { t -> main.post { showPanel("Gemma error: ${t.message}"); coachBusy = false } })
    }

    /** Screenshot scaled to [maxW] pixels wide as JPEG, small enough for the model's image input. */
    private fun jpeg(bmp: Bitmap, maxW: Int): ByteArray {
        val scaled = if (bmp.width <= maxW) bmp
            else Bitmap.createScaledBitmap(bmp, maxW, (bmp.height.toLong() * maxW / bmp.width).toInt(), true)
        val out = java.io.ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.JPEG, 90, out)
        return out.toByteArray()
    }

    /** Bold, coloured "PLAY:" / "WHY:" labels so the answer is quick to scan. */
    private fun styled(text: String): CharSequence {
        val sb = android.text.SpannableStringBuilder(text)
        for ((label, color) in listOf("PLAY:" to "#FFD36B", "WHY:" to "#9EC5FF")) {
            var i = text.indexOf(label)
            while (i >= 0) {
                sb.setSpan(android.text.style.StyleSpan(android.graphics.Typeface.BOLD), i, i + label.length, 0)
                sb.setSpan(android.text.style.ForegroundColorSpan(Color.parseColor(color)), i, i + label.length, 0)
                i = text.indexOf(label, i + 1)
            }
        }
        return sb
    }

    /** Saves the last answer as a lesson: [correct] is the play you confirmed or typed. */
    private fun saveLesson(correct: String, good: Boolean) {
        val b = lastBoard ?: return
        val l = Lesson(System.currentTimeMillis(), b.phase.name, b.cards.map { it.first.name }.distinct(),
            CoachPrompt.summary(b), lastAnswer, correct, good)
        Lessons.add(this, l, lastImage, lastPrompt)
        actions?.visibility = android.view.View.GONE
        showPanel(if (good) "Saved: the coach will repeat this in similar spots." else "Saved your play. The coach will use it in similar spots.")
    }

    /** Text box over Arena for typing the right play. This window takes focus so the keyboard opens. */
    private fun showFix() {
        val wm = getSystemService(WindowManager::class.java)
        val d = resources.displayMetrics.density
        fixBox?.let { runCatching { wm.removeView(it) } }
        val pad = (12 * d).toInt()
        val edit = android.widget.EditText(this).apply {
            hint = "The right play, e.g. Block the 2/2 with Veteran Survivor"
            setText(Regex("PLAY:\\s*(.*)").find(lastAnswer)?.groupValues?.get(1) ?: "")
            setTextColor(Color.WHITE); setHintTextColor(Color.LTGRAY); textSize = 15f
            minLines = 2
        }
        val box = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
            background = GradientDrawable().apply { cornerRadius = 12 * d; setColor(Color.parseColor("#F21B1530")) }
            addView(TextView(this@ScanService).apply { text = "What was the right play?"; setTextColor(Color.WHITE); textSize = 15f })
            addView(edit)
            addView(android.widget.LinearLayout(this@ScanService).apply {
                gravity = Gravity.END
                addView(android.widget.Button(this@ScanService).apply { text = "Cancel"; setOnClickListener { runCatching { wm.removeView(fixBox) }; fixBox = null } })
                addView(android.widget.Button(this@ScanService).apply {
                    text = "Save"
                    setOnClickListener {
                        val t = edit.text.toString().trim()
                        runCatching { wm.removeView(fixBox) }; fixBox = null
                        if (t.isNotEmpty()) saveLesson("PLAY: $t", good = false)
                    }
                })
            })
        }
        val lp = WindowManager.LayoutParams(
            (minOf(width, height) * 1.1).toInt().coerceAtMost((width * 0.6).toInt()),
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, 0, PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL; y = (8 * d).toInt(); softInputMode = WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE }
        wm.addView(box, lp)
        fixBox = box
        edit.requestFocus()
    }

    /** Advice panel at the top centre of the screen; tap the text to hide. Rate/correct buttons show after an answer. */
    private fun showPanel(text: String) {
        val wm = getSystemService(WindowManager::class.java)
        val d = resources.displayMetrics.density
        val p = panel ?: TextView(this).apply {
            textSize = 15f
            setLineSpacing(0f, 1.15f)
            setTextColor(Color.WHITE)
            val pad = (14 * d).toInt()
            setPadding(pad, pad, pad, pad)
            background = GradientDrawable().apply {
                cornerRadius = 12 * d
                setColor(Color.parseColor("#E61B1530"))
                setStroke((1 * d).toInt(), Color.parseColor("#806A3FB5"))
            }
            setOnClickListener { panelBox?.visibility = android.view.View.GONE }
        }.also { tv ->
            fun btn(label: String, onClick: () -> Unit) = android.widget.Button(this).apply {
                this.text = label; textSize = 13f; isAllCaps = false; setOnClickListener { onClick() }
            }
            val row = android.widget.LinearLayout(this).apply {
                visibility = android.view.View.GONE
                addView(btn("👍 Good play") { saveLesson(lastAnswer, good = true) })
                addView(btn("✏️ Fix it") { showFix() })
                addView(btn("✕") { panelBox?.visibility = android.view.View.GONE })
            }
            val boxView = android.widget.LinearLayout(this).apply {
                orientation = android.widget.LinearLayout.VERTICAL
                addView(tv); addView(row)
            }
            val lp = WindowManager.LayoutParams(
                (minOf(width, height) * 1.1).toInt().coerceAtMost((width * 0.6).toInt()),
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, PixelFormat.TRANSLUCENT
            ).apply { gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL; y = (8 * d).toInt() }
            wm.addView(boxView, lp)
            panel = tv; actions = row; panelBox = boxView
        }
        p.text = styled(text)
        actions?.visibility = android.view.View.GONE
        panelBox?.visibility = android.view.View.VISIBLE
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
            .setContentTitle(if (coachMode) "Move coach is on" else "Arena Scanner is reading your screen")
            .setContentText(if (coachMode) "On your turn, tap the Coach button. Long-press it to stop."
                else "Scroll through your collection; it scans as you go. Tap the button to pause.")
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(null, "Stop", stop).build())
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        running = false
        coaching = false
        overlay?.let { runCatching { getSystemService(WindowManager::class.java).removeView(it) } }
        overlay = null
        panelBox?.let { runCatching { getSystemService(WindowManager::class.java).removeView(it) } }
        fixBox?.let { runCatching { getSystemService(WindowManager::class.java).removeView(it) } }
        panel = null
        coachExec.execute { gemma?.close(); gemma = null }
        coachExec.shutdown()
        display?.release()
        projection?.stop()
        if (::bg.isInitialized) bg.post { latest?.close(); latest = null; reader?.close(); bgThread.quitSafely() }
        runCatching { recognizer.close() }
        debugWriter.shutdown()
        super.onDestroy()
    }
}

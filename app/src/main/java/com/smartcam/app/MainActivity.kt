package com.smartcam.app

import android.Manifest
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ContentUris
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.SurfaceTexture
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.provider.Settings
import android.util.Size
import android.view.GestureDetector
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.TextureView
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.WindowInsets
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity(), CamListener {
    private val ui = Handler(Looper.getMainLooper())
    private lateinit var opts: Options
    private lateinit var cam: Cam
    private lateinit var tv: TextureView
    private lateinit var overlay: Overlay
    private lateinit var flash: View
    private lateinit var hud: TextView
    private lateinit var countdown: TextView
    private lateinit var shutter: Button
    private lateinit var thumb: ImageView
    private lateinit var flip: Button
    private lateinit var zoomChip: Button
    private lateinit var lensRow: LinearLayout
    private lateinit var photoTab: TextView
    private lateinit var videoTab: TextView
    private lateinit var settings: ScrollView
    private lateinit var settingsRows: LinearLayout
    private var stops: List<Stop> = emptyList()
    private val lensButtons = ArrayList<Button>()
    private var lastUri: Uri? = null
    private var busy = false
    private var recording = false
    private var videoMode = false
    private var counting = 0

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        opts = Options(this)
        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        val previewLp = { FrameLayout.LayoutParams(MATCH_PARENT, resources.displayMetrics.widthPixels * 4 / 3, Gravity.CENTER) }
        tv = TextureView(this).also { root.addView(it, previewLp()) }
        overlay = Overlay(this).also { root.addView(it, previewLp()) }
        flash = View(this).apply { setBackgroundColor(Color.WHITE); alpha = 0f }.also { root.addView(it, previewLp()) }

        // Top bar: live readout, auto-zoom chip, options.
        val top = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setBackgroundColor(Color.argb(110, 0, 0, 0)) }
        hud = TextView(this).apply { setTextColor(Color.WHITE); textSize = 12f; text = "Starting camera…" }
        top.addView(hud, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        zoomChip = pill("") { opts.autoZoom = !opts.autoZoom; refresh() }
        top.addView(zoomChip, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { marginEnd = dp(6) })
        top.addView(pill("⚙") { toggleSettings() })
        root.addView(top, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT, Gravity.TOP))

        countdown = TextView(this).apply { setTextColor(Color.WHITE); textSize = 96f; setShadowLayer(12f, 0f, 0f, Color.BLACK); visibility = View.GONE }
        root.addView(countdown, FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.CENTER))

        // Bottom panel: lens buttons, photo/video tabs, then last photo | shutter | flip.
        val bottom = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
            setBackgroundColor(Color.argb(120, 0, 0, 0))
        }
        lensRow = LinearLayout(this).apply { gravity = Gravity.CENTER }
        bottom.addView(lensRow, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(8) })
        val tabs = LinearLayout(this).apply { gravity = Gravity.CENTER }
        photoTab = tab("PHOTO") { setMode(false) }
        videoTab = tab("VIDEO") { setMode(true) }
        tabs.addView(photoTab); tabs.addView(videoTab)
        bottom.addView(tabs, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(6) })

        val controls = FrameLayout(this)
        thumb = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            background = GradientDrawable().apply { cornerRadius = dp(12).toFloat(); setColor(Color.DKGRAY); setStroke(dp(2), Color.WHITE) }
            clipToOutline = true
            contentDescription = "Last photo or video, opens gallery"
            setOnClickListener { openGallery() }
        }
        controls.addView(thumb, FrameLayout.LayoutParams(dp(56), dp(56), Gravity.START or Gravity.CENTER_VERTICAL).apply { marginStart = dp(24) })
        shutter = Button(this).apply { setOnClickListener { takePhoto() } }
        controls.addView(shutter, FrameLayout.LayoutParams(dp(76), dp(76), Gravity.CENTER))
        flip = pill("⟲ Flip") { cam.setFront(!cam.front) }.apply { contentDescription = "Switch front/back camera" }
        controls.addView(flip, FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.END or Gravity.CENTER_VERTICAL).apply { marginEnd = dp(20) })
        bottom.addView(controls, LinearLayout.LayoutParams(MATCH_PARENT, dp(104)))
        root.addView(bottom, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT, Gravity.BOTTOM))

        settings = buildSettings()
        root.addView(settings, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT, Gravity.BOTTOM))

        // Keep bars clear of the status bar, notch and gesture area.
        root.setOnApplyWindowInsetsListener { _, ins ->
            val i = ins.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            top.setPadding(dp(16), i.top + dp(8), dp(8), dp(8))
            bottom.setPadding(0, 0, 0, i.bottom)
            settingsRows.setPadding(dp(20), dp(16), dp(20), dp(16) + i.bottom)
            ins
        }
        setContentView(root)

        cam = Cam(this, tv, opts, this)
        setupGestures()
        refresh()
    }

    private fun pill(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label; isAllCaps = false; setTextColor(Color.WHITE); textSize = 13f
        minHeight = 0; minimumHeight = 0; minWidth = 0; minimumWidth = 0; setPadding(dp(14), dp(8), dp(14), dp(8))
        background = GradientDrawable().apply { cornerRadius = dp(20).toFloat(); setColor(Color.argb(150, 40, 40, 40)) }
        setOnClickListener { onClick() }
    }

    private fun tab(label: String, onClick: () -> Unit) = TextView(this).apply {
        text = label; textSize = 13f; letterSpacing = 0.1f; setPadding(dp(16), dp(4), dp(16), dp(4))
        setOnClickListener { onClick() }
    }

    private fun setMode(video: Boolean) {
        if (recording || video == videoMode) return
        videoMode = video
        if (video && checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED)
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 2)
        cam.setVideo(video)
        refresh()
    }

    private fun setAspect(a: Float) {
        val hgt = (resources.displayMetrics.widthPixels * a).toInt()
        for (v in listOf(tv, overlay, flash)) { v.layoutParams = v.layoutParams.apply { height = hgt } }
    }

    private fun setupGestures() {
        val pinch = ScaleGestureDetector(this, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(d: ScaleGestureDetector): Boolean { opts.autoZoom = false; cam.pinch(d.scaleFactor); refresh(); return true }
        })
        val taps = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDoubleTap(e: MotionEvent): Boolean {
                if (settings.visibility == View.VISIBLE) return true
                // The object glides to the centre, so only flash the ring where the finger was.
                overlay.focus(e.x, e.y)
                overlay.postDelayed({ overlay.hideRing() }, 500)
                cam.zoomTo(e.x / tv.width, e.y / tv.height)
                return true
            }
            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                if (settings.visibility == View.VISIBLE) { toggleSettings(); return true }
                overlay.focus(e.x, e.y)
                cam.focusAt(e.x / tv.width, e.y / tv.height)
                return true
            }
            override fun onLongPress(e: MotionEvent) {
                overlay.hideRing(); cam.clearFocus()
                Toast.makeText(this@MainActivity, "Focus back to automatic", Toast.LENGTH_SHORT).show()
            }
        })
        tv.setOnTouchListener { _, e ->
            pinch.onTouchEvent(e)
            if (!pinch.isInProgress) taps.onTouchEvent(e)
            true
        }
    }

    private fun buildSettings(): ScrollView {
        settingsRows = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                cornerRadii = FloatArray(8) { if (it < 4) dp(20).toFloat() else 0f }
                setColor(Color.argb(240, 22, 22, 22))
            }
        }
        fun label(t: String, size: Float = 13f) = TextView(this).apply {
            text = t; textSize = size; setTextColor(if (size > 14) Color.WHITE else Color.LTGRAY); setPadding(0, dp(10), 0, dp(2))
        }.also { settingsRows.addView(it) }
        fun toggle(t: String, hint: String, get: () -> Boolean, set: (Boolean) -> Unit) {
            settingsRows.addView(Switch(this).apply {
                text = t; textSize = 16f; setTextColor(Color.WHITE); isChecked = get()
                setPadding(0, dp(10), 0, 0)
                setOnCheckedChangeListener { _, c -> set(c); refresh() }
            })
            label(hint, 12f)
        }
        fun choice(t: String, values: List<Int>, name: (Int) -> String, get: () -> Int, set: (Int) -> Unit) {
            val row = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(8), 0, 0) }
            row.addView(TextView(this).apply { text = t; textSize = 16f; setTextColor(Color.WHITE) }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            val btn = pill("") {}
            fun upd() { btn.text = name(get()) }
            btn.setOnClickListener { set(values[(values.indexOf(get()) + 1) % values.size]); upd(); refresh() }
            upd()
            row.addView(btn)
            settingsRows.addView(row)
        }

        label("Options", 20f)
        toggle("Smart exposure", "Photos: picks the lowest ISO whose shutter is fast enough for your hands and the subject. Off = phone's own auto-exposure.",
            { opts.smartExposure }, { opts.smartExposure = it })
        toggle("Protect highlights", "Darkens up to 1.5 EV when bright areas would blow out to white.",
            { opts.protectHighlights }, { opts.protectHighlights = it })
        toggle("Auto zoom to faces", "Frames detected faces using the best lens. Pinching or picking a lens turns this off; double-tap doesn't.",
            { opts.autoZoom }, { opts.autoZoom = it })
        toggle("Grid", "Rule-of-thirds lines for composition.", { opts.grid }, { opts.grid = it })
        toggle("Volume keys take photo", "Easier to hold steady than tapping the screen.", { opts.volumeShutter }, { opts.volumeShutter = it })

        choice("Burst (keeps sharpest)", listOf(1, 3, 5), { if (it == 1) "Off" else "$it shots" }, { opts.burst }, { opts.burst = it })
        choice("Max ISO", listOf(0, 800, 1600, 3200), { if (it == 0) "No limit" else "$it" }, { opts.maxIso }, { opts.maxIso = it })
        choice("Self-timer", listOf(0, 3, 10), { if (it == 0) "Off" else "${it}s" }, { opts.timer }, { opts.timer = it })

        val evLabel = label("")
        fun evText() { evLabel.text = "Exposure compensation: %+.1f EV".format(opts.ev) }
        evText()
        settingsRows.addView(SeekBar(this).apply {
            max = 8; progress = opts.evHalfStops + 4
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar, p: Int, fromUser: Boolean) { opts.evHalfStops = p - 4; evText() }
                override fun onStartTrackingTouch(s: SeekBar) {}
                override fun onStopTrackingTouch(s: SeekBar) {}
            })
        })
        label("Tap to focus. Double-tap an object to zoom in and follow it (double-tap again to let go). Long-press for automatic focus.", 12f)
        settingsRows.addView(pill("Done") { toggleSettings() }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(12) })

        return ScrollView(this).apply { addView(settingsRows); visibility = View.GONE }
    }

    private fun toggleSettings() {
        val show = settings.visibility != View.VISIBLE
        settings.visibility = if (show) View.VISIBLE else View.GONE
        if (show) { settings.translationY = dp(60).toFloat(); settings.animate().translationY(0f).setDuration(150).start() }
    }

    private fun refresh() {
        overlay.grid = opts.grid
        zoomChip.text = if (opts.autoZoom) "Auto zoom ✓" else "Auto zoom ✗"
        val red = Color.rgb(230, 40, 40)
        shutter.background = GradientDrawable().apply {
            when {
                recording -> { cornerRadius = dp(14).toFloat(); setColor(red); setStroke(dp(14), Color.WHITE) }
                videoMode -> { shape = GradientDrawable.OVAL; setColor(red); setStroke(dp(5), Color.WHITE) }
                else -> { shape = GradientDrawable.OVAL; setColor(Color.WHITE); setStroke(dp(5), Color.GRAY) }
            }
        }
        shutter.contentDescription = when { recording -> "Stop recording"; videoMode -> "Start recording"; else -> "Take photo" }
        shutter.isEnabled = !busy
        shutter.alpha = if (busy) 0.5f else 1f
        for ((t, on) in listOf(photoTab to !videoMode, videoTab to videoMode)) {
            t.setTextColor(if (on) Color.rgb(255, 214, 0) else Color.LTGRAY)
            t.typeface = if (on) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
            t.alpha = if (recording && !on) 0.4f else 1f
        }
        flip.isEnabled = !recording; flip.alpha = if (recording) 0.4f else 1f
    }

    private fun buildLensRow(front: Boolean) {
        lensRow.removeAllViews(); lensButtons.clear()
        if (front) return
        for (s in stops) {
            val b = pill(s.label) { opts.autoZoom = false; refresh(); cam.selectStop(s) }
            b.contentDescription = "${s.label} lens"
            lensButtons += b
            lensRow.addView(b, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { marginStart = dp(4); marginEnd = dp(4) })
        }
    }

    /** Highlight the lens button for the current zoom (the longest lens at or below it). */
    private fun highlightLens(zoom: Float) {
        val i = stops.indexOfLast { it.ratio <= zoom * 1.02f }.coerceAtLeast(0)
        lensButtons.forEachIndexed { j, b ->
            val on = j == i
            b.setTextColor(if (on) Color.rgb(255, 214, 0) else Color.WHITE)
            b.text = if (on && stops.getOrNull(j)?.let { kotlin.math.abs(it.ratio - zoom) > 0.05f } == true) "%.1fx".format(zoom) else stops[j].label
        }
    }

    private fun takePhoto() {
        if (counting > 0) { counting = 0; ui.removeCallbacksAndMessages(null); countdown.visibility = View.GONE; return }
        if (busy) return
        if (settings.visibility == View.VISIBLE) toggleSettings()
        if (recording || opts.timer == 0) { cam.shoot(); return }
        counting = opts.timer
        countdown.visibility = View.VISIBLE
        fun tick() {
            if (counting <= 0) return
            countdown.text = "$counting"
            counting--
            ui.postDelayed({ if (counting == 0) { countdown.visibility = View.GONE; cam.shoot() } else tick() }, 1000)
        }
        tick()
    }

    // ---- Last photo/video thumbnail and gallery ----

    private fun loadThumb(uri: Uri? = null) = Thread {
        val u = uri ?: latestMedia()
        val bmp = u?.let { runCatching { contentResolver.loadThumbnail(it, Size(dp(56) * 2, dp(56) * 2), null) }.getOrNull() }
        runOnUiThread {
            lastUri = if (bmp != null) u else null
            thumb.setImageBitmap(bmp)
            if (bmp != null && uri != null) {
                thumb.scaleX = 0.6f; thumb.scaleY = 0.6f
                thumb.animate().scaleX(1f).scaleY(1f).setDuration(200).start()
            }
        }
    }.start()

    /** Newest photo or video this app saved (no storage permission needed for the app's own files). */
    private fun latestMedia(): Uri? = runCatching {
        val type = MediaStore.Files.FileColumns.MEDIA_TYPE
        contentResolver.query(
            MediaStore.Files.getContentUri("external"),
            arrayOf(MediaStore.Files.FileColumns._ID, type),
            "(${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ? OR ${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?) AND $type IN (?, ?)",
            arrayOf("Pictures/SmartCam%", "Movies/SmartCam%",
                "${MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE}", "${MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO}"),
            "${MediaStore.MediaColumns.DATE_ADDED} DESC",
        )?.use { c ->
            if (!c.moveToFirst()) return@use null
            val col = if (c.getInt(1) == MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO) MediaStore.Video.Media.EXTERNAL_CONTENT_URI
            else MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            ContentUris.withAppendedId(col, c.getLong(0))
        }
    }.getOrNull()

    private fun openGallery() {
        val u = lastUri
        val intent = if (u != null) Intent(Intent.ACTION_VIEW).setDataAndType(u, contentResolver.getType(u) ?: "image/*")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        else Intent(Intent.ACTION_VIEW, MediaStore.Images.Media.EXTERNAL_CONTENT_URI)
        try { startActivity(intent) } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, "No gallery app found", Toast.LENGTH_SHORT).show()
        }
    }

    // ---- Camera callbacks (arrive on the camera thread) ----

    override fun hud(text: String, zoom: Float) = runOnUiThread { hud.text = text; highlightLens(zoom); zoomChip.text = if (opts.autoZoom) "Auto zoom ✓" else "Auto zoom ✗" }
    override fun busy(busy: Boolean) = runOnUiThread { this.busy = busy; refresh() }
    override fun captured() = runOnUiThread {
        flash.alpha = 0.7f
        flash.animate().alpha(0f).setDuration(250).start()
    }
    override fun saved(uri: Uri, msg: String) = runOnUiThread {
        loadThumb(uri)
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }
    override fun error(msg: String) = runOnUiThread {
        hud.text = msg
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
    }
    override fun configured(aspect: Float, stops: List<Stop>, front: Boolean, video: Boolean) = runOnUiThread {
        setAspect(aspect)
        this.stops = stops
        buildLensRow(front)
        videoMode = video
        refresh()
    }
    override fun recording(on: Boolean) = runOnUiThread { recording = on; refresh() }

    // ---- Lifecycle and permissions ----

    override fun onKeyDown(code: Int, e: KeyEvent): Boolean {
        if (opts.volumeShutter && (code == KeyEvent.KEYCODE_VOLUME_UP || code == KeyEvent.KEYCODE_VOLUME_DOWN)) {
            if (e.repeatCount == 0) takePhoto()
            return true
        }
        return super.onKeyDown(code, e)
    }

    override fun onResume() {
        super.onResume()
        loadThumb()
        if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) start()
        else requestPermissions(arrayOf(Manifest.permission.CAMERA), 1)
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, res: IntArray) {
        if (code == 2) {
            if (res.firstOrNull() != PackageManager.PERMISSION_GRANTED)
                Toast.makeText(this, "Videos will be recorded without sound", Toast.LENGTH_LONG).show()
            return
        }
        if (res.firstOrNull() == PackageManager.PERMISSION_GRANTED) { start(); return }
        hud.text = "SmartCam needs the camera permission. Tap here to open settings."
        hud.setOnClickListener {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)))
        }
    }

    private fun start() {
        hud.setOnClickListener(null)
        tv.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(s: SurfaceTexture, w: Int, h: Int) { cam.open() }
            override fun onSurfaceTextureSizeChanged(s: SurfaceTexture, w: Int, h: Int) {}
            override fun onSurfaceTextureDestroyed(s: SurfaceTexture) = true
            override fun onSurfaceTextureUpdated(s: SurfaceTexture) {}
        }
        if (tv.isAvailable) cam.open()
    }

    override fun onPause() {
        counting = 0; ui.removeCallbacksAndMessages(null); countdown.visibility = View.GONE
        cam.close()
        super.onPause()
    }

    override fun onDestroy() {
        cam.release()
        super.onDestroy()
    }
}

package com.smartcam.app

import android.Manifest
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ContentUris
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.SurfaceTexture
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
    private lateinit var zoomChip: Button
    private lateinit var settings: ScrollView
    private lateinit var settingsRows: LinearLayout
    private var lastUri: Uri? = null
    private var busy = false
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

        // Top bar: live settings readout + settings button.
        val top = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setBackgroundColor(Color.argb(110, 0, 0, 0)) }
        hud = TextView(this).apply { setTextColor(Color.WHITE); textSize = 13f; text = "Starting camera…" }
        top.addView(hud, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        top.addView(pill("⚙ Options") { toggleSettings() })
        root.addView(top, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT, Gravity.TOP))

        countdown = TextView(this).apply { setTextColor(Color.WHITE); textSize = 96f; setShadowLayer(12f, 0f, 0f, Color.BLACK); visibility = View.GONE }
        root.addView(countdown, FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.CENTER))

        // Bottom bar: last photo (left), shutter (centre), auto-zoom chip (right).
        val bottom = FrameLayout(this).apply { setBackgroundColor(Color.argb(110, 0, 0, 0)) }
        thumb = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            background = GradientDrawable().apply { cornerRadius = dp(12).toFloat(); setColor(Color.DKGRAY); setStroke(dp(2), Color.WHITE) }
            clipToOutline = true
            contentDescription = "Last photo, opens gallery"
            setOnClickListener { openGallery() }
        }
        bottom.addView(thumb, FrameLayout.LayoutParams(dp(56), dp(56), Gravity.START or Gravity.CENTER_VERTICAL).apply { marginStart = dp(24) })
        shutter = Button(this).apply {
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.WHITE); setStroke(dp(5), Color.GRAY) }
            contentDescription = "Take photo"
            setOnClickListener { takePhoto() }
        }
        bottom.addView(shutter, FrameLayout.LayoutParams(dp(76), dp(76), Gravity.CENTER))
        zoomChip = pill("") { opts.autoZoom = !opts.autoZoom; refresh() }
        bottom.addView(zoomChip, FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.END or Gravity.CENTER_VERTICAL).apply { marginEnd = dp(16) })
        root.addView(bottom, FrameLayout.LayoutParams(MATCH_PARENT, dp(120), Gravity.BOTTOM))

        settings = buildSettings()
        root.addView(settings, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT, Gravity.BOTTOM))

        // Keep bars clear of the status bar, notch and gesture area.
        root.setOnApplyWindowInsetsListener { _, ins ->
            val i = ins.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            top.setPadding(dp(16), i.top + dp(8), dp(8), dp(8))
            (bottom.layoutParams as FrameLayout.LayoutParams).height = dp(120) + i.bottom
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
        minHeight = 0; minimumHeight = 0; setPadding(dp(14), dp(8), dp(14), dp(8))
        background = GradientDrawable().apply { cornerRadius = dp(20).toFloat(); setColor(Color.argb(150, 40, 40, 40)) }
        setOnClickListener { onClick() }
    }

    private fun setupGestures() {
        val pinch = ScaleGestureDetector(this, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(d: ScaleGestureDetector): Boolean { cam.pinch(d.scaleFactor); refresh(); return true }
        })
        val taps = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onSingleTapUp(e: MotionEvent): Boolean {
                if (settings.visibility == View.VISIBLE) { toggleSettings(); return true }
                overlay.focus(e.x, e.y)
                cam.focusAt(e.x / tv.width, e.y / tv.height)
                return true
            }
            override fun onLongPress(e: MotionEvent) {
                overlay.clearFocus(); cam.clearFocus()
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
        toggle("Smart exposure", "Picks the lowest ISO whose shutter is fast enough for your hands and the subject. Off = phone's own auto-exposure.",
            { opts.smartExposure }, { opts.smartExposure = it })
        toggle("Protect highlights", "Darkens up to 1.5 EV when bright areas would blow out to white.",
            { opts.protectHighlights }, { opts.protectHighlights = it })
        toggle("Auto zoom to faces", "Frames detected faces. Pinching turns this off.", { opts.autoZoom }, { opts.autoZoom = it })
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
        label("Tap the preview to focus there; long-press to go back to automatic focus.", 12f)
        settingsRows.addView(pill("Done") { toggleSettings() }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(12) })

        return ScrollView(this).apply { addView(settingsRows); visibility = View.GONE; isFillViewport = false }
    }

    private fun toggleSettings() {
        val show = settings.visibility != View.VISIBLE
        settings.visibility = if (show) View.VISIBLE else View.GONE
        if (show) { settings.translationY = dp(60).toFloat(); settings.animate().translationY(0f).setDuration(150).start() }
    }

    private fun refresh() {
        overlay.grid = opts.grid
        zoomChip.text = if (opts.autoZoom) "Auto zoom: on" else "Auto zoom: off"
        val enabled = !busy
        shutter.isEnabled = enabled
        shutter.alpha = if (enabled) 1f else 0.5f
    }

    private fun takePhoto() {
        if (counting > 0) { counting = 0; ui.removeCallbacksAndMessages(null); countdown.visibility = View.GONE; return }
        if (busy) return
        if (settings.visibility == View.VISIBLE) toggleSettings()
        if (opts.timer == 0) { cam.shoot(); return }
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

    // ---- Last photo thumbnail and gallery ----

    private fun loadThumb(uri: Uri? = null) = Thread {
        val u = uri ?: latestPhoto()
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

    /** Newest photo this app saved (no storage permission needed for the app's own photos). */
    private fun latestPhoto(): Uri? = runCatching {
        contentResolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI, arrayOf(MediaStore.Images.Media._ID),
            "${MediaStore.Images.Media.RELATIVE_PATH} LIKE ?", arrayOf("Pictures/SmartCam%"),
            "${MediaStore.Images.Media.DATE_ADDED} DESC",
        )?.use { c -> if (c.moveToFirst()) ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, c.getLong(0)) else null }
    }.getOrNull()

    private fun openGallery() {
        val u = lastUri
        val intent = if (u != null) Intent(Intent.ACTION_VIEW).setDataAndType(u, "image/jpeg").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        else Intent(Intent.ACTION_VIEW, MediaStore.Images.Media.EXTERNAL_CONTENT_URI)
        try { startActivity(intent) } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, "No gallery app found", Toast.LENGTH_SHORT).show()
        }
    }

    // ---- Camera callbacks (arrive on the camera thread) ----

    override fun hud(text: String) = runOnUiThread { hud.text = text }
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
}

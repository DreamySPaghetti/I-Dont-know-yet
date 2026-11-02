package com.smartcam.app

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.SurfaceTexture
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.ScaleGestureDetector
import android.view.TextureView
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {
    private lateinit var tv: TextureView
    private lateinit var hud: TextView
    private lateinit var autoBtn: Button
    private lateinit var cam: Cam

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        val dm = resources.displayMetrics
        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        tv = TextureView(this)
        root.addView(tv, FrameLayout.LayoutParams(MATCH_PARENT, dm.widthPixels * 4 / 3, Gravity.CENTER))
        hud = TextView(this).apply {
            setTextColor(Color.WHITE); textSize = 14f; setPadding(32, 96, 32, 16)
            setShadowLayer(4f, 0f, 0f, Color.BLACK)
        }
        root.addView(hud, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT, Gravity.TOP))

        val size = (dm.density * 76).toInt()
        val shutter = Button(this).apply {
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.WHITE); setStroke(10, Color.GRAY) }
            setOnClickListener { cam.shoot() }
        }
        root.addView(shutter, FrameLayout.LayoutParams(size, size, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = size / 2 })
        autoBtn = Button(this).apply { setOnClickListener { cam.autoZoom = true; refreshAuto() } }
        root.addView(autoBtn, FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.BOTTOM or Gravity.END).apply { setMargins(0, 0, 32, size / 2) })
        setContentView(root)

        cam = Cam(this, tv,
            onHud = { s -> runOnUiThread { hud.text = s } },
            onSaved = { s -> runOnUiThread { Toast.makeText(this, s, Toast.LENGTH_SHORT).show() } })
        val pinch = ScaleGestureDetector(this, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(d: ScaleGestureDetector): Boolean { cam.pinch(d.scaleFactor); refreshAuto(); return true }
        })
        tv.setOnTouchListener { _, e -> pinch.onTouchEvent(e); true }
        refreshAuto()
    }

    private fun refreshAuto() { autoBtn.text = if (cam.autoZoom) "Auto zoom ON" else "Auto zoom OFF" }

    override fun onResume() {
        super.onResume()
        if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) start()
        else requestPermissions(arrayOf(Manifest.permission.CAMERA), 1)
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, res: IntArray) {
        if (res.firstOrNull() == PackageManager.PERMISSION_GRANTED) start()
        else hud.text = "SmartCam needs camera permission."
    }

    private fun start() {
        if (tv.isAvailable) { cam.open(); return }
        tv.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(s: SurfaceTexture, w: Int, h: Int) { cam.open() }
            override fun onSurfaceTextureSizeChanged(s: SurfaceTexture, w: Int, h: Int) {}
            override fun onSurfaceTextureDestroyed(s: SurfaceTexture) = true
            override fun onSurfaceTextureUpdated(s: SurfaceTexture) {}
        }
    }

    override fun onPause() {
        cam.close()
        super.onPause()
    }
}

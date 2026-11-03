package com.arenascanner.app

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import java.util.concurrent.Executors

/**
 * Home screen: get the card list, start screen scanning, see and adjust the scanned collection,
 * and export it to the Arena Collection web page.
 */
class MainActivity : Activity() {
    private val io = Executors.newSingleThreadExecutor()
    private lateinit var status: TextView
    private lateinit var dbButton: Button
    private lateinit var scanButton: Button
    private lateinit var summary: TextView
    private val adapter = CardAdapter()
    private val dp get() = resources.displayMetrics.density

    companion object {
        private const val REQ_CAPTURE = 1
        private const val REQ_NOTIFY = 2
        private const val ARENA_PACKAGE = "com.wizards.mtga"
        private val RARITY_COLORS = mapOf("C" to "#3B3F46", "U" to "#6F8296", "R" to "#A8811C", "M" to "#C4471D", "?" to "#8A93A3")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = (16 * dp).toInt()
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(pad, pad, pad, 0) }
        // Android 15 draws apps edge to edge, so pad the content clear of the status and navigation bars.
        root.setOnApplyWindowInsetsListener { v, insets ->
            val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            v.setPadding(pad + bars.left, pad + bars.top, pad + bars.right, bars.bottom)
            insets
        }
        val heading = TextView(this).apply {
            text = "Arena Scanner"; textSize = 24f; setTypeface(typeface, Typeface.BOLD); setPadding(0, 0, 0, pad / 2)
        }

        status = TextView(this).apply { textSize = 14f }
        dbButton = Button(this).apply { setOnClickListener { downloadCards() } }
        scanButton = Button(this).apply { setOnClickListener { toggleScan() } }
        val exportButton = Button(this).apply { text = "Export to collection page"; setOnClickListener { export() } }
        val clearButton = Button(this).apply { text = "Clear collection"; setOnClickListener { confirmClear() } }
        val debugButton = Button(this).apply { text = "Save last scan image"; setOnClickListener { saveDebugImage() } }
        val help = TextView(this).apply {
            textSize = 13f
            setTextColor(Color.DKGRAY)
            text = "How to scan: tap Start scanning and allow screen capture. In Arena, open Collection and scroll at a steady, moderate pace. " +
                "It scans continuously as you scroll, reading each card's name and the 4 diamonds above it (filled = copies you own).\n\n" +
                "Floating button: tap to pause or resume, long-press to stop, drag it to an empty spot so it doesn't cover cards. " +
                "It shows +N when it finds new cards.\n\n" +
                "If a count is wrong, fix it with − and +. Scans never change a count you set yourself."
        }
        summary = TextView(this).apply { textSize = 15f; setTypeface(typeface, Typeface.BOLD); setPadding(0, pad, 0, pad / 2) }
        val list = ListView(this).apply { adapter = this@MainActivity.adapter }

        for (v in listOf(heading, status, dbButton, scanButton, exportButton, clearButton, debugButton, help, summary)) root.addView(v)
        root.addView(list, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)
        title = "Arena Scanner"
    }

    override fun onResume() {
        super.onResume()
        refresh()
        // First launch: fetch the card list right away, since scanning needs it.
        if (!CardDb.isReady(this)) downloadCards()
    }

    private fun refresh() {
        val ready = CardDb.isReady(this)
        if (ready && status.text.isNullOrEmpty()) status.text = "Card list ready."
        dbButton.text = if (ready) "Update card list (after a new set)" else "Download card list"
        scanButton.text = if (ScanService.running) "Stop scanning" else "Start scanning"
        adapter.items = CollectionStore.all(this)
        adapter.notifyDataSetChanged()
        val copies = adapter.items.sumOf { it.copies }
        summary.text = "Your collection: ${adapter.items.size} cards, $copies copies"
    }

    private var downloading = false

    private fun downloadCards() {
        if (downloading) return // one download at a time, or Scryfall rate-limits us
        downloading = true
        dbButton.isEnabled = false
        status.text = "Downloading Standard card list…"
        io.execute {
            try {
                val cards = CardDb.download(this) { n -> runOnUiThread { status.text = "Downloading Standard card list… $n cards (takes about 30 seconds)" } }
                runOnUiThread { downloading = false; status.text = "Card list ready: ${cards.size} Standard cards."; dbButton.isEnabled = true; refresh() }
            } catch (e: Exception) {
                runOnUiThread {
                    downloading = false
                    status.text = "Couldn't download the card list: ${e.message}"
                    dbButton.isEnabled = true
                }
            }
        }
    }

    private fun toggleScan() {
        if (ScanService.running) {
            startService(Intent(this, ScanService::class.java).setAction(ScanService.ACTION_STOP))
            scanButton.postDelayed({ refresh() }, 300)
            return
        }
        if (!CardDb.isReady(this)) {
            toast("Getting the card list first. Tap Start scanning again when it says ready.")
            downloadCards()
            return
        }
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_NOTIFY)
            return
        }
        if (!Settings.canDrawOverlays(this)) {
            toast("Turn on \"Appear on top\" for Arena Scanner, then come back and tap Start scanning again.")
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            return
        }
        val mpm = getSystemService(MediaProjectionManager::class.java)
        @Suppress("DEPRECATION")
        startActivityForResult(mpm.createScreenCaptureIntent(), REQ_CAPTURE)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        // The notification shows while scanning; Android needs it for screen capture. Continue either way.
        if (requestCode == REQ_NOTIFY) {
            if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) toggleScan()
            else toast("Notifications are off, so you won't see the Stop button in your notifications. Long-press Scan to stop.")
        }
    }

    @Deprecated("Uses the platform activity result API to avoid extra libraries.")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION") super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_CAPTURE) return
        if (resultCode != RESULT_OK || data == null) { toast("Screen capture wasn't allowed, so scanning didn't start."); return }
        startForegroundService(Intent(this, ScanService::class.java)
            .putExtra(ScanService.EXTRA_CODE, resultCode)
            .putExtra(ScanService.EXTRA_DATA, data))
        val arena = packageManager.getLaunchIntentForPackage(ARENA_PACKAGE)
        if (arena != null) startActivity(arena) else toast("Scanning is on. Open MTG Arena and go to Collection.")
    }

    private fun export() {
        val text = CollectionStore.exportText(this)
        if (text.isEmpty()) { toast("Nothing to export yet. Scan some cards first."); return }
        getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Arena collection", text))
        toast("Copied. Paste it into \"Paste a list of cards\" on your Arena Collection page.")
        startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text), "Share collection"))
    }

    /**
     * Copies the last scanned screen (with red name boxes, cyan pip areas and the counts read) to
     * Pictures/ArenaScanner, so it can be shared when counts come out wrong.
     */
    private fun saveDebugImage() {
        val f = ScanService.debugFile(this)
        if (!f.exists()) { toast("No scan yet. Scan a page of your collection first."); return }
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "arena_scan_${System.currentTimeMillis()}.png")
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/ArenaScanner")
        }
        val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
        if (uri == null) { toast("Couldn't save the image."); return }
        contentResolver.openOutputStream(uri)?.use { out -> f.inputStream().use { it.copyTo(out) } }
        toast("Saved to Pictures/ArenaScanner. Send it to Claude if counts look wrong.")
    }

    private fun confirmClear() {
        AlertDialog.Builder(this)
            .setMessage("Remove every card from the scanned collection? This can't be undone.")
            .setPositiveButton("Clear") { _, _ -> CollectionStore.clear(this); refresh() }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()

    private inner class CardAdapter : BaseAdapter() {
        var items: List<Owned> = emptyList()
        override fun getCount() = items.size
        override fun getItem(position: Int) = items[position]
        override fun getItemId(position: Int) = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val row = (convertView as? LinearLayout) ?: makeRow()
            val o = items[position]
            val badge = row.getChildAt(0) as TextView
            val name = row.getChildAt(1) as TextView
            val minus = row.getChildAt(2) as Button
            val count = row.getChildAt(3) as TextView
            val plus = row.getChildAt(4) as Button
            badge.text = o.rarity
            badge.setTextColor(Color.parseColor(RARITY_COLORS[o.rarity] ?: "#8A93A3"))
            name.text = o.name
            count.text = o.copies.toString()
            minus.setOnClickListener { CollectionStore.setCopies(this@MainActivity, o.name, o.copies - 1); refresh() }
            plus.setOnClickListener { CollectionStore.setCopies(this@MainActivity, o.name, o.copies + 1); refresh() }
            plus.isEnabled = o.copies < 4
            return row
        }

        private fun makeRow(): LinearLayout {
            val p = (8 * dp).toInt()
            return LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, p / 2, 0, p / 2)
                addView(TextView(context).apply { textSize = 14f; setTypeface(Typeface.MONOSPACE, Typeface.BOLD); minWidth = (28 * dp).toInt() })
                addView(TextView(context).apply { textSize = 15f }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                addView(Button(context).apply { text = "−"; minWidth = 0; minimumWidth = (44 * dp).toInt() })
                addView(TextView(context).apply { textSize = 15f; gravity = Gravity.CENTER; minWidth = (28 * dp).toInt(); typeface = Typeface.MONOSPACE })
                addView(Button(context).apply { text = "+"; minWidth = 0; minimumWidth = (44 * dp).toInt() })
            }
        }
    }
}

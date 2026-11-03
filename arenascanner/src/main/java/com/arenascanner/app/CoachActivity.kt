package com.arenascanner.app

import android.Manifest
import android.app.Activity
import android.app.DownloadManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.WindowInsets
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.util.concurrent.Executors

/**
 * Sets up and starts the move coach: gets the Gemma 4 E2B model onto the phone (download or pick a
 * file you already have), makes sure the card list includes rules text, then starts the overlay.
 */
class CoachActivity : Activity() {
    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private lateinit var modelStatus: TextView
    private lateinit var rulesStatus: TextView
    private lateinit var downloadBtn: Button
    private lateinit var pickBtn: Button
    private lateinit var rulesBtn: Button
    private lateinit var startBtn: Button
    private lateinit var step1: TextView
    private lateinit var lessonsStatus: TextView
    private lateinit var logStatus: TextView
    private lateinit var step2: TextView
    private lateinit var step3: TextView
    private var copying = false
    private var updatingRules = false
    private val dp get() = resources.displayMetrics.density

    companion object {
        private const val REQ_CAPTURE = 11
        private const val REQ_NOTIFY = 12
        private const val REQ_PICK = 13
        private const val ARENA_PACKAGE = "com.wizards.mtga"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = (16 * dp).toInt()
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(pad, pad, pad, pad) }
        val scroll = ScrollView(this).apply { addView(col) }
        scroll.setOnApplyWindowInsetsListener { v, insets ->
            val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        fun label(t: String, size: Float = 14f, bold: Boolean = false) = TextView(this).apply {
            text = t; textSize = size; if (bold) setTypeface(typeface, Typeface.BOLD); setPadding(0, pad / 2, 0, pad / 4)
        }
        col.addView(label("Move coach", 24f, true))
        col.addView(label("On your turn, tap the floating Coach button. It reads the board and asks Gemma 4 E2B, " +
            "running on your phone, for the best play. Nothing is sent online.").apply { setTextColor(context.secondaryText()) })

        step1 = label("", 16f, true); col.addView(step1)
        modelStatus = label("")
        downloadBtn = Button(this).apply { setOnClickListener { downloadModel() } }
        pickBtn = Button(this).apply { text = "Use a .litertlm file I already have"; setOnClickListener { pickModel() } }
        for (v in listOf(modelStatus, downloadBtn, pickBtn)) col.addView(v)

        step2 = label("", 16f, true); col.addView(step2)
        rulesStatus = label("")
        rulesBtn = Button(this).apply { text = "Update card list with rules text"; setOnClickListener { updateRules() } }
        col.addView(rulesStatus); col.addView(rulesBtn)

        step3 = label("", 16f, true); col.addView(step3)
        col.addView(android.widget.RadioGroup(this).apply {
            orientation = android.widget.RadioGroup.HORIZONTAL
            addView(TextView(context).apply { text = "Advice text: " })
            val cur = Gemma.prefs(context).getFloat("panel_text", 15f)
            for ((label, size) in listOf("Small" to 13f, "Medium" to 15f, "Large" to 18f)) addView(android.widget.RadioButton(context).apply {
                text = label; id = size.toInt() + 5000; isChecked = cur == size
                setOnClickListener { Gemma.prefs(context).edit().putFloat("panel_text", size).apply() }
            })
        })
        col.addView(android.widget.CheckBox(this).apply {
            text = "Auto advice: ask by itself at my main phase, attacks, blocks and mulligan"
            isChecked = Gemma.prefs(context).getBoolean("auto_advice", true)
            setOnCheckedChangeListener { _, on -> Gemma.prefs(context).edit().putBoolean("auto_advice", on).apply() }
        })
        startBtn = Button(this).apply {
            textSize = 18f; setTextColor(Color.WHITE); setBackgroundColor(Color.parseColor("#6A3FB5"))
            minHeight = (56 * dp).toInt(); setOnClickListener { toggleCoach() }
        }
        col.addView(startBtn)
        col.addView(label("Match log", 16f, true))
        logStatus = label("")
        col.addView(logStatus)
        col.addView(LinearLayout(this).apply {
            addView(Button(context).apply { text = "Save log as zip"; setOnClickListener { exportLog() } },
                LinearLayout.LayoutParams(0, -2, 1f))
            addView(Button(context).apply { text = "Clear log"; setOnClickListener {
                android.app.AlertDialog.Builder(this@CoachActivity).setMessage("Delete the saved screenshots and answers?")
                    .setPositiveButton("Delete") { _, _ -> CoachLog.clear(this@CoachActivity); refresh() }
                    .setNegativeButton("Cancel", null).show()
            } }, LinearLayout.LayoutParams(0, -2, 1f))
        })
        col.addView(label("Lessons", 16f, true))
        lessonsStatus = label("")
        col.addView(lessonsStatus)
        col.addView(LinearLayout(this).apply {
            addView(Button(context).apply { text = "Export training data"; setOnClickListener { exportLessons() } },
                LinearLayout.LayoutParams(0, -2, 1f))
            addView(Button(context).apply { text = "Clear lessons"; setOnClickListener { confirmClearLessons() } },
                LinearLayout.LayoutParams(0, -2, 1f))
        })
        col.addView(label(
            "Tips:\n" +
            "• After each answer tap 👍 Good play or ✏️ Fix it. The coach reuses your 3 most similar lessons next time.\n" +
            "• Tap Coach when it's your main phase, with your hand visible.\n" +
            "• Gemma takes a few seconds to answer; it's a small model, so treat its advice as a second opinion, " +
            "not the final word. It can misread the board.\n" +
            "• Tap the advice panel to hide it. Long-press the Coach button to turn the coach off.\n" +
            "• The model uses about 3 GB of memory alongside Arena. If Arena or the coach closes unexpectedly, " +
            "close other apps first.\n" +
            "• Wizards' rules on live play assistance in ranked games are your call to check."
        ).apply { setTextColor(context.secondaryText()); textSize = 13f })
        setContentView(scroll)
        title = "Move coach"
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    override fun onPause() {
        super.onPause()
        main.removeCallbacksAndMessages(null)
    }

    private fun refresh() {
        val ready = Gemma.isModelReady(this)
        val dlId = Gemma.prefs(this).getLong(Gemma.KEY_DOWNLOAD_ID, -1L)
        when {
            copying -> {}
            dlId != -1L -> pollDownload(dlId)
            ready -> modelStatus.text = "Gemma 4 E2B is installed (${Gemma.modelFile(this).length() / 1_000_000} MB)."
            else -> modelStatus.text = "Not installed yet. Download it (about 2.6 GB, use Wi-Fi), or pick the file if you already have it."
        }
        downloadBtn.text = if (ready) "Download the model again" else "Download Gemma 4 E2B (2.6 GB)"
        downloadBtn.isEnabled = dlId == -1L && !copying
        pickBtn.isEnabled = dlId == -1L && !copying

        if (!updatingRules) rulesStatus.text = when {
            !CardDb.isReady(this) -> "No card list yet. Tap below to download it (about 30 seconds)."
            CardDb.hasRules(this) -> "Card list includes rules text."
            else -> "Your card list is from an older version without rules text. Update it so the coach knows what cards do."
        }
        rulesBtn.isEnabled = !updatingRules

        startBtn.text = if (ScanService.running && ScanService.coaching) "Stop coach" else "Start coach"
        val hasRules = CardDb.hasRules(this)
        step1.text = (if (ready) "✓ " else "1. ") + "Model"
        step2.text = (if (hasRules) "✓ " else "2. ") + "Card rules text"
        step3.text = (if (ready && hasRules) "3. Ready — start the coach" else "3. Start")
        // Hide setup buttons once done (they come back if something is missing).
        downloadBtn.visibility = if (ready && !copying) android.view.View.GONE else android.view.View.VISIBLE
        pickBtn.visibility = downloadBtn.visibility
        rulesBtn.text = if (hasRules) "Refresh rules text (after a new set)" else "Get card rules text"
        startBtn.isEnabled = ready && CardDb.isReady(this)
        startBtn.alpha = if (startBtn.isEnabled) 1f else 0.5f
        val n = CoachLog.count(this)
        logStatus.text = if (n == 0) "Every coach answer is saved here with a screenshot of the board."
            else "$n answers saved with screenshots. After the match, save the zip and send it to Claude."
        val ls = Lessons.all(this)
        lessonsStatus.text = if (ls.isEmpty()) "No lessons yet. Rate or fix the coach's answers while you play."
            else "${ls.size} lessons saved (${ls.count { !it.good }} fixes, ${ls.count { it.good }} confirmed)."
    }

    // ------------------------------------------------------------ model download

    private fun downloadModel() {
        val f = Gemma.modelFile(this)
        f.parentFile?.mkdirs()
        f.delete() // DownloadManager would otherwise save to a new name next to it
        val req = DownloadManager.Request(Uri.parse(Gemma.DOWNLOAD_URL))
            .setTitle("Gemma 4 E2B")
            .setDescription("Model for Arena Scanner's move coach")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalFilesDir(this, null, "models/${Gemma.FILE_NAME}")
        val id = getSystemService(DownloadManager::class.java).enqueue(req)
        Gemma.prefs(this).edit().putLong(Gemma.KEY_DOWNLOAD_ID, id).apply()
        toast("Downloading in the background. You can leave this screen.")
        refresh()
    }

    /** Shows download progress once a second while this screen is open. */
    private fun pollDownload(id: Long) {
        val dm = getSystemService(DownloadManager::class.java)
        dm.query(DownloadManager.Query().setFilterById(id)).use { c ->
            if (!c.moveToFirst()) { finishDownload(null); return }
            val status = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
            val done = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
            val total = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES))
            when (status) {
                DownloadManager.STATUS_SUCCESSFUL -> { finishDownload(null); return }
                DownloadManager.STATUS_FAILED -> {
                    val reason = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON))
                    finishDownload("The download failed (code $reason). Check your connection and try again.")
                    return
                }
                else -> {
                    val pct = if (total > 0) " ${done * 100 / total}%" else ""
                    modelStatus.text = "Downloading Gemma 4 E2B…$pct (${done / 1_000_000} MB)"
                }
            }
        }
        main.postDelayed({ refresh() }, 1000)
    }

    private fun finishDownload(error: String?) {
        Gemma.prefs(this).edit().remove(Gemma.KEY_DOWNLOAD_ID).apply()
        if (error != null) modelStatus.text = error
        main.post { refresh() }
    }

    // ------------------------------------------------------------ pick an existing file

    private fun pickModel() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*")
        @Suppress("DEPRECATION") startActivityForResult(i, REQ_PICK)
    }

    /** Copies the picked file into the app's model folder (the model needs a real file path). */
    private fun copyModel(uri: Uri) {
        copying = true
        modelStatus.text = "Copying the model…"
        refresh()
        val dest = Gemma.modelFile(this)
        io.execute {
            try {
                dest.parentFile?.mkdirs()
                val tmp = java.io.File(dest.path + ".part")
                val size = contentResolver.openFileDescriptor(uri, "r")?.use { it.statSize } ?: -1L
                contentResolver.openInputStream(uri)!!.use { input ->
                    tmp.outputStream().use { out ->
                        val buf = ByteArray(1 shl 20)
                        var total = 0L
                        var lastShown = 0L
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            total += n
                            if (total - lastShown > 50_000_000) {
                                lastShown = total
                                val pct = if (size > 0) " ${total * 100 / size}%" else ""
                                runOnUiThread { modelStatus.text = "Copying the model…$pct" }
                            }
                        }
                    }
                }
                if (tmp.length() < 1_000_000_000L) {
                    tmp.delete()
                    throw IllegalStateException("That file is too small to be Gemma 4 E2B. Pick the .litertlm file (about 2.6 GB).")
                }
                dest.delete()
                tmp.renameTo(dest)
                runOnUiThread { copying = false; toast("Model ready."); refresh() }
            } catch (e: Exception) {
                runOnUiThread { copying = false; refresh(); modelStatus.text = "Couldn't copy the model: ${e.message}" }
            }
        }
    }

    // ------------------------------------------------------------ rules text

    private fun updateRules() {
        updatingRules = true
        rulesBtn.isEnabled = false
        rulesStatus.text = "Downloading the card list with rules text…"
        io.execute {
            try {
                val cards = CardDb.download(this) { n -> runOnUiThread { rulesStatus.text = "Downloading the card list… $n cards" } }
                runOnUiThread { updatingRules = false; refresh(); rulesStatus.text = "Card list updated: ${cards.size} cards with rules text." }
            } catch (e: Exception) {
                runOnUiThread { updatingRules = false; refresh(); rulesStatus.text = "Couldn't download the card list: ${e.message}" }
            }
        }
    }

    // ------------------------------------------------------------ start / stop

    private fun toggleCoach() {
        if (ScanService.running) {
            startService(Intent(this, ScanService::class.java).setAction(ScanService.ACTION_STOP))
            startBtn.postDelayed({ refresh() }, 300)
            return
        }
        if (!Gemma.isModelReady(this)) { toast("Get the model first (step 1)."); return }
        if (!CardDb.isReady(this)) { toast("Download the card list first (step 2)."); return }
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_NOTIFY)
            return
        }
        if (!Settings.canDrawOverlays(this)) {
            toast("Turn on \"Appear on top\" for Arena Scanner, then come back and tap Start coach again.")
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            return
        }
        @Suppress("DEPRECATION")
        startActivityForResult(getSystemService(MediaProjectionManager::class.java).createScreenCaptureIntent(), REQ_CAPTURE)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_NOTIFY) {
            if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) toggleCoach()
            else toast("Notifications are off. Long-press the Coach button to turn the coach off.")
        }
    }

    @Deprecated("Uses the platform activity result API to avoid extra libraries.")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION") super.onActivityResult(requestCode, resultCode, data)
        when (requestCode) {
            REQ_PICK -> data?.data?.let { copyModel(it) }
            REQ_CAPTURE -> {
                if (resultCode != RESULT_OK || data == null) { toast("Screen capture wasn't allowed, so the coach didn't start."); return }
                startForegroundService(Intent(this, ScanService::class.java)
                    .putExtra(ScanService.EXTRA_CODE, resultCode)
                    .putExtra(ScanService.EXTRA_DATA, data)
                    .putExtra(ScanService.EXTRA_MODE, ScanService.MODE_COACH))
                val arena = packageManager.getLaunchIntentForPackage(ARENA_PACKAGE)
                if (arena != null) startActivity(arena) else toast("Coach is on. Open MTG Arena and start a game.")
            }
        }
    }

    /** Zip of lessons.jsonl + screenshots in Downloads/ArenaScanner, for fine-tuning in Colab later. */
    private fun exportLessons() {
        if (Lessons.all(this).isEmpty()) { toast("No lessons yet."); return }
        val name = "coach_lessons_${System.currentTimeMillis() / 1000}.zip"
        val values = android.content.ContentValues().apply {
            put(android.provider.MediaStore.Downloads.DISPLAY_NAME, name)
            put(android.provider.MediaStore.Downloads.MIME_TYPE, "application/zip")
            put(android.provider.MediaStore.Downloads.RELATIVE_PATH, android.os.Environment.DIRECTORY_DOWNLOADS + "/ArenaScanner")
        }
        val uri = contentResolver.insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: run { toast("Couldn't create the file."); return }
        contentResolver.openOutputStream(uri)?.use { Lessons.exportZip(this, it) }
        toast("Saved Downloads/ArenaScanner/$name")
    }

    /** Zip of every answer + screenshot (+ your 👍/✏️) in Downloads/ArenaScanner, then the share sheet. */
    private fun exportLog() {
        if (CoachLog.count(this) == 0) { toast("Nothing saved yet. Use the coach during a match first."); return }
        val name = "coach_match_${java.text.SimpleDateFormat("yyyy-MM-dd_HHmm", java.util.Locale.US).format(java.util.Date())}.zip"
        val values = android.content.ContentValues().apply {
            put(android.provider.MediaStore.Downloads.DISPLAY_NAME, name)
            put(android.provider.MediaStore.Downloads.MIME_TYPE, "application/zip")
            put(android.provider.MediaStore.Downloads.RELATIVE_PATH, android.os.Environment.DIRECTORY_DOWNLOADS + "/ArenaScanner")
        }
        val uri = contentResolver.insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: run { toast("Couldn't create the file."); return }
        contentResolver.openOutputStream(uri)?.use { CoachLog.exportZip(this, it) }
        toast("Saved Downloads/ArenaScanner/$name")
        startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("application/zip")
            .putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), "Send match log"))
    }

    private fun confirmClearLessons() {
        android.app.AlertDialog.Builder(this).setMessage("Delete all saved lessons? The coach will forget your corrections.")
            .setPositiveButton("Delete") { _, _ -> Lessons.clear(this); refresh() }
            .setNegativeButton("Cancel", null).show()
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()
}

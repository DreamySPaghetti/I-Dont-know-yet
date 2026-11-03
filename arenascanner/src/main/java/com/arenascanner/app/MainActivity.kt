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
    private lateinit var updateButton: Button
    private var query = ""
    private var rarityFilter: String? = null
    private lateinit var autoScrollBox: android.widget.CompoundButton
    private lateinit var listBox: LinearLayout
    private val density get() = resources.displayMetrics.density

    companion object {
        private const val REQ_CAPTURE = 1
        private const val REQ_NOTIFY = 2
        private const val REQ_IMPORT = 3
        private const val MAX_ROWS = 200
        private const val ARENA_PACKAGE = "com.wizards.mtga"
        private val RARITY_COLORS = mapOf("C" to "#3B3F46", "U" to "#6F8296", "R" to "#A8811C", "M" to "#C4471D", "?" to "#8A93A3")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        // Header
        col.addView(titleText("Arena Scanner", 28f))
        status = mutedText("").apply { setPadding(0, dp(2), 0, dp(12)) }
        col.addView(status)
        updateButton = primaryButton("Update available", GOOD) { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(Updates.APK_URL))) }
            .apply { visibility = View.GONE; layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(12) } }
        col.addView(updateButton)

        // Play
        col.addView(card(
            titleText("Play"),
            mutedText("Get move suggestions during a game, or check what a deck is missing."),
            primaryButton("Move coach", COACH) { startActivity(Intent(this, CoachActivity::class.java)) },
            tonalButton("Deck check: what am I missing?") { startActivity(Intent(this, DeckCheckActivity::class.java)) },
        ))

        // Collection
        scanButton = primaryButton("Start scanning") { toggleScan() }
        autoScrollBox = switchRow("Auto-scroll while scanning", Gemma.prefs(this).getBoolean("auto_scroll", false)) { on ->
            Gemma.prefs(this).edit().putBoolean("auto_scroll", on).apply()
            if (on && AutoScroll.instance == null) explainAccessibility()
        }
        val help = mutedText(
            "Open Collection in Arena after tapping Start scanning. Each card's name and the 4 diamonds above it " +
            "(filled = copies you own) are read as you scroll.\n\n" +
            "Floating button: tap to pause or resume, long-press to stop, drag to move it off the cards. " +
            "Counts you set yourself are never changed by a scan."
        ).apply { visibility = View.GONE }
        val helpLink = mutedText("How scanning works ▸").apply {
            setTextColor(Color.parseColor(PRIMARY))
            setOnClickListener {
                help.visibility = if (help.visibility == View.GONE) View.VISIBLE else View.GONE
                text = if (help.visibility == View.GONE) "How scanning works ▸" else "How scanning works ▾"
            }
        }
        col.addView(card(
            titleText("Collection"),
            scanButton,
            autoScrollBox,
            rowOf(tonalButton("Save CSV") { saveCsv() }, tonalButton("Import CSV") {
                @Suppress("DEPRECATION") startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*"), REQ_IMPORT)
            }),
            rowOf(tonalButton("Copy for web page") { export() }, tonalButton("Save scan image") { saveDebugImage() }),
            helpLink, help,
        ))

        // Settings
        dbButton = tonalButton("Update card list") { downloadCards() }
        col.addView(card(
            titleText("Settings"),
            dbButton,
            switchRow("Track all Arena cards (Historic, Alchemy, Brawl)", CardDb.pool(this) == "all") { on ->
                Gemma.prefs(this).edit().putString("card_pool", if (on) "all" else "standard").apply()
                toast(if (on) "Downloading every Arena card (about a minute)…" else "Switching back to Standard cards…")
                downloadCards()
            },
            rowOf(tonalButton("About & privacy") { startActivity(Intent(this, AboutActivity::class.java)) },
                pill("Clear all", surface2(), Color.parseColor("#C0392B")) { confirmClear() }),
        ))

        // Your cards
        summary = mutedText("")
        val search = field("Search your cards").apply {
            id = 4243; isSingleLine = true
            addTextChangedListener(object : android.text.TextWatcher {
                override fun afterTextChanged(s: android.text.Editable?) { query = s.toString().trim().lowercase(); refresh() }
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            })
        }
        val rarityChips = chips(listOf("All", "Mythic", "Rare", "Uncommon", "Common"), 0) { i -> rarityFilter = listOf(null, "M", "R", "U", "C")[i]; refresh() }
        listBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(card(titleText("Your cards"), summary, search, rarityChips, listBox))

        setContentView(page(col))
    }

    /** One card row: rarity pill, name (tap for details), and a − count + stepper. */
    private fun cardRow(o: Owned): View = LinearLayout(this).apply {
        gravity = Gravity.CENTER_VERTICAL
        setPadding(0, dp(6), 0, dp(6))
        val rc = rarityColor(o.rarity)
        addView(TextView(context).apply {
            text = o.rarity; textSize = 12f; gravity = Gravity.CENTER; setTextColor(rc)
            setTypeface(Typeface.MONOSPACE, Typeface.BOLD)
            background = rounded(Color.argb(40, Color.red(rc), Color.green(rc), Color.blue(rc)), 8)
        }, LinearLayout.LayoutParams(dp(28), dp(24)))
        addView(bodyText(o.name, 15f).apply {
            setPadding(dp(10), 0, dp(8), 0)
            setOnClickListener { showCard(o) }
        }, LinearLayout.LayoutParams(0, -2, 1f))
        fun step(label: String, enabled: Boolean, onClick: () -> Unit) = TextView(context).apply {
            text = label; textSize = 18f; gravity = Gravity.CENTER; setTextColor(if (enabled) onSurface() else muted())
            background = rounded(surface2(), 18); isEnabled = enabled; setOnClickListener { onClick() }
        }
        addView(step("−", true) { CollectionStore.setCopies(this@MainActivity, o.name, o.copies - 1); refresh() }, LinearLayout.LayoutParams(dp(36), dp(36)))
        addView(TextView(context).apply {
            text = o.copies.toString(); textSize = 15f; gravity = Gravity.CENTER; setTextColor(onSurface()); typeface = Typeface.MONOSPACE
        }, LinearLayout.LayoutParams(dp(32), -2))
        addView(step("+", o.copies < 4) { CollectionStore.setCopies(this@MainActivity, o.name, o.copies + 1); refresh() }, LinearLayout.LayoutParams(dp(36), dp(36)))
    }

    override fun onResume() {
        super.onResume()
        refresh()
        offerCrashReport()
        firstRunIntro()
        io.execute {
            val newer = runCatching { Updates.check(this) }.getOrNull()
            if (newer != null) runOnUiThread {
                updateButton.text = "Update available (build $newer) · tap to download"
                updateButton.visibility = View.VISIBLE
            }
        }
        // First launch: fetch the card list right away, since scanning needs it.
        if (!CardDb.isReady(this)) downloadCards()
    }

    private fun refresh() {
        val ready = CardDb.isReady(this)
        if (ready && status.text.isNullOrEmpty()) {
            val days = CardDb.ageDays(this)
            status.text = if (days >= 30) "Card list is $days days old. If a new set is out, tap Update card list." else "Card list ready."
        }
        dbButton.text = if (ready) "Update card list" else "Download card list"
        scanButton.text = if (ScanService.running && !ScanService.coaching) "Stop scanning" else "Start scanning"
        autoScrollBox.text = if (autoScrollBox.isChecked && AutoScroll.instance == null) "Auto-scroll (needs Accessibility setup)" else "Auto-scroll while scanning"
        val all = CollectionStore.all(this)
        val shown = all.filter { (query.isEmpty() || query in it.name.lowercase()) && (rarityFilter == null || it.rarity == rarityFilter) }
        // Rows are plain views; cap them so a big collection stays quick (search narrows the rest).
        listBox.removeAllViews()
        for (o in shown.take(MAX_ROWS)) listBox.addView(cardRow(o))
        if (shown.size > MAX_ROWS) listBox.addView(mutedText("Showing $MAX_ROWS of ${shown.size}. Search to find the rest.").apply { setPadding(0, dp(8), 0, 0) })
        val by = all.groupingBy { it.rarity }.eachCount()
        summary.text = if (all.isEmpty()) "No cards yet. Tap Start scanning, then open Collection in MTG Arena." else
            "${all.size} cards · ${all.sumOf { it.copies }} copies\n" +
            "Mythic ${by["M"] ?: 0} · Rare ${by["R"] ?: 0} · Uncommon ${by["U"] ?: 0} · Common ${by["C"] ?: 0}"
    }

    private var downloading = false

    private fun downloadCards() {
        if (downloading) return // one download at a time, or Scryfall rate-limits us
        downloading = true
        dbButton.isEnabled = false
        status.text = "Downloading Standard card list…"
        io.execute {
            try {
                val what = if (CardDb.pool(this) == "all") "Arena" else "Standard"
                val cards = CardDb.download(this) { n -> runOnUiThread { status.text = "Downloading $what card list… $n cards" } }
                runOnUiThread { downloading = false; status.text = "Card list ready: ${cards.size} $what cards."; dbButton.isEnabled = true; refresh() }
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
        if (ScanService.running && ScanService.coaching) { toast("The move coach is on. Turn it off first (long-press the Coach button)."); return }
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
        if (requestCode == REQ_IMPORT) { data?.data?.let { importCsv(it) }; return }
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
     * Saves the collection as a CSV file in Downloads/ArenaScanner (opens in Sheets, Excel, Moxfield
     * import, etc.), then offers to share it.
     */
    private fun saveCsv() {
        if (CollectionStore.all(this).isEmpty()) { toast("Nothing to save yet. Scan some cards first."); return }
        val name = "arena_collection_${java.text.SimpleDateFormat("yyyy-MM-dd_HHmm", java.util.Locale.US).format(java.util.Date())}.csv"
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, name)
            put(MediaStore.Downloads.MIME_TYPE, "text/csv")
            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/ArenaScanner")
        }
        val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
        if (uri == null) { toast("Couldn't create the file in Downloads."); return }
        contentResolver.openOutputStream(uri)?.use { it.write(CollectionStore.exportCsv(this).toByteArray()) }
        toast("Saved Downloads/ArenaScanner/$name")
        val share = Intent(Intent.ACTION_SEND).setType("text/csv").putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        startActivity(Intent.createChooser(share, "Share CSV"))
    }

    /**
     * Copies the last scanned screen (with red name boxes, cyan pip areas and the counts read) to
     * Pictures/ArenaScanner, so it can be shared when counts come out wrong.
     */
    private fun saveDebugImage() {
        val f = ScanService.debugFile(this)
        if (!f.exists()) { toast("No scan yet. Scan a page of your collection first."); return }
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "arena_scan_${System.currentTimeMillis()}.jpg")
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/ArenaScanner")
        }
        val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
        if (uri == null) { toast("Couldn't save the image."); return }
        contentResolver.openOutputStream(uri)?.use { out -> f.inputStream().use { it.copyTo(out) } }
        toast("Saved to Pictures/ArenaScanner. Send it to Claude if counts look wrong.")
    }

    /** After a crash: offer to share the report (plain text in the share sheet), then forget it. */
    private fun offerCrashReport() {
        val f = App.crashFile(this)
        if (!f.exists() || f.length() == 0L) return
        val report = f.readText()
        f.delete()
        AlertDialog.Builder(this)
            .setTitle("Arena Scanner stopped last time")
            .setMessage("Send the crash report so it can be fixed? It contains only the error and your phone model.")
            .setPositiveButton("Send") { _, _ ->
                startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain")
                    .putExtra(Intent.EXTRA_SUBJECT, "Arena Scanner crash report").putExtra(Intent.EXTRA_TEXT, report), "Send crash report"))
            }
            .setNegativeButton("Not now", null)
            .show()
    }

    /** One-time intro to the three things the app does. */
    private fun firstRunIntro() {
        val prefs = Gemma.prefs(this)
        if (prefs.getBoolean("intro_seen", false)) return
        prefs.edit().putBoolean("intro_seen", true).apply()
        AlertDialog.Builder(this)
            .setTitle("Welcome to Arena Scanner")
            .setMessage("1. Scan: open your Arena collection and let it read every card and how many copies you own.\n\n" +
                "2. Deck check: paste a deck from Arena to see what's missing and the wildcards it costs.\n\n" +
                "3. Move coach: an AI on your phone suggests plays during a game.\n\n" +
                "Everything stays on your phone. Start by tapping Start scanning.")
            .setPositiveButton("Got it", null)
            .show()
    }

    /** Auto-scroll needs Arena Scanner switched on under Accessibility (sideloaded apps: allow restricted settings first). */
    private fun explainAccessibility() {
        AlertDialog.Builder(this)
            .setTitle("Turn on auto-scroll")
            .setMessage("Android only lets apps swipe the screen through Accessibility.\n\n" +
                "1. Tap Open settings, find \"Arena Scanner auto-scroll\" (under Downloaded apps) and turn it on.\n" +
                "2. If it's greyed out: Settings → Apps → Arena Scanner → ⋮ menu → Allow restricted settings, then try again.\n\n" +
                "It only swipes while you're scanning with auto-scroll on.")
            .setPositiveButton("Open settings") { _, _ -> startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
            .setNegativeButton("Later", null)
            .show()
    }

    /** Reads a CSV saved by this app (or Count,Name[,Rarity] from elsewhere) and merges it in. */
    private fun importCsv(uri: Uri) {
        val text = runCatching { contentResolver.openInputStream(uri)!!.bufferedReader().readText() }.getOrNull()
        if (text == null) { toast("Couldn't read that file."); return }
        val rows = CollectionStore.parseCsv(text)
        if (rows.isEmpty()) { toast("No cards found. The file needs Count and Name columns."); return }
        val rarities = CardDb.load(this).associate { it.name.lowercase() to it.rarity }
        val n = CollectionStore.importRows(this, rows.map { (name, count, r) -> Triple(name, count, r ?: rarities[name.lowercase()] ?: "?") })
        refresh()
        toast("Imported $n cards.")
    }

    /** Card details: rules text from the card list, and a quick way to set how many you own. */
    private fun showCard(o: Owned) {
        val rules = CardDb.load(this).firstOrNull { it.name == o.name }?.rules?.replace(" — ", "\n")
        val pad = (20 * density).toInt()
        var dialog: AlertDialog? = null
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(pad, pad / 2, pad, 0)
            addView(TextView(context).apply { text = rules ?: "No rules text yet. Update the card list."; textSize = 14f })
            addView(TextView(context).apply { text = "You own ${o.copies}. Set copies:"; setPadding(0, pad / 2, 0, 0); setTypeface(typeface, Typeface.BOLD) })
            addView(LinearLayout(context).apply {
                for (n in 1..4) addView(Button(context).apply {
                    text = "$n"; isEnabled = n != o.copies
                    setOnClickListener { CollectionStore.setCopies(this@MainActivity, o.name, n); refresh(); dialog?.dismiss() }
                }, LinearLayout.LayoutParams(0, -2, 1f))
            })
        }
        dialog = AlertDialog.Builder(this)
            .setTitle(o.name)
            .setView(body)
            .setNeutralButton("Remove") { _, _ -> CollectionStore.setCopies(this, o.name, 0); refresh() }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun confirmClear() {
        AlertDialog.Builder(this)
            .setMessage("Remove every card from the scanned collection?")
            .setPositiveButton("Clear") { _, _ ->
                CollectionStore.clear(this); refresh()
                AlertDialog.Builder(this).setMessage("Collection cleared.")
                    .setPositiveButton("Undo") { _, _ -> CollectionStore.undoClear(this); refresh() }
                    .setNegativeButton("OK", null).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()
}

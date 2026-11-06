package com.arenascanner.app

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Intent
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import java.util.concurrent.Executors

/** Pick a format and export just the cards from your collection that are legal in it (Scryfall legality). */
class FormatExportActivity : Activity() {
    private val io = Executors.newSingleThreadExecutor()
    private lateinit var status: TextView
    private lateinit var preview: LinearLayout
    private lateinit var refreshBtn: android.widget.Button
    private var format = "standard"
    private var result: FormatExport.Result? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        format = Prefs.get(this).getString("export_format", "standard")!!
        val keys = FormatExport.FORMATS.keys.toList()
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(titleText("Export by format", 28f))
        col.addView(mutedText("Only the cards you own that are legal in the format you pick. Legality data from Scryfall.")
            .apply { setPadding(0, dp(2), 0, dp(12)) })
        status = bodyText()
        refreshBtn = primaryButton("Download legality data") { refreshCards() }.apply { visibility = View.GONE }
        col.addView(card(
            titleText("Format", 17f),
            chips(FormatExport.FORMATS.values.toList(), keys.indexOf(format).coerceAtLeast(0)) { i ->
                format = keys[i]; Prefs.get(this).edit().putString("export_format", format).apply(); update()
            },
            status, refreshBtn,
            primaryButton("Save CSV") { saveCsv() },
            rowOf(tonalButton("Copy list") { copy() }, tonalButton("Share") { share() }),
        ))
        preview = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(card(titleText("Preview", 17f), preview))
        setContentView(page(col))
        update()
    }

    private fun update() {
        if (!CardDb.hasLegality(this)) {
            status.text = "Your card list was downloaded before format legality was saved. Download it again to use this (about a minute)."
            refreshBtn.visibility = View.VISIBLE
            result = null; preview.removeAllViews(); return
        }
        refreshBtn.visibility = View.GONE
        val db = CardDb.load(this).associate { it.name.lowercase() to it.legal }
        val r = FormatExport.filter(CollectionStore.all(this), format) { db[it.lowercase()] }
        result = r
        val name = FormatExport.FORMATS[format]
        status.text = "${r.legal.size} of your cards are legal in $name (${r.copies} copies)." +
            if (r.unknown.isEmpty()) "" else "\n${r.unknown.size} cards aren't in your card list, so they're left out" +
                (if (CardDb.pool(this) != "all") ". Turn on \"Track all Arena cards\" in Settings to include them." else ".")
        preview.removeAllViews()
        for (o in r.legal.take(150)) preview.addView(LinearLayout(this).apply {
            setPadding(0, dp(3), 0, dp(3))
            addView(TextView(context).apply { text = o.rarity; setTextColor(rarityColor(o.rarity)); typeface = android.graphics.Typeface.MONOSPACE },
                LinearLayout.LayoutParams(dp(24), -2))
            addView(bodyText("${o.copies}  ${o.name}"), LinearLayout.LayoutParams(0, -2, 1f))
        })
        if (r.legal.size > 150) preview.addView(mutedText("…and ${r.legal.size - 150} more (all included in the export)."))
        if (r.legal.isEmpty()) preview.addView(mutedText("None of your cards are legal in $name."))
    }

    private fun refreshCards() {
        refreshBtn.isEnabled = false
        io.execute {
            try {
                CardDb.download(this) { n -> runOnUiThread { status.text = "Downloading card list… $n cards" } }
                runOnUiThread { refreshBtn.isEnabled = true; update() }
            } catch (e: Exception) {
                runOnUiThread { refreshBtn.isEnabled = true; status.text = "Couldn't download the card list: ${e.message}" }
            }
        }
    }

    private fun ready(): FormatExport.Result? = result?.takeIf { it.legal.isNotEmpty() }
        ?: run { toast("Nothing to export for ${FormatExport.FORMATS[format]}."); null }

    private fun saveCsv() {
        val r = ready() ?: return
        val name = "arena_${format}_${java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date())}.csv"
        val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "text/csv")
            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/ArenaScanner")
        }) ?: run { toast("Couldn't create the file."); return }
        contentResolver.openOutputStream(uri)?.use { it.write(CollectionStore.csvOf(r.legal).toByteArray()) }
        toast("Saved Downloads/ArenaScanner/$name")
    }

    private fun copy() {
        val r = ready() ?: return
        getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Cards", FormatExport.deckList(r.legal)))
        toast("Copied ${r.legal.size} cards")
    }

    private fun share() {
        val r = ready() ?: return
        startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain")
            .putExtra(Intent.EXTRA_SUBJECT, "My ${FormatExport.FORMATS[format]} cards")
            .putExtra(Intent.EXTRA_TEXT, FormatExport.deckList(r.legal)), "Share cards"))
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
}

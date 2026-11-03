package com.arenascanner.app

import android.app.Activity
import android.content.ClipboardManager
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.WindowInsets
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/** Paste a deck (Arena's Export button) and see what you own, what's missing, and the wildcards it costs. */
class DeckCheckActivity : Activity() {
    private lateinit var input: EditText
    private lateinit var out: LinearLayout
    private lateinit var decksSpinner: android.widget.Spinner
    private var deckNames = listOf<String>()
    private var ignoreSelection = true

    private fun decks(): org.json.JSONObject =
        runCatching { org.json.JSONObject(getSharedPreferences("coach", MODE_PRIVATE).getString("decks", "{}")!!) }.getOrDefault(org.json.JSONObject())

    private fun loadDeckList(select: String? = null) {
        val d = decks()
        deckNames = d.keys().asSequence().toList().sorted()
        ignoreSelection = true
        // Each saved deck shows at a glance whether you can build it.
        val owned = CollectionStore.all(this).associateBy { it.name.lowercase() }
        val labels = deckNames.map { n ->
            val r = DeckCheck.check(d.optString(n), { owned[it.lowercase()]?.copies ?: 0 }, { "?" })
            val miss = r.total - r.owned
            if (miss == 0) "$n  ✓ complete" else "$n  · missing $miss"
        }
        decksSpinner.adapter = android.widget.ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item,
            listOf(if (deckNames.isEmpty()) "No saved decks" else "Saved decks…") + labels)
        decksSpinner.setSelection(select?.let { deckNames.indexOf(it) + 1 }?.takeIf { it > 0 } ?: 0)
        decksSpinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: android.widget.AdapterView<*>?, v: android.view.View?, pos: Int, id: Long) {
                if (ignoreSelection) { ignoreSelection = false; return }
                if (pos > 0) { input.setText(decks().optString(deckNames[pos - 1])); run() }
            }
            override fun onNothingSelected(p: android.widget.AdapterView<*>?) {}
        }
    }

    private fun saveDeck() {
        if (input.text.isBlank()) { toast("Paste a deck first."); return }
        val nameBox = EditText(this).apply { hint = "Deck name"; isSingleLine = true
            setText(if (decksSpinner.selectedItemPosition > 0) deckNames[decksSpinner.selectedItemPosition - 1] else "") }
        android.app.AlertDialog.Builder(this).setTitle("Save deck").setView(nameBox)
            .setPositiveButton("Save") { _, _ ->
                val n = nameBox.text.toString().trim().ifEmpty { "Deck ${deckNames.size + 1}" }
                getSharedPreferences("coach", MODE_PRIVATE).edit().putString("decks", decks().put(n, input.text.toString()).toString()).apply()
                loadDeckList(n); toast("Saved \"$n\"")
            }.setNegativeButton("Cancel", null).show()
    }

    private fun deleteDeck() {
        val pos = decksSpinner.selectedItemPosition
        if (pos <= 0) { toast("Pick a saved deck to delete."); return }
        val n = deckNames[pos - 1]
        android.app.AlertDialog.Builder(this).setMessage("Delete \"$n\"?")
            .setPositiveButton("Delete") { _, _ ->
                val d = decks(); d.remove(n)
                getSharedPreferences("coach", MODE_PRIVATE).edit().putString("decks", d.toString()).apply()
                loadDeckList()
            }.setNegativeButton("Cancel", null).show()
    }

    private fun toast(s: String) = android.widget.Toast.makeText(this, s, android.widget.Toast.LENGTH_SHORT).show()
    private val density get() = resources.displayMetrics.density
    private val rarityName = mapOf("M" to "Mythic", "R" to "Rare", "U" to "Uncommon", "C" to "Common", "?" to "Unknown")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(titleText("Deck check", 28f))
        col.addView(mutedText("In Arena open a deck, tap Export, then Paste here. Shows what you own and the wildcards it costs.")
            .apply { setPadding(0, dp(2), 0, dp(12)) })
        // Saved decks: pick one to load it, or save the pasted list under a name.
        decksSpinner = android.widget.Spinner(this).apply { id = 4245; background = rounded(surface2(), 14); setPadding(dp(8), dp(6), dp(8), dp(6)) }
        input = field("4 Llanowar Elves (FDN) 227\n…").apply {
            id = 4242; minLines = 5; maxLines = 10; textSize = 13f; typeface = Typeface.MONOSPACE; gravity = android.view.Gravity.TOP
            isSingleLine = false
            setText(getSharedPreferences("coach", MODE_PRIVATE).getString("last_deck", ""))
        }
        col.addView(card(
            decksSpinner,
            rowOf(tonalButton("Save deck") { saveDeck() }, tonalButton("Delete") { deleteDeck() }),
            input,
            rowOf(tonalButton("Paste") { paste() }, primaryButton("Check deck") { run() }),
        ))
        out = card().apply { visibility = android.view.View.GONE }
        col.addView(out)
        setContentView(page(col))
        loadDeckList()
        // Opened right after tapping Export in Arena? Use the deck on the clipboard.
        if (input.text.isBlank()) {
            val clip = runCatching { getSystemService(ClipboardManager::class.java).primaryClip?.getItemAt(0)?.coerceToText(this)?.toString() }.getOrNull()
            if (clip != null && DeckCheck.parse(clip).size >= 3) input.setText(clip)
        }
        if (input.text.isNotBlank()) run()
    }

    private fun paste() {
        val clip = getSystemService(ClipboardManager::class.java).primaryClip
        val t = clip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(this)?.toString()
        if (t.isNullOrBlank()) { android.widget.Toast.makeText(this, "Clipboard is empty. Tap Export on a deck in Arena first.", android.widget.Toast.LENGTH_LONG).show(); return }
        input.setText(t); run()
    }

    private fun run() {
        val deckText = input.text.toString()
        getSharedPreferences("coach", MODE_PRIVATE).edit().putString("last_deck", deckText).apply()
        val owned = CollectionStore.all(this).associateBy { it.name.lowercase() }
        val rarities = CardDb.load(this).associate { it.name.lowercase() to it.rarity }
        val r = DeckCheck.check(deckText, { owned[it.lowercase()]?.copies ?: 0 }, { owned[it.lowercase()]?.rarity ?: rarities[it.lowercase()] ?: "?" })
        out.removeAllViews()
        out.visibility = android.view.View.VISIBLE
        if (r.lines.isEmpty()) { out.addView(TextView(this).apply { text = "No cards found. Paste a deck list with one card per line." }); return }
        val missing = r.total - r.owned
        out.addView(TextView(this).apply {
            textSize = 17f; setTypeface(typeface, Typeface.BOLD)
            text = if (missing == 0) "✓ You own every card (${r.total})." else "You own ${r.owned} of ${r.total} cards. Missing $missing."
        })
        if (missing > 0) out.addView(Button(this).apply {
            text = "Copy missing cards"
            setOnClickListener {
                val list = r.lines.filter { it.missing > 0 }.joinToString("\n") { "${it.missing} ${it.name}" }
                getSystemService(ClipboardManager::class.java).setPrimaryClip(android.content.ClipData.newPlainText("Missing cards", list))
                android.widget.Toast.makeText(this@DeckCheckActivity, "Copied ${r.lines.count { it.missing > 0 }} cards", android.widget.Toast.LENGTH_SHORT).show()
            }
        })
        val wild = r.wildcards.filter { it.value > 0 }
        if (wild.isNotEmpty()) out.addView(TextView(this).apply {
            textSize = 15f; setPadding(0, (6 * density).toInt(), 0, (10 * density).toInt())
            text = "Wildcards needed: " + wild.entries.joinToString("  ·  ") { "${it.value} ${rarityName[it.key]}" } +
                if ("?" in wild) "\n(Unknown rarity: card isn't in the Standard card list.)" else ""
        })
        for (l in r.lines) out.addView(LinearLayout(this).apply {
            setPadding(0, (3 * density).toInt(), 0, (3 * density).toInt())
            addView(TextView(context).apply { text = l.rarity; setTextColor(this@DeckCheckActivity.rarityColor(l.rarity)); typeface = Typeface.MONOSPACE; minWidth = (24 * density).toInt() })
            addView(TextView(context).apply { text = "${l.need} ${l.name}" + if (l.sideboard) "  (side)" else "" }, LinearLayout.LayoutParams(0, -2, 1f))
            addView(TextView(context).apply {
                text = if (l.missing == 0) "owned" else "need ${l.missing}"
                setTextColor(Color.parseColor(if (l.missing == 0) "#1F7A4D" else "#B3261E")); typeface = Typeface.MONOSPACE
            })
        })
    }
}

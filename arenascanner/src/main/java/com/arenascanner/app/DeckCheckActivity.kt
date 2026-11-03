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
    private val dp get() = resources.displayMetrics.density
    private val rarityName = mapOf("M" to "Mythic", "R" to "Rare", "U" to "Uncommon", "C" to "Common", "?" to "Unknown")
    private val rarityColor = mapOf("M" to "#C4471D", "R" to "#A8811C", "U" to "#6F8296", "C" to "#3B3F46", "?" to "#8A93A3")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = (16 * dp).toInt()
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(pad, pad, pad, pad) }
        val scroll = ScrollView(this).apply { addView(col) }
        scroll.setOnApplyWindowInsetsListener { v, i ->
            val b = i.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout()); v.setPadding(b.left, b.top, b.right, b.bottom); i
        }
        col.addView(TextView(this).apply { text = "Deck check"; textSize = 24f; setTypeface(typeface, Typeface.BOLD) })
        col.addView(TextView(this).apply {
            text = "In Arena open a deck, tap Export, then Paste here. Shows what you own and the wildcards needed."
            setTextColor(context.secondaryText()); setPadding(0, pad / 2, 0, pad / 2)
        })
        input = EditText(this).apply {
            id = 4242; hint = "4 Llanowar Elves (FDN) 227\n…"; minLines = 5; maxLines = 10; textSize = 13f; typeface = Typeface.MONOSPACE
            setText(getSharedPreferences("coach", MODE_PRIVATE).getString("last_deck", ""))
        }
        col.addView(input)
        col.addView(LinearLayout(this).apply {
            addView(Button(context).apply { text = "Paste"; setOnClickListener { paste() } }, LinearLayout.LayoutParams(0, -2, 1f))
            addView(Button(context).apply { text = "Check deck"; setOnClickListener { run() } }, LinearLayout.LayoutParams(0, -2, 1f))
        })
        out = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, pad, 0, 0) }
        col.addView(out)
        setContentView(scroll)
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
            textSize = 15f; setPadding(0, (6 * dp).toInt(), 0, (10 * dp).toInt())
            text = "Wildcards needed: " + wild.entries.joinToString("  ·  ") { "${it.value} ${rarityName[it.key]}" } +
                if ("?" in wild) "\n(Unknown rarity: card isn't in the Standard card list.)" else ""
        })
        for (l in r.lines) out.addView(LinearLayout(this).apply {
            setPadding(0, (3 * dp).toInt(), 0, (3 * dp).toInt())
            addView(TextView(context).apply { text = l.rarity; setTextColor(Color.parseColor(rarityColor[l.rarity] ?: "#8A93A3")); typeface = Typeface.MONOSPACE; minWidth = (24 * dp).toInt() })
            addView(TextView(context).apply { text = "${l.need} ${l.name}" + if (l.sideboard) "  (side)" else "" }, LinearLayout.LayoutParams(0, -2, 1f))
            addView(TextView(context).apply {
                text = if (l.missing == 0) "owned" else "need ${l.missing}"
                setTextColor(Color.parseColor(if (l.missing == 0) "#1F7A4D" else "#B3261E")); typeface = Typeface.MONOSPACE
            })
        })
    }
}

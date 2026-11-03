package com.arenascanner.app

import android.content.Context
import org.json.JSONObject
import java.io.File

/**
 * One owned card: [copies] is 1–4 (Arena never needs more than 4 of a card).
 * [manual] is true once you set the count yourself; scans then leave it alone.
 */
data class Owned(val name: String, val rarity: String, var copies: Int, var manual: Boolean = false)

/** The scanned collection, saved as a small JSON file. Shared by the screen and the scan service. */
object CollectionStore {
    private val cards = LinkedHashMap<String, Owned>()
    private var loaded = false

    private fun file(ctx: Context) = File(ctx.filesDir, "collection.json")

    @Synchronized fun all(ctx: Context): List<Owned> {
        load(ctx); return cards.values.sortedBy { it.name.lowercase() }
    }

    /**
     * Records cards seen in a scan, each with the copies read from its diamonds (null = couldn't tell, 0 = not owned).
     * New cards get the read count, or 1. Known cards take a newly read count unless you set it by hand.
     * Returns how many cards were new.
     */
    @Synchronized fun recordScan(ctx: Context, seen: List<Pair<Card, Int?>>): Int {
        load(ctx)
        var added = 0
        var changed = false
        for ((c, n) in seen) {
            if (n == 0) continue // every diamond empty: not owned
            val ex = cards[c.name]
            if (ex == null) {
                cards[c.name] = Owned(c.name, c.rarity, (n ?: 1).coerceIn(1, 4)); added++; changed = true
            } else if (n != null && !ex.manual && ex.copies != n) {
                ex.copies = n.coerceIn(1, 4); changed = true
            }
        }
        if (changed) save(ctx)
        return added
    }

    @Synchronized fun setCopies(ctx: Context, name: String, copies: Int) {
        load(ctx)
        val o = cards[name] ?: return
        if (copies <= 0) cards.remove(name) else { o.copies = copies.coerceAtMost(4); o.manual = true }
        save(ctx)
    }

    @Synchronized fun clear(ctx: Context) { cards.clear(); loaded = true; save(ctx) }

    /** Spreadsheet-ready CSV: Count, Name, Rarity, Set by hand. Names with commas are quoted. */
    @Synchronized fun exportCsv(ctx: Context): String {
        val rarity = mapOf("C" to "Common", "U" to "Uncommon", "R" to "Rare", "M" to "Mythic")
        fun q(s: String) = if (s.any { it == ',' || it == '"' }) "\"" + s.replace("\"", "\"\"") + "\"" else s
        val rows = all(ctx).map { "${it.copies},${q(it.name)},${rarity[it.rarity] ?: "Unknown"},${if (it.manual) "yes" else "no"}" }
        return (listOf("Count,Name,Rarity,Set by hand") + rows).joinToString("\r\n") + "\r\n"
    }

    /** "4 Card Name [R]" per line: pastes into the Arena Collection page's "Paste a list" box. */
    @Synchronized fun exportText(ctx: Context): String =
        all(ctx).joinToString("\n") { "${it.copies} ${it.name} [${it.rarity}]" }

    private fun load(ctx: Context) {
        if (loaded) return
        loaded = true
        val f = file(ctx)
        if (!f.exists()) return
        val o = JSONObject(f.readText())
        for (k in o.keys()) {
            val c = o.getJSONObject(k)
            cards[k] = Owned(k, c.optString("r", "?"), c.optInt("q", 1), c.optBoolean("m", false))
        }
    }

    private fun save(ctx: Context) {
        val o = JSONObject()
        for ((k, v) in cards) o.put(k, JSONObject().put("r", v.rarity).put("q", v.copies).put("m", v.manual))
        file(ctx).writeText(o.toString())
    }
}

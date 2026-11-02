package com.arenascanner.app

import android.content.Context
import org.json.JSONObject
import java.io.File

/** One owned card: [copies] is 1–4 (Arena never needs more than 4 of a card). */
data class Owned(val name: String, val rarity: String, var copies: Int)

/** The scanned collection, saved as a small JSON file. Shared by the screen and the scan service. */
object CollectionStore {
    private val cards = LinkedHashMap<String, Owned>()
    private var loaded = false

    private fun file(ctx: Context) = File(ctx.filesDir, "collection.json")

    @Synchronized fun all(ctx: Context): List<Owned> {
        load(ctx); return cards.values.sortedBy { it.name.lowercase() }
    }

    /** Adds cards seen in a scan. Returns how many were new. Existing cards keep their copy count. */
    @Synchronized fun addSeen(ctx: Context, seen: List<Card>): Int {
        load(ctx)
        var added = 0
        for (c in seen) if (!cards.containsKey(c.name)) { cards[c.name] = Owned(c.name, c.rarity, 1); added++ }
        if (added > 0) save(ctx)
        return added
    }

    @Synchronized fun setCopies(ctx: Context, name: String, copies: Int) {
        load(ctx)
        val o = cards[name] ?: return
        if (copies <= 0) cards.remove(name) else o.copies = copies.coerceAtMost(4)
        save(ctx)
    }

    @Synchronized fun clear(ctx: Context) { cards.clear(); loaded = true; save(ctx) }

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
            cards[k] = Owned(k, c.optString("r", "?"), c.optInt("q", 1))
        }
    }

    private fun save(ctx: Context) {
        val o = JSONObject()
        for ((k, v) in cards) o.put(k, JSONObject().put("r", v.rarity).put("q", v.copies))
        file(ctx).writeText(o.toString())
    }
}

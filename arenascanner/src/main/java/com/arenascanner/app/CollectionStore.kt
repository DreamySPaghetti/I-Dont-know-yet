package com.arenascanner.app

import android.content.Context
import org.json.JSONObject
import java.io.File

/**
 * One owned card: [copies] is 1–4 (Arena never needs more than 4 of a card).
 * [manual] is true once you set the count yourself; scans then leave it alone.
 */
data class Owned(
    val name: String, val rarity: String, var copies: Int, var manual: Boolean = false,
    /** When the card first appeared in the collection (ms). */
    var added: Long = 0,
    /** How many times its diamonds were read in the scan that last set its count. */
    var reads: Int = 0,
) {
    /** Count came from a single read and wasn't set by hand: worth a quick check. */
    val uncertain get() = !manual && reads == 1
}

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
     * New cards are added only once a count has been read. Known cards take a newly read count unless
     * you set it by hand.
     * Returns how many cards were new.
     */
    /** Counts of the last [recordScan]: new cards and changed counts. */
    @Volatile var lastUpdated = 0
        private set
    /** Names of the cards the last [recordScan] added. */
    @Volatile var lastAdded: List<String> = emptyList()
        private set

    /** [seen]: card, count read (null = couldn't tell), and how many reads that count is based on. */
    @Synchronized fun recordScan(ctx: Context, seen: List<Triple<Card, Int?, Int>>): Int {
        load(ctx)
        var added = 0
        var updated = 0
        var changed = false
        val addedNames = mutableListOf<String>()
        val now = System.currentTimeMillis()
        for ((c, n, reads) in seen) {
            if (n == 0) continue // every diamond empty: not owned
            val ex = cards[c.name]
            if (ex == null) {
                // Only add a card once its diamonds have been read. A name with no readable diamonds
                // above it is usually a word in some card's rules text (e.g. "Flashback"), not a card.
                if (n == null) continue
                cards[c.name] = Owned(c.name, c.rarity, n.coerceIn(1, 4), added = now, reads = reads); added++; changed = true
                addedNames += c.name
            } else if (n != null && !ex.manual) {
                if (ex.copies != n) { ex.copies = n.coerceIn(1, 4); updated++; changed = true }
                if (ex.reads != reads) { ex.reads = reads; changed = true }
            }
        }
        if (changed) save(ctx)
        lastUpdated = updated
        lastAdded = addedNames
        return added
    }

    @Synchronized fun setCopies(ctx: Context, name: String, copies: Int) {
        load(ctx)
        val o = cards[name] ?: return
        if (copies <= 0) cards.remove(name) else { o.copies = copies.coerceAtMost(4); o.manual = true }
        save(ctx)
    }

    /** Adds or replaces cards from an import; imported counts count as set by hand. Returns how many. */
    @Synchronized fun importRows(ctx: Context, rows: List<Triple<String, Int, String>>): Int {
        load(ctx)
        val now = System.currentTimeMillis()
        for ((name, n, r) in rows) cards[name] = Owned(name, r, n.coerceIn(1, 4), manual = true, added = cards[name]?.added ?: now)
        save(ctx)
        return rows.size
    }

    /** CSV rows (name, count, rarity code or null). Needs Count and Name columns; quotes and commas in names are handled. */
    fun parseCsv(text: String): List<Triple<String, Int, String?>> {
        val lines = text.lines().filter { it.isNotBlank() }
        if (lines.isEmpty()) return emptyList()
        fun split(l: String): List<String> {
            val out = mutableListOf<String>(); val sb = StringBuilder(); var q = false; var i = 0
            while (i < l.length) {
                val c = l[i]
                when {
                    q && c == '"' && i + 1 < l.length && l[i + 1] == '"' -> { sb.append('"'); i++ }
                    c == '"' -> q = !q
                    c == ',' && !q -> { out += sb.toString().trim(); sb.clear() }
                    else -> sb.append(c)
                }
                i++
            }
            out += sb.toString().trim()
            return out
        }
        val head = split(lines[0]).map { it.lowercase() }
        val ci = head.indexOfFirst { it == "count" || it == "quantity" || it == "qty" }
        val ni = head.indexOfFirst { it == "name" || it == "card name" || it == "card" }
        val ri = head.indexOf("rarity")
        if (ci < 0 || ni < 0) return emptyList()
        val code = mapOf("mythic" to "M", "rare" to "R", "uncommon" to "U", "common" to "C", "m" to "M", "r" to "R", "u" to "U", "c" to "C")
        return lines.drop(1).mapNotNull { l ->
            val f = split(l)
            val n = f.getOrNull(ci)?.toIntOrNull() ?: return@mapNotNull null
            val name = f.getOrNull(ni)?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            if (n <= 0) null else Triple(name, n, f.getOrNull(ri)?.lowercase()?.let { code[it] })
        }
    }

    /** Clears everything, keeping one backup so it can be undone. */
    @Synchronized fun clear(ctx: Context) {
        load(ctx)
        file(ctx).takeIf { it.exists() }?.copyTo(backup(ctx), overwrite = true)
        cards.clear(); save(ctx)
    }

    @Synchronized fun undoClear(ctx: Context): Boolean {
        val b = backup(ctx)
        if (!b.exists()) return false
        b.copyTo(file(ctx), overwrite = true)
        cards.clear(); loaded = false; load(ctx)
        return true
    }

    private fun backup(ctx: Context) = File(ctx.filesDir, "collection_backup.json")

    /** Spreadsheet-ready CSV: Count, Name, Rarity, Set by hand. Names with commas are quoted. */
    @Synchronized fun exportCsv(ctx: Context): String = csvOf(all(ctx))

    /** CSV for any list of cards (used by the format export too). */
    fun csvOf(cards: List<Owned>): String {
        val rarity = mapOf("C" to "Common", "U" to "Uncommon", "R" to "Rare", "M" to "Mythic")
        fun q(s: String) = if (s.any { it == ',' || it == '"' }) "\"" + s.replace("\"", "\"\"") + "\"" else s
        val rows = cards.map { "${it.copies},${q(it.name)},${rarity[it.rarity] ?: "Unknown"},${if (it.manual) "yes" else "no"}" }
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
            cards[k] = Owned(k, c.optString("r", "?"), c.optInt("q", 1), c.optBoolean("m", false), c.optLong("t", 0), c.optInt("n", 0))
        }
    }

    private fun save(ctx: Context) {
        val o = JSONObject()
        for ((k, v) in cards) o.put(k, JSONObject().put("r", v.rarity).put("q", v.copies).put("m", v.manual).put("t", v.added).put("n", v.reads))
        file(ctx).writeText(o.toString())
    }
}

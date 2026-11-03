package com.arenascanner.app

/** Checks a pasted deck list against the scanned collection. Pure, so it's unit-tested. */
object DeckCheck {
    data class Line(val name: String, val need: Int, val own: Int, val rarity: String, val sideboard: Boolean) {
        val missing get() = (need - own).coerceAtLeast(0)
    }

    data class Result(val lines: List<Line>, val wildcards: Map<String, Int>) {
        val total get() = lines.sumOf { it.need }
        val owned get() = lines.sumOf { minOf(it.need, it.own) }
    }

    private val BASICS = setOf("plains", "island", "swamp", "mountain", "forest", "wastes")

    /** Arena export ("4 Name (SET) 123"), "4x Name", or "Name" lines; "Deck"/"Sideboard" headers. */
    fun parse(text: String): List<Triple<String, Int, Boolean>> {
        val out = linkedMapOf<Pair<String, Boolean>, Int>()
        var side = false
        for (raw in text.lines()) {
            val line = raw.trim()
            if (line.isEmpty()) { if (out.isNotEmpty()) side = true; continue }
            val low = line.lowercase()
            if (low == "deck" || low == "commander" || low == "companion" || low.startsWith("about") || low.startsWith("name ")) { side = false; continue }
            if (low == "sideboard") { side = true; continue }
            val m = Regex("^(\\d+)\\s*[xX]?\\s+(.+)$").find(line)
            val qty = m?.groupValues?.get(1)?.toInt() ?: 1
            var name = m?.groupValues?.get(2) ?: line
            name = name.replace(Regex("\\s+\\([A-Za-z0-9]{2,6}\\)\\s*[\\w-]*\\s*$"), "").replace(Regex("\\s+\\*F\\*$"), "").trim()
            if (name.isEmpty() || qty <= 0) continue
            out.merge(name to side, qty, Int::plus)
        }
        return out.map { (k, v) -> Triple(k.first, v, k.second) }
    }

    fun check(text: String, owned: (String) -> Int, rarityOf: (String) -> String): Result {
        val lines = parse(text).filter { Matcher.normalize(it.first) !in BASICS }.map { (name, n, side) ->
            Line(name, n, owned(name), rarityOf(name), side)
        }
        // A card in both main deck and sideboard shares the owned copies.
        val byName = lines.groupBy { it.name.lowercase() }
        val wild = linkedMapOf("M" to 0, "R" to 0, "U" to 0, "C" to 0, "?" to 0)
        for ((_, ls) in byName) {
            val need = minOf(4, ls.sumOf { it.need })
            val miss = (need - ls.first().own).coerceAtLeast(0)
            wild.merge(ls.first().rarity, miss, Int::plus)
        }
        return Result(lines.sortedWith(compareByDescending<Line> { it.missing > 0 }.thenBy { it.sideboard }.thenBy { it.name }), wild)
    }
}

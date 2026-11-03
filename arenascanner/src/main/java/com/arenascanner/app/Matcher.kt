package com.arenascanner.app

/**
 * Turns lines of OCR text into card names, using the downloaded Standard card list as a dictionary.
 * OCR on a phone screenshot also returns type lines, rules text and UI labels, so only lines that
 * match a real card name (exactly, or within a couple of misread letters) count.
 */
class Matcher(cards: Collection<Card>) {
    private val byKey = HashMap<String, Card>()
    // Fuzzy candidates are looked up by a name's first two and last three letters, so a misread line
    // is only compared with a few dozen names instead of every card (needed for the all-cards pool).
    private val byHead = HashMap<String, MutableList<Pair<String, Card>>>()
    private val byTail = HashMap<String, MutableList<Pair<String, Card>>>()

    init {
        for (c in cards) {
            // Basic lands are free and unlimited in Arena, and "Forest" etc. appear in type lines.
            if (normalize(c.name) in BASICS) continue
            add(normalize(c.name), c)
            // Double-faced cards: Arena prints only the face's own name on the card.
            for (face in c.faces) add(normalize(face), c)
        }
    }

    private fun add(key: String, c: Card) {
        if (key.length < 3 || byKey.containsKey(key)) return
        byKey[key] = c
        byHead.getOrPut(key.take(2)) { mutableListOf() }.add(key to c)
        byTail.getOrPut(key.takeLast(3)) { mutableListOf() }.add(key to c)
    }

    /** Best card for one OCR line, or null if the line isn't a card name. */
    fun match(line: String): Card? {
        val key = normalize(line)
        if (key.length < 3) return null
        byKey[key]?.let { return it }
        // Mana symbols sometimes come through as stray letters at the end of the name line.
        val words = key.split(' ')
        for (drop in 1..2) {
            if (words.size - drop < 1) break
            val tail = words.takeLast(drop)
            if (tail.all { it.length <= 2 }) byKey[words.dropLast(drop).joinToString(" ")]?.let { return it }
        }
        if (key.length < 6) return null
        val maxDist = if (key.length >= 14) 2 else 1
        var best: Card? = null
        var bestDist = maxDist + 1
        for (bucket in listOf(byHead[key.take(2)], byTail[key.takeLast(3)])) {
            for ((k, c) in bucket ?: continue) {
                if (kotlin.math.abs(k.length - key.length) > maxDist) continue
                val d = distance(key, k, bestDist)
                if (d < bestDist) { bestDist = d; best = c }
            }
        }
        return best
    }

    /** Unique cards found across all lines, in reading order. */
    fun matchAll(lines: List<String>): List<Card> {
        val seen = LinkedHashMap<String, Card>()
        for (l in lines) match(l)?.let { seen.putIfAbsent(it.name, it) }
        return seen.values.toList()
    }

    companion object {
        private val BASICS = setOf("plains", "island", "swamp", "mountain", "forest", "wastes",
            "snow covered plains", "snow covered island", "snow covered swamp", "snow covered mountain",
            "snow covered forest", "snow covered wastes")

        fun normalize(s: String): String = s.lowercase()
            .replace('’', '\'').replace('‘', '\'')
            .replace(Regex("[^a-z0-9' ]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()

        /** Levenshtein distance, giving up early once it exceeds [limit]. */
        fun distance(a: String, b: String, limit: Int): Int {
            if (kotlin.math.abs(a.length - b.length) > limit) return limit + 1
            var prev = IntArray(b.length + 1) { it }
            var cur = IntArray(b.length + 1)
            for (i in 1..a.length) {
                cur[0] = i
                var rowMin = cur[0]
                for (j in 1..b.length) {
                    val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                    cur[j] = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + cost)
                    if (cur[j] < rowMin) rowMin = cur[j]
                }
                if (rowMin > limit) return limit + 1
                val t = prev; prev = cur; cur = t
            }
            return prev[b.length]
        }
    }
}

/**
 * One Standard card. [rarity] is C, U, R, M or ? (Arena wildcard tiers).
 * [rules] is "{cost} — type — rules text — P/T", used by the move coach.
 */
data class Card(val name: String, val rarity: String, val faces: List<String> = emptyList(), val rules: String = "")

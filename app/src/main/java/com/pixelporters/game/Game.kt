package com.pixelporters.game

import androidx.compose.ui.graphics.Color
import kotlin.random.Random

const val FLY_MS = 520L
const val MAX_SLOTS = 6

private val PALETTE = linkedMapOf(
    'R' to 0xFFE53935, 'O' to 0xFFFB8C00, 'Y' to 0xFFFDD835, 'G' to 0xFF43A047, 'L' to 0xFF9CCC65,
    'B' to 0xFF1E88E5, 'C' to 0xFF4DD0E1, 'P' to 0xFF8E24AA, 'K' to 0xFFF48FB1, 'W' to 0xFFF5F5F5,
    'N' to 0xFF8D6E63, 'D' to 0xFF455A64, 'S' to 0xFFFFCC80
)
private val KEYS = PALETTE.keys.toList()
val COLORS = PALETTE.values.map { Color(it) }

class Pic(val name: String, vararg val rows: String)

val PICS = listOf(
    Pic("Heart", ".RR.RR.", "RKRRRRR", "RRRRRRR", "RRRRRRR", ".RRRRR.", "..RRR..", "...R..."),
    Pic(
        "Mushroom", "...RRR...", ".RRWRRRR.", "RRRRRWRRR", "RWRRRRRWR", "RRRRRRRRR",
        "...SSS...", "...SSS...", "..SSSSS.."
    ),
    Pic(
        "Sunny Day", "....Y....", ".Y.....Y.", "...OOO...", "..OYYYO..", "Y.OYYYO.Y",
        "..OYYYO..", "...OOO...", ".Y.....Y.", "....Y...."
    ),
    Pic(
        "Little Tree", "...GGG...", "..GGLGG..", ".GGGGGLG.", "GLGGGGGGG", ".GGGLGGG.",
        "..GGGGG..", "....N....", "....N....", "...NNN..."
    ),
    Pic(
        "Goldfish", "...OOOO....", ".OOOOOOO..O", "OWDOOYOOOOO", "OOOOOYOOOOO", ".OOOOOOO..O", "...OOOO...."
    ),
    Pic(
        "Flower", "...K.K...", "..KKKKK..", ".KKKYKKK.", "..KKKKK..", "...K.K...",
        "....G....", "..L.G....", "..LLG.LL.", "....GLL..", "....G...."
    ),
    Pic(
        "Cottage", "....RR....", "...RRRR...", "..RRRRRR..", ".RRRRRRRR.", "RRRRRRRRRR",
        ".WWWWWWWW.", ".WBBWWNNW.", ".WBBWWNNW.", ".WWWWWNNW.", "GGGGGGGGGG"
    ),
    Pic(
        "Rocket", "....R....", "...RRR...", "...WWW...", "..WWWWW..", "..WCCCW..", "..WCCCW..",
        "..WWWWW..", "..WWWWW..", ".RWWWWWR.", "RRWWWWWRR", "R..OYO..R", "...OYO...", "....O...."
    ),
    Pic(
        "Kitten", ".O.......O.", ".OO.....OO.", ".OOOOOOOOO.", "OOOOOOOOOOO", "OOGOOOOOGOO",
        "OOOOOKOOOOO", "OOOWWWWWOOO", ".OOWWWWWOO.", "..OOOOOOO.."
    ),
    Pic(
        "Cupcake", ".....R....", "....KKK...", "..KKKWKKK.", ".KKWKKKKKK", ".KKKKKKWKK",
        "..NNNNNNN.", "..NSNSNSN.", "...NSNSN..", "...NNNNN.."
    ),
    Pic(
        "Sailboat", ".....W.....", "....WW.....", "...WWW.R...", "..WWWW.RR..", ".WWWWW.RRR.",
        "WWWWWW.RRRR", ".....N.....", "NNNNNNNNNNN", ".NNNNNNNNN.", "BBBBBBBBBBB", "CBBBCBBBCBB"
    ),
    Pic(
        "Grapes", "....G.L..", "....GL...", "..PPPP...", ".PPKPPP..", ".PPPPKPP.",
        "..PKPPP..", "..PPPP...", "...PP....", "...P....."
    )
)

class Stack(val color: Int, var n: Int)
class Slot(val color: Int, var n: Int, val since: Long)
class Flyer(val color: Int, val slot: Int, val cell: Int, val start: Long)

class Game(val level: Int) {
    val pic = PICS[(level - 1) % PICS.size]
    val w = pic.rows.maxOf { it.length }
    val h = pic.rows.size
    val color = IntArray(w * h) { i -> KEYS.indexOf(pic.rows[i / w].getOrElse(i % w) { '.' }) }
    val filled = BooleanArray(w * h)
    private val reserved = BooleanArray(w * h)
    val laneCount = if (level <= 2) 3 else 4
    var slotCount = if (level <= 4) 5 else 4
        private set
    val slots = MutableList<Slot?>(MAX_SLOTS) { null }
    val lanes = List(laneCount) { ArrayDeque<Stack>() }
    val flyers = mutableListOf<Flyer>()
    val total = color.count { it >= 0 }
    var done = 0
        private set
    var status = 0
        private set
    var rescued = false
        private set
    var now = 0L
        private set
    private var lastDispatch = 0L

    init {
        val rnd = Random(level * 7919 + 13)
        val sim = BooleanArray(w * h) { color[it] < 0 }
        fun open(x: Int): Int {
            for (y in h - 1 downTo 0) if (!sim[y * w + x]) return y * w + x
            return -1
        }
        val seq = mutableListOf<Stack>()
        val maxStack = if (level <= 3) 5 else 8
        while (sim.any { !it }) {
            val opens = (0 until w).map { open(it) }.filter { it >= 0 }
            val c = color[opens[rnd.nextInt(opens.size)]]
            val target = rnd.nextInt(2, maxStack + 1)
            var k = 0
            while (k < target) {
                val i = (0 until w).map { open(it) }.firstOrNull { it >= 0 && color[it] == c } ?: break
                sim[i] = true
                k++
            }
            if (seq.isNotEmpty() && seq.last().color == c && seq.last().n + k <= 9) seq.last().n += k else seq += Stack(c, k)
        }
        seq.forEach { lanes[rnd.nextInt(laneCount)].addLast(it) }
    }

    private fun openCell(x: Int): Int {
        for (y in h - 1 downTo 0) {
            val i = y * w + x
            if (color[i] < 0 || filled[i] || reserved[i]) continue
            return i
        }
        return -1
    }

    fun isOpen(i: Int) = openCell(i % w) == i

    private fun target(c: Int): Int {
        for (x in 0 until w) {
            val i = openCell(x)
            if (i >= 0 && color[i] == c) return i
        }
        return -1
    }

    fun tap(lane: Int): Boolean {
        if (status != 0) return false
        val q = lanes[lane]
        if (q.isEmpty()) return false
        val si = (0 until slotCount).firstOrNull { slots[it] == null } ?: return false
        val st = q.removeFirst()
        slots[si] = Slot(st.color, st.n, now)
        return true
    }

    fun rescue() {
        if (rescued || slotCount >= MAX_SLOTS) return
        slotCount++
        rescued = true
        status = 0
    }

    fun update(t: Long) {
        now = t
        if (status != 0) return
        val it = flyers.iterator()
        while (it.hasNext()) {
            val f = it.next()
            if (t - f.start >= FLY_MS) {
                filled[f.cell] = true
                done++
                it.remove()
            }
        }
        if (t - lastDispatch >= 110) {
            var any = false
            for (si in 0 until slotCount) {
                val s = slots[si] ?: continue
                val i = target(s.color)
                if (i < 0) continue
                reserved[i] = true
                s.n--
                flyers += Flyer(s.color, si, i, t)
                any = true
                if (s.n == 0) slots[si] = null
            }
            if (any) lastDispatch = t
        }
        if (done == total) {
            status = 1
        } else if (flyers.isEmpty() && (0 until slotCount).all { slots[it] != null } &&
            (0 until slotCount).none { target(slots[it]!!.color) >= 0 }
        ) {
            status = 2
        }
    }
}

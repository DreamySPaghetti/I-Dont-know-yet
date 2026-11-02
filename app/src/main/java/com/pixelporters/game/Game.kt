package com.pixelporters.game

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import kotlin.random.Random

const val SPEED = 2.2f
const val DOCKS = 3
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
    Pic("Mushroom", "...RRR...", ".RRWRRRR.", "RRRRRWRRR", "RWRRRRRWR", "RRRRRRRRR", "...SSS...", "...SSS...", "..SSSSS.."),
    Pic("Sunny Day", "....Y....", ".Y.....Y.", "...OOO...", "..OYYYO..", "Y.OYYYO.Y", "..OYYYO..", "...OOO...", ".Y.....Y.", "....Y...."),
    Pic("Little Tree", "...GGG...", "..GGLGG..", ".GGGGGLG.", "GLGGGGGGG", ".GGGLGGG.", "..GGGGG..", "....N....", "....N....", "...NNN..."),
    Pic("Goldfish", "...OOOO....", ".OOOOOOO..O", "OWDOOYOOOOO", "OOOOOYOOOOO", ".OOOOOOO..O", "...OOOO...."),
    Pic("Flower", "...K.K...", "..KKKKK..", ".KKKYKKK.", "..KKKKK..", "...K.K...", "....G....", "..L.G....", "..LLG.LL.", "....GLL..", "....G...."),
    Pic("Cottage", "....RR....", "...RRRR...", "..RRRRRR..", ".RRRRRRRR.", "RRRRRRRRRR", ".WWWWWWWW.", ".WBBWWNNW.", ".WBBWWNNW.", ".WWWWWNNW.", "GGGGGGGGGG"),
    Pic("Rocket", "....R....", "...RRR...", "...WWW...", "..WWWWW..", "..WCCCW..", "..WCCCW..", "..WWWWW..", "..WWWWW..", ".RWWWWWR.", "RRWWWWWRR", "R..OYO..R", "...OYO...", "....O...."),
    Pic("Kitten", ".O.......O.", ".OO.....OO.", ".OOOOOOOOO.", "OOOOOOOOOOO", "OOGOOOOOGOO", "OOOOOKOOOOO", "OOOWWWWWOOO", ".OOWWWWWOO.", "..OOOOOOO.."),
    Pic("Cupcake", ".....R....", "....KKK...", "..KKKWKKK.", ".KKWKKKKKK", ".KKKKKKWKK", "..NNNNNNN.", "..NSNSNSN.", "...NSNSN..", "...NNNNN.."),
    Pic("Sailboat", ".....W.....", "....WW.....", "...WWW.R...", "..WWWW.RR..", ".WWWWW.RRR.", "WWWWWW.RRRR", ".....N.....", "NNNNNNNNNNN", ".NNNNNNNNN.", "BBBBBBBBBBB", "CBBBCBBBCBB"),
    Pic("Grapes", "....G.L..", "....GL...", "..PPPP...", ".PPKPPP..", ".PPPPKPP.", "..PKPPP..", "..PPPP...", "...PP....", "...P.....")
)

class Hole(val color: Int, val cap: Int) {
    var reserved = 0
    var delivered = 0
    var since = 0L
    var doneAt = 0L
}

class Stored(val color: Int) {
    var arrived = false
    var since = 0L
}

class Carrier(
    val color: Int,
    val path: List<Offset>,
    val pickAt: Int,
    val dropAt: Int,
    val cell: Int,
    val hole: Hole?,
    val slot: Int
) {
    var idx = 1
    var pos = path[0]
    var dir = Offset(1f, 0f)
    var carrying = pickAt == 0
    var dropped = false
    var pause = 0f
    var walked = 0f
    var done = false
}

class Game(val level: Int) {
    val pic = PICS[(level - 1) % PICS.size]
    val w = pic.rows.maxOf { it.length }
    val h = pic.rows.size
    val color = IntArray(w * h) { i -> KEYS.indexOf(pic.rows[i / w].getOrElse(i % w) { '.' }) }
    val present = BooleanArray(w * h) { color[it] >= 0 }
    private val reserved = BooleanArray(w * h)
    val total = present.count { it }
    var collected = 0
        private set

    var slotCount = if (level <= 3) 5 else 4
        private set
    val slots = MutableList<Stored?>(MAX_SLOTS) { null }
    val docks = MutableList<Hole?>(DOCKS) { null }
    val queue = ArrayDeque<Hole>()
    val carriers = mutableListOf<Carrier>()
    var status = 0
        private set
    var rescued = false
        private set
    var now = 0L
        private set
    var refusedAt = -10_000L
        private set
    private var last = 0L
    private var lastStorage = 0L

    val ww = maxOf(w + 1f, 8.5f)
    val bx0 = (ww - w) / 2f
    val roadY = h + 0.8f
    val dockY = h + 2.2f
    val slotY = h + 3.8f
    val worldH = h + 4.6f
    val nest = Offset(0.45f, roadY)

    fun colX(x: Int) = bx0 + x + 0.5f
    fun cellPos(i: Int) = Offset(colX(i % w), i / w + 0.5f)
    fun dockPos(d: Int) = Offset(ww * (d + 0.5f) / DOCKS, dockY)
    fun slotPos(s: Int) = Offset(ww / 2 + (s - (slotCount - 1) / 2f) * 1.3f, slotY)

    init {
        val rnd = Random(level * 7919 + 13)
        val sim = present.copyOf()
        fun low(x: Int): Int {
            for (y in h - 1 downTo 0) if (sim[y * w + x]) return y * w + x
            return -1
        }
        val maxCap = if (level <= 3) 4 else 6
        while (true) {
            val ex = (0 until w).map { low(it) }.filter { it >= 0 }
            if (ex.isEmpty()) break
            val c = color[ex[rnd.nextInt(ex.size)]]
            val target = rnd.nextInt(2, maxCap + 1)
            var k = 0
            while (k < target) {
                val i = (0 until w).map { low(it) }.firstOrNull { it >= 0 && color[it] == c } ?: break
                sim[i] = false
                k++
            }
            queue.addLast(Hole(c, k))
        }
        for (d in 0 until DOCKS) docks[d] = queue.removeFirstOrNull()
    }

    fun exposed(x: Int): Int {
        for (y in h - 1 downTo 0) {
            val i = y * w + x
            if (!present[i]) continue
            return if (reserved[i]) -1 else i
        }
        return -1
    }

    private fun exposedIgnoringReserved(x: Int): Int {
        for (y in h - 1 downTo 0) {
            val i = y * w + x
            if (present[i] && !reserved[i]) return i
        }
        return -1
    }

    fun nextInColumn(x: Int) = exposedIgnoringReserved(x)

    private fun openDock(c: Int) = (0 until DOCKS).firstOrNull { d -> docks[d]?.let { it.color == c && it.reserved < it.cap } == true }

    fun tapColumn(x: Int): Boolean {
        if (status != 0 || x !in 0 until w) return false
        val i = exposedIgnoringReserved(x)
        if (i < 0) return false
        val c = color[i]
        val cx = colX(x)
        val start = listOf(nest, Offset(cx, roadY), cellPos(i), Offset(cx, roadY))
        val d = openDock(c)
        if (d != null) {
            val hole = docks[d]!!
            hole.reserved++
            reserved[i] = true
            val dp = dockPos(d)
            carriers += Carrier(c, start + listOf(Offset(dp.x, roadY), dp, Offset(dp.x, roadY), nest), 2, 5, i, hole, -1)
            return true
        }
        val s = (0 until slotCount).firstOrNull { slots[it] == null }
        if (s == null) {
            refusedAt = now
            return false
        }
        slots[s] = Stored(c)
        reserved[i] = true
        val sp = slotPos(s)
        carriers += Carrier(c, start + listOf(Offset(sp.x, roadY), sp, Offset(sp.x, roadY), nest), 2, 5, i, null, s)
        return true
    }

    fun rescue() {
        if (rescued || slotCount >= MAX_SLOTS) return
        slotCount++
        rescued = true
        status = 0
    }

    private fun arrive(c: Carrier, k: Int) {
        if (k == c.pickAt && !c.carrying) {
            present[c.cell] = false
            c.carrying = true
            c.pause = 0.35f
        }
        if (k == c.dropAt && !c.dropped) {
            c.carrying = false
            c.dropped = true
            c.pause = 0.25f
            val hole = c.hole
            if (hole != null) {
                hole.delivered++
                collected++
            } else {
                slots[c.slot]?.let { it.arrived = true; it.since = now }
            }
        }
        if (k == c.path.lastIndex) c.done = true
    }

    private fun step(c: Carrier, dt: Float) {
        if (c.pause > 0) {
            c.pause -= dt
            return
        }
        var left = SPEED * dt
        while (left > 0 && !c.done) {
            val tgt = c.path[c.idx]
            val dv = tgt - c.pos
            val dist = dv.getDistance()
            if (dist > 0.001f) c.dir = dv / dist
            if (dist <= left) {
                c.pos = tgt
                c.walked += dist
                left -= dist
                arrive(c, c.idx)
                c.idx++
                if (c.pause > 0) break
            } else {
                c.pos += c.dir * left
                c.walked += left
                left = 0f
            }
        }
    }

    fun update(t: Long) {
        val dt = if (last == 0L) 0f else ((t - last) / 1000f).coerceIn(0f, 0.05f)
        last = t
        now = t
        if (status != 0) return
        carriers.forEach { step(it, dt) }
        carriers.removeAll { it.done }

        if (t - lastStorage > 350) {
            for (s in 0 until slotCount) {
                val st = slots[s] ?: continue
                if (!st.arrived) continue
                val d = openDock(st.color) ?: continue
                val hole = docks[d]!!
                hole.reserved++
                slots[s] = null
                val sp = slotPos(s)
                val dp = dockPos(d)
                val mid = (dockY + slotY) / 2
                carriers += Carrier(
                    st.color,
                    listOf(sp, Offset(sp.x, mid), Offset(dp.x, mid), dp, Offset(dp.x, roadY), nest),
                    0, 3, -1, hole, -1
                )
                lastStorage = t
                break
            }
        }

        for (d in 0 until DOCKS) {
            val hole = docks[d] ?: continue
            if (hole.delivered < hole.cap) continue
            if (hole.doneAt == 0L) hole.doneAt = t
            if (t - hole.doneAt > 600) docks[d] = queue.removeFirstOrNull()?.also { it.since = t }
        }

        if (queue.isEmpty() && docks.all { it == null }) {
            status = 1
            return
        }
        val busy = carriers.any { !it.dropped }
        val storageFull = (0 until slotCount).all { slots[it]?.arrived == true }
        if (!busy && storageFull &&
            (0 until slotCount).none { openDock(slots[it]!!.color) != null } &&
            (0 until w).none { x -> exposedIgnoringReserved(x).let { it >= 0 && openDock(color[it]) != null } }
        ) {
            status = 2
        }
    }
}

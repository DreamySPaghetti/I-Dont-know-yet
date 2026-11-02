package com.pixelporters.game

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import kotlin.random.Random

const val SPEED = 2.0f
const val DOCKS = 3
const val MAX_SLOTS = 6

private val PALETTE = linkedMapOf(
    'R' to 0xFFE53935, 'O' to 0xFFFB8C00, 'Y' to 0xFFFDD835, 'G' to 0xFF43A047, 'L' to 0xFF9CCC65,
    'B' to 0xFF1E88E5, 'C' to 0xFF4DD0E1, 'P' to 0xFF8E24AA, 'K' to 0xFFF48FB1, 'W' to 0xFFF5F5F5,
    'N' to 0xFF8D6E63, 'D' to 0xFF455A64, 'S' to 0xFFFFCC80
)
private val KEYS = PALETTE.keys.toList()
val COLORS = PALETTE.values.map { Color(it) }
private val STACK_COLORS = listOf('R', 'Y', 'B', 'G', 'P', 'O', 'C', 'K').map { KEYS.indexOf(it) }

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

class Stored(val color: Int, val load: Int) {
    var arrived = false
    var since = 0L
}

class Ant(
    val color: Int,
    val load: Int,
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
    val n = w * h
    val pixel = IntArray(n) { i -> KEYS.indexOf(pic.rows[i / w].getOrElse(i % w) { '.' }) }
    val stackColor: IntArray
    val height: IntArray
    val present = BooleanArray(n) { true }
    private val reserved = BooleanArray(n)
    var cleared = 0
        private set

    var slotCount = if (level <= 3) 5 else 4
        private set
    val slots = MutableList<Stored?>(MAX_SLOTS) { null }
    val docks = MutableList<Hole?>(DOCKS) { null }
    val queue = ArrayDeque<Hole>()
    val ants = mutableListOf<Ant>()
    var speed = 1f
    var status = 0
        private set
    var rescued = false
        private set
    var now = 0L
        private set
    var refusedAt = -10_000L
        private set
    var blockedAt = -10_000L
        private set
    var blockedCell = -1
        private set
    private var last = 0L
    private var lastStorage = 0L

    val ww = maxOf(w + 3f, 8.5f)
    val bx0 = (ww - w) / 2f
    val roadY = h + 1.35f
    val dockY = h + 2.75f
    val slotY = h + 4.35f
    val worldH = h + 5.1f
    val nest = Offset(0.45f, roadY)
    private val entrance = Offset(bx0 - 0.5f, h + 0.5f)

    fun cellPos(i: Int) = Offset(bx0 + i % w + 0.5f, i / w + 0.5f)
    fun dockPos(d: Int) = Offset(ww * (d + 0.5f) / DOCKS, dockY)
    fun slotPos(s: Int) = Offset(ww / 2 + (s - (slotCount - 1) / 2f) * 1.3f, slotY)

    init {
        val rnd = Random(level * 7919 + 13)
        val colorCount = when {
            level <= 2 -> 3
            level <= 6 -> 4
            level <= 12 -> 5
            else -> 6
        }
        val palette = STACK_COLORS.shuffled(rnd).take(colorCount)
        stackColor = IntArray(n) { palette[rnd.nextInt(palette.size)] }
        height = IntArray(n) { 1 + rnd.nextInt(3) }

        val sim = BooleanArray(n) { true }
        val maxCap = if (level <= 3) 3 else 5
        while (true) {
            val acc = (0 until n).filter { sim[it] && open(it, sim) }
            if (acc.isEmpty()) break
            val c = stackColor[acc[rnd.nextInt(acc.size)]]
            val target = rnd.nextInt(1, maxCap + 1)
            var k = 0
            while (k < target) {
                val cand = (0 until n).filter { sim[it] && stackColor[it] == c && open(it, sim) }
                if (cand.isEmpty()) break
                sim[cand[rnd.nextInt(cand.size)]] = false
                k++
            }
            queue.addLast(Hole(c, k))
        }
        for (d in 0 until DOCKS) docks[d] = queue.removeFirstOrNull()
    }

    private fun open(i: Int, filled: BooleanArray): Boolean {
        val x = i % w
        val y = i / w
        if (x == 0 || y == 0 || x == w - 1 || y == h - 1) return true
        return !filled[i - 1] || !filled[i + 1] || !filled[i - w] || !filled[i + w]
    }

    fun reachable(i: Int) = present[i] && !reserved[i] && open(i, present)
    fun isReserved(i: Int) = reserved[i]

    private fun walk(gx: Int, gy: Int): Boolean {
        if (gx < -1 || gy < -1 || gx > w || gy > h) return false
        if (gx == -1 || gy == -1 || gx == w || gy == h) return true
        return !present[gy * w + gx]
    }

    private fun route(target: Int): List<Offset> {
        val gw = w + 2
        val gh = h + 2
        val prev = IntArray(gw * gh) { -2 }
        val tx = target % w
        val ty = target / w
        fun id(x: Int, y: Int) = (y + 1) * gw + (x + 1)
        val q = ArrayDeque<Int>()
        val s = id(-1, h)
        prev[s] = -1
        q.addLast(s)
        var goal = -1
        while (q.isNotEmpty()) {
            val cur = q.removeFirst()
            val cx = cur % gw - 1
            val cy = cur / gw - 1
            if (kotlin.math.abs(cx - tx) + kotlin.math.abs(cy - ty) == 1) {
                goal = cur
                break
            }
            for ((ddx, ddy) in listOf(0 to -1, 1 to 0, 0 to 1, -1 to 0)) {
                val nx = cx + ddx
                val ny = cy + ddy
                if (!walk(nx, ny)) continue
                val ni = id(nx, ny)
                if (prev[ni] != -2) continue
                prev[ni] = cur
                q.addLast(ni)
            }
        }
        val cells = mutableListOf<Int>()
        var c = goal
        while (c >= 0) {
            cells += c
            c = prev[c]
        }
        cells.reverse()
        val pts = cells.map { Offset(bx0 + (it % gw - 1) + 0.5f, (it / gw - 1) + 0.5f) }
        val out = mutableListOf<Offset>()
        for (j in pts.indices) {
            if (j in 1 until pts.lastIndex) {
                val a = pts[j - 1]
                val b = pts[j]
                val cc = pts[j + 1]
                if ((a.x == b.x && b.x == cc.x) || (a.y == b.y && b.y == cc.y)) continue
            }
            out += pts[j]
        }
        val toward = (cellPos(target) - out.last()) * 0.28f
        out[out.lastIndex] = out.last() + toward
        return out
    }

    private fun openDock(c: Int) = (0 until DOCKS).firstOrNull { d -> docks[d]?.let { it.color == c && it.reserved < it.cap } == true }

    /** 1 = sent, 0 = nothing there, -1 = blocked, -2 = storage full */
    fun tapCell(i: Int): Int {
        if (status != 0 || i !in 0 until n || !present[i] || reserved[i]) return 0
        if (!open(i, present)) {
            blockedAt = now
            blockedCell = i
            return -1
        }
        val c = stackColor[i]
        val d = openDock(c)
        val s = if (d == null) (0 until slotCount).firstOrNull { slots[it] == null } else null
        if (d == null && s == null) {
            refusedAt = now
            return -2
        }
        val r = route(i)
        val path = mutableListOf(nest, Offset(entrance.x, roadY))
        path += r
        val pickAt = path.lastIndex
        path += r.reversed().drop(1)
        path += Offset(entrance.x, roadY)
        reserved[i] = true
        if (d != null) {
            val hole = docks[d]!!
            hole.reserved++
            val dp = dockPos(d)
            path += listOf(Offset(dp.x, roadY), dp)
            val dropAt = path.lastIndex
            path += listOf(Offset(dp.x, roadY), nest)
            ants += Ant(c, height[i], path, pickAt, dropAt, i, hole, -1)
        } else {
            slots[s!!] = Stored(c, height[i])
            val sp = slotPos(s)
            path += listOf(Offset(sp.x, roadY), sp)
            val dropAt = path.lastIndex
            path += listOf(Offset(sp.x, roadY), nest)
            ants += Ant(c, height[i], path, pickAt, dropAt, i, null, s)
        }
        return 1
    }

    fun rescue() {
        if (rescued || slotCount >= MAX_SLOTS) return
        slotCount++
        rescued = true
        status = 0
    }

    private fun arrive(a: Ant, k: Int) {
        if (k == a.pickAt && !a.carrying) {
            present[a.cell] = false
            cleared++
            a.carrying = true
            a.pause = 0.45f
        }
        if (k == a.dropAt && !a.dropped) {
            a.carrying = false
            a.dropped = true
            a.pause = 0.35f
            val hole = a.hole
            if (hole != null) hole.delivered++ else slots[a.slot]?.let { it.arrived = true; it.since = now }
        }
        if (k == a.path.lastIndex) a.done = true
    }

    private fun step(a: Ant, dt: Float) {
        if (a.pause > 0) {
            a.pause -= dt
            return
        }
        var left = SPEED * speed * dt
        while (left > 0 && !a.done) {
            val tgt = a.path[a.idx]
            val dv = tgt - a.pos
            val dist = dv.getDistance()
            if (dist > 0.001f) a.dir = dv / dist
            if (dist <= left) {
                a.pos = tgt
                a.walked += dist
                left -= dist
                arrive(a, a.idx)
                a.idx++
                if (a.pause > 0) break
            } else {
                a.pos += a.dir * left
                a.walked += left
                left = 0f
            }
        }
    }

    fun update(t: Long) {
        val dt = if (last == 0L) 0f else ((t - last) / 1000f).coerceIn(0f, 0.05f) * if (speed > 1f) 1f else 1f
        last = t
        now = t
        if (status != 0) return
        ants.forEach { a -> if (a.pause > 0) a.pause -= dt * (speed - 1f) }
        ants.forEach { step(it, dt) }
        ants.removeAll { it.done }

        if (t - lastStorage > 400 / speed) {
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
                ants += Ant(
                    st.color, st.load,
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
            if (t - hole.doneAt > 650) docks[d] = queue.removeFirstOrNull()?.also { it.since = t }
        }

        if (queue.isEmpty() && docks.all { it == null }) {
            status = 1
            return
        }
        val busy = ants.any { !it.dropped }
        val storageFull = (0 until slotCount).all { slots[it]?.arrived == true }
        if (!busy && storageFull &&
            (0 until slotCount).none { openDock(slots[it]!!.color) != null } &&
            (0 until n).none { reachable(it) && openDock(stackColor[it]) != null }
        ) {
            status = 2
        }
    }
}

package com.pixelporters.game

import java.util.PriorityQueue
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

val CAPS = intArrayOf(5, 10, 15, 20, 25, 30, 50)
const val MAX_SLOTS = 7
const val ANT_MAX = 14

// ---------------- random ----------------
class Rng(seed: Int) {
    private var a = seed
    fun next(): Double {
        a += 0x6D2B79F5
        var t = a
        t = (t xor (t ushr 15)) * (1 or t)
        t = (t + ((t xor (t ushr 7)) * (61 or t))) xor t
        return ((t xor (t ushr 14)).toLong() and 0xffffffffL) / 4294967296.0
    }
    fun int(n: Int) = (next() * n).toInt().coerceIn(0, max(0, n - 1))
    fun <T> pick(l: List<T>) = l[int(l.size)]
}

fun <T> MutableList<T>.shuffleWith(r: () -> Double): MutableList<T> {
    for (i in size - 1 downTo 1) {
        val j = (r() * (i + 1)).toInt().coerceIn(0, i)
        val t = this[i]; this[i] = this[j]; this[j] = t
    }
    return this
}

class Pt(val x: Float, val y: Float)

// ---------------- board ----------------
class Board {
    val W: Int
    val H: Int
    val N: Int
    val nc: Int
    val color: IntArray
    val dist: FloatArray
    private val z: IntArray
    val present: BooleanArray
    val reserved: BooleanArray
    val open: BooleanArray
    private val inHeap: BooleanArray
    private val heaps: Array<PriorityQueue<Int>>
    val left: IntArray
    var total: Int
    var hash: Int
    var ver = 0
    private val cmp: Comparator<Int>

    constructor(W: Int, H: Int, grid: IntArray, nc: Int, nests: List<Pt>) {
        this.W = W; this.H = H; N = W * H; this.nc = nc; color = grid
        dist = FloatArray(N) { i ->
            val x = i % W + .5f
            val y = i / W + .5f
            nests.minOf { hypot(x - it.x, y - it.y) }
        }
        var s = 0x9e3779b9.toInt()
        z = IntArray(N) { s = s xor (s shl 13); s = s xor (s ushr 17); s = s xor (s shl 5); s }
        present = BooleanArray(N) { grid[it] >= 0 }
        reserved = BooleanArray(N); open = BooleanArray(N); inHeap = BooleanArray(N)
        cmp = makeCmp(dist)
        heaps = Array(nc) { PriorityQueue(16, cmp) }
        left = IntArray(nc)
        var t = 0
        for (i in 0 until N) if (grid[i] >= 0) { left[grid[i]]++; t++ }
        total = t; hash = 0
        rebuild()
    }

    private constructor(o: Board) {
        W = o.W; H = o.H; N = o.N; nc = o.nc; color = o.color; dist = o.dist; z = o.z; cmp = o.cmp
        present = o.present.copyOf(); reserved = o.reserved.copyOf(); open = o.open.copyOf(); inHeap = o.inHeap.copyOf()
        heaps = Array(nc) { PriorityQueue(o.heaps[it]) }
        left = o.left.copyOf(); total = o.total; hash = o.hash
    }

    fun clone() = Board(this)

    private fun makeCmp(d: FloatArray) = Comparator<Int> { a, b ->
        val c = d[a].compareTo(d[b]); if (c != 0) c else a.compareTo(b)
    }

    fun border(i: Int): Boolean {
        val x = i % W; val y = i / W
        return x == 0 || y == 0 || x == W - 1 || y == H - 1
    }

    fun adjOpen(i: Int): Boolean {
        val x = i % W
        return (x > 0 && open[i - 1]) || (x < W - 1 && open[i + 1]) || (i >= W && open[i - W]) || (i < N - W && open[i + W])
    }

    fun reachable(i: Int) = present[i] && !reserved[i] && (border(i) || adjOpen(i))

    private fun push(i: Int) { inHeap[i] = true; heaps[color[i]].add(i) }

    fun rebuild() {
        open.fill(false); inHeap.fill(false)
        heaps.forEach { it.clear() }
        val st = ArrayList<Int>()
        for (i in 0 until N) if (!present[i] && border(i)) { open[i] = true; st.add(i) }
        flood(st)
        for (i in 0 until N) if (present[i] && (border(i) || adjOpen(i))) {
            if (reserved[i]) inHeap[i] = true else if (!inHeap[i]) push(i)
        }
        ver++
    }

    private fun flood(st: ArrayList<Int>) {
        while (st.isNotEmpty()) {
            val a = st.removeAt(st.size - 1)
            val x = a % W
            val nb = intArrayOf(if (x > 0) a - 1 else -1, if (x < W - 1) a + 1 else -1, if (a >= W) a - W else -1, if (a < N - W) a + W else -1)
            for (b in nb) {
                if (b < 0) continue
                if (present[b]) {
                    if (!inHeap[b] && !reserved[b]) push(b)
                } else if (!open[b]) {
                    open[b] = true; st.add(b)
                }
            }
        }
    }

    fun eat(i: Int) {
        present[i] = false; reserved[i] = false
        left[color[i]]--; total--; hash = hash xor z[i]
        open[i] = true
        flood(arrayListOf(i)); ver++
    }

    fun pop(c: Int): Int {
        val h = heaps[c]
        while (h.isNotEmpty()) { val t = h.poll()!!; if (present[t] && !reserved[t]) return t }
        return -1
    }

    fun peek(c: Int): Int {
        val h = heaps[c]
        while (h.isNotEmpty()) { val t = h.peek()!!; if (present[t] && !reserved[t]) return t; h.poll() }
        return -1
    }

    fun reserve(t: Int) { reserved[t] = true }
    fun unreserve(t: Int) { reserved[t] = false; if (present[t]) heaps[color[t]].add(t) }

    fun removeColor(c: Int) {
        for (i in 0 until N) if (present[i] && color[i] == c) {
            present[i] = false; reserved[i] = false; left[c]--; total--; hash = hash xor z[i]
        }
        rebuild()
    }
}

// ---------------- procedural pixel art ----------------
fun inTri(px: Double, py: Double, ax: Double, ay: Double, bx: Double, by: Double, cx: Double, cy: Double): Boolean {
    val d1 = (px - bx) * (ay - by) - (ax - bx) * (py - by)
    val d2 = (px - cx) * (by - cy) - (bx - cx) * (py - cy)
    val d3 = (px - ax) * (cy - ay) - (cx - ax) * (py - ay)
    return !((d1 < 0 || d2 < 0 || d3 < 0) && (d1 > 0 || d2 > 0 || d3 > 0))
}

fun ell(u: Double, v: Double, cx: Double, cy: Double, rx: Double, ry: Double, rot: Double = 0.0): Boolean {
    var x = u - cx
    var y = v - cy
    if (rot != 0.0) { val c = cos(rot); val s = sin(rot); val nx = x * c + y * s; y = -x * s + y * c; x = nx }
    return x * x / (rx * rx) + y * y / (ry * ry) <= 1
}

fun shade(hex: String, amt: Double): String {
    val n = hex.substring(1).toInt(16)
    var r = (n shr 16).toDouble(); var g = (n shr 8 and 255).toDouble(); var b = (n and 255).toDouble()
    if (amt < 0) { r *= 1 + amt; g *= 1 + amt; b *= 1 + amt } else { r += (255 - r) * amt; g += (255 - g) * amt; b += (255 - b) * amt }
    return "#%02x%02x%02x".format(r.roundToInt(), g.roundToInt(), b.roundToInt())
}

private fun hyp(a: Double, b: Double) = hypot(a, b)

class Theme(val name: String, val colors: (Rng) -> Map<String, String>, val f: (Double, Double) -> String?)

val THEMES = listOf(
    Theme("Heart", { r -> val m = r.pick(listOf("#e8524a", "#ec6fa8", "#9b6bd6", "#ff8a3d")); mapOf("main" to m, "light" to shade(m, .6), "dark" to shade(m, -.32)) }) { u, v ->
        val x = (u - .5) * 2.6; val y = -(v - .54) * 2.6; val a = x * x + y * y - 1
        when {
            a * a * a - x * x * y * y * y > 0 -> null
            (x + .48) * (x + .48) + (y - .38) * (y - .38) < .07 -> "light"
            x * .55 - y * .85 > .8 -> "dark"
            else -> "main"
        }
    },
    Theme("Fruit", { r -> val m = r.pick(listOf("#e8524a", "#f39c34", "#8cc63f", "#f2c94c")); mapOf("body" to m, "dark" to shade(m, -.28), "light" to shade(m, .7), "stem" to "#7a4b2a", "leaf" to if (m == "#8cc63f") "#2e7d32" else "#43a047") }) { u, v ->
        when {
            u > .46 && u < .55 && v > .08 && v < .3 -> "stem"
            ell(u, v, .67, .19, .14, .06, -.5) -> "leaf"
            !ell(u, v, .5, .6, .37, .35) -> null
            v < .33 && abs(u - .5) < .07 -> null
            ell(u, v, .36, .48, .075, .1) -> "light"
            (u - .5) * .7 + (v - .6) * .7 > .16 -> "dark"
            else -> "body"
        }
    },
    Theme("Kitty", { r ->
        val fur = r.pick(listOf("#f0a35e", "#8d8d99", "#4a4a55", "#e9d8c0"))
        mapOf("fur" to fur, "light" to if (fur == "#e9d8c0") "#ffffff" else shade(fur, .55), "pink" to "#f28fa6", "eye" to r.pick(listOf("#43a047", "#3b82c4", "#f6c443")))
    }) { u, v ->
        fun ear(s: Double) = inTri(u, v, .5 + s * .34, .48, .5 + s * .31, .1, .5 + s * .06, .32)
        fun ein(s: Double) = inTri(u, v, .5 + s * .28, .42, .5 + s * .28, .19, .5 + s * .13, .33)
        when {
            ein(-1.0) || ein(1.0) -> "pink"
            !ell(u, v, .5, .6, .35, .32) && !ear(-1.0) && !ear(1.0) -> null
            ell(u, v, .37, .57, .065, .075) || ell(u, v, .63, .57, .065, .075) -> "eye"
            inTri(u, v, .44, .66, .56, .66, .5, .72) -> "pink"
            ell(u, v, .5, .75, .14, .075) -> "light"
            else -> "fur"
        }
    },
    Theme("Fish", { r -> val b = r.pick(listOf("#f39c34", "#4a90e2", "#e8524a", "#2bb3a3")); mapOf("body" to b, "fin" to shade(b, -.3), "light" to shade(b, .55), "stripe" to "#ffffff", "eye" to "#22223b") }) { u, v ->
        when {
            ell(u, v, .27, .46, .05, .055) -> "eye"
            ell(u, v, .46, .5, .33, .23) -> when {
                u > .4 && u < .62 && abs(((u - .4) * 10) % 1 - .5) > .28 -> "stripe"
                v > .58 -> "light"
                else -> "body"
            }
            inTri(u, v, .72, .5, .97, .24, .97, .76) || inTri(u, v, .33, .3, .6, .31, .48, .14) -> "fin"
            else -> null
        }
    },
    Theme("Flower", { r -> val p = r.pick(listOf("#ec6fa8", "#9b6bd6", "#e8524a", "#f6c443", "#4a90e2")); mapOf("petal" to p, "light" to shade(p, .5), "center" to if (p == "#f6c443") "#8d5a2b" else "#f6c443", "stem" to "#3f9b45", "leaf" to "#7cc85a") }) { u, v ->
        val dx = u - .5; val dy = v - .38; val rr = hyp(dx, dy); val th = atan2(dy, dx)
        val rp = .31 * (.55 + .45 * abs(cos(2.5 * th)))
        when {
            rr < .095 -> "center"
            rr < rp -> if (rr < rp * .55) "light" else "petal"
            abs(u - .5) < .035 && v > .6 && v < .97 -> "stem"
            ell(u, v, .37, .8, .12, .05, .5) || ell(u, v, .63, .74, .12, .05, -.5) -> "leaf"
            else -> null
        }
    },
    Theme("Rocket", { r -> val n = r.pick(listOf("#e8524a", "#4a90e2", "#9b6bd6")); mapOf("body" to "#eef1f5", "nose" to n, "window" to "#4fc3f7", "frame" to "#7d8799", "flame" to "#ff9f43", "flame2" to "#ffe066") }) { u, v ->
        val x = abs(u - .5)
        var res: String? = null
        if (v >= .06 && v < .26 && x < .16 * (v - .06) / .2) res = "nose"
        else if (v >= .26 && v < .74 && x < .16) {
            val d = hyp(u - .5, v - .42)
            res = if (d < .07) "window" else if (d < .1) "frame" else if (v > .56 && v < .62) "nose" else "body"
        } else if (v >= .56 && v < .84 && x >= .16 && x < .16 + .15 * (v - .56) / .28) res = "nose"
        else if (v >= .74 && v < .97) {
            val w = .12 * (1 - (v - .74) / .23)
            res = if (x < w * .5) "flame2" else if (x < w) "flame" else null
        }
        res
    },
    Theme("House", { r -> mapOf("wall" to r.pick(listOf("#f6d7a7", "#e7c9a9", "#cfe3f7")), "roof" to r.pick(listOf("#c0392b", "#3d5a80", "#6d4c41")), "door" to "#7a4b2a", "window" to "#8fd3ff", "chimney" to "#8d6e63", "grass" to "#5cb85c") }) { u, v ->
        when {
            v >= .88 && v < .96 && u > .06 && u < .94 -> "grass"
            u > .62 && u < .72 && v > .15 && v < .36 -> "chimney"
            v >= .16 && v < .47 && abs(u - .5) < .41 * (v - .16) / .31 + .02 -> "roof"
            u >= .19 && u < .81 && v >= .47 && v < .88 -> when {
                u > .43 && u < .57 && v > .65 -> "door"
                v > .54 && v < .67 && ((u > .25 && u < .38) || (u > .62 && u < .75)) -> "window"
                else -> "wall"
            }
            else -> null
        }
    },
    Theme("Mushroom", { r -> mapOf("cap" to r.pick(listOf("#e8524a", "#9b6bd6", "#f39c34")), "spot" to "#ffffff", "stem" to "#f3e3c3", "gill" to "#cdb892", "grass" to "#5cb85c") }) { u, v ->
        when {
            v >= .86 && v < .94 && u > .1 && u < .9 -> "grass"
            v < .5 && ell(u, v, .5, .5, .43, .37) ->
                if (hyp(u - .35, v - .31) < .075 || hyp(u - .6, v - .23) < .065 || hyp(u - .73, v - .4) < .055 || hyp(u - .24, v - .44) < .045) "spot" else "cap"
            v >= .5 && v < .55 && abs(u - .5) < .37 -> "gill"
            v >= .55 && v < .86 && abs(u - .5) < .13 + .03 * (v - .55) / .31 -> "stem"
            else -> null
        }
    },
    Theme("Tree", { r -> mapOf("leaf" to r.pick(listOf("#43a047", "#2e7d32", "#7cb342")), "light" to "#a5d66f", "fruit" to r.pick(listOf("#e8524a", "#f6c443", "#ec6fa8")), "trunk" to "#7a4b2a", "grass" to "#5cb85c") }) { u, v ->
        val c = hyp(u - .5, v - .34) < .26 || hyp(u - .31, v - .47) < .2 || hyp(u - .69, v - .47) < .2
        when {
            v >= .88 && v < .96 && u > .13 && u < .87 -> "grass"
            c -> when {
                hyp(u - .4, v - .3) < .04 || hyp(u - .62, v - .38) < .04 || hyp(u - .45, v - .53) < .04 || hyp(u - .71, v - .56) < .035 -> "fruit"
                hyp(u - .42, v - .23) < .085 -> "light"
                else -> "leaf"
            }
            u > .44 && u < .56 && v >= .55 && v < .88 -> "trunk"
            else -> null
        }
    }
)
private val RING_COLORS = listOf("#5b4636", "#2f3e46", "#7d5ba6", "#3a6b35")

class Art(var W: Int, var H: Int, var grid: IntArray, var pal: MutableList<String>, val name: String)

fun makeArt(theme: Theme, S: Int, r: Rng): Art {
    val cm = theme.colors(r)
    val pal = mutableListOf<String>()
    val idx = HashMap<String, Int>()
    val grid = IntArray(S * S) { -1 }
    for (y in 0 until S) for (x in 0 until S) {
        val role = theme.f((x + .5) / S, (y + .5) / S) ?: continue
        val hex = cm[role] ?: continue
        val k = idx.getOrPut(hex) { pal.add(hex); pal.size - 1 }
        grid[y * S + x] = k
    }
    return Art(S, S, grid, pal, theme.name)
}

fun nbrs(i: Int, W: Int, H: Int): IntArray {
    val x = i % W; val y = i / W
    val o = IntArray(4); var n = 0
    if (x > 0) o[n++] = i - 1
    if (x < W - 1) o[n++] = i + 1
    if (y > 0) o[n++] = i - W
    if (y < H - 1) o[n++] = i + W
    return o.copyOf(n)
}

fun counts(a: Art): IntArray { val c = IntArray(a.pal.size); for (v in a.grid) if (v >= 0) c[v]++; return c }

fun compact(a: Art) {
    val c = counts(a); val map = IntArray(a.pal.size) { -1 }; val pal = mutableListOf<String>()
    a.pal.forEachIndexed { i, h -> if (c[i] > 0) { map[i] = pal.size; pal.add(h) } }
    for (i in a.grid.indices) if (a.grid[i] >= 0) a.grid[i] = map[a.grid[i]]
    a.pal = pal
}

fun mergeInto(a: Art, c: Int) {
    val g = a.grid; val src = g.copyOf(); val votes = IntArray(a.pal.size)
    for (i in g.indices) if (src[i] == c) for (j in nbrs(i, a.W, a.H)) if (src[j] >= 0 && src[j] != c) votes[src[j]]++
    var best = -1; var bv = 0
    votes.forEachIndexed { k, v -> if (v > bv) { bv = v; best = k } }
    for (i in g.indices) if (src[i] == c) g[i] = best
}

fun mergeSmall(a: Art, minN: Int) {
    for (pass in 0 until 4) {
        val c = counts(a); var changed = false
        c.forEachIndexed { k, n -> if (n in 1 until minN) { mergeInto(a, k); changed = true } }
        if (!changed) break
    }
    compact(a)
}

fun capColors(a: Art, maxC: Int) {
    while (true) {
        compact(a)
        val c = counts(a)
        if (c.size <= maxC) return
        var k = 0
        c.forEachIndexed { i, n -> if (n < c[k]) k = i }
        mergeInto(a, k)
    }
}

class Depths(val cd: IntArray, val d: IntArray, val maxD: Int)

fun colorDepths(a: Art): Depths {
    val W = a.W; val H = a.H; val grid = a.grid; val N = W * H; val INF = 1_000_000_000
    val d = IntArray(N) { INF }; val ctx = IntArray(N) { -1 }
    var cur = ArrayList<Int>(); var next = ArrayList<Int>()
    for (i in 0 until N) {
        val x = i % W; val y = i / W
        if (x == 0 || y == 0 || x == W - 1 || y == H - 1) { d[i] = 0; ctx[i] = if (grid[i] >= 0) grid[i] else -1; cur.add(i) }
    }
    var layer = 0
    while (cur.isNotEmpty()) {
        var q = 0
        while (q < cur.size) {
            val ai = cur[q]; q++
            if (d[ai] != layer) continue
            val ca = if (grid[ai] >= 0) grid[ai] else ctx[ai]
            for (b in nbrs(ai, W, H)) {
                val cost: Int; val cb: Int
                if (grid[b] < 0) { cost = 0; cb = ca } else { cost = if (ca == -1 || ca == grid[b]) 0 else 1; cb = grid[b] }
                val nd = layer + cost
                if (nd < d[b]) { d[b] = nd; ctx[b] = cb; if (cost == 1) next.add(b) else cur.add(b) }
            }
        }
        cur = next; next = ArrayList(); layer++
    }
    val cd = IntArray(a.pal.size) { INF }; var maxD = 0
    for (i in 0 until N) if (grid[i] >= 0 && d[i] < cd[grid[i]]) cd[grid[i]] = d[i]
    for (v in cd) if (v < INF && v > maxD) maxD = v
    return Depths(cd, d, maxD)
}

/** Depth limit applies per tile: any tile buried deeper than [limit] layers is recoloured to a shallower neighbour. */
fun reduceDepth(a: Art, limit: Int) {
    for (pass in 0 until 30) {
        val dd = colorDepths(a)
        var deepest = 0
        for (i in a.grid.indices) if (a.grid[i] >= 0 && dd.d[i] > deepest) deepest = dd.d[i]
        if (deepest <= limit) return
        val g = a.grid; val src = g.copyOf()
        for (i in g.indices) {
            if (src[i] < 0 || dd.d[i] <= limit) continue
            var best = -1; var bd = dd.d[i]
            for (j in nbrs(i, a.W, a.H)) if (src[j] >= 0 && dd.d[j] < bd) { bd = dd.d[j]; best = src[j] }
            if (best >= 0) g[i] = best
        }
    }
}

fun addRings(a: Art, k: Int) {
    if (k <= 0) return
    val W = a.W; val H = a.H; val grid = a.grid; val N = W * H
    val layer = IntArray(N) { -1 }; val q = ArrayList<Int>()
    for (i in 0 until N) {
        if (grid[i] < 0) continue
        val x = i % W; val y = i / W
        if (x == 0 || y == 0 || x == W - 1 || y == H - 1 || nbrs(i, W, H).any { grid[it] < 0 }) { layer[i] = 0; q.add(i) }
    }
    var h = 0
    while (h < q.size) { val ai = q[h++]; for (b in nbrs(ai, W, H)) if (grid[b] >= 0 && layer[b] < 0) { layer[b] = layer[ai] + 1; q.add(b) } }
    for (j in 0 until k) {
        val hex = RING_COLORS[j]
        var ci = a.pal.indexOf(hex)
        if (ci < 0) { ci = a.pal.size; a.pal.add(hex) }
        for (i in 0 until N) if (layer[i] == j) grid[i] = ci
    }
}

fun fix5(a: Art, r: Rng) {
    val W = a.W; val H = a.H; val grid = a.grid; val c = counts(a)
    c.forEachIndexed { k, n ->
        val rem = n % 5
        if (rem == 0) return@forEachIndexed
        val tiles = ArrayList<Pair<Int, Double>>()
        for (i in grid.indices) if (grid[i] == k) {
            val x = i % W; val y = i / W
            var e = if (x == 0 || y == 0 || x == W - 1 || y == H - 1) 2.0 else 0.0
            for (j in nbrs(i, W, H)) if (grid[j] < 0) e++
            tiles.add(i to e + r.next() * .5)
        }
        tiles.sortByDescending { it.second }
        for (t in 0 until rem) grid[tiles[t].first] = -1
    }
    compact(a)
}

fun crop(a: Art) {
    var x0 = a.W; var y0 = a.H; var x1 = -1; var y1 = -1
    for (i in 0 until a.W * a.H) if (a.grid[i] >= 0) {
        val x = i % a.W; val y = i / a.W
        x0 = min(x0, x); x1 = max(x1, x); y0 = min(y0, y); y1 = max(y1, y)
    }
    val nW = x1 - x0 + 1; val nH = y1 - y0 + 1
    val ng = IntArray(nW * nH)
    for (y in 0 until nH) for (x in 0 until nW) ng[y * nW + x] = a.grid[(y + y0) * a.W + x + x0]
    a.W = nW; a.H = nH; a.grid = ng
}

// ---------------- colour distinctness (CIE76) ----------------
private val labCache = java.util.concurrent.ConcurrentHashMap<String, DoubleArray>()
fun lab(hex: String): DoubleArray = labCache.getOrPut(hex) { labOf(hex) }
private fun labOf(hex: String): DoubleArray {
    val n = hex.substring(1).toInt(16)
    fun lin(c: Int): Double { val v = c / 255.0; return if (v <= .04045) v / 12.92 else Math.pow((v + .055) / 1.055, 2.4) }
    val r = lin(n shr 16); val g = lin(n shr 8 and 255); val b = lin(n and 255)
    val x = (.4124 * r + .3576 * g + .1805 * b) / .95047
    val y = .2126 * r + .7152 * g + .0722 * b
    val z = (.0193 * r + .1192 * g + .9505 * b) / 1.08883
    fun f(t: Double) = if (t > .008856) Math.cbrt(t) else 7.787 * t + 16.0 / 116
    val fx = f(x); val fy = f(y); val fz = f(z)
    return doubleArrayOf(116 * fy - 16, 500 * (fx - fy), 200 * (fy - fz))
}

fun labToHex(l: DoubleArray): String {
    val fy = (l[0] + 16) / 116; val fx = fy + l[1] / 500; val fz = fy - l[2] / 200
    fun inv(t: Double) = if (t * t * t > .008856) t * t * t else (t - 16.0 / 116) / 7.787
    val x = inv(fx) * .95047; val y = inv(fy); val z = inv(fz) * 1.08883
    val r = 3.2406 * x - 1.5372 * y - .4986 * z
    val g = -.9689 * x + 1.8758 * y + .0415 * z
    val b = .0557 * x - .2040 * y + 1.0570 * z
    fun gam(c: Double): Int { val v = if (c <= .0031308) 12.92 * c else 1.055 * Math.pow(c, 1 / 2.4) - .055; return (v * 255).roundToInt().coerceIn(0, 255) }
    return "#%02x%02x%02x".format(gam(r), gam(g), gam(b))
}

fun deltaE(a: String, b: String): Double {
    val p = lab(a); val q = lab(b)
    return Math.sqrt((p[0] - q[0]) * (p[0] - q[0]) + (p[1] - q[1]) * (p[1] - q[1]) + (p[2] - q[2]) * (p[2] - q[2]))
}

private val RESERVE = listOf(
    "#e8524a", "#f6c443", "#4a90e2", "#5cb85c", "#9b6bd6", "#f39c34", "#ec6fa8", "#2bb3a3",
    "#7a4b2a", "#2f3e46", "#ffffff", "#8cc63f", "#4fc3f7", "#c0392b", "#ffb3c7", "#1f4e8c"
)

/** Recolours (or merges) the rarer of any two colours closer than deltaE 20. */
fun makeDistinct(a: Art) {
    var guard = 0
    while (guard++ < 40) {
        compact(a)
        val c = counts(a)
        var hit = -1
        loop@ for (i in a.pal.indices) for (j in i + 1 until a.pal.size) if (deltaE(a.pal[i], a.pal[j]) < 20) { hit = if (c[i] < c[j]) i else j; break@loop }
        if (hit < 0) return
        val rep = RESERVE.firstOrNull { cand -> a.pal.indices.all { it == hit || deltaE(cand, a.pal[it]) >= 20 } }
        if (rep != null) a.pal[hit] = rep else mergeInto(a, hit)
    }
}

/** Adds a new colour by splitting a large region into two bands (no depth change). */
fun addColor(a: Art): Boolean {
    val c = counts(a)
    var k = 0
    c.forEachIndexed { i, n -> if (n > c[k]) k = i }
    if (c[k] < 20) return false
    val rep = RESERVE.firstOrNull { cand -> a.pal.all { deltaE(cand, it) >= 20 } } ?: return false
    val ys = a.grid.indices.filter { a.grid[it] == k }.map { it / a.W }.sorted()
    val cut = ys[ys.size / 2]
    val ni = a.pal.size; a.pal.add(rep)
    for (i in a.grid.indices) if (a.grid[i] == k && i / a.W > cut) a.grid[i] = ni
    return true
}

/** Split depth: paints part of the outermost ring with the most deeply buried colour, so one colour lives both outside and inside. */
fun splitRing(a: Art, r: Rng): Int {
    val dd = colorDepths(a)
    var deep = -1; var bd = -1
    dd.cd.forEachIndexed { k, v -> if (v in 1..999 && v > bd) { bd = v; deep = k } }
    if (deep < 0) return -1
    val W = a.W; val H = a.H; val g = a.grid
    var cx = 0.0; var cy = 0.0; var n = 0
    for (i in g.indices) if (g[i] >= 0) { cx += i % W; cy += i / W; n++ }
    cx /= n; cy /= n
    val phase = r.next() * 6.28
    val freq = 2 + r.int(3)
    for (i in g.indices) {
        if (g[i] < 0 || g[i] == deep) continue
        val x = i % W; val y = i / W
        val edge = x == 0 || y == 0 || x == W - 1 || y == H - 1 || nbrs(i, W, H).any { g[it] < 0 }
        if (!edge) continue
        if (sin(Math.atan2(y - cy, x - cx) * freq + phase) > .15) g[i] = deep
    }
    return deep
}

class ArtOut(val art: Art, val split: Set<Int>)

fun colorRange(L: Int, tag: String = "Normal") = if (L <= 20) (if (tag == "Normal") 3..5 else 4..5) else if (L <= 100) 4..5 else 5..8

private val BACKDROPS = listOf("#cfe8f7", "#f3e7c9", "#d6efd0", "#e6dcf5", "#fbe0d4", "#d9e4ea")
private val GROUNDS = listOf("#8bc97a", "#c9a978", "#7fb0d9", "#b9a2d6")

/** The board is a solid rectangle: empty background cells become backdrop tiles (sky above, ground below on bigger boards). */
fun fillBackground(a: Art, L: Int, r: Rng) {
    val sky = r.pick(BACKDROPS)
    var si = a.pal.indexOf(sky); if (si < 0) { si = a.pal.size; a.pal.add(sky) }
    var gi = -1
    if (L > 12 && r.next() < .7) {
        val ground = r.pick(GROUNDS)
        gi = a.pal.indexOf(ground); if (gi < 0) { gi = a.pal.size; a.pal.add(ground) }
    }
    val cut = (a.H * (.68 + r.next() * .14)).toInt()
    for (i in a.grid.indices) if (a.grid[i] < 0) a.grid[i] = if (gi >= 0 && i / a.W >= cut) gi else si
}

fun buildArt(theme: Theme, S: Int, L: Int, tag: String, r: Rng): ArtOut? {
    val a = makeArt(theme, S, r)
    fillBackground(a, L, r)
    val limit = if (L <= 20) 1 else if (L <= 100) 2 else 4
    val minDepth = if (L > 100) 3 else 0
    mergeSmall(a, 5)
    val base = colorDepths(a).maxD
    val ringsWanted = if (tag == "Super Hard") 2 else if (tag == "Hard") 1 else if (L > 20) 1 else 0
    var rings = max(ringsWanted, minDepth - base)
    rings = max(0, min(min(rings, limit - base), 3))
    addRings(a, rings)
    val range = colorRange(L, tag)
    var guard = 0
    while (counts(a).count { it > 0 } < range.first && guard++ < 6) if (!addColor(a)) break
    if (L >= 4 && (tag != "Normal" || L > 12 || r.next() < .5)) splitRing(a, r)
    reduceDepth(a, limit)
    mergeSmall(a, 5)
    capColors(a, range.last)
    makeDistinct(a)
    fix5(a, r)
    if (a.pal.isEmpty()) return null
    crop(a)
    // colours that appear at more than one burying depth
    val dd = colorDepths(a)
    val lo = IntArray(a.pal.size) { Int.MAX_VALUE }; val hi = IntArray(a.pal.size) { -1 }
    for (i in a.grid.indices) { val c = a.grid[i]; if (c < 0) continue; lo[c] = min(lo[c], dd.d[i]); hi[c] = max(hi[c], dd.d[i]) }
    val split = a.pal.indices.filter { hi[it] > lo[it] }.toSet()
    return ArtOut(a, split)
}

// ---------------- cubes ----------------
open class CubeDef(val id: Int, val color: Int, val cap: Int, var hidden: Boolean = false, var lockedBy: Int? = null, var ropeFrom: Int? = null)

class Level(
    val L: Int, val tag: String, val name: String, val W: Int, val H: Int, val grid: IntArray,
    val palette: List<String>, val nests: List<Pt>, val cols: List<List<CubeDef>>, val solution: List<Int>?, val hint: String?,
    /** Id of a player-made puzzle, null for campaign levels. */
    val custom: String? = null
)

fun splitCubes(a: Art, L: Int, split: Set<Int>, r: Rng, small: Boolean = false): List<Pair<Int, Int>> {
    val c = counts(a); val out = ArrayList<Pair<Int, Int>>()
    val total = c.sum()
    var base = if (L < 10) 10 else if (L < 30) 15 else if (L < 60) 20 else 25
    if (small) base = min(base, max(5, (total / 22 / 5) * 5))
    c.forEachIndexed { color, n ->
        var rem = n
        var first = true
        while (rem > 0) {
            val target = if (color in split && first) (if (small && total < 260) 10 else max(base, 30)) else base
            val opts = CAPS.filter { it <= rem && (!small || total >= 260 || it <= 10) }
            val w = opts.map { 1.0 / (1 + abs(it - target) / 5.0) }
            var t = r.next() * w.sum(); var pick = opts[0]
            for (k in opts.indices) { t -= w[k]; if (t <= 0) { pick = opts[k]; break } }
            out.add(color to pick); rem -= pick; first = false
        }
    }
    return out
}

/**
 * Depth deal: each cube is ordered by its colour's burying depth, but with probability [p] the order is reversed
 * for that cube, so deeply buried colours end up in front. Every such cube must be tapped and held in a slot before
 * the cubes behind it are reachable; raising p raises the number of slots a level needs.
 */
fun depthDeal(cubes: List<Pair<Int, Int>>, cd: IntArray, split: Set<Int>, nCols: Int, r: Rng, p: Double): List<MutableList<CubeDef>> {
    val keyed = cubes.map { c ->
        val d = cd[c.first].toDouble() + (if (c.first in split) .5 else 0.0)
        val k = if (r.next() < p) -d - (if (c.second >= 25) .4 else 0.0) else d
        c to k + r.next() * .9
    }.sortedBy { it.second }.map { it.first }
    val cols = MutableList(nCols) { mutableListOf<CubeDef>() }
    keyed.forEachIndexed { i, (color, cap) -> cols[if (r.next() < .6) i % nCols else r.int(nCols)].add(CubeDef(i, color, cap)) }
    for (i in 0 until nCols) if (cols[i].isEmpty()) {
        var b = 0
        cols.forEachIndexed { j, c -> if (c.size > cols[b].size) b = j }
        if (cols[b].size > 1) cols[i].add(cols[b].removeAt(cols[b].size - 1))
    }
    return cols.filter { it.isNotEmpty() }
}

private class Ref(val c: CubeDef, val ci: Int, val k: Int)

fun applySpecials(cols: List<MutableList<CubeDef>>, L: Int, r: Rng) {
    val all = ArrayList<Ref>()
    cols.forEachIndexed { ci, col -> col.forEachIndexed { k, c -> all.add(Ref(c, ci, k)) } }
    if (L >= 8) for (o in all) if (o.k > 0 && r.next() < .22) o.c.hidden = true
    if (L >= 12) {
        var n = min(4, 1 + L / 40)
        val cand = all.filter { it.k > 0 }.toMutableList().shuffleWith(r::next)
        for (o in cand) { if (n <= 0) break; o.c.lockedBy = cols[o.ci][o.k - 1].id; n-- }
    }
    if (L >= 100) {
        var n = if (L >= 200) 2 else 1
        val cand = all.filter { it.k > 0 && it.c.lockedBy == null }.toMutableList().shuffleWith(r::next)
        for (b in cand) {
            if (n <= 0) break
            val a = all.find { o -> o.ci != b.ci && o.c.id < b.c.id && o.c.ropeFrom == null && all.none { x -> x.c.ropeFrom == o.c.id } }
            if (a != null && all.none { x -> x.c.ropeFrom == b.c.id }) { b.c.ropeFrom = a.c.id; n-- }
        }
    }
}

// ---------------- solver ----------------
class SolveResult(val path: List<Int>, val nodes: Int)

object SolverStats { @JvmField var nodes = 0L }
class SSlot(val cube: CubeDef, var rem: Int)
class SState(val board: Board, val ptr: IntArray, val slots: MutableList<SSlot>, val status: ByteArray)

fun cubeCount(cols: List<List<CubeDef>>): Int { var n = 0; for (c in cols) for (x in c) n = max(n, x.id + 1); return n }

fun solveState(st0: SState, cols: List<List<CubeDef>>, slotCount: Int, nodeLimit: Int): SolveResult? {
    var nodes = 0
    val memo = HashSet<String>()
    val path = ArrayList<Int>()
    fun fix(st: SState) {
        var prog = true
        while (prog) {
            prog = false
            for (s in st.slots) while (s.rem > 0) {
                val t = st.board.pop(s.cube.color); if (t < 0) break
                st.board.eat(t); s.rem--; prog = true
            }
            for (k in st.slots.size - 1 downTo 0) if (st.slots[k].rem == 0) { st.status[st.slots[k].cube.id] = 2; st.slots.removeAt(k); prog = true }
        }
    }
    fun tappable(st: SState, ci: Int): CubeDef? {
        val col = cols[ci]; if (st.ptr[ci] >= col.size) return null
        val c = col[st.ptr[ci]]
        val lb = c.lockedBy; if (lb != null && st.status[lb].toInt() != 2) return null
        val rf = c.ropeFrom; if (rf != null && st.status[rf].toInt() == 0) return null
        return c
    }
    // 1 = solved, 0 = dead end, -1 = node budget exhausted
    fun dfs(st: SState): Int {
        if (st.board.total == 0) return 1
        if (++nodes > nodeLimit) return -1
        if (st.slots.size >= slotCount) return 0
        val key = st.ptr.joinToString(",") + "|" + st.slots.map { "${it.cube.id}:${it.rem}" }.sorted().joinToString(",") + "|" + st.board.hash
        if (!memo.add(key)) return 0
        val cand = ArrayList<Triple<Int, CubeDef, Int>>()
        for (ci in cols.indices) {
            val c = tappable(st, ci) ?: continue
            cand.add(Triple(ci, c, (if (st.board.peek(c.color) >= 0) 0 else 2) + (if (st.slots.any { it.cube.color == c.color }) 1 else 0)))
        }
        cand.sortWith(compareBy<Triple<Int, CubeDef, Int>> { it.third }.thenBy { it.second.cap })
        for ((ci, c) in cand) {
            val ns = SState(st.board.clone(), st.ptr.copyOf(), st.slots.map { SSlot(it.cube, it.rem) }.toMutableList(), st.status.copyOf())
            ns.ptr[ci]++; ns.status[c.id] = 1; ns.slots.add(SSlot(c, c.cap))
            fix(ns); path.add(ci)
            val r = dfs(ns)
            if (r != 0) { if (r < 0) return -1; return 1 }
            path.removeAt(path.size - 1)
        }
        return 0
    }
    fix(st0)
    val res = dfs(st0)
    SolverStats.nodes += nodes
    return if (res == 1) SolveResult(path.toList(), nodes) else null
}

fun solveLevel(board: Board, cols: List<List<CubeDef>>, slotCount: Int, nodeLimit: Int): SolveResult? {
    val st = SState(board.clone(), IntArray(cols.size), mutableListOf(), ByteArray(cubeCount(cols)))
    return solveState(st, cols, slotCount, nodeLimit)
}

// ---------------- levels ----------------
fun difficulty(L: Int) = if (L % 10 == 0) "Super Hard" else if (L % 5 == 0) "Hard" else "Normal"

fun nestsFor(W: Int, H: Int, L: Int): List<Pt> {
    val y = H + .5f + .03f * max(W, H)
    return if (L >= 18) listOf(Pt(W * .25f, y), Pt(W * .75f, y)) else listOf(Pt(W / 2f, y))
}

private class Tut(val name: String, val pal: List<String>, val rows: List<String>, val key: String, val cols: List<List<Pair<Char, Int>>>, val hint: String)

fun tutorialLevel(L: Int): Level {
    val t = when (L) {
        1 -> Tut("Heart", listOf("#e8524a"), listOf(".RR..RR.", "RRRRRRRR", "RRRRRRRR", "RRRRRRRR", ".RRRRRR.", "..RRRR..", "...RR..."), "R",
            listOf(listOf('R' to 10, 'R' to 10), listOf('R' to 10), listOf('R' to 10)), "Tap a cube in the tray. Its ants carry tiles of that colour home.")
        2 -> Tut("Apple", listOf("#5cb85c", "#e8524a"), listOf("....GG..", "...GGG..", ".RRRRRR.", "RRRRRRRR", "RRRRRRRR", "RRRRRRRR", ".RRRRRR.", "..RRRR.."), "GR",
            listOf(listOf('G' to 5, 'R' to 10), listOf('R' to 20), listOf('R' to 10)), "The number on a cube is how many tiles its ants will carry.")
        else -> Tut("Sunny Pond", listOf("#4a90e2", "#f6c443"), listOf(".BBBBBB.", ".BYYYYB.", ".BYYYYB.", ".BYYYYB.", ".BYYYB..", ".BBBBBB."), "BY",
            listOf(listOf('Y' to 15), listOf('B' to 10), listOf('B' to 10)), "Ants can only reach tiles touching open ground. Clear the blue to reach the yellow!")
    }
    val W = t.rows[0].length; val H = t.rows.size
    val grid = IntArray(W * H) { -1 }
    t.rows.forEachIndexed { y, row -> row.forEachIndexed { x, ch -> val k = t.key.indexOf(ch); if (k >= 0) grid[y * W + x] = k } }
    var id = 0
    val cols = t.cols.map { col -> col.map { (ch, cap) -> CubeDef(id++, t.key.indexOf(ch), cap) } }
    val nests = nestsFor(W, H, L)
    val sol = solveLevel(Board(W, H, grid, t.pal.size, nests), cols, 5, 20000)
    return Level(L, "Normal", t.name, W, H, grid, t.pal, nests, cols, sol?.path, t.hint)
}

fun levelHint(L: Int): String? = when (L) {
    8 -> "\"?\" cubes reveal their colour when they land in a slot."
    12 -> "Locked cubes open once the cube in front of them has finished."
    18 -> "A second nest hole! Ants use whichever hole is closer to their tile."
    100 -> "Roped cubes must be taken in order."
    else -> null
}

// ---------------- difficulty measurement ----------------
const val GEN_NODES = 4000

/** One naive game in the instant-eat model: tap a random tappable cube whose colour is reachable now (else any tappable). */
fun naivePlay(board0: Board, cols: List<List<CubeDef>>, slotCount: Int, r: Rng): Boolean {
    val st = SState(board0.clone(), IntArray(cols.size), mutableListOf(), ByteArray(cubeCount(cols)))
    fun fix() {
        var prog = true
        while (prog) {
            prog = false
            for (s in st.slots) while (s.rem > 0) { val t = st.board.pop(s.cube.color); if (t < 0) break; st.board.eat(t); s.rem--; prog = true }
            for (k in st.slots.size - 1 downTo 0) if (st.slots[k].rem == 0) { st.status[st.slots[k].cube.id] = 2; st.slots.removeAt(k); prog = true }
        }
    }
    fix()
    while (true) {
        if (st.board.total == 0) return true
        if (st.slots.size >= slotCount) return false
        val cand = ArrayList<Int>()
        for (ci in cols.indices) {
            val col = cols[ci]; if (st.ptr[ci] >= col.size) continue
            val c = col[st.ptr[ci]]
            val lb = c.lockedBy; if (lb != null && st.status[lb].toInt() != 2) continue
            val rf = c.ropeFrom; if (rf != null && st.status[rf].toInt() == 0) continue
            cand.add(ci)
        }
        if (cand.isEmpty()) return false
        val ringed = cand.filter { val c = cols[it][st.ptr[it]]; !c.hidden && st.board.peek(c.color) >= 0 }
        val ci = r.pick(if (ringed.isNotEmpty()) ringed else cand)
        val c = cols[ci][st.ptr[ci]]
        st.ptr[ci]++; st.status[c.id] = 1; st.slots.add(SSlot(c, c.cap))
        fix()
    }
}

fun naiveRate(board: Board, cols: List<List<CubeDef>>, runs: Int, seed: Int): Double {
    val r = Rng(seed); var w = 0
    repeat(runs) { if (naivePlay(board, cols, 5, r)) w++ }
    return w.toDouble() / runs
}

fun naiveWinRate(lv: Level, runs: Int = 20): Double = naiveRate(Board(lv.W, lv.H, lv.grid, lv.palette.size, lv.nests), lv.cols, runs, lv.L * 7 + 1)

/** Smallest slot count (2-5) the solver can win with inside its node budget; 6 if none. */
fun minSlots(lv: Level, nodes: Int = GEN_NODES): Int {
    val b = Board(lv.W, lv.H, lv.grid, lv.palette.size, lv.nests)
    for (k in 2..5) if (solveLevel(b, lv.cols, k, nodes) != null) return k
    return 6
}

class Band(val maxMin: Int, val minMin: Int, val naiveMax: Double) {
    fun ok(ms: Int, naive: Double) = ms in minMin..maxMin && naive <= naiveMax + 1e-9
}

fun bandFor(L: Int, tag: String): Band = when {
    L <= 3 -> Band(5, 2, 1.0)
    L <= 20 -> if (tag == "Normal") Band(4, 2, 1.0) else Band(5, 5, 1.0)
    L <= 100 -> when (tag) { "Normal" -> Band(5, 4, .85); "Hard" -> Band(5, 5, .5); else -> Band(5, 5, .25) }
    else -> when (tag) { "Normal" -> Band(5, 4, .7); "Hard" -> Band(5, 5, .35); else -> Band(5, 5, .15) }
}

private class Cand(val cols: List<List<CubeDef>>, val sol: List<Int>, val score: Double, val art: Art, val theme: String, val nests: List<Pt>)

/** At the level start, is some white-ringed front cube not among the first two taps of the solution? */
private fun hasDecoy(board: Board, cols: List<List<CubeDef>>, sol: List<Int>): Boolean {
    val firstTaps = sol.take(2).toSet()
    val b = board.clone()
    return cols.indices.any { ci -> val c = cols[ci][0]; c.lockedBy == null && c.ropeFrom == null && !c.hidden && b.peek(c.color) >= 0 && ci !in firstTaps }
}

private val cache = HashMap<Int, Level>()
private val cacheLock = Any()

/** Deterministic per level number; cached. Safe to call from a background thread. */
fun genLevel(L: Int): Level {
    synchronized(cacheLock) { cache[L]?.let { return it } }
    val lv = if (L <= 3) tutorialLevel(L) else generate(L)
    synchronized(cacheLock) { cache[L] = lv; if (cache.size > 12) cache.keys.filter { it < L - 3 }.forEach { cache.remove(it) } }
    return lv
}

/** Searches deals for one finished picture and keeps the one closest to [band]. */
private fun searchDeals(
    art: Art, split: Set<Int>, L: Int, tag: String, band: Band, nCols: Int, r: Rng, name: String,
    startNodes: Long, budget: Long, bestIn: Cand?
): Cand? {
    var best = bestIn
    val cubes = splitCubes(art, L, split, r, band.minMin >= 5)
    val cd = colorDepths(art).cd
    val nests = nestsFor(art.W, art.H, L)
    val board = Board(art.W, art.H, art.grid, art.pal.size, nests)
    var p = if (band.minMin >= 5) .55 else if (band.minMin == 4) .35 else .1
    for (d in 0 until 300) {
        if (SolverStats.nodes - startNodes > budget && best != null) break
        val cols = depthDeal(cubes, cd, split, nCols, r, (p + (r.next() - .5) * .16).coerceIn(0.0, 1.0))
        applySpecials(cols, L, r)
        var naive = 0.0
        if (band.naiveMax < 1.0) {
            val first = naiveRate(board, cols, 5, L * 131 + d)
            naive = if (first - band.naiveMax > .45) first else (first * 5 + naiveRate(board, cols, 15, L * 137 + d) * 15) / 20
        }
        val naivePen = max(0.0, naive - band.naiveMax) * 2
        if (best != null && naivePen >= best.score) continue
        val sol = solveLevel(board, cols, 5, GEN_NODES * 3)
        if (sol == null) { p = max(0.0, p - .06); continue }
        var slotPen = 0.0
        if (band.maxMin <= 4 && solveLevel(board, cols, 4, GEN_NODES) == null) { slotPen += 1.0; p = max(0.0, p - .04) }
        if (band.minMin >= 5 && solveLevel(board, cols, 4, GEN_NODES) != null) {
            slotPen += if (solveLevel(board, cols, 3, GEN_NODES / 2) != null) 1.0 else .6; p = min(1.0, p + .05)
        }
        if (band.minMin == 4 && solveLevel(board, cols, 3, GEN_NODES) != null) { slotPen += .6; p = min(1.0, p + .04) }
        if (naivePen > 0) p = min(1.0, p + .03)
        val decoyPen = if (tag != "Normal" && !hasDecoy(board, cols, sol.path)) .3 else 0.0
        val score = naivePen + slotPen + decoyPen
        if (best == null || score < best.score) best = Cand(cols, sol.path, score, art, name, nests)
        if (score == 0.0) break
    }
    return best
}

private fun generate(L: Int): Level {
    val r = Rng(L * 9973 + 7)
    val tag = difficulty(L)
    val band = bandFor(L, tag)
    val S = if (L <= 20) 10 + (L - 4) / 3 else min(30, 15 + (L - 20) / 4)
    val SS = (S * 1.12).roundToInt()
    val nCols = (if (L <= 10) 3 else if (L <= 40) 4 else 5).let { if (band.minMin >= 5) max(3, it - 1) else it }
    val budget = if (band.minMin >= 5) 220_000L else 140_000L
    val startNodes = SolverStats.nodes
    var best: Cand? = null
    var arts = 0
    for (attempt in 0 until 40) {
        val maxArts = if (band.minMin >= 4) 6 else 3
        val bb = best
        if (bb != null && (bb.score == 0.0 || arts >= maxArts || SolverStats.nodes - startNodes > budget)) break
        val theme = THEMES[(L * 7 + attempt) % THEMES.size]
        val ao = buildArt(theme, SS, L, tag, r) ?: continue
        val art = ao.art
        if (art.pal.size < colorRange(L, tag).first && attempt < 30) continue
        if (art.grid.count { it >= 0 } < 15) continue
        arts++
        best = searchDeals(art, ao.split, L, tag, band, nCols, r, theme.name, startNodes, budget, best)
    }
    val b = best ?: return tutorialLevel(1)
    return Level(L, tag, b.theme, b.art.W, b.art.H, b.art.grid, b.art.pal.toList(), b.nests, b.cols, b.sol, levelHint(L))
}

// ---------------- player pictures ----------------
/** k-means colour quantisation in Lab space (deterministic). Pixels with alpha < 128 come back as -1. */
fun quantize(argb: IntArray, k: Int, seed: Int): Pair<IntArray, MutableList<String>> {
    val r = Rng(seed)
    val idx = argb.indices.filter { (argb[it] ushr 24) >= 128 }
    val out = IntArray(argb.size) { -1 }
    if (idx.isEmpty()) return out to mutableListOf()
    fun hex(c: Int) = "#%06x".format(c and 0xffffff)
    val labs = idx.map { lab(hex(argb[it])) }
    val kk = min(k, labs.size)
    val cent = ArrayList<DoubleArray>()
    cent.add(labs[r.int(labs.size)].copyOf())
    val dist = DoubleArray(labs.size) { Double.MAX_VALUE }
    fun d2(a: DoubleArray, b: DoubleArray) = (a[0] - b[0]) * (a[0] - b[0]) + (a[1] - b[1]) * (a[1] - b[1]) + (a[2] - b[2]) * (a[2] - b[2])
    while (cent.size < kk) {
        var sum = 0.0
        for (i in labs.indices) { dist[i] = min(dist[i], d2(labs[i], cent.last())); sum += dist[i] }
        if (sum <= 0) break
        var t = r.next() * sum; var pick = labs.size - 1
        for (i in labs.indices) { t -= dist[i]; if (t <= 0) { pick = i; break } }
        cent.add(labs[pick].copyOf())
    }
    val asg = IntArray(labs.size)
    repeat(14) {
        for (i in labs.indices) { var b = 0; var bd = Double.MAX_VALUE; for (c in cent.indices) { val d = d2(labs[i], cent[c]); if (d < bd) { bd = d; b = c } }; asg[i] = b }
        val sum = Array(cent.size) { DoubleArray(4) }
        for (i in labs.indices) { val s = sum[asg[i]]; s[0] += labs[i][0]; s[1] += labs[i][1]; s[2] += labs[i][2]; s[3]++ }
        for (c in cent.indices) if (sum[c][3] > 0) cent[c] = doubleArrayOf(sum[c][0] / sum[c][3], sum[c][1] / sum[c][3], sum[c][2] / sum[c][3])
    }
    // each cluster is shown with the average real colour of its pixels
    val rgb = Array(cent.size) { LongArray(4) }
    for ((j, i) in idx.withIndex()) { val c = argb[i]; val s = rgb[asg[j]]; s[0] += (c shr 16 and 255).toLong(); s[1] += (c shr 8 and 255).toLong(); s[2] += (c and 255).toLong(); s[3]++ }
    val pal = mutableListOf<String>()
    val map = IntArray(cent.size) { -1 }
    for (c in cent.indices) if (rgb[c][3] > 0) { map[c] = pal.size; pal.add("#%02x%02x%02x".format(rgb[c][0] / rgb[c][3], rgb[c][1] / rgb[c][3], rgb[c][2] / rgb[c][3])) }
    for ((j, i) in idx.withIndex()) out[i] = map[asg[j]]
    return out to pal
}

/** Removes single stray pixels: a tile whose neighbours mostly share one other colour takes that colour. */
fun despeckle(a: Art) {
    val src = a.grid.copyOf()
    for (i in src.indices) {
        if (src[i] < 0) continue
        val nb = nbrs(i, a.W, a.H)
        if (nb.any { src[it] == src[i] }) continue
        val votes = nb.filter { src[it] >= 0 }.groupingBy { src[it] }.eachCount()
        val best = votes.maxByOrNull { it.value } ?: continue
        if (best.value * 2 >= nb.size) a.grid[i] = best.key
    }
}

/** Pushes near-identical colours apart in lightness (keeping their hue) until every pair is at least deltaE 20 apart. */
fun separateColors(a: Art) {
    var guard = 0
    while (guard++ < 200) {
        compact(a)
        var bi = -1; var bj = -1; var bd = 20.0
        for (i in a.pal.indices) for (j in i + 1 until a.pal.size) { val d = deltaE(a.pal[i], a.pal[j]); if (d < bd) { bd = d; bi = i; bj = j } }
        if (bi < 0) return
        val li = lab(a.pal[bi]).copyOf(); val lj = lab(a.pal[bj]).copyOf()
        val (lo, hi) = if (li[0] <= lj[0]) li to lj else lj to li
        val canDown = lo[0] > 4; val canUp = hi[0] < 96
        if (!canDown && !canUp) { val c = counts(a); mergeInto(a, if (c[bi] < c[bj]) bi else bj); continue }
        if (canDown) lo[0] = max(0.0, lo[0] - 3)
        if (canUp) hi[0] = min(100.0, hi[0] + 3)
        val nlo = labToHex(lo); val nhi = labToHex(hi)
        if (li[0] <= lj[0]) { a.pal[bi] = nlo; a.pal[bj] = nhi } else { a.pal[bj] = nlo; a.pal[bi] = nhi }
    }
}

/** Makes every colour count a multiple of 5 by recolouring single edge tiles to a neighbouring colour; only (total % 5) tiles are removed. */
fun fix5Gentle(a: Art, r: Rng) {
    val W = a.W; val H = a.H; val g = a.grid
    var guard = 0
    while (guard++ < 600) {
        val c = counts(a)
        val rems = c.map { it % 5 }
        val nonzero = rems.indices.filter { rems[it] != 0 }
        if (nonzero.size <= 1) break
        // cheapest colour to fix: the one closest to a multiple of 5
        val k = nonzero.minByOrNull { rems[it] }!!
        var bestTile = -1; var bestTo = -1; var bestScore = -1
        for (i in g.indices) {
            if (g[i] != k) continue
            for (j in nbrs(i, W, H)) {
                val to = g[j]; if (to < 0 || to == k) continue
                val score = (if ((rems[to] + 1) % 5 == 0) 100 else 0) + rems[to] * 10 + nbrs(i, W, H).count { g[it] == to }
                if (score > bestScore) { bestScore = score; bestTile = i; bestTo = to }
            }
        }
        if (bestTile < 0) break
        g[bestTile] = bestTo
    }
    fix5(a, r)
}

/** Turns a downscaled picture into a pixel map ready to play (colours merged, distinct, multiples of 5). */
fun pixelArt(argb: IntArray, w: Int, h: Int, colors: Int, depthLimit: Int, seed: Int, name: String, frame: Int = 0): Art? {
    val (grid, pal) = quantize(argb, colors, seed)
    if (pal.isEmpty()) return null
    var a = Art(w, h, grid, pal, name)
    if (frame > 0) {
        // a picture-frame border buries the whole photo one layer deeper per ring
        val fw = w + 2 * frame; val fh = h + 2 * frame
        val g2 = IntArray(fw * fh) { -1 }
        for (y in 0 until h) for (x in 0 until w) g2[(y + frame) * fw + x + frame] = grid[y * w + x]
        a = Art(fw, fh, g2, pal, name)
        val frames = listOf("#5b4636", "#c9b28f", "#2f3e46", "#7a8a99", "#7d5ba6")
        for (k in 0 until frame) {
            val hex = frames.firstOrNull { c -> a.pal.all { deltaE(c, it) >= 20 } } ?: frames[k]
            val ci = a.pal.size; a.pal.add(hex)
            for (i in g2.indices) { val x = i % fw; val y = i / fw; if (min(min(x, y), min(fw - 1 - x, fh - 1 - y)) == k) g2[i] = ci }
        }
    }
    if (a.grid.any { it < 0 }) fillBackground(a, 1, Rng(seed))
    despeckle(a)
    mergeSmall(a, 5)
    capColors(a, colors + frame)
    separateColors(a)
    fix5Gentle(a, Rng(seed + 1))
    if (a.pal.isEmpty() || a.grid.count { it >= 0 } < 15) return null
    crop(a)
    return a
}

/** Difficulty choices for player pictures, mapped onto campaign bands. */
fun customDifficulty(d: Int): Triple<Int, String, Int> = when (d) {
    0 -> Triple(8, "Normal", 1)     // relaxed: up to 4 slots needed
    1 -> Triple(30, "Normal", 2)    // normal: needs 4+ slots, careless taps lose
    else -> Triple(45, "Hard", 3)   // hard: needs all 5 slots
}

fun customLevel(id: String, art: Art, difficulty: Int, seed: Int): Level? {
    val (L, tag, _) = customDifficulty(difficulty)
    val band = bandFor(L, tag)
    val r = Rng(seed)
    val dd = colorDepths(art)
    val lo = IntArray(art.pal.size) { Int.MAX_VALUE }; val hi = IntArray(art.pal.size) { -1 }
    for (i in art.grid.indices) { val c = art.grid[i]; if (c < 0) continue; lo[c] = min(lo[c], dd.d[i]); hi[c] = max(hi[c], dd.d[i]) }
    val split = art.pal.indices.filter { hi[it] > lo[it] }.toSet()
    val nCols = if (band.minMin >= 5) 4 else if (art.W * art.H > 500) 5 else 4
    val b = searchDeals(art, split, L, tag, band, nCols, r, art.name, SolverStats.nodes, 200_000L, null) ?: return null
    return Level(L, "Custom", art.name, art.W, art.H, art.grid, art.pal.toList(), b.nests, b.cols, b.sol, null, id)
}

// ---------------- real-time engine ----------------
enum class St { TRAY, FLYING, SLOT, DONE }
enum class AS { IDLE, WAIT, OUT, PICK, BACK }
enum class GS { PLAY, WON, JAM }

class Cube(d: CubeDef) : CubeDef(d.id, d.color, d.cap, d.hidden, d.lockedBy, d.ropeFrom) {
    /** Number shown on the cube: counts down as ants deliver. */
    var rem = cap
    /** Tiles still to claim in the rules model (what the solver sees). */
    var lrem = cap
    /** Tiles already claimed in the model, waiting for an ant to fetch them (in order). */
    val queue = ArrayDeque<Int>()
    var status = St.TRAY
    var slot = -1
    var anim = 0f
    var bounce = 0f
    var flip = 1f
    var revealed = !hidden
    var shake = 0f
    val ants = ArrayList<Ant>()
    var fromCol = 0
    var tapSeq = 0
}

/** A walkable route sampled into points with cumulative lengths (board tile units). */
class Route(val xs: FloatArray, val ys: FloatArray, val cum: FloatArray) {
    val len get() = cum[cum.size - 1]
}

class Ant {
    var cube: Cube? = null
    var state = AS.IDLE
    var tile = -1
    var carry = -1
    var d = 0f
    var delay = 0f
    var walk = 0f
    var x = 0f; var y = 0f; var ang = 0f
    var route: Route? = null
}

sealed class Ev {
    class Tap(val cube: Cube) : Ev()
    class Land(val cube: Cube) : Ev()
    class Done(val cube: Cube) : Ev()
    class Deliver(val cube: Cube) : Ev()
    class Eat(val i: Int, val color: Int, val quiet: Boolean = false) : Ev()
    object Won : Ev()
    object Jam : Ev()
}

/**
 * Real-time game. Rules are resolved exactly like the solver's model the moment a cube lands (earlier-tapped cubes
 * claim first, each claim can expose more tiles); ants then animate those claims. So any level the solver can win
 * is winnable here too. [board] is the rules state; [shown] is what is still drawn on screen.
 */
class Engine(val lv: Level) {
    val W = lv.W
    val H = lv.H
    val board = Board(lv.W, lv.H, lv.grid, lv.palette.size, lv.nests)
    val shown = BooleanArray(W * H) { lv.grid[it] >= 0 }
    var shownLeft = shown.count { it }
        private set
    val byId = arrayOfNulls<Cube>(cubeCount(lv.cols))
    var cols: MutableList<MutableList<Cube>> = lv.cols.map { col -> col.map { d -> Cube(d).also { byId[it.id] = it } }.toMutableList() }.toMutableList()
    var slotCount = 5
    val slots = arrayOfNulls<Cube>(MAX_SLOTS)
    val ants = ArrayList<Ant>()
    private val toFinish = ArrayList<Cube>()
    private var tapCounter = 0
    private val order = ArrayList<Cube>()
    private val pool = ArrayList<Ant>()
    val events = ArrayList<Ev>()
    var status = GS.PLAY
    var used = false
    private var jamT = 0f
    var time = 0f
    val speed = 2.2f + .3f * max(lv.W, lv.H)

    fun isLocked(c: Cube): Boolean { val l = c.lockedBy ?: return false; val o = byId[l] ?: return false; return o.status != St.DONE }

    fun tappable(c: Cube?): Boolean {
        if (c == null || c.status != St.TRAY) return false
        if (cols.none { it.isNotEmpty() && it[0] === c }) return false
        if (isLocked(c)) return false
        val rf = c.ropeFrom; if (rf != null) { val a = byId[rf]; if (a != null && a.status == St.TRAY) return false }
        return true
    }

    fun freeSlot(): Int { for (i in 0 until slotCount) if (slots[i] == null) return i; return -1 }

    /** "ok", "empty", "locked", "full", "no" */
    fun tap(ci: Int): String {
        if (status != GS.PLAY) return "no"
        val col = cols.getOrNull(ci) ?: return "empty"
        if (col.isEmpty()) return "empty"
        val c = col[0]
        if (!tappable(c)) { c.shake = .35f; return "locked" }
        val si = freeSlot(); if (si < 0) { c.shake = .35f; return "full" }
        col.removeAt(0); c.status = St.FLYING; c.slot = si; c.anim = 0f; c.fromCol = ci; c.tapSeq = ++tapCounter; slots[si] = c
        events.add(Ev.Tap(c))
        return "ok"
    }

    private fun newAnt(c: Cube): Ant {
        val a = if (pool.isEmpty()) Ant() else pool.removeAt(pool.size - 1)
        a.cube = c; a.state = AS.IDLE; a.tile = -1; a.carry = -1; a.d = 0f; a.route = null; a.delay = 0f
        a.walk = (Math.random() * 3).toFloat(); a.x = 0f; a.y = 0f; a.ang = 0f
        ants.add(a); return a
    }

    private fun releaseAnts(c: Cube) {
        for (a in c.ants) { ants.remove(a); a.cube = null; pool.add(a) }
        c.ants.clear()
    }

    /** The rules model: slotted cubes, in tap order, claim every reachable tile of their colour until nothing changes. */
    private fun settle() {
        order.clear()
        for (si in 0 until slotCount) { val c = slots[si]; if (c != null && c.status == St.SLOT) order.add(c) }
        order.sortBy { it.tapSeq }
        var prog = true
        while (prog) {
            prog = false
            for (c in order) while (c.lrem > 0) {
                val t = board.pop(c.color); if (t < 0) break
                board.eat(t); c.lrem--; c.queue.addLast(t); prog = true
            }
        }
    }

    private fun land(c: Cube) {
        c.status = St.SLOT
        if (c.hidden && !c.revealed) { c.revealed = true; c.flip = 0f }
        repeat(min(c.rem, ANT_MAX)) { c.ants.add(newAnt(c)) }
        events.add(Ev.Land(c))
        settle()
    }

    private fun finish(c: Cube) { c.status = St.DONE; slots[c.slot] = null; releaseAnts(c); events.add(Ev.Done(c)) }

    private fun hide(t: Int, quiet: Boolean) {
        if (!shown[t]) return
        shown[t] = false; shownLeft--
        events.add(Ev.Eat(t, board.color[t], quiet))
    }

    private fun nestIndex(t: Int): Int {
        val x = t % W + .5f; val y = t / W + .5f
        return lv.nests.indices.minByOrNull { hypot(x - lv.nests[it].x, y - lv.nests[it].y) }!!
    }

    // ---- routes over open ground (outer margin ring + eaten cells), BFS from each nest ----
    private val GW = W + 2
    private val GH = H + 2
    private val bfsDist = Array(lv.nests.size) { IntArray(GW * GH) }
    private val bfsPrev = Array(lv.nests.size) { IntArray(GW * GH) }
    private val bfsVer = IntArray(lv.nests.size) { -1 }
    private val bfsTime = FloatArray(lv.nests.size) { -1f }
    private val DX = intArrayOf(1, -1, 0, 0)
    private val DY = intArrayOf(0, 0, 1, -1)
    private var bfsQ = IntArray(GW * GH)

    private fun walkable(gx: Int, gy: Int): Boolean {
        val x = gx - 1; val y = gy - 1
        if (x < 0 || y < 0 || x >= W || y >= H) return true
        return board.open[y * W + x]
    }

    private fun runBfs(ni: Int) {
        val dist = bfsDist[ni]; val prev = bfsPrev[ni]
        dist.fill(-1); prev.fill(-1)
        val n = lv.nests[ni]
        val sx = (n.x.toInt() + 1).coerceIn(0, GW - 1); val sy = GH - 1
        val q = bfsQ; var h = 0; var t = 0
        val s0 = sy * GW + sx; dist[s0] = 0; q[t++] = s0
        while (h < t) {
            val c = q[h++]; val cx = c % GW; val cy = c / GW
            for (k in 0 until 4) {
                val nx = cx + DX[k]; val ny = cy + DY[k]
                if (nx < 0 || ny < 0 || nx >= GW || ny >= GH) continue
                val nb = ny * GW + nx
                if (dist[nb] >= 0 || !walkable(nx, ny)) continue
                dist[nb] = dist[c] + 1; prev[nb] = c; q[t++] = nb
            }
        }
        bfsVer[ni] = board.ver; bfsTime[ni] = time
    }

    private fun bestNeighbour(ni: Int, t: Int): Int {
        val gx = t % W + 1; val gy = t / W + 1
        var best = -1; var bd = Int.MAX_VALUE
        for (k in 0 until 4) {
            val nb = (gy + DY[k]) * GW + gx + DX[k]
            val dd = bfsDist[ni][nb]
            if (dd in 0 until bd) { bd = dd; best = nb }
        }
        return best
    }

    private fun buildRoute(ni: Int, t: Int): Route {
        if (bfsVer[ni] < 0 || (bfsVer[ni] != board.ver && time - bfsTime[ni] >= .1f)) runBfs(ni)
        var nb = bestNeighbour(ni, t)
        if (nb < 0) { runBfs(ni); nb = bestNeighbour(ni, t) }
        val n = lv.nests[ni]
        val tx = t % W + .5f; val ty = t / W + .5f
        val px = ArrayList<Float>(); val py = ArrayList<Float>()
        px.add(n.x); py.add(n.y)
        if (nb >= 0) {
            val cells = ArrayList<Int>(); var c = nb
            while (c >= 0 && cells.size <= GW * GH) { cells.add(c); c = bfsPrev[ni][c] }
            cells.reverse()
            for ((k, cc) in cells.withIndex()) {
                if (k in 1 until cells.size - 1) {
                    val a = cells[k - 1]; val b = cells[k + 1]
                    if ((a % GW == cc % GW && cc % GW == b % GW) || (a / GW == cc / GW && cc / GW == b / GW)) continue
                }
                px.add(cc % GW - .5f); py.add(cc / GW - .5f)
            }
            val lx = px[px.size - 1]; val ly = py[py.size - 1]
            px.add(lx + (tx - lx) * .55f); py.add(ly + (ty - ly) * .55f)
        } else { px.add(tx); py.add(ty) }
        // Catmull-Rom smoothing
        val m = px.size
        val n2 = (m - 1) * 4 + 1
        val sx = FloatArray(n2); val sy = FloatArray(n2); var o = 0
        for (i in 0 until m - 1) {
            val p0x = px[max(0, i - 1)]; val p0y = py[max(0, i - 1)]
            val p1x = px[i]; val p1y = py[i]; val p2x = px[i + 1]; val p2y = py[i + 1]
            val p3x = px[min(m - 1, i + 2)]; val p3y = py[min(m - 1, i + 2)]
            for (k in 0 until 4) {
                val u = k / 4f; val u2 = u * u; val u3 = u2 * u
                sx[o] = .5f * (2 * p1x + (-p0x + p2x) * u + (2 * p0x - 5 * p1x + 4 * p2x - p3x) * u2 + (-p0x + 3 * p1x - 3 * p2x + p3x) * u3)
                sy[o] = .5f * (2 * p1y + (-p0y + p2y) * u + (2 * p0y - 5 * p1y + 4 * p2y - p3y) * u2 + (-p0y + 3 * p1y - 3 * p2y + p3y) * u3)
                o++
            }
        }
        sx[o] = px[m - 1]; sy[o] = py[m - 1]
        val cum = FloatArray(n2)
        for (i in 1 until n2) cum[i] = cum[i - 1] + hypot(sx[i] - sx[i - 1], sy[i] - sy[i - 1])
        return Route(sx, sy, cum)
    }

    private fun place(a: Ant, back: Boolean) {
        val r = a.route ?: return
        val d = a.d.coerceIn(0f, r.len)
        var lo = 0; var hi = r.cum.size - 1
        while (lo < hi - 1) { val mid = (lo + hi) / 2; if (r.cum[mid] <= d) lo = mid else hi = mid }
        val seg = (r.cum[hi] - r.cum[lo]).let { if (it <= 0f) 1f else it }
        val f = (d - r.cum[lo]) / seg
        a.x = r.xs[lo] + (r.xs[hi] - r.xs[lo]) * f; a.y = r.ys[lo] + (r.ys[hi] - r.ys[lo]) * f
        val dx = r.xs[hi] - r.xs[lo]; val dy = r.ys[hi] - r.ys[lo]
        if (dx != 0f || dy != 0f) a.ang = if (back) atan2(-dy, -dx) else atan2(dy, dx)
    }

    /** Idle ants fetch their cube's claimed tiles in claim order, single file. */
    private fun dispatch() {
        for (si in 0 until slotCount) {
            val c = slots[si] ?: continue
            if (c.status != St.SLOT || c.queue.isEmpty()) continue
            var k = 0
            for (a in c.ants) {
                if (c.queue.isEmpty()) break
                if (a.state != AS.IDLE) continue
                val t = c.queue.removeFirst()
                a.tile = t; a.state = AS.WAIT; a.delay = k * .35f / speed; k++
                a.route = buildRoute(nestIndex(t), t); a.d = 0f; place(a, false)
            }
        }
    }

    fun update(dt: Float) {
        time += dt
        if (status != GS.PLAY) return
        for (si in 0 until MAX_SLOTS) {
            val c = slots[si] ?: continue
            if (c.status == St.FLYING) { c.anim += dt / .25f; if (c.anim >= 1f) { c.anim = 1f; land(c) } }
            if (c.flip < 1f) c.flip = min(1f, c.flip + dt / .3f)
            if (c.bounce > 0f) c.bounce = max(0f, c.bounce - dt * 4)
        }
        for (col in cols) for (c in col) if (c.shake > 0f) c.shake -= dt
        dispatch()
        val v = speed
        toFinish.clear()
        var i = 0
        while (i < ants.size) {
            val a = ants[i++]
            val c = a.cube ?: continue
            when (a.state) {
                AS.WAIT -> { a.delay -= dt; if (a.delay <= 0f) a.state = AS.OUT }
                AS.OUT -> {
                    a.d += v * dt; a.walk += v * dt * 2.2f; place(a, false)
                    if (a.d >= (a.route?.len ?: 0f)) { hide(a.tile, false); a.carry = c.color; a.state = AS.PICK; a.delay = .1f }
                }
                AS.PICK -> { a.delay -= dt; if (a.delay <= 0f) a.state = AS.BACK }
                AS.BACK -> {
                    a.d -= v * dt; a.walk += v * dt * 2.2f; place(a, true)
                    if (a.d <= 0f) {
                        a.carry = -1; a.state = AS.IDLE; a.route = null; c.rem--; c.bounce = 1f
                        events.add(Ev.Deliver(c))
                        if (c.rem <= 0 && c !in toFinish) toFinish.add(c)
                    }
                }
                AS.IDLE -> {}
            }
        }
        for (c in toFinish) finish(c)
        checkEnd(dt)
    }

    fun midTrip() = ants.any { it.state != AS.IDLE }
    fun anyCarrying() = ants.any { it.carry >= 0 }
    fun flying() = slots.any { it != null && it.status == St.FLYING }
    fun anyTappable() = freeSlot() >= 0 && cols.any { it.isNotEmpty() && tappable(it[0]) }
    /** Some slotted cube still has claimed tiles waiting for an ant. */
    fun slottedReach(): Boolean {
        for (si in 0 until slotCount) { val c = slots[si] ?: continue; if (c.status == St.SLOT && c.queue.isNotEmpty()) return true }
        return false
    }
    fun quiescent() = !midTrip() && !flying() && !slottedReach()

    private fun checkEnd(dt: Float) {
        if (board.total == 0 && quiescent()) { status = GS.WON; events.add(Ev.Won); return }
        if (quiescent() && !anyTappable()) {
            jamT += dt; if (jamT > .45f) { status = GS.JAM; events.add(Ev.Jam) }
        } else jamT = 0f
    }

    // ---- boosters ----
    fun extraSlot(): Boolean {
        if (slotCount >= MAX_SLOTS) return false
        slotCount++; used = true
        if (status == GS.JAM) { status = GS.PLAY; jamT = 0f }
        return true
    }

    /** "ok", "stuck" (no winnable order found in 20 tries; last kept) or "none". */
    fun shuffle(r: () -> Double = Math::random): String {
        val lens = cols.map { it.size }
        val pool0 = cols.flatten()
        if (pool0.size < 2) return "none"
        val wasLocked = pool0.filter { isLocked(it) }.map { it.id }.toSet()
        used = true
        for (attempt in 0 until 20) {
            val all = pool0.toMutableList().shuffleWith(r)
            var k = 0
            cols = lens.map { n -> all.subList(k, k + n).toMutableList().also { k += n } }.toMutableList()
            for (col in cols) for (b in col.toList()) {
                val a = b.ropeFrom?.let { byId[it] } ?: continue
                val ia = col.indexOf(a); val ib = col.indexOf(b)
                if (ia >= 0 && ib in 0 until ia) { col[ia] = b; col[ib] = a }
            }
            for (col in cols) col.forEachIndexed { j, c -> if (c.id in wasLocked) c.lockedBy = if (j > 0) col[j - 1].id else null }
            if (solveEngine(this, 20000) != null) return "ok"
        }
        return "stuck"
    }

    /** Sends a slotted cube back to the front of the column it came from, keeping its remaining number. */
    fun pickUp(si: Int): Boolean {
        val c = slots[si] ?: return false
        if (c.status != St.SLOT) return false
        // tiles already claimed (being carried or queued) count as delivered
        for (a in c.ants) if (a.state == AS.WAIT && a.tile >= 0) hide(a.tile, true)
        for (t in c.queue) hide(t, true)
        c.queue.clear()
        releaseAnts(c); slots[si] = null
        c.rem = c.lrem
        used = true
        if (c.lrem <= 0) { c.status = St.DONE; return true }
        c.status = St.TRAY; c.slot = -1; c.anim = 0f
        val home = cols.getOrNull(c.fromCol) ?: cols.minByOrNull { it.size }!!
        home.add(0, c)
        return true
    }

    fun vacuum(color: Int): Boolean {
        for (si in 0 until MAX_SLOTS) {
            val c = slots[si] ?: continue
            if (c.color != color) continue
            for (a in c.ants) if (a.state == AS.WAIT && a.tile >= 0) hide(a.tile, true)
            for (t in c.queue) hide(t, true)
            c.queue.clear(); releaseAnts(c); c.status = St.DONE; slots[si] = null
        }
        for (col in cols) for (j in col.size - 1 downTo 0) if (col[j].color == color) { col[j].status = St.DONE; col.removeAt(j) }
        for (c in byId) if (c != null && c.status != St.DONE) {
            val lb = c.lockedBy; if (lb != null && byId[lb]!!.status == St.DONE && byId[lb]!!.color == color) c.lockedBy = null
            val rf = c.ropeFrom; if (rf != null && byId[rf]!!.color == color) c.ropeFrom = null
        }
        val removed = (0 until board.N).filter { board.present[it] && board.color[it] == color }
        board.removeColor(color)
        for (t in removed) hide(t, true)
        for (t in 0 until board.N) if (shown[t] && board.color[t] == color) hide(t, true)
        settle()
        used = true; return true
    }

    fun colorsLeft() = (0 until board.nc).filter { board.left[it] > 0 }

    /** For each colour, the numbers the rules still expect on cubes equal the tiles left on the board. */
    fun audit(): Boolean {
        val need = IntArray(board.nc)
        for (c in byId) if (c != null && c.status != St.DONE) need[c.color] += c.lrem
        return need.contentEquals(board.left) && byId.all { c -> c == null || c.status == St.DONE || c.rem >= c.lrem }
    }
}

fun solveEngine(e: Engine, nodeLimit: Int = 20000): SolveResult? {
    val cols: List<List<CubeDef>> = e.cols.map { it.toList() }
    val b = e.board.clone()
    val status = ByteArray(e.byId.size)
    e.byId.forEach { c -> if (c != null) status[c.id] = when (c.status) { St.DONE -> 2; St.TRAY -> 0; else -> 1 } }
    val slots = mutableListOf<SSlot>()
    (0 until e.slotCount).mapNotNull { e.slots[it] }.filter { it.lrem > 0 }.sortedBy { it.tapSeq }.forEach { c -> slots.add(SSlot(c, c.lrem)) }
    return solveState(SState(b, IntArray(cols.size), slots, status), cols, e.slotCount, nodeLimit)
}

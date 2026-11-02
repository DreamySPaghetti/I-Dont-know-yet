package com.pixelporters.game

/** Self-test harness. Runs on the JVM (unit tests in CI) or standalone via main(). Returns the number of failures. */
fun runHarness(maxLevel: Int = 200, out: (String) -> Unit = ::println): Int {
    var fails = 0
    fun check(c: Boolean, msg: String) { if (!c) { fails++; out("FAIL: $msg") } }

    // 1-2. generation, 5-slot solvability, difficulty table
    class Row(var n: Int = 0, var ms: Double = 0.0, var nv: Double = 0.0, var miss: Int = 0)
    val rows = linkedMapOf<String, Row>()
    val levels = HashMap<Int, Level>()
    var slowest = 0L; var slowestL = 0
    for (L in 1..maxLevel) {
        val t0 = System.nanoTime()
        val lv = genLevel(L)
        val ms = (System.nanoTime() - t0) / 1_000_000
        if (ms > slowest) { slowest = ms; slowestL = L }
        levels[L] = lv
        val tiles = IntArray(lv.palette.size); for (v in lv.grid) if (v >= 0) tiles[v]++
        val caps = IntArray(lv.palette.size); for (col in lv.cols) for (c in col) { caps[c.color] += c.cap; check(c.cap in CAPS, "L$L bad cap ${c.cap}") }
        check(tiles.contentEquals(caps), "L$L capacity sums ${tiles.toList()} vs ${caps.toList()}")
        val b = Board(lv.W, lv.H, lv.grid, lv.palette.size, lv.nests)
        check(solveLevel(b, lv.cols, 5, 20000) != null, "L$L not solvable with 5 slots")
        for (i in lv.palette.indices) for (j in i + 1 until lv.palette.size) check(deltaE(lv.palette[i], lv.palette[j]) >= 20, "L$L colours $i/$j too close")
        if (L < 4) continue
        val band = if (L <= 20) "4-20" else if (L <= 100) "21-100" else "101+"
        val key = "$band ${lv.tag}"
        val m = minSlots(lv); val nv = naiveWinRate(lv)
        val r = rows.getOrPut(key) { Row() }
        r.n++; r.ms += m; r.nv += nv
        if (!bandFor(L, lv.tag).ok(m, nv)) r.miss++
    }
    out(String.format("%-20s %6s %9s %9s %7s", "band / tag", "count", "minSlots", "naiveWin", "missed"))
    for ((k, r) in rows) out(String.format("%-20s %6d %9.2f %9.2f %7d", k, r.n, r.ms / r.n, r.nv / r.n, r.miss))
    out("slowest generation: level $slowestL, $slowest ms")

    // 3. replay the solver's path in the real-time engine
    val rnd = Rng(4242)
    val pick = (1..maxLevel).toMutableList().shuffleWith(rnd::next).take(30).sorted()
    var replans = 0
    for (L in pick) {
        val lv = levels[L]!!
        val e = Engine(lv)
        val seq = ArrayDeque(lv.solution ?: emptyList())
        var f = 0
        while (e.status == GS.PLAY && f < 200_000) {
            if (seq.isNotEmpty() && !e.midTrip() && !e.flying() && !e.slottedReach()) {
                if (e.tap(seq.first()) == "ok") seq.removeFirst()
                else {
                    replans++
                    val s = solveEngine(e, 30000)
                    if (s == null) { check(false, "L$L replan failed"); break }
                    seq.clear(); seq.addAll(s.path)
                }
            }
            e.update(1f / 60); f++
            if (e.status == GS.JAM) check(!e.anyCarrying(), "L$L jam while carrying")
            if (f % 30 == 0) check(e.audit(), "L$L audit frame $f")
        }
        check(e.status == GS.WON, "L$L replay ended ${e.status} (left ${e.board.total})")
    }
    out("replayed ${pick.size} levels in the real-time engine: $pick (replans: $replans)")

    // 4. audit around every booster used mid-level
    for (L in listOf(9, 12, 20, 30, 45, 110)) {
        if (L > maxLevel) continue
        val e = Engine(levels[L]!!)
        fun run(frames: Int) { repeat(frames) { e.update(1f / 60); if (it % 30 == 0) check(e.audit(), "L$L audit (boosters)") } }
        for (k in 0 until 3) e.tap(k % e.cols.size)
        run(90)
        check(e.extraSlot(), "L$L extra slot"); check(e.audit(), "L$L audit after extra slot"); run(60)
        val si = (0 until e.slotCount).firstOrNull { e.slots[it]?.status == St.SLOT }
        if (si != null) { e.pickUp(si); check(e.audit(), "L$L audit after pick up") }
        run(30)
        val sh = e.shuffle(rnd::next); check(e.audit(), "L$L audit after shuffle ($sh)")
        run(30)
        e.vacuum(e.colorsLeft().first()); check(e.audit(), "L$L audit after vacuum")
        for (c in e.byId) if (c != null && c.status != St.DONE) check(e.board.left[c.color] + e.ants.count { it.carry == c.color } > 0, "L$L orphan cube colour ${c.color}")
        var f = 0
        while (e.status == GS.PLAY && f < 200_000) {
            if (!e.midTrip() && !e.flying() && !e.slottedReach()) { val s = solveEngine(e, 30000); if (s == null || s.path.isEmpty()) break; e.tap(s.path[0]) }
            e.update(1f / 60); f++
            if (f % 30 == 0) check(e.audit(), "L$L audit after boosters")
        }
        out("L$L after Extra Slot, Pick Up, Shuffle ($sh), Vacuum: ${e.status}")
    }
    out(if (fails == 0) "ALL PASS" else "$fails FAILURES")
    return fails
}

fun main(args: Array<String>) {
    val n = args.firstOrNull()?.toIntOrNull() ?: 200
    val f = runHarness(n)
    if (f > 0) System.exit(1)
}

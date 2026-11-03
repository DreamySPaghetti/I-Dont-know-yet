package com.arenascanner.app

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Pure image logic (no Android types, so it is unit-tested on the JVM):
 *  - [Settle] decides when the screen has stopped scrolling and shows something new.
 *  - [Layout] works out where each card sits from the positions of the card names OCR found.
 *  - [PipCounter] counts the lit "copies owned" pips under a card.
 */

/** A rectangle in screen pixels. */
data class Box(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width get() = right - left
    val height get() = bottom - top
}

/**
 * Feed it a small grayscale fingerprint of each frame. It answers true once the screen has held
 * still for [stableTicks] checks and looks different from the last scanned screen.
 */
class Settle(private val stableTicks: Int = 2, private val stillDiff: Double = 3.0, private val newDiff: Double = 6.0) {
    private var prev: IntArray? = null
    private var lastScanned: IntArray? = null
    private var still = 0

    fun offer(fp: IntArray): Boolean {
        val p = prev
        prev = fp
        if (p == null || p.size != fp.size) { still = 0; return false }
        still = if (diff(p, fp) < stillDiff) still + 1 else 0
        if (still < stableTicks) return false
        val last = lastScanned
        if (last != null && last.size == fp.size && diff(last, fp) < newDiff) return false
        return true
    }

    /** Call after a scan so the same screen isn't scanned again. */
    fun scanned(fp: IntArray) { lastScanned = fp; still = 0 }

    companion object {
        fun diff(a: IntArray, b: IntArray): Double {
            var s = 0L
            for (i in a.indices) s += abs(a[i] - b[i])
            return s.toDouble() / a.size
        }
    }
}

/**
 * Arena's collection grid, measured from a real screen recording (landscape, 2340×1080, card ≈ 254 px wide).
 * All offsets are fractions of the card width W so they scale with screen size.
 */
object Layout {
    const val NAME_INSET = 0.071   // name text starts this far in from the card's left edge
    const val PIP_ABOVE = 0.17     // diamond row centre sits this far above the top of the name text
    const val PIP_PITCH = 0.109    // spacing between the 4 diamonds
    const val PIP_RADIUS = 0.036   // half-width of one diamond
    const val CARD_PER_PITCH = 0.852 // card width ÷ distance between neighbouring cards

    /** Card width from the spacing of card names in the same row; falls back to Arena's usual size. */
    fun cardWidth(names: List<Box>, screenW: Int, screenH: Int): Int {
        val minDim = min(screenW, screenH)
        val fallback = (minDim * 0.235).toInt()
        val lineH = names.map { it.height }.sorted().let { if (it.isEmpty()) 16 else it[it.size / 2] }
        val gaps = mutableListOf<Int>()
        for (row in names.groupBy { it.top / max(1, lineH * 3) }.values) {
            val xs = row.map { it.left }.sorted()
            for (i in 1 until xs.size) {
                val d = xs[i] - xs[i - 1]
                if (d > fallback * 0.8) gaps.add(d)
            }
        }
        if (gaps.isEmpty()) return fallback
        gaps.sort()
        // A missed card in between makes a double gap; the smallest common gap is one card.
        val w = (gaps[0] * CARD_PER_PITCH).toInt()
        return w.coerceIn((minDim * 0.15).toInt(), (minDim * 0.33).toInt())
    }

    /** Expected centre of the first (leftmost) diamond for a card whose name text box is [name]. */
    fun firstPip(name: Box, cardW: Int): Pair<Double, Double> {
        val cardLeft = name.left - NAME_INSET * cardW
        val combCentre = cardLeft + 0.5 * cardW
        return (combCentre - 1.5 * PIP_PITCH * cardW) to (name.top - PIP_ABOVE * cardW)
    }
}

/** What was read for one card: copies owned (null = unreadable) and where the 4 diamonds were found. */
data class PipRead(val count: Int?, val slots: List<Pair<Int, Int>>)

object PipCounter {
    /**
     * Counts owned copies from the 4 diamonds above a card. An owned copy is a near-black diamond with
     * a bright outline; an unowned slot has a faint outline. Owned diamonds always fill from the left.
     * Searches around the expected spot so a few pixels of error in the name box don't matter.
     */
    fun count(luma: (Int, Int) -> Int, w: Int, h: Int, name: Box, cardW: Int): PipRead {
        val (ex, ey) = Layout.firstPip(name, cardW)
        val p = Layout.PIP_PITCH * cardW
        val r = Layout.PIP_RADIUS * cardW
        val range = max(4, (0.07 * cardW).toInt())
        var bestTotal = -1.0
        var bestDx = 0
        var bestDy = 0
        var dy = -range
        while (dy <= range) {
            var dx = -range
            while (dx <= range) {
                var total = 0.0
                for (i in 0 until 4) total += max(0.0, slot(luma, w, h, ex + dx + i * p, ey + dy, r)[0])
                if (total > bestTotal) { bestTotal = total; bestDx = dx; bestDy = dy }
                dx += 2
            }
            dy += 2
        }
        val slots = (0 until 4).map { (ex + bestDx + it * p).toInt() to (ey + bestDy).toInt() }
        if (bestTotal < 100) return PipRead(null, slots)
        val owned = (0 until 4).map {
            val s = slot(luma, w, h, ex + bestDx + it * p, ey + bestDy, r)
            s[0] >= 85 && s[1] < 45 && s[2] >= 105
        }
        val n = owned.count { it }
        // Owned copies are always the leftmost diamonds; anything else is a misread.
        if (owned != List(n) { true } + List(4 - n) { false }) return PipRead(null, slots)
        return PipRead(n, slots)
    }

    /** [contrast, centre brightness, outline brightness] for a diamond centred at (cx, cy). */
    private fun slot(luma: (Int, Int) -> Int, w: Int, h: Int, cx: Double, cy: Double, r: Double): DoubleArray {
        val x = Math.round(cx).toInt(); val y = Math.round(cy).toInt()
        if (x < 3 || y < 3 || x >= w - 3 || y >= h - 3) return doubleArrayOf(0.0, 255.0, 0.0)
        fun peak(px: Double, py: Double): Int {
            val ix = Math.round(px).toInt(); val iy = Math.round(py).toInt()
            if (ix < 1 || iy < 1 || ix >= w - 1 || iy >= h - 1) return 0
            var m = 0
            for (yy in iy - 1..iy + 1) for (xx in ix - 1..ix + 1) m = max(m, luma(xx, yy))
            return m
        }
        val h2 = r / 2
        val edge = intArrayOf(
            peak(cx + r, cy), peak(cx - r, cy), peak(cx, cy + r), peak(cx, cy - r),
            peak(cx + h2, cy + h2), peak(cx - h2, cy + h2), peak(cx + h2, cy - h2), peak(cx - h2, cy - h2),
        ).sorted().drop(2).average() // ignore the 2 weakest points (anti-aliasing, video blur)
        var sum = 0
        for (yy in y - 2..y + 2) for (xx in x - 2..x + 2) sum += luma(xx, yy)
        val centre = sum / 25.0
        return doubleArrayOf(edge - centre, centre, edge)
    }
}

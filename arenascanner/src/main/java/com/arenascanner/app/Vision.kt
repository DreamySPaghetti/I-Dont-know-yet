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

object Layout {
    /**
     * Estimated card width in pixels from all card-name boxes on screen. Cards in the same row are
     * spaced one card width (plus a small gap) apart, so the usual spacing between neighbours gives it.
     */
    fun cardWidth(names: List<Box>, screenWidth: Int): Int {
        val lineH = names.map { it.height }.sorted().let { if (it.isEmpty()) 30 else it[it.size / 2] }
        val gaps = mutableListOf<Int>()
        val rows = names.groupBy { (it.top + lineH / 2) / max(1, lineH * 2) }
        for (row in rows.values) {
            val xs = row.map { it.left }.sorted()
            for (i in 1 until xs.size) {
                val d = xs[i] - xs[i - 1]
                if (d > lineH * 3) gaps.add(d)
            }
        }
        if (gaps.isNotEmpty()) {
            gaps.sort()
            return (gaps[gaps.size / 2] * 0.94).toInt()
        }
        // One card per row visible: fall back to the name's text height (about 1/13 of the card width).
        return min(screenWidth / 2, max(lineH * 13, screenWidth / 6))
    }

    /** Where the copy pips should be, given a card's name box and the card width. */
    fun pipRegion(name: Box, cardW: Int, screenW: Int, screenH: Int): Box {
        val cardLeft = name.left - (cardW * 0.08).toInt()
        val cardTop = name.top - (cardW * 0.07).toInt()
        // A card is about 1.4 times as tall as it is wide; the pips sit just under its bottom edge.
        return Box(
            (cardLeft + cardW * 0.05).toInt().coerceIn(0, screenW),
            (cardTop + cardW * 1.30).toInt().coerceIn(0, screenH),
            (cardLeft + cardW * 0.95).toInt().coerceIn(0, screenW),
            (cardTop + cardW * 1.75).toInt().coerceIn(0, screenH),
        )
    }
}

object PipCounter {
    /**
     * Counts lit pips (1–4) in [r]. [luma] gives brightness 0–255 at a pixel.
     * Looks for a horizontal line of 1–4 short, evenly spaced bright marks that stays the same over
     * at least two pixel rows. Returns null when nothing pip-like is found, so the caller keeps the
     * existing count rather than guessing.
     */
    fun count(luma: (Int, Int) -> Int, r: Box, cardW: Int): Int? {
        if (r.width < 12 || r.height < 3) return null
        val minRun = max(2, (cardW * 0.025).toInt())
        val maxRun = max(minRun + 2, (cardW * 0.17).toInt())
        val joinGap = max(1, (cardW * 0.008).toInt())
        val counts = IntArray(r.height)
        val strength = IntArray(r.height)
        val row = IntArray(r.width)
        for (yi in 0 until r.height) {
            val y = r.top + yi
            for (xi in row.indices) row[xi] = luma(r.left + xi, y)
            val bg = row.copyOf().also { it.sort() }[row.size / 2]
            val thr = max(140, bg + 55)
            val runs = mutableListOf<IntArray>() // [start, endExclusive]
            var i = 0
            while (i < row.size) {
                if (row[i] >= thr) {
                    val s = i
                    while (i < row.size && row[i] >= thr) i++
                    val last = runs.lastOrNull()
                    if (last != null && s - last[1] <= joinGap) last[1] = i else runs.add(intArrayOf(s, i))
                } else i++
            }
            if (runs.isEmpty() || runs.size > 4) continue
            if (runs.any { it[1] - it[0] < minRun || it[1] - it[0] > maxRun }) continue
            if (runs.size >= 3) {
                // Pips are evenly spaced.
                val pitches = (1 until runs.size).map { runs[it][0] - runs[it - 1][0] }
                if (pitches.max() > pitches.min() * 1.6) continue
            }
            if (runs.size >= 2) {
                // ...and close together: the whole row of 4 fits well inside the card width.
                val pitch = runs[1][0] - runs[0][0]
                if (pitch > cardW * 0.3) continue
            }
            counts[yi] = runs.size
            strength[yi] = runs.sumOf { it[1] - it[0] }
        }
        // Keep counts seen on at least two neighbouring rows; prefer the strongest such band.
        var best: Int? = null
        var bestStrength = 0
        for (yi in 1 until r.height) {
            val c = counts[yi]
            if (c > 0 && c == counts[yi - 1]) {
                val s = strength[yi] + strength[yi - 1]
                if (s > bestStrength) { bestStrength = s; best = c }
            }
        }
        return best
    }
}

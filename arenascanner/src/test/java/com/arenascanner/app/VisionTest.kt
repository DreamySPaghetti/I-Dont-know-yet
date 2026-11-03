package com.arenascanner.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VisionTest {
    private val cardW = 200
    private val region = Box(0, 0, 180, 60)

    /** Dark strip with 4 pip slots; the first [lit] are bright, the rest dim. */
    private fun pips(lit: Int, litLuma: Int = 220, dimLuma: Int = 70, bg: Int = 30): (Int, Int) -> Int = { x, y ->
        val slot = (x - 30) / 30
        val inPip = x >= 30 && slot in 0..3 && (x - 30) % 30 < 18 && y in 22..27
        when {
            !inPip -> bg
            slot < lit -> litLuma
            else -> dimLuma
        }
    }

    @Test fun countsEachNumberOfLitPips() {
        for (n in 1..4) assertEquals(n, PipCounter.count(pips(n), region, cardW))
    }

    @Test fun noPipsGivesNull() = assertNull(PipCounter.count(pips(0), region, cardW))

    @Test fun brightBackgroundStillWorks() {
        // Lighter strip under the card: pips must stand well above the background.
        assertEquals(2, PipCounter.count(pips(2, litLuma = 245, dimLuma = 150, bg = 140), region, cardW))
    }

    @Test fun wideBrightAreaIsNotPips() {
        // A card's white text box drifting into the search area is one wide bright run, not pips.
        val textBox = { _: Int, y: Int -> if (y in 10..40) 235 else 30 }
        assertNull(PipCounter.count(textBox, region, cardW))
    }

    @Test fun singleRowNoiseIgnored() {
        val noise = { x: Int, y: Int -> if (y == 30 && x in 50..60) 250 else 30 }
        assertNull(PipCounter.count(noise, region, cardW))
    }

    @Test fun cardWidthFromNeighbours() {
        val names = listOf(Box(20, 100, 150, 130), Box(240, 102, 380, 132), Box(460, 100, 600, 130))
        assertEquals((220 * 0.94).toInt(), Layout.cardWidth(names, 1080))
    }

    @Test fun pipRegionIsBelowTheCard() {
        val r = Layout.pipRegion(Box(100, 100, 250, 130), 200, 1080, 2400)
        assertTrue(r.top > 100 + 200)   // below a card at least 1.3 widths tall
        assertTrue(r.left >= 80 && r.right <= 300)
    }

    @Test fun settleWaitsForStillnessAndNewContent() {
        val s = Settle(stableTicks = 2)
        val a = IntArray(10) { 50 }
        val b = IntArray(10) { 120 }
        assertFalse(s.offer(a))          // first frame
        assertFalse(s.offer(a))          // still for 1 tick
        assertTrue(s.offer(a))           // still for 2 ticks: scan
        s.scanned(a)
        assertFalse(s.offer(a)); assertFalse(s.offer(a)); assertFalse(s.offer(a)) // same screen: no rescan
        assertFalse(s.offer(b))          // scrolling
        assertFalse(s.offer(b))
        assertTrue(s.offer(b))           // settled on a new screen
    }

    @Test fun continuousModeScansWhileMoving() {
        val s = Settle(stableTicks = 0, newDiff = 4.0)
        assertFalse(s.offer(IntArray(10) { 50 }))   // first frame only sets the baseline
        assertTrue(s.offer(IntArray(10) { 80 }))    // moving: scan anyway
        s.scanned(IntArray(10) { 80 })
        assertFalse(s.offer(IntArray(10) { 82 }))   // barely changed: skip
        assertTrue(s.offer(IntArray(10) { 100 }))   // scrolled on: scan
    }
}

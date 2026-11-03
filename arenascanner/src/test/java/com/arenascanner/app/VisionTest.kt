package com.arenascanner.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import javax.imageio.ImageIO

class VisionTest {
    /**
     * arena_row.png: a strip from a real Arena collection recording (2340×1080 phone, landscape),
     * showing the diamonds and name bars of the first 4 cards in the top row.
     * Names start at x = 71, 370, 669, 967 with their text top at y ≈ 80.
     */
    private val strip = ImageIO.read(javaClass.getResourceAsStream("/arena_row.png"))
    private val luma = { x: Int, y: Int ->
        val p = strip.getRGB(x, y)
        (((p shr 16) and 0xFF) * 3 + ((p shr 8) and 0xFF) * 6 + (p and 0xFF)) / 10
    }
    private val names = listOf(
        Box(71, 80, 300, 96),   // Insatiable Skittermaw: 3 owned
        Box(370, 80, 530, 96),  // Loot, the Anomaly: 1 owned
        Box(669, 81, 900, 97),  // Marauding Blight-Priest: 2 owned
        Box(967, 81, 1110, 97), // Memory Theft: 1 owned
    )
    private val expected = listOf(3, 1, 2, 1)

    private fun read(name: Box, cardW: Int) = PipCounter.count(luma, strip.width, strip.height, name, cardW).count

    @Test fun cardWidthFromRealSpacing() {
        val w = Layout.cardWidth(names, 2340, 1080)
        assertTrue("card width $w", w in 245..262) // measured 254 px
    }

    @Test fun readsRealArenaDiamonds() {
        val w = Layout.cardWidth(names, 2340, 1080)
        assertEquals(expected, names.map { read(it, w) })
    }

    @Test fun toleratesNameBoxOffByAFewPixels() {
        val w = Layout.cardWidth(names, 2340, 1080)
        for ((dx, dy) in listOf(-12 to -8, 12 to 8, -6 to 10, 10 to -10)) {
            val shifted = names.map { Box(it.left + dx, it.top + dy, it.right + dx, it.bottom + dy) }
            assertEquals("offset $dx,$dy", expected, shifted.map { read(it, w) })
        }
    }

    @Test fun fallbackWidthWorksForALoneCard() {
        // Only one name read: width comes from screen height instead of neighbour spacing.
        val w = Layout.cardWidth(listOf(names[2]), 2340, 1080)
        assertEquals(2, read(names[2], w))
    }

    @Test fun nothingAboveAName_givesNull() {
        // A "name" in the middle of the dark background has no diamonds above it.
        assertNull(read(Box(560, 60, 700, 76), 254))
    }

    @Test fun settleWaitsForStillnessAndNewContent() {
        val s = Settle(stableTicks = 2)
        val a = IntArray(10) { 50 }
        val b = IntArray(10) { 120 }
        assertFalse(s.offer(a))
        assertFalse(s.offer(a))
        assertTrue(s.offer(a))
        s.scanned(a)
        assertFalse(s.offer(a)); assertFalse(s.offer(a)); assertFalse(s.offer(a))
        assertFalse(s.offer(b))
        assertFalse(s.offer(b))
        assertTrue(s.offer(b))
    }

    @Test fun continuousModeScansWhileMoving() {
        val s = Settle(stableTicks = 0, newDiff = 4.0)
        assertFalse(s.offer(IntArray(10) { 50 }))
        assertTrue(s.offer(IntArray(10) { 80 }))
        s.scanned(IntArray(10) { 80 })
        assertFalse(s.offer(IntArray(10) { 82 }))
        assertTrue(s.offer(IntArray(10) { 100 }))
    }
}

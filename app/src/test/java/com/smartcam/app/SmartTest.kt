package com.smartcam.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class SmartTest {
    @Test fun brightSceneUsesBaseIso() {
        val e = Smart.plan(400, 1_000_000, 0f, 33_000_000, 50, 3200, 10_000, 500_000_000)
        assertEquals(50, e.iso)
        assertEquals(8_000_000, e.timeNs)
    }

    @Test fun shakyHandsCapShutterAndRaiseIso() {
        val limit = Smart.shutterLimitNs(0.5f, 0f, 1f, false)
        val e = Smart.plan(100, 100_000_000, 0f, limit, 50, 3200, 10_000, 500_000_000)
        assertTrue(e.timeNs <= limit)
        assertTrue(e.iso > 100)
    }

    @Test fun clippedHighlightsDarken() = assertTrue(Smart.highlightBias(0.1f) < 0f)

    @Test fun smallCentredFaceZoomsIn() =
        assertTrue(Smart.targetZoom(1900, 1400, 2100, 1600, 4000, 3000, 1f, 8f) > 2f)

    @Test fun edgeFaceDoesNotZoomIn() =
        assertEquals(1f, Smart.targetZoom(3800, 1400, 3990, 1600, 4000, 3000, 1f, 8f), 0.01f)

    @Test fun noiseSharperThanFlat() {
        val r = Random(1)
        val noisy = IntArray(64 * 64) { r.nextInt(256) }
        val flat = IntArray(64 * 64) { 128 }
        assertTrue(Smart.sharpness(noisy, 64, 64) > Smart.sharpness(flat, 64, 64))
    }
}

package com.pixelporters.game

import org.junit.Assert.assertEquals
import org.junit.Test

class LogicTest {
    @Test
    fun harness() {
        assertEquals("harness failures", 0, runHarness(200))
    }
}

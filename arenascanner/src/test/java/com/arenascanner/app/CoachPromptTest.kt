package com.arenascanner.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CoachPromptTest {
    private val elves = Card("Llanowar Elves", "C", rules = "{G} — Creature — Elf Druid — {T}: Add {G}. — 1/1")
    private val murder = Card("Murder", "C", rules = "{1}{B}{B} — Instant — Destroy target creature.")

    @Test fun includesRulesTextForEachCardOnce() {
        val p = CoachPrompt.build(listOf(elves, murder, elves), emptyList())
        assertTrue(p.contains("- Llanowar Elves: {G} — Creature — Elf Druid"))
        assertTrue(p.contains("- Murder: {1}{B}{B} — Instant"))
        assertEquals(1, Regex("Llanowar Elves").findAll(p).count())
    }

    @Test fun keepsShortScreenTextAndDropsLongLines() {
        val p = CoachPrompt.build(emptyList(), listOf("20", "17", "End Turn", "x".repeat(80)))
        assertTrue(p.contains("20 | 17 | End Turn"))
        assertFalse(p.contains("x".repeat(80)))
    }

    @Test fun limitsCardCount() {
        val many = (1..40).map { Card("Card $it", "C", rules = "text") }
        val p = CoachPrompt.build(many, emptyList(), maxCards = 25)
        assertTrue(p.contains("- Card 25:"))
        assertFalse(p.contains("- Card 26:"))
    }

    @Test fun asksForFixedFormat() {
        val p = CoachPrompt.build(listOf(elves), emptyList())
        assertTrue(p.endsWith("PLAY: ...\nWHY: ..."))
        assertTrue(CoachPrompt.SYSTEM.contains("PLAY:") && CoachPrompt.SYSTEM.contains("WHY:"))
    }
}

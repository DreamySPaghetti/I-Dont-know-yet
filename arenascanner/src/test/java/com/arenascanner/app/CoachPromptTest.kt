package com.arenascanner.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CoachPromptTest {
    private val elves = Card("Llanowar Elves", "C", rules = "{G} — Creature — Elf Druid — {T}: Add {G}. — 1/1")
    private val murder = Card("Murder", "C", rules = "{1}{B}{B} — Instant — Destroy target creature.")

    private val H = CoachPrompt.Zone.HAND
    private val T = CoachPrompt.Zone.THEIRS

    @Test fun groupsByZoneAndAddsKeywordReminders() {
        val flyer = Card("Serra Angel", "U", rules = "{3}{W}{W} — Creature — Angel — Flying, vigilance — 4/4")
        val p = CoachPrompt.build(listOf(flyer to T, elves to H), emptyList())
        assertTrue(p.indexOf("Your hand:") < p.indexOf("Opponent's battlefield:"))
        assertTrue(p.contains("- flying: "))
        assertTrue(p.contains("- vigilance: "))
        assertFalse(p.contains("- deathtouch: "))
    }

    @Test fun zonesFromScreenPosition() {
        assertEquals(T, CoachPrompt.zoneOf(0.2f, false))
        assertEquals(CoachPrompt.Zone.MINE, CoachPrompt.zoneOf(0.6f, false))
        assertEquals(H, CoachPrompt.zoneOf(0.9f, false))
        assertEquals(CoachPrompt.Zone.OPENING, CoachPrompt.zoneOf(0.2f, true))
    }

    @Test fun includesRulesTextForEachCardOnce() {
        val p = CoachPrompt.build(listOf(elves to H, murder to T, elves to H), emptyList())
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
        val many = (1..40).map { Card("Card $it", "C", rules = "text") to H }
        val p = CoachPrompt.build(many, emptyList(), maxCards = 25)
        assertTrue(p.contains("- Card 25:"))
        assertFalse(p.contains("- Card 26:"))
    }

    @Test fun asksForFixedFormat() {
        val p = CoachPrompt.build(listOf(elves to H), emptyList())
        assertTrue(p.endsWith("PLAY: ...\nWHY: ..."))
        assertTrue(CoachPrompt.SYSTEM.contains("PLAY:") && CoachPrompt.SYSTEM.contains("WHY:"))
    }

    @Test fun mulliganScreenAsksKeepOrMulligan() {
        val p = CoachPrompt.build(listOf(elves to CoachPrompt.Zone.OPENING), listOf("Mulligan", "Keep 7", "You will return 1 card"))
        assertTrue(p.contains("keep it or mulligan"))
        assertTrue(p.endsWith("PLAY: KEEP or MULLIGAN\nWHY: ..."))
    }
}

package com.arenascanner.app

import com.arenascanner.app.CoachPrompt.Seen
import com.arenascanner.app.CoachPrompt.Zone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CoachPromptTest {
    private val cards = listOf(
        Card("Seam Rip", "U", rules = "{W} — Enchantment — When this enchantment enters, exile target nonland permanent."),
        Card("Veteran Survivor", "U", rules = "{W} — Creature — Human Survivor — hexproof — 2/1"),
        Card("Shrine Keeper", "C", rules = "{1}{W} — Creature — Human Cleric — 2/2"),
    )
    private val match = { t: String -> cards.firstOrNull { Matcher.normalize(it.name) == Matcher.normalize(t) } }

    /** Text positions taken from a real blocking screenshot (2000×923 landscape). */
    private val blockScreen = listOf(
        Seen("15", 0.52f, 0.05f, 0.03f),
        Seen("Plains", 0.27f, 0.15f, 0.009f),
        Seen("Shrine Keeper", 0.48f, 0.28f, 0.009f),
        Seen("2/2", 0.50f, 0.39f, 0.015f),
        Seen("1/1", 0.58f, 0.38f, 0.015f),
        Seen("Choose blockers.", 0.52f, 0.42f, 0.02f),
        Seen("Veteran Survivor", 0.48f, 0.49f, 0.009f),
        Seen("5/3", 0.50f, 0.61f, 0.015f),
        Seen("2/1", 0.60f, 0.61f, 0.015f),
        Seen("Plains", 0.24f, 0.67f, 0.009f),
        Seen("Plains", 0.25f, 0.68f, 0.009f),
        Seen("Seam Rip", 0.51f, 0.81f, 0.013f),
        Seen("25", 0.52f, 0.93f, 0.03f),
        Seen("No Blocks", 0.89f, 0.92f, 0.03f),
    )

    @Test fun readsBlockingStep() {
        val b = CoachPrompt.analyze(blockScreen, match)
        assertEquals(CoachPrompt.Phase.BLOCK, b.phase)
        assertEquals(25, b.lifeYou)
        assertEquals(15, b.lifeOpp)
        assertEquals(mapOf("Plains" to 2), b.lands[Zone.MINE])
        assertEquals(listOf("5/3", "2/1"), b.creatures[Zone.MINE])
        assertEquals(listOf("2/2", "1/1"), b.creatures[Zone.THEIRS])
        assertTrue(b.cards.contains(cards[0] to Zone.HAND))
        assertTrue(b.cards.contains(cards[1] to Zone.MINE))
        assertTrue(b.cards.contains(cards[2] to Zone.THEIRS))
        val p = CoachPrompt.build(b)
        assertTrue(p.contains("must choose blockers"))
        assertTrue(p.contains("Lands already in play (at least): Plains x2"))
        assertTrue(p.contains("don't tell me to play them again"))
        assertTrue(p.contains("Life: me 25, opponent 15."))
        assertTrue(p.contains("- hexproof: "))
    }

    @Test fun handLandIsNotInPlay() {
        val b = CoachPrompt.analyze(listOf(Seen("Plains", 0.25f, 0.76f, 0.013f), Seen("To Combat", 0.88f, 0.98f, 0.015f)), match)
        assertEquals(mapOf("Plains" to 1), b.lands[Zone.HAND])
        assertEquals(CoachPrompt.Phase.MAIN1, b.phase)
        assertTrue(CoachPrompt.build(b).contains("- Basic lands: Plains x1"))
    }

    @Test fun typeLineIsNotACard() {
        val b = CoachPrompt.analyze(listOf(Seen("Basic Land - Plains", 0.3f, 0.8f, 0.012f)), match)
        assertTrue(b.cards.isEmpty())
        assertTrue(b.lands.isEmpty())
    }

    @Test fun mulliganScreen() {
        val b = CoachPrompt.analyze(listOf(Seen("Seam Rip", 0.5f, 0.3f, 0.02f), Seen("Mulligan", 0.4f, 0.85f, 0.04f), Seen("Keep 7", 0.6f, 0.85f, 0.04f)), match)
        assertEquals(CoachPrompt.Phase.MULLIGAN, b.phase)
        assertEquals(cards[0] to Zone.OPENING, b.cards.single())
        assertTrue(CoachPrompt.build(b).endsWith("PLAY: KEEP or MULLIGAN\nWHY: ..."))
    }

    @Test fun keywordRemindersOnlyForVisibleCards() {
        val p = CoachPrompt.build(CoachPrompt.analyze(listOf(Seen("Veteran Survivor", 0.5f, 0.5f, 0.009f)), match))
        assertTrue(p.contains("- hexproof: "))
        assertFalse(p.contains("- deathtouch: "))
    }
}

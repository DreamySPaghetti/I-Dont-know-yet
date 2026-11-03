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

    @Test fun highlightedHandCardsArePlayable() {
        // From a real main-phase screenshot: Plains in hand not outlined (land already played), Seam Rip outlined.
        val b = CoachPrompt.analyze(listOf(
            Seen("Plains", 0.25f, 0.76f, 0.013f, glow = 0f),
            Seen("Seam Rip", 0.40f, 0.70f, 0.013f, glow = 0.29f),
            Seen("To Combat", 0.88f, 0.98f, 0.015f),
        ), match)
        assertEquals(setOf("Seam Rip"), b.playable)
        val p = CoachPrompt.build(b)
        assertTrue(p.contains("Seam Rip [PLAYABLE NOW]"))
        assertTrue(p.contains("already played a land this turn"))
        assertTrue(p.contains("Only suggest cards marked [PLAYABLE NOW]"))
    }

    @Test fun glowFractionCountsBrightBlue() {
        val blue = 0x10C8FF
        val all = CoachPrompt.glowFraction({ _, _ -> blue }, 400, 400, 100, 200, 200, 216)
        val none = CoachPrompt.glowFraction({ _, _ -> 0x3A4050 }, 400, 400, 100, 200, 200, 216)
        assertEquals(1f, all, 0.001f)
        assertEquals(0f, none, 0.001f)
    }

    /** From the match log: hand collapsed, "Negate" (instant) at the same height as battlefield lands. */
    @Test fun spellsAreNeverOnTheBattlefieldAndStacksCount() {
        val negate = Card("Negate", "C", rules = "{1}{U} — Instant — Counter target noncreature spell.")
        val turtle = Card("Aegis Turtle", "C", rules = "{U} — Creature — Turtle — 0/5")
        val m = { t: String -> listOf(negate, turtle).firstOrNull { it.name == t } }
        val b = CoachPrompt.analyze(listOf(
            Seen("Aegis Turtle", 0.58f, 0.50f, 0.007f),
            Seen("x4", 0.09f, 0.70f, 0.012f),
            Seen("Island", 0.107f, 0.67f, 0.007f),
            Seen("Negate", 0.47f, 0.70f, 0.009f),
            Seen("To Combat", 0.88f, 0.98f, 0.015f),
        ), m)
        assertTrue(b.cards.contains(negate to Zone.HAND))
        assertTrue(b.cards.contains(turtle to Zone.MINE))
        assertEquals(mapOf("Island" to 4), b.lands[Zone.MINE])
        val p = CoachPrompt.build(b)
        assertTrue(p.contains("never cast or play these again): Aegis Turtle"))
    }

    @Test fun warnsAboutCastingWhatIsInPlayAndTwoLands() {
        val turtle = Card("Aegis Turtle", "C", rules = "{U} — Creature — Turtle — 0/5")
        val b = CoachPrompt.Board(listOf(turtle to Zone.MINE), emptyMap(), emptyMap(), null, null, CoachPrompt.Phase.MAIN1, emptyList())
        assertEquals(listOf("Aegis Turtle is already on your battlefield."), CoachPrompt.check("PLAY: Cast Aegis Turtle.\nWHY: x", b))
        assertTrue(CoachPrompt.check("PLAY: Attack with Aegis Turtle.", b).isEmpty())
        assertEquals(listOf("You can only play one land per turn."), CoachPrompt.check("PLAY: Play the basic Island and the basic Island.", b))
    }

    @Test fun retryNamesTheMistakesAndKeepsTheFormat() {
        val r = CoachPrompt.retry("Board...\nWhat now? Answer in exactly this format:\nPLAY: ...\nWHY: ...", "PLAY: Cast Aegis Turtle", listOf("Aegis Turtle is already on your battlefield."))
        assertTrue(r.contains("My first answer was: \"PLAY: Cast Aegis Turtle\""))
        assertTrue(r.contains("- Aegis Turtle is already on your battlefield."))
        assertTrue(r.endsWith("PLAY: ...\nWHY: ..."))
    }

    @Test fun manaValueAndLands() {
        assertEquals(3, CoachPrompt.manaValue(Card("Murder", "C", rules = "{1}{B}{B} — Instant — Destroy target creature.")))
        assertEquals(null, CoachPrompt.manaValue(Card("Thornwood Falls", "C", rules = "Land — This land enters tapped.")))
        assertTrue(CoachPrompt.isLand(Card("Thornwood Falls", "C", rules = "Land — This land enters tapped.")))
    }

    @Test fun openingHandFacts() {
        val falls = Card("Thornwood Falls", "C", rules = "Land — This land enters tapped.")
        val elves = Card("Llanowar Elves", "C", rules = "{G} — Creature — Elf Druid — 1/1")
        val wurm = Card("Wurm", "C", rules = "{4}{G}{G} — Creature — Wurm — 6/6")
        val b = CoachPrompt.Board(listOf(falls to Zone.OPENING, elves to Zone.OPENING, wurm to Zone.OPENING),
            mapOf(Zone.OPENING to mapOf("Forest" to 2)), emptyMap(), null, null, CoachPrompt.Phase.MULLIGAN, emptyList())
        assertTrue(CoachPrompt.openingFacts(b)!!.startsWith("Opening hand facts: 3 lands, 2 spells, 1 of them cost 2 or less"))
    }

    @Test fun blockMathFromTheBlockingScreenshot() {
        val b = CoachPrompt.analyze(blockScreen, match)
        val f = CoachPrompt.combatFacts(b)!!
        assertTrue(f, f.contains("If their 2/2 attacks: your 5/3 kills it and survives; your 2/1 trades with it"))
        assertTrue(f, f.contains("If their 1/1 attacks: your 5/3 kills it and survives; your 2/1 kills it and survives"))
    }

    @Test fun attackMath() {
        val b = CoachPrompt.Board(emptyList(), emptyMap(), mapOf(Zone.MINE to listOf("5/3", "1/1"), Zone.THEIRS to listOf("2/2", "0/5")),
            null, null, CoachPrompt.Phase.ATTACK, emptyList())
        val f = CoachPrompt.combatFacts(b)!!
        assertTrue(f, f.contains("Your 5/3 attacking: no blocker of theirs kills it; kills a blocking 2/2"))
        assertTrue(f, f.contains("Your 1/1 attacking: dies if blocked by their 2/2"))
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

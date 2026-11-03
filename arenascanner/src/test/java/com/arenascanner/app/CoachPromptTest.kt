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
        // Shrine Keeper's name sits above its 2/2 box, so it's linked; the 1/1's name wasn't read.
        assertTrue(f, f.contains("If their Shrine Keeper 2/2 attacks: your Veteran Survivor 5/3 kills it and survives; your 2/1 trades with it"))
        assertTrue(f, f.contains("If their 1/1 attacks: your Veteran Survivor 5/3 kills it and survives; your 2/1 trades with it"))
    }

    @Test fun abilitiesChangeTheMath() {
        val sailor = CoachPrompt.Fighter("Spectral Sailor 1/1", 1, 1, setOf("flying"))
        val wall = CoachPrompt.Fighter("Wall 0/5", 0, 5)
        val ninja = CoachPrompt.Fighter("Ninja 2/2", 2, 2, setOf("deathtouch"))
        val knight = CoachPrompt.Fighter("Knight 2/2", 2, 2, setOf("first strike"))
        val bear = CoachPrompt.Fighter("Bear 2/2", 2, 2)
        assertEquals(false to true, CoachPrompt.fight(knight, bear))   // first strike kills before it's hit
        assertEquals(false to true, CoachPrompt.fight(ninja, wall))    // deathtouch kills the 0/5 wall; the wall deals 0
        val block = CoachPrompt.Board(emptyList(), emptyMap(), emptyMap(), null, null, CoachPrompt.Phase.BLOCK, emptyList(),
            fighters = mapOf(Zone.THEIRS to listOf(sailor), Zone.MINE to listOf(wall, bear)))
        assertTrue(CoachPrompt.combatFacts(block)!!.contains("If their Spectral Sailor 1/1 (flying) attacks: none of your creatures can block it"))
        val attack = block.copy(phase = CoachPrompt.Phase.ATTACK, fighters = mapOf(Zone.MINE to listOf(bear), Zone.THEIRS to listOf(ninja)))
        assertTrue(CoachPrompt.combatFacts(attack)!!.contains("Your Bear 2/2 attacking: dies if blocked by their Ninja 2/2; kills a blocking Ninja 2/2"))
    }

    @Test fun lethalAndDanger() {
        val flier = CoachPrompt.Fighter("Angel 4/4", 4, 4, setOf("flying"))
        val bear = CoachPrompt.Fighter("Bear 2/2", 2, 2)
        val wall = CoachPrompt.Fighter("Wall 0/5", 0, 5)
        val atk = CoachPrompt.Board(emptyList(), emptyMap(), emptyMap(), 10, 5, CoachPrompt.Phase.ATTACK, emptyList(),
            fighters = mapOf(Zone.MINE to listOf(flier, bear), Zone.THEIRS to listOf(wall)))
        // Wall blocks the bear; the flier's 4 gets through: not lethal at 5, lethal at 4.
        assertTrue(CoachPrompt.combatFacts(atk)!!.contains("at least 4 damage gets through (they're at 5)"))
        assertTrue(CoachPrompt.combatFacts(atk.copy(lifeOpp = 4))!!.contains("LETHAL"))
        val blk = atk.copy(phase = CoachPrompt.Phase.BLOCK, lifeYou = 3,
            fighters = mapOf(Zone.MINE to listOf(wall), Zone.THEIRS to listOf(flier)))
        assertTrue(CoachPrompt.combatFacts(blk)!!.contains("DANGER: their creatures have 4 power and you're at 3"))
    }

    @Test fun tappedCardsChangeManaAndCombat() {
        val b = CoachPrompt.analyze(listOf(
            Seen("x3", 0.09f, 0.70f, 0.012f),
            Seen("Island", 0.107f, 0.67f, 0.007f),                    // untapped stack of 3
            Seen("Island", 0.20f, 0.67f, 0.007f, angle = 90f),       // one tapped Island
            Seen("To Combat", 0.88f, 0.98f, 0.015f),
        ), match)
        assertEquals(3, b.untappedLands)
        assertEquals(4, b.totalLands)
        assertTrue(CoachPrompt.build(b).contains("3 of my 4 lands are untapped"))

        val attacker = CoachPrompt.Fighter("Bear 2/2", 2, 2, tapped = true)
        val idle = CoachPrompt.Fighter("Ox 5/5", 5, 5)
        val wall = CoachPrompt.Fighter("Wall 0/5", 0, 5)
        val blk = CoachPrompt.Board(emptyList(), emptyMap(), emptyMap(), 20, 20, CoachPrompt.Phase.BLOCK, emptyList(),
            fighters = mapOf(Zone.THEIRS to listOf(attacker, idle), Zone.MINE to listOf(wall)))
        val f = CoachPrompt.combatFacts(blk)!!
        assertTrue(f, f.contains("Attacking you (tapped): Bear 2/2"))
        assertFalse(f, f.contains("If their Ox 5/5 attacks"))
    }

    @Test fun instantSpeedDuringCombat() {
        val murder = Card("Murder", "C", rules = "{1}{B}{B} — Instant — Destroy target creature.")
        val bear = Card("Grizzly Bears", "C", rules = "{1}{G} — Creature — Bear — 2/2")
        val b = CoachPrompt.Board(listOf(murder to Zone.HAND, bear to Zone.HAND), emptyMap(), emptyMap(), null, null, CoachPrompt.Phase.BLOCK, emptyList())
        assertTrue(CoachPrompt.build(b).contains("Instant-speed cards I could use right now: Murder."))
        assertEquals(listOf("Grizzly Bears can't be cast now: it isn't an instant and doesn't have flash."),
            CoachPrompt.check("PLAY: Cast Grizzly Bears and block.", b))
        assertTrue(CoachPrompt.check("PLAY: Cast Murder on the attacker.", b).isEmpty())
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

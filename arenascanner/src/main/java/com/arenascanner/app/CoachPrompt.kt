package com.arenascanner.app

/**
 * What the move coach tells Gemma. Kept as plain text (no Android types) so it is unit-tested.
 * A 2B on-device model reads small card text poorly, so the prompt hands it each visible card's
 * real rules text and asks for a short, fixed answer format.
 */
object CoachPrompt {
    const val SYSTEM = "You are an expert Magic: The Gathering Arena coach. Give the single best play for the " +
        "player's current turn, based on the screenshot and the card text provided. " +
        "Trust the provided card text over what you see in the image. Never invent cards that are not visible or listed. " +
        "Be concrete: which land to play, which spells to cast and in what order, which creatures attack, what to hold back.\n" +
        "Strategy checklist:\n" +
        "1. Lethal: if your attackers' total power beats the opponent's life after their possible blocks, attack with everything.\n" +
        "2. Play a land every turn if you have one in hand.\n" +
        "3. Use your mana efficiently: prefer the play that spends the most mana this turn (curve out).\n" +
        "4. Attack before casting non-haste creatures, unless the spell helps the attack (removal, pump, haste).\n" +
        "5. Attack only where blocks are bad for the opponent; avoid losing a creature for nothing unless you are racing.\n" +
        "6. Save removal for the opponent's best threat; use it now if that creature will hurt you next turn.\n" +
        "7. When ahead, keep pressure; when behind, block well and stabilize.\n" +
        "Answer in exactly two short lines:\n" +
        "PLAY: one or two sentences.\n" +
        "WHY: one or two sentences.\n" +
        "If the screen shows the opening-hand choice (Mulligan / Keep buttons), answer instead:\n" +
        "PLAY: KEEP or MULLIGAN.\n" +
        "WHY: count lands and spells. Keep 7 cards with 2-5 lands and plays on turns 1-3; mulligan 0-1 or 6-7 land hands.\n" +
        "If it is not the player's turn or the screen is unclear, say so in one line instead."

    /** Short rules reminders, added only for keywords that appear on the visible cards. */
    val KEYWORDS = linkedMapOf(
        "flying" to "can only be blocked by creatures with flying or reach",
        "reach" to "can block flying creatures",
        "deathtouch" to "any damage it deals to a creature destroys it",
        "trample" to "excess combat damage goes to the player",
        "lifelink" to "damage it deals also gains you that much life",
        "first strike" to "deals combat damage before creatures without first strike",
        "double strike" to "deals first-strike and regular combat damage",
        "menace" to "can't be blocked except by two or more creatures",
        "vigilance" to "attacking doesn't tap it",
        "haste" to "can attack and tap the turn it comes in",
        "defender" to "can't attack",
        "flash" to "can be cast any time you could cast an instant",
        "hexproof" to "can't be targeted by opponents' spells or abilities",
        "ward" to "opponents' spells/abilities targeting it are countered unless they pay the ward cost",
        "indestructible" to "isn't destroyed by damage or destroy effects",
        "prowess" to "gets +1/+1 until end of turn whenever you cast a noncreature spell",
        "toxic" to "combat damage to a player also gives that many poison counters",
        "surveil" to "look at the top cards, put any in your graveyard, rest back on top",
        "scry" to "look at the top cards, put any on the bottom, rest back on top",
    )

    /** Where a card was seen: decides whether it's in your hand, on your side, or the opponent's. */
    enum class Zone(val label: String) { HAND("Your hand"), MINE("Your battlefield"), THEIRS("Opponent's battlefield"), OPENING("Opening hand") }

    /** Zone from the vertical centre of the card's name on screen (fraction of screen height). */
    fun zoneOf(centreY: Float, mulligan: Boolean) = when {
        mulligan -> Zone.OPENING
        centreY < 0.45f -> Zone.THEIRS
        centreY < 0.72f -> Zone.MINE
        else -> Zone.HAND
    }

    fun isMulligan(text: List<String>) =
        text.any { it.contains("Mulligan", ignoreCase = true) } && text.any { it.trim().startsWith("Keep", ignoreCase = true) }

    /**
     * The question sent with the screenshot.
     * [cards]: cards recognised on screen with the zone each was seen in.
     * [otherText]: other short text read from the screen, e.g. life totals, phase, button labels.
     */
    fun build(cards: List<Pair<Card, Zone>>, otherText: List<String>, maxCards: Int = 25): String {
        val sb = StringBuilder()
        sb.append("This is a screenshot of my MTG Arena game on my phone. My side is the bottom half of the screen ")
            .append("and my hand is along the bottom edge; my opponent is the top half.\n\n")
        val unique = cards.distinctBy { it.first.name }.take(maxCards)
        for (zone in Zone.values()) {
            val inZone = unique.filter { it.second == zone }
            if (inZone.isEmpty()) continue
            sb.append(zone.label).append(":\n")
            for ((c, _) in inZone) {
                sb.append("- ").append(c.name)
                if (c.rules.isNotEmpty()) sb.append(": ").append(c.rules.take(320))
                sb.append('\n')
            }
            sb.append('\n')
        }
        val text = unique.joinToString(" ") { it.first.rules.lowercase() }
        val kw = KEYWORDS.filterKeys { Regex("\\b" + Regex.escape(it) + "\\b").containsMatchIn(text) }
        if (kw.isNotEmpty()) {
            sb.append("Keyword reminders:\n")
            for ((k, v) in kw) sb.append("- ").append(k).append(": ").append(v).append('\n')
            sb.append('\n')
        }
        val other = otherText.map { it.trim() }.filter { it.length in 1..40 }.distinct().joinToString(" | ").take(500)
        if (other.isNotEmpty()) sb.append("Other text on screen (life totals, mana, phase, buttons): ").append(other).append("\n\n")
        if (isMulligan(otherText)) sb.append("This is my opening hand. Should I keep it or mulligan? Answer in exactly this format:\nPLAY: KEEP or MULLIGAN\nWHY: ...")
        else sb.append("What is my best play this turn? Answer in exactly this format:\nPLAY: ...\nWHY: ...")
        return sb.toString()
    }
}

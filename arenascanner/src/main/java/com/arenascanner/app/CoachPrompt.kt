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
        "Be concrete: which land to play, which spells to cast and in what order, which creatures attack, what to hold back. " +
        "Consider available mana, the opponent's untapped creatures, and both life totals. " +
        "Answer in exactly two short lines:\n" +
        "PLAY: one or two sentences.\n" +
        "WHY: one or two sentences.\n" +
        "If the screen shows the opening-hand choice (Mulligan / Keep buttons), answer instead:\n" +
        "PLAY: KEEP or MULLIGAN.\n" +
        "WHY: count the lands and spells and say whether the hand can cast spells on curve.\n" +
        "If it is not the player's turn or the screen is unclear, say so in one line instead."

    /**
     * The question sent with the screenshot.
     * [cards]: card names recognised on screen (with rules text when the card list has it).
     * [otherText]: other short text read from the screen, e.g. life totals, phase, button labels.
     */
    fun build(cards: List<Card>, otherText: List<String>, maxCards: Int = 25): String {
        val sb = StringBuilder()
        sb.append("This is a screenshot of my MTG Arena game on my phone. My side is the bottom half of the screen ")
            .append("and my hand is along the bottom edge; my opponent is the top half.\n\n")
        val unique = cards.distinctBy { it.name }.take(maxCards)
        if (unique.isNotEmpty()) {
            sb.append("Cards I can read on screen:\n")
            for (c in unique) {
                sb.append("- ").append(c.name)
                if (c.rules.isNotEmpty()) sb.append(": ").append(c.rules.take(320))
                sb.append('\n')
            }
            sb.append('\n')
        }
        val other = otherText.map { it.trim() }.filter { it.length in 1..40 }.distinct().joinToString(" | ").take(500)
        if (other.isNotEmpty()) sb.append("Other text on screen (life totals, mana, phase, buttons): ").append(other).append("\n\n")
        val mulligan = otherText.any { it.contains("Mulligan", ignoreCase = true) } &&
            otherText.any { it.trim().startsWith("Keep", ignoreCase = true) }
        if (mulligan) sb.append("This is my opening hand. Should I keep it or mulligan? Answer in exactly this format:\nPLAY: KEEP or MULLIGAN\nWHY: ...")
        else sb.append("What is my best play this turn? Answer in exactly this format:\nPLAY: ...\nWHY: ...")
        return sb.toString()
    }
}

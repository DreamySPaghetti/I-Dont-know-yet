package com.arenascanner.app

/** Which of your cards are legal in a format, using Scryfall's legality data. Pure, so it's unit-tested. */
object FormatExport {
    /** Scryfall format key to the name shown in the app. Arena formats first. */
    val FORMATS = linkedMapOf(
        "standard" to "Standard", "alchemy" to "Alchemy", "explorer" to "Explorer", "historic" to "Historic",
        "timeless" to "Timeless", "standardbrawl" to "Standard Brawl", "brawl" to "Brawl", "pioneer" to "Pioneer",
        "modern" to "Modern", "legacy" to "Legacy", "vintage" to "Vintage", "pauper" to "Pauper", "commander" to "Commander",
    )

    data class Result(val legal: List<Owned>, val unknown: List<Owned>) {
        val copies get() = legal.sumOf { it.copies }
    }

    /** [legalOf] gives a card's legal formats, or null if the card isn't in the card list. */
    fun filter(cards: List<Owned>, format: String, legalOf: (String) -> Set<String>?): Result {
        val legal = mutableListOf<Owned>(); val unknown = mutableListOf<Owned>()
        for (c in cards) {
            val l = legalOf(c.name)
            when {
                l == null -> unknown += c
                format in l -> legal += c
            }
        }
        return Result(legal, unknown)
    }

    /** "4 Card Name" lines: pastes into Arena's deck import, Moxfield, Archidekt and most deck sites. */
    fun deckList(cards: List<Owned>) = cards.joinToString("\n") { "${it.copies} ${it.name}" }
}

package com.arenascanner.app

import org.junit.Assert.assertEquals
import org.junit.Test

class FormatExportTest {
    private val cards = listOf(Owned("Llanowar Elves", "C", 4), Owned("Thoughtseize", "R", 2), Owned("Mystery Card", "U", 1))
    private val db = mapOf(
        "Llanowar Elves" to setOf("standard", "pioneer", "historic"),
        "Thoughtseize" to setOf("pioneer", "historic", "modern"),
    )

    @Test fun keepsOnlyLegalCardsAndReportsUnknown() {
        val r = FormatExport.filter(cards, "pioneer") { db[it] }
        assertEquals(listOf("Llanowar Elves", "Thoughtseize"), r.legal.map { it.name })
        assertEquals(6, r.copies)
        assertEquals(listOf("Mystery Card"), r.unknown.map { it.name })
        assertEquals(listOf("Llanowar Elves"), FormatExport.filter(cards, "standard") { db[it] }.legal.map { it.name })
    }

    @Test fun deckListFormat() = assertEquals("4 Llanowar Elves\n2 Thoughtseize", FormatExport.deckList(cards.take(2)))
}

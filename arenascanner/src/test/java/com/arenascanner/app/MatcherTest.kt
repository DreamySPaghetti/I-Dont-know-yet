package com.arenascanner.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MatcherTest {
    private val cards = listOf(
        Card("Sheoldred, the Apocalypse", "M"),
        Card("Go for the Throat", "U"),
        Card("Llanowar Elves", "C"),
        Card("Opt", "C"),
        Card("Forest", "C"),
        Card("Wastes", "C"),
        Card("Fable of the Mirror-Breaker", "R", listOf("Fable of the Mirror-Breaker", "Reflection of Kiki-Jiki")),
    )
    private val m = Matcher(cards)

    @Test fun exactNameMatches() = assertEquals("Llanowar Elves", m.match("Llanowar Elves")?.name)

    @Test fun punctuationAndCaseIgnored() = assertEquals("Sheoldred, the Apocalypse", m.match("SHEOLDRED THE APOCALYPSE")?.name)

    @Test fun oneMisreadLetterStillMatches() = assertEquals("Go for the Throat", m.match("Go for the Thr0at")?.name)

    @Test fun strayManaLettersDropped() = assertEquals("Llanowar Elves", m.match("Llanowar Elves G")?.name)

    @Test fun backFaceFindsCard() = assertEquals("Fable of the Mirror-Breaker", m.match("Reflection of Kiki-Jiki")?.name)

    @Test fun rulesTextIgnored() {
        assertNull(m.match("Creature — Elf Druid"))
        assertNull(m.match("Destroy target artifact creature"))
    }

    @Test fun shortWordsNeedExactMatch() {
        assertEquals("Opt", m.match("Opt")?.name)
        assertNull(m.match("Oft"))
    }

    @Test fun fuzzyMatchStillFindsMisreadNamesViaHeadOrTail() {
        assertEquals("Sheoldred, the Apocalypse", m.match("Sheoldred the Apocalypsc")?.name) // last letter misread
        assertEquals("Sheoldred, the Apocalypse", m.match("Shcoldred the Apocalypse")?.name) // early letter misread
    }

    @Test fun basicLandsIgnored() {
        assertNull(m.match("Forest"))
        assertNull(m.match("Wastes"))
    }

    @Test fun matchAllDeduplicates() {
        val found = m.matchAll(listOf("Opt", "Instant", "Opt", "Llanowar Elves", "Collection"))
        assertEquals(listOf("Opt", "Llanowar Elves"), found.map { it.name })
    }
}

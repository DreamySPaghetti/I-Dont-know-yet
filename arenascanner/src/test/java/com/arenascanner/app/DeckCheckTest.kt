package com.arenascanner.app

import org.junit.Assert.assertEquals
import org.junit.Test

class DeckCheckTest {
    private val export = """
        Deck
        4 Llanowar Elves (FDN) 227
        2 Murder (FDN) 70
        20 Forest (FDN) 280

        Sideboard
        2 Murder (FDN) 70
        1 Negate (FDN) 158
    """.trimIndent()

    @Test fun parsesArenaExportWithSideboard() {
        val p = DeckCheck.parse(export)
        assertEquals(Triple("Llanowar Elves", 4, false), p[0])
        assertEquals(Triple("Murder", 2, true), p[3])
        assertEquals(5, p.size)
    }

    @Test fun countsMissingAndWildcardsSkippingBasics() {
        val own = mapOf("llanowar elves" to 3, "murder" to 2)
        val rar = mapOf("llanowar elves" to "C", "murder" to "U", "negate" to "C")
        val r = DeckCheck.check(export, { own[it.lowercase()] ?: 0 }, { rar[it.lowercase()] ?: "?" })
        // Murder: 2 main + 2 side = 4 needed, 2 owned -> 2 uncommon. Elves 1 common, Negate 1 common.
        assertEquals(mapOf("M" to 0, "R" to 0, "U" to 2, "C" to 2, "?" to 0), r.wildcards)
        assertEquals(false, r.lines.any { it.name == "Forest" })
    }
}

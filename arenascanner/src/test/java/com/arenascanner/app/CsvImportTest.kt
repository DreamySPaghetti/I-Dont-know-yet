package com.arenascanner.app

import org.junit.Assert.assertEquals
import org.junit.Test

class CsvImportTest {
    @Test fun readsOwnExportWithQuotedNames() {
        val csv = "Count,Name,Rarity,Set by hand\r\n4,Murder,Common,no\r\n1,\"Loot, the Nexus\",Rare,yes\r\n"
        assertEquals(listOf(Triple("Murder", 4, "C"), Triple("Loot, the Nexus", 1, "R")), CollectionStore.parseCsv(csv))
    }

    @Test fun acceptsOtherColumnOrdersAndSkipsBadRows() {
        val csv = "Name,Quantity\nOpt,2\nBroken,x\n,3\n"
        assertEquals(listOf(Triple("Opt", 2, null)), CollectionStore.parseCsv(csv))
    }

    @Test fun needsCountAndName() = assertEquals(emptyList<Triple<String, Int, String?>>(), CollectionStore.parseCsv("a,b\n1,2"))
}

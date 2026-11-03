package com.arenascanner.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LessonTest {
    private fun l(t: Long, phase: String, vararg cards: String) = Lesson(t, phase, cards.toList(), "board", "ans", "PLAY: x$t", false)

    @Test fun picksSameStepWithSharedCards() {
        val all = listOf(l(1, "BLOCK", "Veteran Survivor"), l(2, "MAIN1", "Seam Rip"), l(3, "BLOCK", "Shrine Keeper"))
        val best = LessonMatch.best(all, "BLOCK", setOf("Veteran Survivor", "Seam Rip"))
        assertEquals(listOf(1L), best.map { it.time })
    }

    @Test fun manySharedCardsCountEvenInAnotherStep() {
        val all = listOf(l(1, "MAIN2", "A", "B", "C"))
        assertEquals(1, LessonMatch.best(all, "MAIN1", setOf("A", "B", "C")).size)
    }

    @Test fun capsAtThreeNewestFirstOnTies() {
        val all = (1L..6L).map { l(it, "MAIN1", "A") }
        assertEquals(listOf(6L, 5L, 4L), LessonMatch.best(all, "MAIN1", setOf("A")).map { it.time })
    }

    @Test fun lessonsAppearInPrompt() {
        val b = CoachPrompt.Board(emptyList(), emptyMap(), emptyMap(), null, null, CoachPrompt.Phase.BLOCK, emptyList())
        val p = CoachPrompt.build(b, lessons = listOf(l(1, "BLOCK", "A")))
        assertTrue(p.contains("Lessons from my past games"))
        assertTrue(p.contains("right play: PLAY: x1"))
    }
}

package com.eider.karoomaverickhud.settings

import org.junit.Assert.assertEquals
import org.junit.Test

/** Verifies which numbered pages race mode cycles ([raceBasePages] filter + fallback + cap). */
class RaceModeTest {

    private val a = listOf("a1", "a2")
    private val b = listOf("b1", "b2")
    private val c = listOf("c1", "c2")

    @Test
    fun keepsOnlyFlaggedPages() {
        assertEquals(listOf(a, c), raceBasePages(listOf(a, b, c), listOf(true, false, true), cap = 4))
    }

    @Test
    fun missingFlagCountsAsIncluded() {
        // Only one flag supplied; the second page has no flag → still included.
        assertEquals(listOf(a, b), raceBasePages(listOf(a, b), listOf(true), cap = 4))
    }

    @Test
    fun emptyFlagsIncludesEveryPage() {
        assertEquals(listOf(a, b), raceBasePages(listOf(a, b), emptyList(), cap = 4))
    }

    @Test
    fun fallsBackToAllPagesWhenNoneFlagged() {
        // Race mode must never be blank — all-false falls back to every page.
        assertEquals(listOf(a, b), raceBasePages(listOf(a, b), listOf(false, false), cap = 4))
    }

    @Test
    fun capsFieldsPerPage() {
        val big = listOf("1", "2", "3", "4", "5", "6")
        assertEquals(listOf(listOf("1", "2", "3", "4")), raceBasePages(listOf(big), listOf(true), cap = 4))
    }
}

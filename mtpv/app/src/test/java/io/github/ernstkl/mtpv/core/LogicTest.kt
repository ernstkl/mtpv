package io.github.ernstkl.mtpv.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PagingTest {
    @Test
    fun followingPages() {
        assertEquals(listOf(3, 4, 5), (0..2).map { Paging.pageFor(3, it, 12) })
    }

    @Test
    fun pastTheEndIsBlank() {
        assertEquals(listOf(11, 12, 0), (0..2).map { Paging.pageFor(11, it, 12) })
    }

    @Test
    fun startStopsWhenLastPageIsOnFirstScreen() {
        assertEquals(11, Paging.clampStart(11, 12))
        assertEquals(12, Paging.clampStart(13, 12))
        assertEquals(1, Paging.clampStart(0, 12))
        assertEquals(listOf(12, 0, 0), (0..2).map { Paging.pageFor(12, it, 12) })
    }

    @Test
    fun fewerPagesThanScreens() {
        assertEquals(2, Paging.clampStart(3, 2))
        assertEquals(listOf(1, 2, 0), (0..2).map { Paging.pageFor(1, it, 2) })
    }
}

class VersionGateTest {
    @Test
    fun newerVersionFromSameController() {
        assertTrue(VersionGate.accepts("a", 5, "a", 6))
        assertFalse(VersionGate.accepts("a", 5, "a", 5))
        assertFalse(VersionGate.accepts("a", 5, "a", 4))
    }

    @Test
    fun otherControllerAlwaysWins() {
        assertTrue(VersionGate.accepts("a", 5, "b", 1))
        assertTrue(VersionGate.accepts(null, 0, "a", 1))
    }
}

class EvictionTest {
    private fun e(id: String, size: Long, used: Long) = Eviction.Entry(id, size, used)

    @Test
    fun oldestGoFirst() {
        val entries = listOf(e("old", 10, 1), e("mid", 10, 2), e("new", 10, 3))
        assertEquals(listOf("old"), Eviction.victims(entries, emptySet(), maxFiles = 2, maxBytes = 1000))
    }

    @Test
    fun keptFilesSurviveEvenWhenOldest() {
        val entries = listOf(e("old", 10, 1), e("mid", 10, 2), e("new", 10, 3))
        assertEquals(listOf("mid"), Eviction.victims(entries, setOf("old"), maxFiles = 2, maxBytes = 1000))
    }

    @Test
    fun byteLimit() {
        val entries = listOf(e("a", 60, 1), e("b", 60, 2))
        assertEquals(listOf("a"), Eviction.victims(entries, emptySet(), maxFiles = 10, maxBytes = 100))
    }
}

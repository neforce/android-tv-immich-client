package com.immichclient.tv

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VerjaardagTest {
    private fun d(s: String) = LocalDate.parse(s)

    @Test fun vandaagJarig() {
        assertEquals(d("2026-11-19"), Jarig.recent(d("1989-11-19"), d("2026-11-19"), 2))
        assertEquals(37, Jarig.leeftijd(d("1989-11-19"), d("2026-11-19")))
    }

    @Test fun dagenErna() {
        val g = d("1989-11-19")
        assertEquals(d("2026-11-19"), Jarig.recent(g, d("2026-11-21"), 2))
        assertNull(Jarig.recent(g, d("2026-11-22"), 2))
        assertNull(Jarig.recent(g, d("2026-11-23"), 2))
        assertEquals(d("2026-11-19"), Jarig.recent(g, d("2026-11-23"), 4))
        assertNull(Jarig.recent(g, d("2026-11-20"), 0))
        // vóór de verjaardag: niets
        assertNull(Jarig.recent(g, d("2026-11-18"), 7))
    }

    @Test fun jaarwisseling() {
        val g = d("1980-12-31")
        assertEquals(d("2026-12-31"), Jarig.recent(g, d("2027-01-01"), 2))
        assertEquals(46, Jarig.leeftijd(g, d("2026-12-31")))
        assertEquals(d("2026-12-31"), Jarig.recent(g, d("2027-01-02"), 2))
        assertNull(Jarig.recent(g, d("2027-01-03"), 2))
        assertEquals(1L, Jarig.afstand(g, d("2027-01-01")))
        assertEquals(1L, Jarig.afstand(d("1980-01-01"), d("2026-12-31")))
    }

    @Test fun schrikkeldag() {
        val g = d("2000-02-29")
        assertEquals(d("2027-02-28"), Jarig.inJaar(g, 2027))
        assertEquals(d("2028-02-29"), Jarig.inJaar(g, 2028))
        assertEquals(d("2027-02-28"), Jarig.recent(g, d("2027-02-28"), 0))
        assertEquals(d("2027-02-28"), Jarig.recent(g, d("2027-03-02"), 2))
        assertNull(Jarig.recent(g, d("2028-02-28"), 2))
        assertEquals(d("2028-02-29"), Jarig.recent(g, d("2028-03-01"), 2))
        assertTrue(Jarig.opVerjaardag(g, d("2023-02-28")))
        assertFalse(Jarig.opVerjaardag(g, d("2024-02-28")))
        assertTrue(Jarig.opVerjaardag(g, d("2024-02-29")))
        // marge van 7 dagen loopt door tot in maart
        assertEquals(setOf(2, 3), Jarig.maanden(g, 7))
    }

    @Test fun geboortedagTeltNietAlsVerjaardag() {
        assertNull(Jarig.recent(d("2026-11-20"), d("2026-11-21"), 2))
        assertTrue(Jarig.opVerjaardag(d("2026-11-20"), d("2026-11-20")))
    }

    @Test fun maandenMetMarge() {
        assertEquals(setOf(11), Jarig.maanden(d("1989-11-19"), 7))
        assertEquals(setOf(10, 11), Jarig.maanden(d("1989-11-03"), 7))
        assertEquals(setOf(12, 1), Jarig.maanden(d("1980-12-31"), 7))
    }
}

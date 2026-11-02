package com.immichclient.tv

import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SchudderTest {
    /** Diashow nabootsen: volgende kiezen, tonen (markeren), herhalen. */
    private fun speel(n: Int, start: Int, stappen: Int, seed: Int, mag: (Int) -> Boolean = { true }): List<Int> {
        val s = Schudder(Random(seed))
        val getoond = mutableListOf(start)
        var huidig = start
        repeat(stappen) {
            val d = s.volgende(n, huidig, mag)
            if (d < 0) return getoond
            s.markeer(d); huidig = d; getoond += d
        }
        return getoond
    }

    @Test fun elkeFotoEenKeerPerRonde() {
        for (seed in 0 until 200) {
            val n = 1 + seed % 40 + 1
            val r = speel(n, start = seed % n, stappen = n - 1, seed = seed)
            assertEquals("seed $seed", n, r.size)
            assertEquals("seed $seed: dubbel in eerste ronde", n, r.toSet().size)
        }
    }

    @Test fun volgendeRondesOokCompleetEnGeenDirecteHerhaling() {
        val n = 25
        val r = speel(n, start = 3, stappen = n * 4 - 1, seed = 7)
        // ronde 1 = n foto's; daarna telt de laatste van de vorige ronde als gezien, dus rondes van n - 1
        assertEquals(n, r.take(n).toSet().size)
        r.drop(n).chunked(n - 1).filter { it.size == n - 1 }.forEach { assertEquals(n - 1, it.toSet().size) }
        r.zipWithNext().forEach { (a, b) -> assertNotEquals(a, b) }
    }

    @Test fun overgeslagenItems() {
        val n = 30
        val r = speel(n, start = 0, stappen = 40, seed = 1) { it % 3 != 0 } // "video's" overslaan
        val eersteRonde = r.drop(1).take(19) // 20 toegestane, start (0) telde niet mee
        assertTrue(eersteRonde.none { it % 3 == 0 })
        assertEquals(19, eersteRonde.toSet().size)
    }

    @Test fun nietsMogelijk() {
        assertEquals(-1, Schudder().volgende(1, 0) { true })
        assertEquals(-1, Schudder().volgende(5, 0) { false })
    }

    @Test fun zelfBekekenFotoTeltAlsGezien() {
        val s = Schudder(Random(3))
        val n = 10
        s.volgende(n, 0) { true }
        s.markeer(5) // tijdens de diashow zelf naar 5 gebladerd
        val r = mutableListOf<Int>(); var h = 5
        repeat(8) { val d = s.volgende(n, h) { true }; s.markeer(d); r += d; h = d }
        assertEquals((1..9).toSet() - 5, r.toSet())
    }
}

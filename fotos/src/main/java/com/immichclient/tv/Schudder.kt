package com.immichclient.tv

import kotlin.random.Random

/**
 * Willekeurige volgorde zonder herhaling: per ronde één keer geschud; een item komt pas terug als alle andere
 * geweest zijn. Daarna begint een nieuwe ronde, waarin het huidige item meteen als gezien telt (geen directe herhaling).
 */
class Schudder(private val random: Random = Random.Default) {
    private var volgorde = IntArray(0)
    private var gezien = BooleanArray(0)
    private var pos = 0

    private fun nieuweRonde(n: Int) {
        volgorde = IntArray(n) { it }.also { it.shuffle(random) }
        gezien = BooleanArray(n)
        pos = 0
    }

    /** Volgende item na [huidig] dat [mag]; markeert nog niets (dat doet [markeer]). -1 = geen enkel item mogelijk. */
    fun volgende(n: Int, huidig: Int, mag: (Int) -> Boolean): Int {
        if (n <= 1) return -1
        if (volgorde.size != n) nieuweRonde(n)
        markeer(huidig)
        for (ronde in 0..1) {
            for (p in pos until n) {
                val i = volgorde[p]
                if (!gezien[i] && i != huidig && mag(i)) return i
            }
            if (ronde == 0) { nieuweRonde(n); markeer(huidig) }
        }
        return -1
    }

    /** [i] is getoond. */
    fun markeer(i: Int) {
        if (i !in gezien.indices) return
        gezien[i] = true
        while (pos < volgorde.size && gezien[volgorde[pos]]) pos++
    }
}

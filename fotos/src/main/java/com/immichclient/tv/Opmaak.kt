package com.immichclient.tv

import androidx.compose.runtime.Immutable

/**
 * Immich-achtige "justified" opmaak: foto's in hun eigen verhouding, rijen precies even breed.
 * Kleine dagen (passen in één rij) staan naast elkaar met elk een eigen datumkopje; grote dagen krijgen
 * hun eigen rijen. Alles in px, uitgerekend vóór er een beeld geladen is: niets verspringt.
 */
@Immutable data class Vak(val asset: Asset, val breedte: Float)
@Immutable data class Stuk(val kop: String?, val vakken: List<Vak>)
@Immutable data class Regel(val sleutel: String, val stukken: List<Stuk>, val hoogte: Float, val metKop: Boolean)

@Immutable data class Groep(val kop: String?, val assets: List<Asset>)

object Opmaak {
    /** Uiterste verhoudingen voor de opmaak (een panorama wordt bijgesneden, niet een reep van 3 px hoog). */
    private fun r(a: Asset) = a.ratio.coerceIn(0.5f, 2.6f)

    fun regels(prefix: String, groepen: List<Groep>, breedte: Float, rijHoogte: Float, tussen: Float, groepTussen: Float): List<Regel> {
        val uit = ArrayList<Regel>()
        var lopend = ArrayList<Stuk>()
        var lopendBreed = 0f
        var n = 0
        fun sluitLopend() {
            if (lopend.isEmpty()) return
            uit.add(Regel("$prefix-${n++}", lopend, rijHoogte, lopend.any { it.kop != null }))
            lopend = ArrayList(); lopendBreed = 0f
        }
        for (g in groepen) {
            if (g.assets.isEmpty()) continue
            val natuurlijk = g.assets.sumOf { (r(it) * rijHoogte).toDouble() }.toFloat() + tussen * (g.assets.size - 1)
            if (natuurlijk <= breedte) {
                // past in één rij: naast de vorige kleine dag(en) als het past
                val nodig = natuurlijk + if (lopend.isEmpty()) 0f else groepTussen
                if (lopendBreed + nodig > breedte) sluitLopend()
                lopendBreed += natuurlijk + if (lopend.isEmpty()) 0f else groepTussen
                lopend.add(Stuk(g.kop, g.assets.map { Vak(it, r(it) * rijHoogte) }))
                continue
            }
            sluitLopend()
            // grote dag: rijen vullen tot de breedte en dan precies passend schalen
            var rij = ArrayList<Asset>()
            var som = 0f
            var eerste = true
            for (a in g.assets) {
                rij.add(a); som += r(a)
                if (som * rijHoogte + tussen * (rij.size - 1) >= breedte) {
                    val h = (breedte - tussen * (rij.size - 1)) / som
                    uit.add(Regel("$prefix-${n++}", listOf(Stuk(if (eerste) g.kop else null, rij.map { Vak(it, r(it) * h) })), h, eerste && g.kop != null))
                    eerste = false; rij = ArrayList(); som = 0f
                }
            }
            if (rij.isNotEmpty()) {
                uit.add(Regel("$prefix-${n++}", listOf(Stuk(if (eerste) g.kop else null, rij.map { Vak(it, r(it) * rijHoogte) })), rijHoogte, eerste && g.kop != null))
            }
        }
        sluitLopend()
        return uit
    }

    /** Ruwe schatting van de hoogte van een maand die nog niet geladen is (alleen voor de scrollbalk/plaatshouder). */
    fun schatHoogte(aantal: Int, breedte: Float, rijHoogte: Float, kopHoogte: Float): Float {
        val rijen = kotlin.math.ceil(aantal * 1.25f * rijHoogte / breedte).coerceAtLeast(1f)
        // begrensd: Compose kan geen item van >262.000 px aan (een maand met duizenden foto's)
        return (rijen * rijHoogte + rijen * 0.5f * kopHoogte).coerceAtMost(2400f)
    }

    fun perDag(assets: List<Asset>, metJaar: Boolean): List<Groep> {
        val uit = ArrayList<Groep>()
        var dag: java.time.LocalDate? = null
        var lijst = ArrayList<Asset>()
        for (a in assets) {
            val d = a.datum.toLocalDate()
            if (d != dag) {
                if (lijst.isNotEmpty()) uit.add(Groep(dagTitel(dag!!, metJaar) + plaatsVan(lijst), lijst))
                dag = d; lijst = ArrayList()
            }
            lijst.add(a)
        }
        if (lijst.isNotEmpty()) uit.add(Groep(dagTitel(dag!!, metJaar) + plaatsVan(lijst), lijst))
        return uit
    }

    /** Meest voorkomende plaats van die dag achter de datum, zoals Immich ("Sat 17 Oct · Lisbon"). */
    private fun plaatsVan(l: List<Asset>): String {
        val stad = l.mapNotNull { it.stad }.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key ?: return ""
        return " ·  $stad"
    }
}

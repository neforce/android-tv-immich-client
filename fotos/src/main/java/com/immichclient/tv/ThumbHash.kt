package com.immichclient.tv

import android.graphics.Bitmap
import android.util.Base64
import android.util.LruCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * ThumbHash (github.com/evanw/thumbhash, MIT): ~25 bytes per foto die Immich meelevert. Daaruit een wazig
 * 32 px-plaatje als voorvertoning, zodat er tijdens snel scrollen nooit lege vakken staan.
 */
object ThumbHash {
    private val cache = LruCache<String, ImageBitmap>(3000) // 32×32×4 B ≈ 4 kB per stuk

    fun bitmap(hash: String): ImageBitmap? {
        cache.get(hash)?.let { return it }
        val b = runCatching { decodeer(Base64.decode(hash, Base64.DEFAULT)) }.getOrNull() ?: return null
        cache.put(hash, b)
        return b
    }

    fun inCache(hash: String): ImageBitmap? = cache.get(hash)

    fun leeg() = cache.evictAll()

    private fun decodeer(h: ByteArray): ImageBitmap {
        fun u(i: Int) = h[i].toInt() and 0xFF
        val header24 = u(0) or (u(1) shl 8) or (u(2) shl 16)
        val header16 = u(3) or (u(4) shl 8)
        val lDc = (header24 and 63) / 63f
        val pDc = ((header24 shr 6) and 63) / 31.5f - 1
        val qDc = ((header24 shr 12) and 63) / 31.5f - 1
        val lScale = ((header24 shr 18) and 31) / 31f
        val hasAlpha = (header24 shr 23) != 0
        val pScale = ((header16 shr 3) and 63) / 63f
        val qScale = ((header16 shr 9) and 63) / 63f
        val isLandscape = (header16 shr 15) != 0
        val lx = max(3, if (isLandscape) (if (hasAlpha) 5 else 7) else header16 and 7)
        val ly = max(3, if (isLandscape) header16 and 7 else (if (hasAlpha) 5 else 7))
        val aDc = if (hasAlpha) (u(5) and 15) / 15f else 1f
        val aScale = (u(5) shr 4) / 15f

        val acStart = if (hasAlpha) 6 else 5
        var acIndex = 0
        fun kanaal(nx: Int, ny: Int, scale: Float): FloatArray {
            val ac = ArrayList<Float>()
            for (cy in 0 until ny) {
                var cx = if (cy > 0) 0 else 1
                while (cx * ny < nx * (ny - cy)) {
                    ac.add((((u(acStart + (acIndex shr 1)) shr ((acIndex and 1) shl 2)) and 15) / 7.5f - 1) * scale)
                    acIndex++
                    cx++
                }
            }
            return ac.toFloatArray()
        }
        val lAc = kanaal(lx, ly, lScale)
        val pAc = kanaal(3, 3, pScale * 1.25f)
        val qAc = kanaal(3, 3, qScale * 1.25f)
        val aAc = if (hasAlpha) kanaal(5, 5, aScale) else null

        val ratio = lx.toFloat() / ly
        val w = (if (ratio > 1) 32f else 32 * ratio).roundToInt().coerceAtLeast(1)
        val hh = (if (ratio > 1) 32 / ratio else 32f).roundToInt().coerceAtLeast(1)
        val px = IntArray(w * hh)
        val nx = max(lx, if (hasAlpha) 5 else 3)
        val ny = max(ly, if (hasAlpha) 5 else 3)
        val fx = FloatArray(nx)
        val fy = FloatArray(ny)
        for (y in 0 until hh) {
            for (cy in 0 until ny) fy[cy] = cos(PI / hh * (y + 0.5) * cy).toFloat()
            for (x in 0 until w) {
                var l = lDc; var p = pDc; var q = qDc; var a = aDc
                for (cx in 0 until nx) fx[cx] = cos(PI / w * (x + 0.5) * cx).toFloat()
                var j = 0
                for (cy in 0 until ly) {
                    var cx = if (cy > 0) 0 else 1
                    val fy2 = fy[cy] * 2
                    while (cx * ly < lx * (ly - cy)) { l += lAc[j++] * fx[cx] * fy2; cx++ }
                }
                j = 0
                for (cy in 0 until 3) {
                    var cx = if (cy > 0) 0 else 1
                    val fy2 = fy[cy] * 2
                    while (cx < 3 - cy) { val f = fx[cx] * fy2; p += pAc[j] * f; q += qAc[j] * f; j++; cx++ }
                }
                if (aAc != null) {
                    j = 0
                    for (cy in 0 until 5) {
                        var cx = if (cy > 0) 0 else 1
                        val fy2 = fy[cy] * 2
                        while (cx < 5 - cy) { a += aAc[j++] * fx[cx] * fy2; cx++ }
                    }
                }
                val b = l - 2f / 3f * p
                val r = (3 * l - b + q) / 2
                val g = r - q
                fun k(v: Float) = (255 * min(1f, max(0f, v))).toInt()
                px[y * w + x] = (k(a) shl 24) or (k(r) shl 16) or (k(g) shl 8) or k(b)
            }
        }
        return Bitmap.createBitmap(px, w, hh, Bitmap.Config.ARGB_8888).asImageBitmap()
    }
}

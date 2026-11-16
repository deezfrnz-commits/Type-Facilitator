package com.typefacilitator.core

import kotlin.math.sqrt

/**
 * Turns a finger path over the letter keys into words. Each dictionary word that starts and ends
 * near the path's ends is laid out as the ideal line through its keys; the word whose line stays
 * closest to the finger path wins, with common and expected words preferred when it is close.
 */
class SwipeDecoder(private val dict: Dictionary, private val user: UserModel, private val bigrams: Map<String, List<String>>) {
    private val gx = FloatArray(N)
    private val gy = FloatArray(N)
    private val wx = FloatArray(N)
    private val wy = FloatArray(N)
    private val px = FloatArray(64)
    private val py = FloatArray(64)

    /**
     * [xs],[ys]: the first [n] points of the path. [keyX],[keyY]: centres of the keys a..z (index 0..25).
     * [keyW]: distance between neighbouring key centres. Returns up to [max] words, best first, lowercase
     * unless the word itself has capitals.
     */
    fun decode(xs: FloatArray, ys: FloatArray, n: Int, keyX: FloatArray, keyY: FloatArray, keyW: Float, prevLower: String?, max: Int): List<String> {
        if (n < 2) return emptyList()
        val glen = resample(xs, ys, n, gx, gy)
        if (glen < keyW * 0.7f) return emptyList()
        val ctxUser = if (prevLower != null) user.next(prevLower, 12).map { Dictionary.lower(it) } else emptyList()
        val ctxBuilt = (bigrams[prevLower ?: ""] ?: emptyList()).map { Dictionary.lower(it) }

        val startD = FloatArray(26) { dist(keyX[it], keyY[it], xs[0], ys[0]) }
        val endD = FloatArray(26) { dist(keyX[it], keyY[it], xs[n - 1], ys[n - 1]) }
        val sMin = startD.min()
        val eMin = endD.min()
        val bestW = arrayOfNulls<String>(KEEP)
        val bestS = FloatArray(KEEP) { Float.MAX_VALUE }
        val one = CharArray(1)
        val q = object : CharSequence {
            override val length = 1
            override fun get(index: Int) = one[0]
            override fun subSequence(startIndex: Int, endIndex: Int) = one[0].toString()
        }
        for (s in 0 until 26) {
            if (startD[s] > maxOf(keyW * 1.25f, sMin + keyW * 0.3f)) continue
            one[0] = 'a' + s
            val lo = dict.lowerBound(q)
            val hi = dict.prefixEnd(q, lo)
            var prevKey: String? = null
            for (i in lo until hi) {
                val f = dict.freq(i)
                if (f == 0) continue
                val len = dict.length(i)
                if (len < 2) continue
                // key sequence of the word: letters only, repeated letters once
                var k = 0
                var ok = true
                var last = -1
                for (c in 0 until len) {
                    val ch = dict.charAt(i, c)
                    if (ch == '\'') continue
                    val li = ch - 'a'
                    if (li !in 0..25) { ok = false; break }
                    if (li != last) {
                        if (k >= px.size) { ok = false; break }
                        px[k] = keyX[li]; py[k] = keyY[li]; k++
                        last = li
                    }
                }
                if (!ok || k < 2) continue
                if (endD[last] > maxOf(keyW * 1.25f, eMin + keyW * 0.3f)) continue
                var wl = 0f
                for (j in 1 until k) wl += dist(px[j], py[j], px[j - 1], py[j - 1])
                if (wl > glen * 1.35f + keyW || wl < glen * 0.55f - keyW) continue
                resample(px, py, k, wx, wy)
                var d = 0f
                for (j in 0 until N) d += dist(gx[j], gy[j], wx[j], wy[j])
                d /= N * keyW
                if (d > 1.1f) continue
                // corners of the ideal line that the finger never came near count against the word
                var miss = 0f
                var from = 0
                for (j in 0 until k) {
                    var bd = Float.MAX_VALUE
                    var bi = from
                    val reach = keyW
                    for (m in from until N) {
                        val dd = dist(gx[m], gy[m], px[j], py[j])
                        if (dd < bd) { bd = dd; bi = m }
                        else if (bd <= reach && dd > bd + keyW * 0.25f) break // first pass over this key is enough
                    }
                    from = bi
                    val e = bd / keyW - 0.7f
                    if (e > 0) miss += e
                }
                val key = Dictionary.lower(dict.word(i))
                var score = d + 0.5f * miss - 0.0022f * f - (len - 2) * 0.004f
                val uc = user.count(key)
                if (uc > 0) score -= minOf(0.12f, 0.03f * uc)
                if (user.isBlocked(key)) continue
                if (ctxUser.contains(key)) score -= 0.12f
                else if (ctxBuilt.contains(key)) score -= 0.08f
                if (key == prevKey) continue // same spelling with different capitals: keep the commoner
                prevKey = key
                if (score < bestS[KEEP - 1]) {
                    var p = KEEP - 1
                    while (p > 0 && bestS[p - 1] > score) { bestS[p] = bestS[p - 1]; bestW[p] = bestW[p - 1]; p-- }
                    bestS[p] = score; bestW[p] = dict.word(i)
                }
            }
        }
        val out = ArrayList<String>(max)
        for (w in bestW) if (w != null && out.size < max && out.none { it.equals(w, true) }) out.add(w)
        return out
    }

    /** Writes N points evenly spaced along the polyline into ox/oy; returns the polyline length. */
    private fun resample(x: FloatArray, y: FloatArray, n: Int, ox: FloatArray, oy: FloatArray): Float {
        var total = 0f
        for (i in 1 until n) total += dist(x[i], y[i], x[i - 1], y[i - 1])
        if (total <= 0f) {
            for (j in 0 until N) { ox[j] = x[0]; oy[j] = y[0] }
            return 0f
        }
        val step = total / (N - 1)
        var seg = 1
        var acc = 0f
        var segLen = dist(x[1], y[1], x[0], y[0])
        for (j in 0 until N) {
            val target = step * j
            while (seg < n - 1 && acc + segLen < target) {
                acc += segLen
                seg++
                segLen = dist(x[seg], y[seg], x[seg - 1], y[seg - 1])
            }
            val t = if (segLen > 0f) ((target - acc) / segLen).coerceIn(0f, 1f) else 0f
            ox[j] = x[seg - 1] + (x[seg] - x[seg - 1]) * t
            oy[j] = y[seg - 1] + (y[seg] - y[seg - 1]) * t
        }
        return total
    }

    private fun dist(ax: Float, ay: Float, bx: Float, by: Float): Float {
        val dx = ax - bx
        val dy = ay - by
        return sqrt(dx * dx + dy * dy)
    }

    companion object {
        private const val N = 36
        private const val KEEP = 8
    }
}

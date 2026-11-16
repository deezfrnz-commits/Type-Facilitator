package com.typefacilitator.core

import java.io.BufferedInputStream
import java.io.DataInputStream
import java.io.InputStream

/**
 * The built-in English word list, held as one Latin-1 byte block plus an offset per word
 * (about 2 MB of RAM for 137k words, no per-word objects). Entries are sorted by lowercase
 * spelling, so exact and prefix lookups are binary searches.
 *
 * freq(i): 0 = a real word that is never offered (offensive), otherwise 1..255, higher = more common.
 */
class Dictionary private constructor(
    private val blob: ByteArray,
    private val off: IntArray,
    private val freq: ByteArray,
) {
    val size: Int get() = freq.size

    fun word(i: Int): String = String(blob, off[i], length(i), Charsets.ISO_8859_1)
    fun freq(i: Int): Int = freq[i].toInt() and 0xFF
    fun length(i: Int): Int = off[i + 1] - 1 - off[i]

    /** Lowercased character k of entry i. */
    fun charAt(i: Int, k: Int): Char = lower(blob[off[i] + k].toInt() and 0xFF).toChar()

    /** True if the entry is spelled without capitals, i.e. may be typed in any capitalisation. */
    fun isLowercase(i: Int): Boolean {
        for (k in off[i] until off[i + 1] - 1) {
            val b = blob[k].toInt() and 0xFF
            if (lower(b) != b) return false
        }
        return true
    }

    /** Compares entry i's lowercase spelling with [q] (already lowercase), or with its first [n] chars. */
    private fun cmp(i: Int, q: CharSequence, prefixOnly: Boolean): Int {
        val len = length(i)
        val qn = q.length
        var k = 0
        while (k < len && k < qn) {
            val d = charAt(i, k).code - q[k].code
            if (d != 0) return d
            k++
        }
        if (k == qn) return if (prefixOnly || len == qn) 0 else 1
        return -1
    }

    /** First index whose spelling is >= [q]. */
    fun lowerBound(q: CharSequence): Int {
        var lo = 0
        var hi = size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (cmp(mid, q, false) < 0) lo = mid + 1 else hi = mid
        }
        return lo
    }

    /** First index >= [from] whose spelling does not start with [q]. */
    fun prefixEnd(q: CharSequence, from: Int): Int {
        var lo = from
        var hi = size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (cmp(mid, q, true) <= 0) lo = mid + 1 else hi = mid
        }
        return lo
    }

    /** Index of the most frequent entry spelled [q] (lowercase), or -1. */
    fun find(q: CharSequence): Int {
        val i = lowerBound(q)
        return if (i < size && cmp(i, q, false) == 0) i else -1
    }

    /**
     * True if [w] is a correctly spelled and correctly capitalised word: "hello", "Hello", "HELLO",
     * "Paris" and "PARIS" are, "paris" and "helo" are not.
     */
    fun isWord(w: String): Boolean {
        val q = lower(w)
        var i = find(q)
        if (i < 0) {
            if (q.endsWith("'s") && q.length > 2) return isWord(w.substring(0, w.length - 2))
            if (q.endsWith("'") && q.length > 1) return isWord(w.substring(0, w.length - 1))
            return false
        }
        if (isAllUpper(w)) return true
        while (i < size && cmp(i, q, false) == 0) {
            if (isLowercase(i)) return true
            val e = word(i)
            if (e == w) return true
            i++
        }
        return false
    }

    companion object {
        fun load(input: InputStream): Dictionary {
            val d = DataInputStream(BufferedInputStream(input, 1 shl 16))
            val n = d.readInt()
            val len = d.readInt()
            val freq = ByteArray(n)
            d.readFully(freq)
            val blob = ByteArray(len)
            d.readFully(blob)
            val off = IntArray(n + 1)
            var k = 1
            for (i in 0 until len) if (blob[i] == 10.toByte() && k <= n) off[k++] = i + 1
            return Dictionary(blob, off, freq)
        }

        /** Latin-1 lowercase; other characters are returned unchanged. */
        fun lower(b: Int): Int = if (b in 65..90 || (b in 0xC0..0xDE && b != 0xD7)) b + 32 else b

        fun lower(s: String): String {
            var same = true
            for (c in s) if (lower(c.code) != c.code) { same = false; break }
            if (same) return s
            val a = CharArray(s.length) { lower(s[it].code).toChar() }
            return String(a)
        }

        fun isAllUpper(w: String): Boolean {
            var letters = 0
            for (c in w) {
                if (c.isLetter()) {
                    if (!c.isUpperCase()) return false
                    letters++
                }
            }
            return letters >= 2
        }
    }
}

/** The preferred spelling for a lowercase key: the all-lowercase entry if there is one, else the most common. */
fun Dictionary.canonical(q: String): String? {
    var i = find(q)
    if (i < 0) return null
    val first = word(i)
    while (i < size && length(i) == q.length && Dictionary.lower(word(i)) == q) {
        if (isLowercase(i)) return word(i)
        i++
    }
    return first
}

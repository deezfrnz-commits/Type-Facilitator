package com.typefacilitator.core

import java.io.BufferedReader
import java.io.Reader

/** Word completion, spelling correction, next-word prediction and learning. Pure logic, no Android. */
class Engine(
    val dict: Dictionary,
    val user: UserModel,
    private val fixes: Map<String, String>,
    private val bigrams: Map<String, List<String>>,
) {
    /** [words] best first; [auto] is the replacement to apply when the word is finished, if any. */
    class Result(val words: List<String>, val auto: String?, val typedValid: Boolean)

    private class Cand(val word: String, var score: Int, var corr: Boolean)

    private val cands = HashMap<String, Cand>()
    private var buf = CharArray(64)
    private val seq = Seq()

    private inner class Seq : CharSequence {
        var n = 0
        override val length: Int get() = n
        override fun get(index: Int): Char = buf[index]
        override fun subSequence(startIndex: Int, endIndex: Int): CharSequence = String(buf, startIndex, endIndex - startIndex)
        override fun toString(): String = String(buf, 0, n)
    }

    /** Correctly spelled, or something the user has deliberately typed before. Never flags numbers. */
    @Synchronized fun isValid(word: String): Boolean {
        if (word.isEmpty() || word.any { it.isDigit() } || word.none { it.isLetter() }) return true
        if (word.any { it.code > 255 }) return true // not English: no opinion
        return dict.isWord(word) || user.count(Dictionary.lower(word)) >= 2
    }

    @Synchronized fun suggest(typed: String, prevLower: String?, max: Int): Result {
        val q = Dictionary.lower(typed)
        if (q.isEmpty() || q.length > 48) return Result(emptyList(), null, true)
        cands.clear()
        val ctxUser = if (prevLower != null) user.next(prevLower, 12) else emptyList()
        val ctxBuilt = bigrams[prevLower ?: ""] ?: emptyList()

        // completions of what was typed, from the dictionary
        val lo = dict.lowerBound(q)
        val hi = dict.prefixEnd(q, lo)
        val topI = IntArray(TOP) { -1 }
        val topS = IntArray(TOP) { Int.MIN_VALUE }
        for (i in lo until hi) {
            val f = dict.freq(i)
            if (f == 0) continue
            val extra = dict.length(i) - q.length
            val s = if (extra == 0) f + 60 else f - 4 * minOf(extra, 6)
            if (s > topS[TOP - 1]) {
                var k = TOP - 1
                while (k > 0 && topS[k - 1] < s) { topS[k] = topS[k - 1]; topI[k] = topI[k - 1]; k-- }
                topS[k] = s; topI[k] = i
            }
        }
        for (k in 0 until TOP) if (topI[k] >= 0) add(dict.word(topI[k]), topS[k], false, prevLower, ctxBuilt)
        // completions the user has taught the keyboard
        user.completions(q) { e ->
            val key = Dictionary.lower(e.form)
            val i = dict.find(key)
            if (i >= 0 || e.count >= 2) {
                val base = if (i >= 0) dict.freq(i) else 170
                add(e.form, base - 4 * minOf(key.length - q.length, 6), false, prevLower, ctxBuilt)
            }
        }
        // words that usually follow the previous word
        for (w in ctxUser) if (Dictionary.lower(w).startsWith(q)) add(w, baseFreq(w), false, prevLower, ctxBuilt)
        for (w in ctxBuilt) if (Dictionary.lower(w).startsWith(q)) add(w, baseFreq(w), false, prevLower, ctxBuilt)

        val typedValid = isValid(typed)
        val lettersOnly = q.all { it in 'a'..'z' || it == '\'' || it.code >= 0xC0 }
        var auto: String? = null
        if (lettersOnly && q.length >= 2) {
            if (q.length + 2 > buf.size) buf = CharArray(q.length + 16)
            val a = q.toCharArray()
            edits(a, a.size, 0, prevLower, ctxBuilt)
            if (!typedValid && q.length >= 5 && cands.values.none { it.corr && it.score >= AUTO_MIN }) {
                edits2(a, prevLower, ctxBuilt)
            }
        }
        if (!typedValid && lettersOnly) {
            val fix = fixes[q]
            val canon = dict.canonical(q)
            if (fix != null) auto = fix
            else if (canon != null && (q.length > 1 || q == "i")) auto = canon // right letters, wrong capitals: i -> I, paris -> Paris
            else {
                val best = cands.values.filter { it.corr }.maxByOrNull { it.score }
                if (best != null && best.score >= AUTO_MIN) auto = best.word
            }
        }

        val upper = Dictionary.isAllUpper(typed)
        val cap = typed[0].isUpperCase()
        fun shape(w: String): String = when {
            upper -> w.uppercase()
            cap && w[0].isLowerCase() -> w.substring(0, 1).uppercase() + w.substring(1)
            else -> w
        }
        val out = ArrayList<String>(max)
        val autoShaped = auto?.let { shape(it) }
        if (autoShaped != null && autoShaped != typed) out.add(autoShaped) else auto = null
        val sorted = cands.values.sortedByDescending { it.score }
        if (!typedValid || auto != null) out.add(typed)
        for (c in sorted) {
            if (out.size >= max) break
            val w = shape(c.word)
            if (w == typed || out.contains(w)) continue
            out.add(w)
        }
        return Result(out, if (auto != null) autoShaped else null, typedValid)
    }

    /** Likely next words after [prevLower] (null or "" = start of a sentence). */
    @Synchronized fun predict(prevLower: String?, max: Int): List<String> {
        val out = ArrayList<String>(max)
        fun put(w: String) {
            if (out.size < max && !out.contains(w) && !user.isBlocked(Dictionary.lower(w))) out.add(w)
        }
        if (!prevLower.isNullOrEmpty()) for (w in user.next(prevLower, max)) put(w)
        for (w in bigrams[prevLower ?: ""] ?: emptyList()) put(w)
        if (!prevLower.isNullOrEmpty()) for (w in bigrams["*"] ?: emptyList()) put(w)
        return out
    }

    /**
     * Records a finished word. [autoCapped] says the capital was added by the keyboard (sentence
     * start), so it is not part of the word. [strong] marks a word the user insisted on.
     */
    @Synchronized fun learn(prevLower: String?, word: String, autoCapped: Boolean, strong: Boolean = false) {
        if (word.length > 40 || word.none { it.isLetter() } || word.any { it.isDigit() || it.isWhitespace() }) return
        val key = Dictionary.lower(word)
        val canon = dict.canonical(key)
        val form = when {
            canon != null && dict.isWord(word) -> if (canon == key || word == canon) canon else word
            canon != null -> word
            autoCapped && word.length > 1 && word.substring(1) == key.substring(1) -> key
            else -> word
        }
        user.learn(prevLower, form, if (strong) 2 else 1)
    }

    private fun baseFreq(w: String): Int {
        val i = dict.find(Dictionary.lower(w))
        return if (i >= 0) dict.freq(i) else 80
    }

    private fun add(word: String, base: Int, corr: Boolean, prevLower: String?, ctxBuilt: List<String>) {
        val key = Dictionary.lower(word)
        if (user.isBlocked(key)) return
        var s = base + minOf(60, 10 * user.count(key))
        val c = user.nextCount(prevLower, key)
        if (c > 0) s += 40 + minOf(60, 15 * c)
        val r = ctxBuilt.indexOfFirst { Dictionary.lower(it) == key }
        if (r >= 0) s += maxOf(45 - 6 * r, 15)
        val old = cands[key]
        if (old == null) cands[key] = Cand(word, s, corr)
        else if (s > old.score) { old.score = s; old.corr = corr && old.corr }
        else if (!corr) old.corr = false
    }

    private fun tryWord(n: Int, cost: Int, prevLower: String?, ctxBuilt: List<String>) {
        seq.n = n
        val i = dict.find(seq)
        if (i < 0) return
        val f = dict.freq(i)
        if (f == 0) return
        add(dict.word(i), f - cost, true, prevLower, ctxBuilt)
    }

    /** Every word one typing slip away from a[0..n): a dropped, extra, swapped or neighbouring letter. */
    private fun edits(a: CharArray, n: Int, base: Int, prevLower: String?, ctxBuilt: List<String>) {
        val b = buf
        // extra letter typed
        for (p in 0 until n) {
            var k = 0
            for (i in 0 until n) if (i != p) b[k++] = a[i]
            val doubled = (p > 0 && a[p] == a[p - 1]) || (p + 1 < n && a[p] == a[p + 1])
            tryWord(n - 1, base + (if (doubled) 22 else 30), prevLower, ctxBuilt)
        }
        // two letters swapped
        for (p in 0 until n - 1) {
            if (a[p] == a[p + 1]) continue
            System.arraycopy(a, 0, b, 0, n)
            b[p] = a[p + 1]; b[p + 1] = a[p]
            tryWord(n, base + 22, prevLower, ctxBuilt)
        }
        // wrong letter
        System.arraycopy(a, 0, b, 0, n)
        for (p in 0 until n) {
            val orig = a[p]
            for (c in ALPHA) {
                if (c == orig) continue
                b[p] = c
                var cost = if (near(orig, c)) 18 else 42
                if (p == 0) cost += 15
                tryWord(n, base + cost, prevLower, ctxBuilt)
            }
            b[p] = orig
        }
        // letter left out
        for (p in 0..n) {
            System.arraycopy(a, 0, b, 0, p)
            System.arraycopy(a, p, b, p + 1, n - p)
            for (c in ALPHA) {
                b[p] = c
                val cost = when {
                    c == '\'' -> 6
                    (p > 0 && a[p - 1] == c) || (p < n && a[p] == c) -> 20
                    else -> 30
                }
                tryWord(n + 1, base + cost + (if (p == 0) 15 else 0), prevLower, ctxBuilt)
            }
        }
    }

    /** Two slips: only tried for longer words that had no good single-slip correction. */
    private fun edits2(a: CharArray, prevLower: String?, ctxBuilt: List<String>) {
        val n = a.size
        val t = CharArray(n + 1)
        for (p in 0 until n) { // extra letter
            var k = 0
            for (i in 0 until n) if (i != p) t[k++] = a[i]
            edits(t, n - 1, 38, prevLower, ctxBuilt)
        }
        for (p in 0 until n - 1) { // swap
            System.arraycopy(a, 0, t, 0, n)
            t[p] = a[p + 1]; t[p + 1] = a[p]
            edits(t, n, 30, prevLower, ctxBuilt)
        }
        System.arraycopy(a, 0, t, 0, n)
        for (p in 1 until n) { // neighbouring key
            val orig = a[p]
            val nb = NEAR[orig.code and 0xFF] ?: continue
            for (c in nb) { t[p] = c; edits(t, n, 26, prevLower, ctxBuilt) }
            t[p] = orig
        }
        for (p in 1..n) { // doubled letter left out
            System.arraycopy(a, 0, t, 0, p)
            System.arraycopy(a, p, t, p + 1, n - p)
            t[p] = a[p - 1]
            edits(t, n + 1, 28, prevLower, ctxBuilt)
        }
    }

    companion object {
        private const val TOP = 8
        /** A correction is applied automatically only if (word frequency - slip cost) reaches this. */
        const val AUTO_MIN = 38
        private val ALPHA = "abcdefghijklmnopqrstuvwxyz'".toCharArray()
        private val NEAR = arrayOfNulls<String>(256).also { m ->
            val rows = arrayOf("qwertyuiop", "asdfghjkl", "zxcvbnm")
            val offs = floatArrayOf(0f, 0.5f, 1.5f)
            for (r in rows.indices) for (i in rows[r].indices) {
                val sb = StringBuilder()
                val x = i + offs[r]
                for (r2 in rows.indices) for (j in rows[r2].indices) {
                    if (r2 == r && j == i) continue
                    if (kotlin.math.abs(r2 - r) <= 1 && kotlin.math.abs(j + offs[r2] - x) <= 1.1f) sb.append(rows[r2][j])
                }
                m[rows[r][i].code] = sb.toString()
            }
        }

        fun near(a: Char, b: Char): Boolean = a.code < 256 && NEAR[a.code]?.indexOf(b)?.let { it >= 0 } == true

        /** "typo=correction" lines. */
        fun parseFixes(r: Reader): Map<String, String> {
            val m = HashMap<String, String>()
            BufferedReader(r).forEachLine { l ->
                val i = l.indexOf('=')
                if (i > 0) m[Dictionary.lower(l.substring(0, i))] = l.substring(i + 1)
            }
            return m
        }

        /** "word next1 next2 ..." lines; the word "^" is the start of a sentence, "*" follows anything. */
        fun parseBigrams(r: Reader): Map<String, List<String>> {
            val m = HashMap<String, List<String>>()
            BufferedReader(r).forEachLine { l ->
                val p = l.trim().split(' ')
                if (p.size > 1) m[if (p[0] == "^") "" else p[0]] = p.subList(1, p.size)
            }
            return m
        }
    }
}

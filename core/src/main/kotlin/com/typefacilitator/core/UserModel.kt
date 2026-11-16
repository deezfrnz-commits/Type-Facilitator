package com.typefacilitator.core

import java.io.BufferedReader
import java.io.Reader
import java.io.Writer

/**
 * What the keyboard has learned from the user: how often each word was typed, which word tends
 * to follow which, and which suggestions the user removed. Everything here can be deleted.
 */
class UserModel {
    class Entry(var form: String, var count: Int)

    private val uni = HashMap<String, Entry>()
    private val bi = HashMap<String, HashMap<String, Int>>()
    private val blocked = HashSet<String>()
    var dirty = false
        private set

    val wordCount: Int get() = uni.size

    fun count(lower: String): Int = uni[lower]?.count ?: 0
    fun form(lower: String): String? = uni[lower]?.form
    fun isBlocked(lower: String): Boolean = blocked.contains(lower)

    fun learn(prevLower: String?, form: String, weight: Int = 1) {
        val key = Dictionary.lower(form)
        if (key.isEmpty() || key.length > 40) return
        blocked.remove(key)
        val e = uni[key]
        if (e == null) uni[key] = Entry(form, weight) else {
            e.count = minOf(e.count + weight, 100000)
            if (e.form != form && form != key) e.form = form
        }
        if (prevLower != null && prevLower.isNotEmpty()) {
            val m = bi.getOrPut(prevLower) { HashMap() }
            m[key] = minOf((m[key] ?: 0) + 1, 100000)
        }
        dirty = true
    }

    /** Words learned after [prevLower], most frequent first, in the spelling the user used. */
    fun next(prevLower: String, max: Int): List<String> {
        val m = bi[prevLower] ?: return emptyList()
        return m.entries.filter { !blocked.contains(it.key) }.sortedByDescending { it.value }.take(max)
            .map { uni[it.key]?.form ?: it.key }
    }

    fun nextCount(prevLower: String?, lower: String): Int = if (prevLower == null) 0 else bi[prevLower]?.get(lower) ?: 0

    /** Calls [f] for every learned word starting with [prefix] (lowercase). */
    fun completions(prefix: String, f: (Entry) -> Unit) {
        for ((k, e) in uni) if (k.length > prefix.length && k.startsWith(prefix) && !blocked.contains(k)) f(e)
    }

    /** Removes a word from everything learned and stops it being suggested. */
    fun forget(word: String) {
        val key = Dictionary.lower(word)
        uni.remove(key)
        bi.remove(key)
        for (m in bi.values) m.remove(key)
        blocked.add(key)
        dirty = true
    }

    fun unblock(word: String) {
        if (blocked.remove(Dictionary.lower(word))) dirty = true
    }

    fun clear() {
        uni.clear(); bi.clear(); blocked.clear()
        dirty = true
    }

    /** Learned words, most used first. */
    fun words(): List<Entry> = uni.values.sortedByDescending { it.count }
    fun blockedWords(): List<String> = blocked.sorted()

    fun save(w: Writer) {
        prune()
        for (e in uni.values) w.write("u\t${e.form}\t${e.count}\n")
        for ((p, m) in bi) for ((n, c) in m) w.write("b\t$p\t$n\t$c\n")
        for (x in blocked) w.write("x\t$x\n")
        w.flush()
        dirty = false
    }

    fun load(r: Reader) {
        uni.clear(); bi.clear(); blocked.clear()
        BufferedReader(r).forEachLine { line ->
            val p = line.split('\t')
            try {
                when (p[0]) {
                    "u" -> uni[Dictionary.lower(p[1])] = Entry(p[1], p[2].toInt())
                    "b" -> bi.getOrPut(p[1]) { HashMap() }[p[2]] = p[3].toInt()
                    "x" -> blocked.add(p[1])
                }
            } catch (_: Exception) {
            }
        }
        dirty = false
    }

    /** Keeps the model small: the least used words and pairs are dropped first. */
    private fun prune() {
        if (uni.size > MAX_WORDS) {
            val drop = uni.entries.sortedBy { it.value.count }.take(uni.size - MAX_WORDS * 9 / 10).map { it.key }
            for (k in drop) uni.remove(k)
        }
        if (bi.size > MAX_PREV) {
            val drop = bi.entries.sortedBy { e -> e.value.values.sum() }.take(bi.size - MAX_PREV * 9 / 10).map { it.key }
            for (k in drop) bi.remove(k)
        }
        for (m in bi.values) if (m.size > MAX_NEXT) {
            val drop = m.entries.sortedBy { it.value }.take(m.size - MAX_NEXT).map { it.key }
            for (k in drop) m.remove(k)
        }
    }

    companion object {
        const val MAX_WORDS = 6000
        const val MAX_PREV = 3000
        const val MAX_NEXT = 12
    }
}

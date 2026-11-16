package com.typefacilitator

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File

class Clip(val text: String, var pinned: Boolean, val time: Long)

/**
 * The clipboard history: up to [MAX] pieces of copied text in any language, newest first,
 * kept in one private file. Pinned clips are never pushed out by new ones.
 */
class ClipStore(private val file: File) {
    val items = ArrayList<Clip>()

    init { load() }

    /** Adds copied text (or moves it to the top if already stored). Returns false if nothing changed. */
    fun add(text: String): Boolean {
        if (text.isBlank() || text.length > MAX_CHARS) return false
        if (items.isNotEmpty() && items[0].text == text) return false
        val old = items.firstOrNull { it.text == text }
        if (old != null) items.remove(old)
        items.add(0, Clip(text, old?.pinned ?: false, System.currentTimeMillis()))
        while (items.size > MAX) {
            val i = items.indexOfLast { !it.pinned }
            items.removeAt(if (i >= 0) i else items.size - 1)
        }
        save()
        return true
    }

    fun remove(c: Clip) { items.remove(c); save() }
    fun togglePin(c: Clip) { c.pinned = !c.pinned; save() }

    /** Deletes everything, or everything that is not pinned. */
    fun clear(keepPinned: Boolean) {
        if (keepPinned) items.removeAll { !it.pinned } else items.clear()
        save()
    }

    /** Pinned clips first, then newest first. */
    fun ordered(): List<Clip> = items.filter { it.pinned } + items.filter { !it.pinned }

    private fun load() {
        items.clear()
        if (!file.exists()) return
        try {
            DataInputStream(file.inputStream().buffered()).use { d ->
                if (d.readInt() != 1) return
                val n = d.readInt()
                for (i in 0 until minOf(n, MAX)) {
                    val pinned = d.readBoolean()
                    val time = d.readLong()
                    val b = ByteArray(d.readInt())
                    d.readFully(b)
                    items.add(Clip(String(b, Charsets.UTF_8), pinned, time))
                }
            }
        } catch (_: Exception) {
        }
    }

    private fun save() {
        try {
            val tmp = File(file.path + ".tmp")
            DataOutputStream(tmp.outputStream().buffered()).use { d ->
                d.writeInt(1); d.writeInt(items.size)
                for (c in items) {
                    val b = c.text.toByteArray(Charsets.UTF_8)
                    d.writeBoolean(c.pinned); d.writeLong(c.time); d.writeInt(b.size); d.write(b)
                }
            }
            if (!tmp.renameTo(file)) { file.delete(); tmp.renameTo(file) }
        } catch (_: Exception) {
        }
    }

    companion object {
        const val MAX = 100
        const val MAX_CHARS = 200_000
    }
}

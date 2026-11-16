package com.typefacilitator

import android.content.Context
import android.util.Log
import com.typefacilitator.core.Dictionary
import com.typefacilitator.core.Engine
import com.typefacilitator.core.SwipeDecoder
import com.typefacilitator.core.UserModel
import java.io.File

/**
 * Everything the keyboard, the spell checker and the settings screen share (they run in one
 * process): the dictionary engine, the learned words and the clipboard history.
 */
object Data {
    val user = UserModel()
    @Volatile var engine: Engine? = null
    @Volatile var decoder: SwipeDecoder? = null
    lateinit var clips: ClipStore
    private var started = false
    private var userFile: File? = null
    private val waiting = ArrayList<Runnable>()

    /** Starts loading in the background (once); [onReady] runs on the loader thread when the engine exists. */
    @Synchronized fun init(ctx: Context, onReady: Runnable? = null) {
        if (engine != null) { onReady?.run(); return }
        if (onReady != null) waiting.add(onReady)
        if (started) return
        started = true
        val app = ctx.applicationContext
        clips = ClipStore(File(app.filesDir, "clips.bin"))
        userFile = File(app.filesDir, "user.txt")
        Thread {
            try {
                val dict = app.assets.open("dict.bin").use { Dictionary.load(it) }
                val fixes = app.assets.open("fixes.txt").reader().use { Engine.parseFixes(it) }
                val bigrams = app.assets.open("bigrams.txt").reader().use { Engine.parseBigrams(it) }
                val f = userFile!!
                if (f.exists()) f.reader().use { user.load(it) }
                decoder = SwipeDecoder(dict, user, bigrams)
                engine = Engine(dict, user, fixes, bigrams)
                val run = synchronized(this) { ArrayList(waiting).also { waiting.clear() } }
                for (r in run) r.run()
            } catch (e: Exception) {
                Log.e("TypeFacilitator", "loading dictionary", e)
            }
        }.start()
    }

    fun saveUser() {
        val f = userFile ?: return
        val e = engine ?: return
        if (!user.dirty) return
        try {
            synchronized(e) { f.writer().use { user.save(it) } }
        } catch (_: Exception) {
        }
    }

    fun forget(word: String) { val e = engine ?: return; synchronized(e) { user.forget(word) } }
    fun clearLearned() { val e = engine; if (e != null) synchronized(e) { user.clear() } else user.clear(); saveUser() }
}

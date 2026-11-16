package com.typefacilitator.core

import java.io.File
import java.io.StringReader
import java.io.StringWriter
import java.util.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CoreTest {
    companion object {
        private val assets = File(System.getProperty("tf.assets"))
        val dict: Dictionary by lazy { File(assets, "dict.bin").inputStream().use { Dictionary.load(it) } }
        val fixes by lazy { File(assets, "fixes.txt").reader().use { Engine.parseFixes(it) } }
        val bigrams by lazy { File(assets, "bigrams.txt").reader().use { Engine.parseBigrams(it) } }
        fun engine(user: UserModel = UserModel()) = Engine(dict, user, fixes, bigrams)

        // key centres of a honeycomb QWERTY, in key-width units
        val keyX = FloatArray(26)
        val keyY = FloatArray(26)
        init {
            val rows = arrayOf("qwertyuiop", "asdfghjkl", "zxcvbnm")
            val offs = floatArrayOf(0.5f, 1.0f, 1.5f)
            for (r in rows.indices) for (i in rows[r].indices) {
                keyX[rows[r][i] - 'a'] = (i + offs[r]) * 100f
                keyY[rows[r][i] - 'a'] = r * 130f + 65f
            }
        }

        /** A finger path for [word]: through each key with [noise] px of error, corners cut. */
        fun path(word: String, rnd: Random, noise: Float): Pair<FloatArray, FloatArray> {
            val cx = ArrayList<Float>()
            val cy = ArrayList<Float>()
            var last = ' '
            for (c in word.lowercase()) {
                if (c !in 'a'..'z' || c == last) continue
                last = c
                cx.add(keyX[c - 'a'] + (rnd.nextGaussian() * noise).toFloat())
                cy.add(keyY[c - 'a'] + (rnd.nextGaussian() * noise).toFloat())
            }
            val xs = ArrayList<Float>()
            val ys = ArrayList<Float>()
            for (i in 0 until cx.size - 1) for (t in 0 until 8) {
                xs.add(cx[i] + (cx[i + 1] - cx[i]) * t / 8f)
                ys.add(cy[i] + (cy[i + 1] - cy[i]) * t / 8f)
            }
            xs.add(cx.last()); ys.add(cy.last())
            // smooth twice: real swipes round the corners
            repeat(2) {
                for (i in 1 until xs.size - 1) {
                    xs[i] = (xs[i - 1] + xs[i] * 2 + xs[i + 1]) / 4
                    ys[i] = (ys[i - 1] + ys[i] * 2 + ys[i + 1]) / 4
                }
            }
            return xs.toFloatArray() to ys.toFloatArray()
        }
    }

    @Test fun dictionaryLookups() {
        assertTrue(dict.size > 100000)
        for (w in listOf("hello", "Hello", "HELLO", "the", "I", "I'm", "don't", "Paris", "PARIS", "keyboard's", "cats", "world"))
            assertTrue(dict.isWord(w), w)
        for (w in listOf("helo", "paris", "i", "teh", "recieve", "dont", "xqzt")) assertFalse(dict.isWord(w), w)
        assertEquals("I", dict.canonical("i"))
        assertEquals("the", dict.canonical("the"))
        // sorted, so every word can be found again
        val rnd = Random(1)
        repeat(2000) {
            val i = rnd.nextInt(dict.size)
            val f = dict.find(Dictionary.lower(dict.word(i)))
            assertTrue(f >= 0 && Dictionary.lower(dict.word(f)) == Dictionary.lower(dict.word(i)))
        }
    }

    @Test fun completions() {
        val e = engine()
        assertTrue("the" in e.suggest("th", null, 3).words)
        assertTrue(e.suggest("keyb", null, 3).words.any { it.startsWith("keyboard") })
        assertTrue(e.suggest("Tomor", null, 3).words.contains("Tomorrow"))
        assertTrue(e.suggest("HEL", null, 5).words.all { it == it.uppercase() })
        val r = e.suggest("hello", null, 3)
        assertTrue(r.typedValid); assertNull(r.auto)
    }

    @Test fun autocorrect() {
        val e = engine()
        val cases = mapOf(
            "teh" to "the", "recieve" to "receive", "dont" to "don't", "im" to "I'm", "i" to "I", "helllo" to "hello",
            "becuase" to "because", "tomorow" to "tomorrow", "thnaks" to "thanks", "wierd" to "weird", "alot" to "a lot",
            "definately" to "definitely", "freind" to "friend", "paris" to "Paris", "Teh" to "The", "didnt" to "didn't",
            "keybaord" to "keyboard", "woudl" to "would", "adn" to "and", "yuo" to "you", "peopel" to "people",
            "seperate" to "separate", "occured" to "occurred", "goverment" to "government", "tommorow" to "tomorrow",
        )
        var bad = 0
        for ((t, want) in cases) {
            val r = e.suggest(t, null, 3)
            if (r.auto != want) { println("autocorrect $t -> ${r.auto} ${r.words} (want $want)"); bad++ }
            assertFalse(r.typedValid, t)
        }
        assertTrue(bad <= 1, "$bad corrections wrong")
        // valid words and numbers are left alone
        for (t in listOf("form", "there", "Hello", "abc123", "OK", "it's")) assertNull(e.suggest(t, null, 3).auto, t)
        // nonsense has no confident correction
        assertNull(e.suggest("xkqzvwpt", null, 3).auto)
    }

    @Test fun nextWordAndLearning() {
        val user = UserModel()
        val e = engine(user)
        assertTrue(e.predict("i", 3).contains("am"))
        assertTrue(e.predict(null, 3).contains("I"))
        // a new word is not corrected once the user has insisted on it
        assertNotNull(e.suggest("zorblat", null, 3).let { it.typedValid.not() })
        e.learn("my", "zorblat", autoCapped = false, strong = true)
        assertTrue(e.suggest("zorblat", null, 3).typedValid)
        assertTrue(e.suggest("zor", null, 3).words.contains("zorblat"), e.suggest("zor", null, 3).words.toString())
        assertEquals("zorblat", e.predict("my", 3)[0])
        // capital added by the keyboard at a sentence start is not learned as part of the word
        e.learn(null, "Hello", autoCapped = true)
        assertEquals("hello", user.form("hello"))
        e.learn(null, "Paris", autoCapped = true)
        assertEquals("Paris", user.form("paris"))
        // save / load round trip
        val w = StringWriter(); user.save(w)
        val u2 = UserModel(); u2.load(StringReader(w.toString()))
        assertEquals(2, u2.count("zorblat"))
        assertEquals(listOf("zorblat"), u2.next("my", 3))
        // forgetting removes the word everywhere
        user.forget("zorblat")
        assertFalse(e.suggest("zor", null, 3).words.contains("zorblat"))
        assertFalse(e.predict("my", 3).contains("zorblat"))
        user.clear()
        assertEquals(0, user.wordCount)
    }

    @Test fun swipeTyping() {
        val dec = SwipeDecoder(dict, UserModel(), bigrams)
        val words = ("hello world the quick brown fox jumps over lazy dog keyboard typing swipe because people " +
            "about would there their which other after first think where right great little before through " +
            "something morning tomorrow thanks please sorry message phone today tonight meeting dinner love " +
            "friend house water question answer problem number never always maybe really going want need " +
            "this that with have from they what when your said each time will make like just know take " +
            "good some could them see than then now look only come its over also back use two how our work").split(" ")
        for (noise in floatArrayOf(0f, 18f, 30f)) {
            val rnd = Random(7)
            var top1 = 0
            var top3 = 0
            val miss = ArrayList<String>()
            for (w in words) {
                val (xs, ys) = path(w, rnd, noise)
                val r = dec.decode(xs, ys, xs.size, keyX, keyY, 100f, null, 4)
                if (r.firstOrNull() == w) top1++
                if (w in r.take(3)) top3++ else miss.add("$w->$r")
            }
            println("swipe noise=$noise: top1 $top1/${words.size}, top3 $top3/${words.size} $miss")
            if (noise == 0f) { assertTrue(top1 >= words.size * 80 / 100); assertTrue(top3 >= words.size * 93 / 100) }
            if (noise == 18f) assertTrue(top3 >= words.size * 85 / 100)
        }
        // speed: decoding must be fast enough to feel instant
        val (xs, ys) = path("something", Random(1), 10f)
        val t = System.nanoTime()
        repeat(50) { dec.decode(xs, ys, xs.size, keyX, keyY, 100f, null, 4) }
        println("swipe decode: ${(System.nanoTime() - t) / 50 / 1000} us")
    }

    @Test fun suggestSpeed() {
        val e = engine()
        val t = System.nanoTime()
        for (w in listOf("t", "th", "keybaord", "definately", "xkqzvwpt", "internationalisation")) repeat(20) { e.suggest(w, "the", 3) }
        println("suggest: ${(System.nanoTime() - t) / 120 / 1000} us average")
    }
}

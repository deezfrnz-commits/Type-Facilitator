package com.typefacilitator

import android.content.Context
import android.content.SharedPreferences

/** All user settings, read once into plain fields so drawing and typing never touch storage. */
class Prefs(ctx: Context) {
    val sp: SharedPreferences = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    var autoCap = true; var autoCorrect = true; var suggestions = true; var nextWord = true
    var learn = true; var doubleSpace = true; var swipe = true; var trail = true
    var underline = true; var spaceCursor = true; var numberRow = false; var popup = true
    var hints = true; var punctSpace = true
    var longPress = 350
    var vibrate = true; var vibMs = 12; var sound = false
    var heightP = 100; var heightL = 100; var bottomPad = 0
    var oneHand = false; var oneHandRight = true; var oneHandW = 80
    var floating = false; var floatX = -1; var floatY = -1; var floatW = 72
    var clip = true
    var fontScale = 100; var outline = true
    var cBg = 0; var cKey = 0; var cFn = 0; var cText = 0; var cFnText = 0; var cAccent = 0; var cOutline = 0

    init { load() }

    fun load() {
        autoCap = sp.getBoolean("autoCap", true); autoCorrect = sp.getBoolean("autoCorrect", true)
        suggestions = sp.getBoolean("suggestions", true); nextWord = sp.getBoolean("nextWord", true)
        learn = sp.getBoolean("learn", true); doubleSpace = sp.getBoolean("doubleSpace", true)
        swipe = sp.getBoolean("swipe", true); trail = sp.getBoolean("trail", true)
        underline = sp.getBoolean("underline", true); spaceCursor = sp.getBoolean("spaceCursor", true)
        numberRow = sp.getBoolean("numberRow", false); popup = sp.getBoolean("popup", true)
        hints = sp.getBoolean("hints", true); punctSpace = sp.getBoolean("punctSpace", true)
        longPress = sp.getInt("longPress", 350)
        vibrate = sp.getBoolean("vibrate", true); vibMs = sp.getInt("vibMs", 12); sound = sp.getBoolean("sound", false)
        heightP = sp.getInt("heightP", 100); heightL = sp.getInt("heightL", 100); bottomPad = sp.getInt("bottomPad", 0)
        oneHand = sp.getBoolean("oneHand", false); oneHandRight = sp.getBoolean("oneHandRight", true)
        oneHandW = sp.getInt("oneHandW", 80)
        floating = sp.getBoolean("floating", false); floatX = sp.getInt("floatX", -1); floatY = sp.getInt("floatY", -1)
        floatW = sp.getInt("floatW", 72)
        clip = sp.getBoolean("clip", true)
        fontScale = sp.getInt("fontScale", 100); outline = sp.getBoolean("outline", true)
        val t = THEMES[0].colors
        cBg = sp.getInt("cBg", t[0]); cKey = sp.getInt("cKey", t[1]); cFn = sp.getInt("cFn", t[2])
        cText = sp.getInt("cText", t[3]); cFnText = sp.getInt("cFnText", t[4]); cAccent = sp.getInt("cAccent", t[5])
        cOutline = sp.getInt("cOutline", t[6])
    }

    fun set(key: String, v: Boolean) { sp.edit().putBoolean(key, v).apply(); load() }
    fun set(key: String, v: Int) { sp.edit().putInt(key, v).apply(); load() }

    fun applyTheme(t: Theme) {
        val e = sp.edit().putString("theme", t.name)
        for (i in COLOR_KEYS.indices) e.putInt(COLOR_KEYS[i], t.colors[i])
        e.apply(); load()
    }

    class Theme(val name: String, val colors: IntArray)

    companion object {
        const val FILE = "tf"
        val COLOR_KEYS = arrayOf("cBg", "cKey", "cFn", "cText", "cFnText", "cAccent", "cOutline")
        val COLOR_NAMES = arrayOf("Background", "Letter keys", "Function keys", "Letters", "Function key symbols", "Highlight", "Key outline")
        private fun c(v: Long) = v.toInt()
        /** background, key, function key, text, function text, accent, outline */
        val THEMES = arrayOf(
            Theme("Black & Silver", intArrayOf(c(0xFF000000), c(0xFF141414), c(0xFFC0C0C0), c(0xFFFFFFFF), c(0xFF000000), c(0xFFFFFFFF), c(0xFFC0C0C0))),
            Theme("White", intArrayOf(c(0xFFFFFFFF), c(0xFFF1F1F1), c(0xFFC0C0C0), c(0xFF000000), c(0xFF000000), c(0xFF000000), c(0xFF8E8E8E))),
            Theme("Silver", intArrayOf(c(0xFFC0C0C0), c(0xFFECECEC), c(0xFF000000), c(0xFF000000), c(0xFFFFFFFF), c(0xFFFFFFFF), c(0xFF000000))),
            Theme("Pure Black", intArrayOf(c(0xFF000000), c(0xFF000000), c(0xFF1C1C1C), c(0xFFFFFFFF), c(0xFFC0C0C0), c(0xFFC0C0C0), c(0xFF4A4A4A))),
            Theme("Midnight", intArrayOf(c(0xFF0B1220), c(0xFF17233A), c(0xFF2F4A7A), c(0xFFE8EEF8), c(0xFFFFFFFF), c(0xFF7FB2FF), c(0xFF3A5488))),
            Theme("Forest", intArrayOf(c(0xFF0D1A12), c(0xFF183022), c(0xFF2F6B45), c(0xFFE9F5EC), c(0xFFFFFFFF), c(0xFF8FE3A8), c(0xFF3C7A54))),
            Theme("Ember", intArrayOf(c(0xFF1A0E0B), c(0xFF2E1A14), c(0xFFB5482A), c(0xFFFFF1EA), c(0xFFFFFFFF), c(0xFFFFB38A), c(0xFF8A4A36))),
        )
    }
}

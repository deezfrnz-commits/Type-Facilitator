package com.typefacilitator

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast

/** Setup steps, a field to try the keyboard in, and every option. Built in code: no layout files. */
class SettingsActivity : Activity() {
    private lateinit var prefs: Prefs
    private lateinit var list: LinearLayout
    private var d = 1f
    private val black = Color.BLACK
    private val silver = 0xFFC0C0C0.toInt()
    private val white = Color.WHITE
    private val card = 0xFF141414.toInt()
    private var status: TextView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        Data.init(this)
        d = resources.displayMetrics.density
        window.statusBarColor = black
        window.navigationBarColor = black
        val rootView = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(black) }
        rootView.fitsSystemWindows = true
        val title = TextView(this)
        title.text = "TypeFacilitator"
        title.setTextColor(white); title.typeface = Typeface.DEFAULT_BOLD
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
        title.setPadding(dp(18), dp(14), dp(18), dp(6))
        rootView.addView(title)
        val test = EditText(this)
        test.hint = "Try the keyboard here"
        test.setHintTextColor(0xFF8A8A8A.toInt()); test.setTextColor(white)
        test.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        test.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES or InputType.TYPE_TEXT_FLAG_MULTI_LINE
        test.imeOptions = EditorInfo.IME_FLAG_NO_EXTRACT_UI
        test.maxLines = 4
        test.background = HexDrawable(card, silver, d)
        test.setPadding(dp(20), dp(10), dp(20), dp(10))
        test.contentDescription = "test field"
        val tlp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        tlp.setMargins(dp(10), dp(4), dp(10), dp(8))
        rootView.addView(test, tlp)
        val scroll = ScrollView(this)
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(10), 0, dp(10), dp(24)) }
        scroll.addView(list)
        rootView.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(rootView)
        build()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) updateStatus()
    }

    private fun dp(v: Int) = (v * d).toInt()

    // ---------------------------------------------------------------- row builders
    private fun header(t: String) {
        val v = TextView(this)
        v.text = t.uppercase()
        v.setTextColor(silver); v.typeface = Typeface.DEFAULT_BOLD
        v.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        v.letterSpacing = 0.12f
        v.setPadding(dp(10), dp(18), 0, dp(4))
        list.addView(v)
    }

    private fun row(titleText: String, sub: String?): Pair<LinearLayout, TextView?> {
        val r = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        r.background = HexDrawable(card, 0xFF3A3A3A.toInt(), d)
        r.setPadding(dp(20), dp(8), dp(12), dp(8))
        r.minimumHeight = dp(52)
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val t = TextView(this)
        t.text = titleText; t.setTextColor(white); t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        col.addView(t)
        var s: TextView? = null
        if (sub != null) {
            s = TextView(this)
            s.text = sub; s.setTextColor(0xFF9C9C9C.toInt()); s.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            col.addView(s)
        }
        r.addView(col, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        lp.topMargin = dp(4)
        list.addView(r, lp)
        return r to s
    }

    private fun action(titleText: String, sub: String?, run: () -> Unit): TextView? {
        val (r, s) = row(titleText, sub)
        r.setOnClickListener { run() }
        return s
    }

    private fun toggle(titleText: String, sub: String?, key: String, def: Boolean) {
        val (r, _) = row(titleText, sub)
        val b = HexButton(this, Icons.DONE)
        b.fillColor = black; b.lineColor = silver; b.inkColor = black; b.checkedColor = white
        b.checked = prefs.sp.getBoolean(key, def)
        b.isClickable = false
        r.addView(b, LinearLayout.LayoutParams(dp(36), dp(38)))
        r.contentDescription = titleText
        r.setOnClickListener { b.checked = !b.checked; prefs.set(key, b.checked) }
    }

    private fun slider(titleText: String, key: String, min: Int, max: Int, def: Int, unit: String) {
        val (r, s) = row(titleText, "")
        r.orientation = LinearLayout.VERTICAL
        (r.getChildAt(0).layoutParams as LinearLayout.LayoutParams).apply { width = ViewGroup.LayoutParams.MATCH_PARENT; weight = 0f }
        val bar = SeekBar(this)
        bar.max = max - min
        bar.progress = prefs.sp.getInt(key, def) - min
        s?.text = "${bar.progress + min}$unit"
        bar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, p: Int, fromUser: Boolean) { s?.text = "${p + min}$unit" }
            override fun onStartTrackingTouch(sb: SeekBar) {}
            override fun onStopTrackingTouch(sb: SeekBar) { prefs.set(key, sb.progress + min) }
        })
        r.addView(bar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }

    private fun rebuild() { list.removeAllViews(); build() }

    private fun updateStatus() {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        val enabled = try { imm.enabledInputMethodList.any { it.packageName == packageName } } catch (_: Exception) { false }
        val current = try {
            if (android.os.Build.VERSION.SDK_INT >= 34) imm.currentInputMethodInfo?.packageName == packageName
            else (Settings.Secure.getString(contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD) ?: "").startsWith("$packageName/")
        } catch (_: Exception) { false }
        status?.text = when {
            current -> "TypeFacilitator is your keyboard. Tap the field above to try it."
            enabled -> "Turned on. Now do step 2 to start using it."
            else -> "Not turned on yet. Do step 1, then step 2."
        }
    }

    // ---------------------------------------------------------------- content
    private fun build() {
        header("Set up")
        action("1. Turn on TypeFacilitator", "Opens Android's keyboard list. Switch TypeFacilitator on.") {
            startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
        }
        action("2. Choose TypeFacilitator", "Pick it as the keyboard to type with.") {
            (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).showInputMethodPicker()
        }
        status = TextView(this).apply { setTextColor(silver); setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f); setPadding(dp(10), dp(8), dp(10), 0) }
        list.addView(status)
        updateStatus()

        header("Typing")
        toggle("Auto-capitalise", "Capital letter at the start of sentences", "autoCap", true)
        toggle("Auto-correct", "Fix typos when you press space. Backspace undoes a fix", "autoCorrect", true)
        toggle("Word suggestions", "Show completions above the keys", "suggestions", true)
        toggle("Next-word guesses", "Suggest the word likely to come next", "nextWord", true)
        toggle("Learn my words", "Remember words and word patterns you use. Nothing leaves the phone", "learn", true)
        toggle("Underline misspellings", "Mark words that are not in the dictionary", "underline", true)
        toggle("Swipe typing", "Slide a finger across the letters of a word", "swipe", true)
        toggle("Show swipe trail", null, "trail", true)
        toggle("Double space for full stop", null, "doubleSpace", true)
        toggle("Tidy spaces around punctuation", "After a suggestion or swipe, \"word .\" becomes \"word. \"", "punctSpace", true)
        toggle("Slide on space bar to move cursor", null, "spaceCursor", true)

        header("Layout and size")
        toggle("Number row", "Always show 1-0 above the letters", "numberRow", false)
        toggle("Number hints on top row", "Hold Q-P for 1-0", "hints", true)
        slider("Key height (upright)", "heightP", 60, 160, 100, "%")
        slider("Key height (sideways)", "heightL", 60, 160, 100, "%")
        slider("Space under the keyboard", "bottomPad", 0, 60, 0, " dp")
        toggle("One-handed mode", "Narrower keyboard pushed to one side", "oneHand", false)
        toggle("One-handed: keep on the right", null, "oneHandRight", true)
        slider("One-handed width", "oneHandW", 55, 92, 80, "%")
        toggle("Floating keyboard", "A small keyboard you can drag anywhere. Drag the top bar to move, the corner to resize", "floating", false)
        slider("Floating width", "floatW", 45, 100, 72, "%")

        header("Key press")
        toggle("Pop-up on key press", "Show the pressed letter above the finger", "popup", true)
        toggle("Vibrate", null, "vibrate", true)
        slider("Vibration strength", "vibMs", 1, 50, 12, " ms")
        toggle("Sound", null, "sound", false)
        slider("Long-press delay", "longPress", 150, 800, 350, " ms")

        header("Look")
        themes()
        for (i in Prefs.COLOR_KEYS.indices) colorRow(i)
        toggle("Key outlines", null, "outline", true)
        slider("Letter size", "fontScale", 70, 140, 100, "%")

        header("Clipboard")
        toggle("Keep clipboard history", "Up to ${ClipStore.MAX} copied texts, any language. Open it from the clipboard hexagon on the keyboard", "clip", true)
        action("View clipboard history", "${Data.clips.items.size} of ${ClipStore.MAX} saved. Tap an entry to copy it again") { showClips() }

        header("Your data")
        val n = Data.user.wordCount
        action("Learned words", "$n words. Tap a word to delete it") { showWords() }
        action("Delete learned words and word guesses", "Forgets everything the keyboard learned from your typing") {
            confirm("Delete all learned words and word patterns?") { Data.clearLearned(); toast("Learned words deleted"); rebuild() }
        }
        action("Delete clipboard history", "Removes all saved clips, including pinned ones") {
            confirm("Delete all saved clips?") { Data.clips.clear(false); notifyKeyboard(); toast("Clipboard history deleted"); rebuild() }
        }
        action("Delete recent emoji", null) {
            prefs.sp.edit().remove("recentEmoji").apply(); toast("Recent emoji deleted")
        }
        action("Delete everything", "Learned words, clipboard history, recent emoji and cached data") {
            confirm("Delete all stored typing data?") {
                Data.clearLearned(); Data.clips.clear(false)
                prefs.sp.edit().remove("recentEmoji").apply()
                cacheDir.listFiles()?.forEach { it.deleteRecursively() }
                notifyKeyboard(); toast("Everything deleted"); rebuild()
            }
        }
        action("Reset all settings", "Back to the black, silver and white defaults") {
            confirm("Reset every setting?") { prefs.sp.edit().clear().apply(); prefs.load(); rebuild() }
        }

        header("About")
        val about = TextView(this)
        about.text = "TypeFacilitator 1.0\nWorks fully offline: the app has no internet permission, so nothing you type, copy or teach it can leave the phone.\n" +
            "English word list from the Android Open Source Project (Apache License 2.0). Emoji list from Unicode."
        about.setTextColor(0xFF9C9C9C.toInt()); about.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        about.setPadding(dp(10), dp(6), dp(10), 0)
        list.addView(about)
    }

    /** Makes the running keyboard re-read its settings (any preference write does it). */
    private fun notifyKeyboard() = prefs.sp.edit().putLong("stamp", System.currentTimeMillis()).apply()

    private fun themes() {
        val (r, _) = row("Colour theme", "Tap one, or set each colour below")
        r.orientation = LinearLayout.VERTICAL
        (r.getChildAt(0).layoutParams as LinearLayout.LayoutParams).apply { width = ViewGroup.LayoutParams.MATCH_PARENT; weight = 0f }
        val strip = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val current = prefs.sp.getString("theme", Prefs.THEMES[0].name)
        for (t in Prefs.THEMES) {
            val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL; setPadding(dp(4), dp(6), dp(4), 0) }
            val b = HexButton(this, 0, "Aa")
            b.fillColor = t.colors[1]; b.lineColor = if (t.name == current) white else t.colors[6]; b.inkColor = t.colors[3]; b.checkedColor = t.colors[5]
            b.setOnClickListener { prefs.applyTheme(t); rebuild() }
            b.contentDescription = "theme ${t.name}"
            val wrap = LinearLayout(this).apply { setBackgroundColor(t.colors[0]); setPadding(dp(5), dp(5), dp(5), dp(5)) }
            wrap.addView(b, LinearLayout.LayoutParams(dp(44), dp(48)))
            col.addView(wrap)
            val name = TextView(this)
            name.text = t.name; name.setTextColor(if (t.name == current) white else 0xFF9C9C9C.toInt())
            name.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f); name.gravity = Gravity.CENTER
            col.addView(name)
            strip.addView(col)
        }
        val hs = HorizontalScrollView(this)
        hs.isHorizontalScrollBarEnabled = false
        hs.addView(strip)
        r.addView(hs)
    }

    private fun colorRow(i: Int) {
        val key = Prefs.COLOR_KEYS[i]
        val cur = prefs.sp.getInt(key, Prefs.THEMES[0].colors[i])
        val (r, _) = row(Prefs.COLOR_NAMES[i], String.format("#%06X", cur and 0xFFFFFF))
        val sw = HexButton(this, 0, "")
        sw.fillColor = cur; sw.lineColor = silver; sw.isClickable = false
        r.addView(sw, LinearLayout.LayoutParams(dp(36), dp(38)))
        r.setOnClickListener { pickColor(i, cur) }
    }

    private fun pickColor(i: Int, cur: Int) {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(12), dp(16), 0) }
        val input = EditText(this)
        input.setText(String.format("#%06X", cur and 0xFFFFFF))
        input.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        val palette = longArrayOf(
            0xFF000000, 0xFF141414, 0xFF2B2B2B, 0xFF4A4A4A, 0xFF808080, 0xFFC0C0C0, 0xFFE8E8E8, 0xFFFFFFFF,
            0xFFE53935, 0xFFFB8C00, 0xFFFDD835, 0xFF43A047, 0xFF00ACC1, 0xFF1E88E5, 0xFF5E35B1, 0xFFD81B60,
            0xFF5D1F1A, 0xFF6B3F10, 0xFF5E5310, 0xFF1D4A22, 0xFF0D4750, 0xFF123A63, 0xFF2D1B57, 0xFF5A0E2B,
        )
        for (rowIdx in 0 until 3) {
            val line = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            for (c in 0 until 8) {
                val col = palette[rowIdx * 8 + c].toInt()
                val b = HexButton(this, 0, "")
                b.fillColor = col; b.lineColor = silver; b.checkedColor = col
                b.setOnClickListener { input.setText(String.format("#%06X", col and 0xFFFFFF)) }
                line.addView(b, LinearLayout.LayoutParams(0, dp(40), 1f))
            }
            box.addView(line)
        }
        box.addView(input)
        AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle(Prefs.COLOR_NAMES[i]).setView(box)
            .setPositiveButton("Use") { _, _ ->
                try {
                    var t = input.text.toString().trim()
                    if (!t.startsWith("#")) t = "#$t"
                    val v = Color.parseColor(t) or 0xFF000000.toInt()
                    prefs.sp.edit().putInt(Prefs.COLOR_KEYS[i], v).putString("theme", "Custom").apply()
                    prefs.load(); rebuild()
                } catch (_: Exception) {
                    toast("Not a colour. Use six hex digits, like #C0C0C0")
                }
            }
            .setNegativeButton("Cancel", null).show()
    }

    private fun confirm(msg: String, run: () -> Unit) {
        AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert).setMessage(msg)
            .setPositiveButton("Delete") { _, _ -> run() }.setNegativeButton("Cancel", null).show()
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    private fun showWords() {
        val words = Data.user.words().map { it.form }
        if (words.isEmpty()) { toast("Nothing learned yet"); return }
        val items = words.toTypedArray()
        AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert).setTitle("Tap a word to delete it")
            .setItems(items) { _, which ->
                Data.forget(items[which]); Data.user.unblock(items[which]); Data.saveUser()
                toast("Deleted “${items[which]}”"); rebuild(); showWords()
            }
            .setNegativeButton("Close", null).show()
    }

    private fun showClips() {
        val clips = Data.clips.ordered()
        if (clips.isEmpty()) { toast("No saved clips"); return }
        val items = clips.map { (if (it.pinned) "📌 " else "") + it.text.take(120).replace('\n', ' ') }.toTypedArray()
        AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert).setTitle("Clipboard history")
            .setItems(items) { _, which ->
                (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("", clips[which].text))
                toast("Copied")
            }
            .setNegativeButton("Close", null).show()
    }
}

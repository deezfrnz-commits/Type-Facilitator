package com.typefacilitator

import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.res.Configuration
import android.inputmethodservice.InputMethodService
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.text.InputType
import android.text.SpannableString
import android.text.Spanned
import android.text.style.SuggestionSpan
import android.util.Log
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.widget.FrameLayout
import android.widget.Toast
import com.typefacilitator.core.Dictionary
import com.typefacilitator.core.Engine
import com.typefacilitator.core.SwipeDecoder
import java.text.BreakIterator
import java.util.Locale

/** The input method: turns key presses, swipes and picks into edits of the focused text field. */
class TfService : InputMethodService(), KeyboardView.Listener, GridPanel.Listener, ClipPanel.Listener,
    ClipboardManager.OnPrimaryClipChangedListener, SharedPreferences.OnSharedPreferenceChangeListener {

    private lateinit var prefs: Prefs
    private val clips: ClipStore get() = Data.clips
    private val engine: Engine? get() = Data.engine
    private val decoder: SwipeDecoder? get() = Data.decoder
    private val main = Handler(Looper.getMainLooper())

    private var root: FrameLayout? = null
    private var board: FrameLayout? = null
    private var kv: KeyboardView? = null
    private var grid: GridPanel? = null
    private var clipPanel: ClipPanel? = null

    // the word being typed and what is on the strip
    private val composing = StringBuilder()
    private var result: Engine.Result? = null
    private var shown: List<String> = emptyList()
    private var stripKind = 0 // 1 = completions of the current word, 2 = next-word guesses, 3 = other readings of a swipe
    private var prevLower: String? = null
    private var capAuto = false

    private var shift = 0 // 0 off, 1 next letter, 2 caps lock
    private var manualShift = false
    private var lastShiftTime = 0L

    private var info: EditorInfo? = null
    private var composeOk = false; private var learnOk = false; private var textField = false
    private var mode = Layouts.MODE_TEXT
    private var numeric = 0

    private var undoTyped: String? = null; private var undoOut = ""; private var undoLen = 0; private var undoSep = ""
    private var swipeTotal = 0; private var swipeWord = 0
    private var autoSpaced = false
    private var lastSpaceTime = 0L
    private var lastEdit = 0L
    private var selStart = -1; private var selEnd = -1
    private var selectMode = false
    private var dragX = Int.MIN_VALUE; private var dragY = 0; private var dragW = 0

    private val ic: InputConnection? get() = currentInputConnection

    // ---------------------------------------------------------------- lifecycle
    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
        Data.init(this, Runnable { main.post { if (isInputViewShown) updateStrip() } })
        prefs.sp.registerOnSharedPreferenceChangeListener(this)
        (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).addPrimaryClipChangedListener(this)
    }

    override fun onDestroy() {
        (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).removePrimaryClipChangedListener(this)
        prefs.sp.unregisterOnSharedPreferenceChangeListener(this)
        Data.saveUser()
        super.onDestroy()
    }

    override fun onCreateInputView(): View {
        val r = FrameLayout(this)
        val b = FrameLayout(this)
        val k = KeyboardView(this, prefs, this)
        b.addView(k, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        r.addView(b)
        root = r; board = b; kv = k; grid = null; clipPanel = null
        applyMode()
        return r
    }

    override fun onEvaluateFullscreenMode(): Boolean = false

    override fun onStartInputView(ei: EditorInfo, restarting: Boolean) {
        super.onStartInputView(ei, restarting)
        info = ei
        prefs.load()
        resetWord()
        val cls = ei.inputType and InputType.TYPE_MASK_CLASS
        val variation = ei.inputType and InputType.TYPE_MASK_VARIATION
        numeric = when (cls) {
            InputType.TYPE_CLASS_NUMBER, InputType.TYPE_CLASS_DATETIME -> 1
            InputType.TYPE_CLASS_PHONE -> 2
            else -> 0
        }
        val isText = cls == InputType.TYPE_CLASS_TEXT
        val password = isText && (variation == InputType.TYPE_TEXT_VARIATION_PASSWORD ||
            variation == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD || variation == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD)
        mode = when {
            isText && variation == InputType.TYPE_TEXT_VARIATION_URI -> Layouts.MODE_URL
            isText && (variation == InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS || variation == InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS) -> Layouts.MODE_EMAIL
            else -> Layouts.MODE_TEXT
        }
        val noSuggest = ei.inputType and InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS != 0
        textField = isText && !password && mode == Layouts.MODE_TEXT
        composeOk = textField && !noSuggest && variation != InputType.TYPE_TEXT_VARIATION_FILTER && (prefs.suggestions || prefs.autoCorrect)
        learnOk = composeOk && prefs.learn && (ei.imeOptions and 0x1000000) == 0 // IME_FLAG_NO_PERSONALIZED_LEARNING
        selStart = ei.initialSelStart; selEnd = ei.initialSelEnd
        selectMode = false
        shift = 0; manualShift = false
        hidePanels()
        applyMode()
        val k = kv ?: return
        k.swipeAllowed = textField
        k.selectMode = false
        k.enterIcon = when (ei.imeOptions and (EditorInfo.IME_MASK_ACTION or EditorInfo.IME_FLAG_NO_ENTER_ACTION)) {
            EditorInfo.IME_ACTION_SEARCH -> Icons.SEARCH
            EditorInfo.IME_ACTION_SEND -> Icons.SEND
            EditorInfo.IME_ACTION_GO, EditorInfo.IME_ACTION_NEXT -> Icons.NEXT
            EditorInfo.IME_ACTION_DONE -> Icons.DONE
            else -> Icons.ENTER
        }
        k.layout = if (numeric != 0) Layouts.numpad(numeric == 2) else Layouts.letters(prefs.numberRow, mode)
        if (Build.VERSION.SDK_INT >= 21) window?.window?.navigationBarColor = prefs.cBg
        updateShift()
        updateStrip()
        readClipboard()
        k.postDelayed({ k.dumpKeys() }, 900)
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        super.onFinishInputView(finishingInput)
        kv?.cancelTouches()
        resetWord()
        Thread { Data.saveUser() }.start()
    }

    override fun onFinishInput() {
        super.onFinishInput()
        resetWord()
    }

    private fun resetWord() {
        composing.setLength(0); result = null; shown = emptyList(); stripKind = 0
        clearUndo(); swipeTotal = 0; autoSpaced = false; capAuto = false
    }

    /** A setting changed (here or in the settings screen): redraw with it straight away. */
    override fun onSharedPreferenceChanged(sp: SharedPreferences?, key: String?) {
        if (key == "recentEmoji" || key == "floatX" || key == "floatY") return
        main.removeCallbacks(applyPrefs)
        main.post(applyPrefs)
    }

    private val applyPrefs = Runnable {
        prefs.load()
        val k = kv
        if (k != null && isInputViewShown) {
            if (k.layout.name == "letters") k.layout = Layouts.letters(prefs.numberRow, mode)
            applyMode()
            grid?.invalidate()
            if (clipPanel?.visibility == View.VISIBLE) clipPanel?.refresh()
            updateShift(); updateStrip()
        }
    }

    // ---------------------------------------------------------------- docked / floating placement
    private fun applyMode() {
        val r = root ?: return
        val b = board ?: return
        val k = kv ?: return
        val dm = resources.displayMetrics
        val land = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        if (prefs.floating) {
            val w = (dm.widthPixels * prefs.floatW / 100f * (if (land) 0.6f else 1f)).toInt()
            r.minimumHeight = dm.heightPixels
            val lp = FrameLayout.LayoutParams(w, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.LEFT)
            lp.leftMargin = if (prefs.floatX < 0) (dm.widthPixels - w) / 2 else prefs.floatX.coerceIn(0, maxOf(0, dm.widthPixels - w))
            lp.topMargin = if (prefs.floatY < 0) (dm.heightPixels * 0.45f).toInt() else prefs.floatY.coerceIn(0, (dm.heightPixels * 0.8f).toInt())
            b.layoutParams = lp
        } else {
            r.minimumHeight = 0
            b.layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM)
        }
        k.floating = prefs.floating
        k.layout = k.layout
    }

    override fun onComputeInsets(out: Insets) {
        super.onComputeInsets(out)
        val b = board
        if (!prefs.floating || b == null || !isInputViewShown) return
        // floating: the app keeps its full size and only the keyboard rectangle takes touches
        val loc = IntArray(2)
        b.getLocationInWindow(loc)
        val h = window?.window?.decorView?.height ?: return
        out.contentTopInsets = h
        out.visibleTopInsets = h
        out.touchableInsets = Insets.TOUCHABLE_INSETS_REGION
        out.touchableRegion.set(loc[0], loc[1], loc[0] + b.width, loc[1] + b.height)
    }

    override fun onFloatMove(dx: Float, dy: Float, done: Boolean) {
        val b = board ?: return
        val r = root ?: return
        val lp = b.layoutParams as FrameLayout.LayoutParams
        if (dragX == Int.MIN_VALUE) { dragX = lp.leftMargin; dragY = lp.topMargin }
        lp.leftMargin = (dragX + dx).toInt().coerceIn(0, maxOf(0, r.width - b.width))
        lp.topMargin = (dragY + dy).toInt().coerceIn(0, maxOf(0, r.height - b.height))
        b.layoutParams = lp
        if (done) {
            dragX = Int.MIN_VALUE
            prefs.sp.edit().putInt("floatX", lp.leftMargin).putInt("floatY", lp.topMargin).apply()
            prefs.load()
            kv?.let { v -> v.postDelayed({ v.dumpKeys() }, 300) }
        }
    }

    override fun onFloatResize(dx: Float, done: Boolean) {
        val b = board ?: return
        val r = root ?: return
        val lp = b.layoutParams as FrameLayout.LayoutParams
        if (dragX == Int.MIN_VALUE) { dragX = 0; dragW = lp.width }
        val sw = resources.displayMetrics.widthPixels
        lp.width = (dragW + dx).toInt().coerceIn((sw * 0.45f).toInt(), r.width - lp.leftMargin)
        b.layoutParams = lp
        if (done) {
            dragX = Int.MIN_VALUE
            val land = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
            prefs.set("floatW", (lp.width * 100f / sw / (if (land) 0.6f else 1f)).toInt().coerceIn(45, 100))
            kv?.let { v -> v.postDelayed({ v.dumpKeys() }, 300) }
        }
    }

    // ---------------------------------------------------------------- panels
    private fun showPanel(which: Int) { // 0 clipboard, 1 emoji, 2 symbols
        val b = board ?: return
        val k = kv ?: return
        val h = k.height
        finishWord(false)
        if (which == 0) {
            val p = clipPanel ?: ClipPanel(this, prefs, clips, this).also { clipPanel = it; b.addView(it, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)) }
            p.panelHeight = h; p.visibility = View.VISIBLE; grid?.visibility = View.GONE
            readClipboard()
            p.refresh(); p.requestLayout()
        } else {
            val g = grid ?: GridPanel(this, prefs, this).also { grid = it; b.addView(it, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)) }
            g.panelHeight = h; g.visibility = View.VISIBLE; clipPanel?.visibility = View.GONE
            g.requestLayout()
            g.show(which == 1)
        }
        k.visibility = View.GONE
    }

    private fun hidePanels() {
        grid?.visibility = View.GONE
        clipPanel?.visibility = View.GONE
        kv?.visibility = View.VISIBLE
        kv?.let { v -> v.post { v.dumpKeys() } }
    }

    override fun onPanelKey(code: Int) {
        when (code) {
            K.ABC -> { hidePanels(); updateShift(); updateStrip() }
            K.DEL -> backspace()
        }
    }

    override fun onGridText(text: String) {
        val c = ic ?: return
        finishWord(false)
        clearUndo(); swipeTotal = 0; autoSpaced = false
        c.commitText(text, 1)
        lastEdit = SystemClock.uptimeMillis()
    }

    override fun onClipText(text: String) {
        val c = ic ?: return
        finishWord(false)
        clearUndo(); swipeTotal = 0; autoSpaced = false
        c.commitText(text, 1)
        hidePanels()
        afterEdit()
    }

    // ---------------------------------------------------------------- clipboard history
    override fun onPrimaryClipChanged() = readClipboard()

    private fun readClipboard() {
        if (!prefs.clip) return
        try {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = cm.primaryClip ?: return
            if (clip.itemCount == 0) return
            // passwords copied from a password manager are marked sensitive: never keep those
            if (Build.VERSION.SDK_INT >= 24 && clip.description.extras?.getBoolean("android.content.extra.IS_SENSITIVE") == true) return
            val t = clip.getItemAt(0).coerceToText(this)?.toString() ?: return
            if (clips.add(t) && clipPanel?.visibility == View.VISIBLE) clipPanel?.refresh()
        } catch (_: Exception) {
        }
    }

    // ---------------------------------------------------------------- feedback
    override fun onPress(code: Int) {
        if (prefs.vibrate) {
            try {
                @Suppress("DEPRECATION") val v = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
                if (Build.VERSION.SDK_INT >= 26) v.vibrate(VibrationEffect.createOneShot(prefs.vibMs.toLong().coerceAtLeast(1), VibrationEffect.DEFAULT_AMPLITUDE))
                else @Suppress("DEPRECATION") v.vibrate(prefs.vibMs.toLong())
            } catch (_: Exception) {
            }
        }
        if (prefs.sound) {
            val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            am.playSoundEffect(
                when (code) {
                    K.DEL -> AudioManager.FX_KEYPRESS_DELETE
                    K.ENTER -> AudioManager.FX_KEYPRESS_RETURN
                    K.SPACE -> AudioManager.FX_KEYPRESS_SPACEBAR
                    else -> AudioManager.FX_KEYPRESS_STANDARD
                }, 0.6f
            )
        }
    }

    // ---------------------------------------------------------------- keys
    override fun onKey(code: Int, text: String) {
        val k = kv ?: return
        when (code) {
            K.SHIFT -> {
                val now = SystemClock.uptimeMillis()
                shift = if (now - lastShiftTime < 450 && shift != 2) 2 else if (shift == 0) 1 else 0
                lastShiftTime = now; manualShift = true
                k.shift = shift
                if (stripKind == 2) updateStrip()
            }
            K.CAPS -> { shift = 2; manualShift = true; k.shift = 2 }
            K.DEL -> backspace()
            K.ENTER -> enter()
            K.SPACE -> space()
            K.SYM -> k.layout = Layouts.symbols(mode)
            K.SYM2 -> k.layout = Layouts.symbols2(mode)
            K.ABC -> { k.layout = Layouts.letters(prefs.numberRow, mode); selectMode = false; k.selectMode = false; updateShift() }
            K.EMOJI, K.TB_EMOJI -> showPanel(1)
            K.SYMBOLS -> showPanel(2)
            K.TB_CLIP -> showPanel(0)
            K.TB_EDIT -> { finishWord(false); k.layout = Layouts.edit; updateStrip() }
            K.TB_ONEHAND -> { prefs.set("oneHand", !prefs.oneHand); k.layout = k.layout }
            K.OH_SWITCH -> { prefs.set("oneHandRight", !prefs.oneHandRight); k.layout = k.layout }
            K.OH_EXIT -> { prefs.set("oneHand", false); k.layout = k.layout }
            K.TB_FLOAT -> { prefs.set("floating", !prefs.floating); applyMode() }
            K.TB_HIDE -> requestHideSelf(0)
            K.TB_SETTINGS -> {
                requestHideSelf(0)
                startActivity(Intent(this, SettingsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            K.LEFT -> move(KeyEvent.KEYCODE_DPAD_LEFT)
            K.RIGHT -> move(KeyEvent.KEYCODE_DPAD_RIGHT)
            K.UP -> move(KeyEvent.KEYCODE_DPAD_UP)
            K.DOWN -> move(KeyEvent.KEYCODE_DPAD_DOWN)
            K.HOME -> move(KeyEvent.KEYCODE_MOVE_HOME)
            K.END -> move(KeyEvent.KEYCODE_MOVE_END)
            K.SELECT -> { selectMode = !selectMode; k.selectMode = selectMode }
            K.SELALL -> menu(android.R.id.selectAll)
            K.COPY -> menu(android.R.id.copy)
            K.CUT -> menu(android.R.id.cut)
            K.PASTE -> menu(android.R.id.paste)
            K.UNDO -> { finishWord(false); sendKey(KeyEvent.KEYCODE_Z, KeyEvent.META_CTRL_ON) }
            K.REDO -> { finishWord(false); sendKey(KeyEvent.KEYCODE_Z, KeyEvent.META_CTRL_ON or KeyEvent.META_SHIFT_ON) }
            K.TEXT -> typeText(text)
            else -> if (code > 0) typeText(if (shift > 0 && text.length == 1 && text[0].isLetter()) text.uppercase() else text)
        }
    }

    private fun typeText(s: String) {
        if (s.isEmpty()) return
        val c0 = s[0]
        val wordChar = s.length == 1 && (c0.isLetter() || (composing.isNotEmpty() && (c0 == '\'' || c0.isDigit())))
        if (wordChar) typeLetter(s) else typeOther(s)
    }

    private fun typeLetter(s: String) {
        val c = ic ?: return
        clearUndo(); swipeTotal = 0; autoSpaced = false
        if (!composeOk) c.commitText(s, 1)
        else {
            if (composing.isEmpty()) {
                // start of a word: note the word before it, and take over a fragment the cursor is touching
                val before = c.getTextBeforeCursor(48, 0)?.toString() ?: ""
                val after = c.getTextAfterCursor(1, 0)
                if (after != null && after.isNotEmpty() && after[0].isLetterOrDigit()) {
                    c.commitText(s, 1) // typing inside an existing word: leave it alone
                    oneShotShift(); lastEdit = SystemClock.uptimeMillis()
                    return
                }
                var t = before.length
                while (t > 0 && before.length - t < 30 && (before[t - 1].isLetter() || before[t - 1] == '\'')) t--
                val tail = before.substring(t)
                c.beginBatchEdit()
                if (tail.isNotEmpty() && tail.any { it.isLetter() } && selStart == selEnd) {
                    c.deleteSurroundingText(tail.length, 0)
                    composing.append(tail)
                    prevLower = contextOf(before.substring(0, t))
                    capAuto = false
                } else {
                    prevLower = contextOf(before)
                    capAuto = shift == 1 && !manualShift
                }
                composing.append(s)
                c.setComposingText(composing, 1)
                c.endBatchEdit()
            } else {
                composing.append(s)
                c.setComposingText(composing, 1)
            }
        }
        oneShotShift()
        lastEdit = SystemClock.uptimeMillis()
        updateStrip()
    }

    private fun oneShotShift() {
        if (shift == 1) { shift = 0; kv?.shift = 0 }
        manualShift = false
    }

    private fun typeOther(s: String) {
        val c = ic ?: return
        var corrected = false
        var swapped = false
        c.beginBatchEdit()
        if (composing.isNotEmpty()) corrected = finishWord(true)
        else if (autoSpaced && prefs.punctSpace && s.length == 1 && s[0] in ".,!?;:") {
            if (c.getTextBeforeCursor(1, 0)?.toString() == " ") { c.deleteSurroundingText(1, 0); swapped = true }
        }
        c.commitText(s, 1)
        if (swapped) c.commitText(" ", 1)
        c.endBatchEdit()
        if (corrected) { undoLen = undoOut.length + s.length; undoSep = s } else clearUndo()
        swipeTotal = 0; autoSpaced = false
        if (shift == 1 && manualShift) { shift = 0; manualShift = false }
        afterEdit()
    }

    private fun space() {
        val c = ic ?: return
        val now = SystemClock.uptimeMillis()
        var period = false
        if (composing.isNotEmpty()) {
            c.beginBatchEdit()
            val corrected = finishWord(true)
            c.commitText(" ", 1)
            c.endBatchEdit()
            if (corrected) { undoLen = undoOut.length + 1; undoSep = " " } else clearUndo()
        } else {
            clearUndo()
            val b = if (prefs.doubleSpace && textField && now - lastSpaceTime < 700) c.getTextBeforeCursor(2, 0) else null
            if (b != null && b.length == 2 && b[1] == ' ' && (b[0].isLetterOrDigit() || b[0] in ")]\"'%")) {
                c.beginBatchEdit(); c.deleteSurroundingText(1, 0); c.commitText(". ", 1); c.endBatchEdit()
                period = true
            } else c.commitText(" ", 1)
        }
        lastSpaceTime = if (period) 0 else now
        swipeTotal = 0; autoSpaced = false
        val k = kv
        if (k != null && k.layout.name.startsWith("symbols")) k.layout = Layouts.letters(prefs.numberRow, mode)
        afterEdit()
    }

    private fun enter() {
        finishWord(true)
        clearUndo(); swipeTotal = 0; autoSpaced = false
        sendKeyChar('\n')
        afterEdit()
    }

    private fun backspace() {
        val c = ic ?: return
        if (composing.isNotEmpty()) {
            val n = if (composing.length >= 2 && Character.isSurrogatePair(composing[composing.length - 2], composing[composing.length - 1])) 2 else 1
            composing.setLength(composing.length - n)
            if (composing.isEmpty()) c.commitText("", 1) else c.setComposingText(composing, 1)
            lastEdit = SystemClock.uptimeMillis()
            if (composing.isEmpty()) updateShift()
            updateStrip()
            return
        }
        val typed = undoTyped
        if (typed != null) { // the keyboard changed the word; backspace puts back what was typed
            c.beginBatchEdit(); c.deleteSurroundingText(undoLen, 0); c.commitText(typed + undoSep, 1); c.endBatchEdit()
            if (learnOk) engine?.learn(null, typed, false, strong = true)
            clearUndo(); afterEdit(); return
        }
        if (swipeTotal > 0) { // a swiped word is removed whole
            c.deleteSurroundingText(swipeTotal, 0)
            swipeTotal = 0; autoSpaced = false; afterEdit(); return
        }
        if (selStart >= 0 && selStart != selEnd) c.commitText("", 1)
        else {
            val b = c.getTextBeforeCursor(24, 0)
            if (b == null || b.isEmpty()) sendDownUpKeyEvents(KeyEvent.KEYCODE_DEL)
            else {
                // delete one whole character as the user sees it (an emoji can be many code units)
                val bi = BreakIterator.getCharacterInstance()
                bi.setText(b.toString())
                val start = bi.preceding(b.length)
                c.deleteSurroundingText(if (start == BreakIterator.DONE) 1 else b.length - start, 0)
            }
        }
        autoSpaced = false
        afterEdit()
    }

    private fun clearUndo() { undoTyped = null; undoLen = 0 }

    /** Commits the word being typed, corrected if [auto] allows. Returns true if it was changed. */
    private fun finishWord(auto: Boolean): Boolean {
        if (composing.isEmpty()) return false
        val c = ic
        val typed = composing.toString()
        val r = result
        var out = typed
        val fix = r?.auto
        if (auto && prefs.autoCorrect && fix != null) out = fix
        composing.setLength(0); result = null
        if (c == null) return false
        val valid = out != typed || r == null || r.typedValid
        if (!valid && prefs.underline) {
            val sp = SpannableString(out)
            val alt = r?.words?.filter { it != out }?.take(3)?.toTypedArray() ?: emptyArray()
            sp.setSpan(SuggestionSpan(this, Locale.ENGLISH, alt, SuggestionSpan.FLAG_MISSPELLED, null), 0, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            c.commitText(sp, 1)
        } else c.commitText(out, 1)
        if (learnOk && valid) engine?.learn(prevLower, out, capAuto)
        if (out != typed) { undoTyped = typed; undoOut = out } else clearUndo()
        prevLower = Dictionary.lower(out.substringAfterLast(' '))
        lastEdit = SystemClock.uptimeMillis()
        return out != typed
    }

    private fun afterEdit() {
        lastEdit = SystemClock.uptimeMillis()
        updateShift()
        updateStrip()
    }

    /** The word before the cursor, lowercase; null at the start of a sentence; "," after other punctuation. */
    private fun contextOf(before: String): String? {
        val s = before.trimEnd(' ')
        if (s.isEmpty()) return null
        val last = s[s.length - 1]
        if (last == '.' || last == '!' || last == '?' || last == '\n') return null
        if (!last.isLetterOrDigit() && last != '\'') return ","
        var t = s.length
        while (t > 0 && (s[t - 1].isLetterOrDigit() || s[t - 1] == '\'')) t--
        return Dictionary.lower(s.substring(t))
    }

    private fun shape(w: String): String = when {
        shift == 2 -> w.uppercase()
        shift == 1 && w.isNotEmpty() && w[0].isLowerCase() -> w.substring(0, 1).uppercase() + w.substring(1)
        else -> w
    }

    private fun updateStrip() {
        val k = kv ?: return
        val e = engine
        if (e == null || !composeOk || k.layout.name == "edit") {
            result = null; shown = emptyList(); stripKind = 0
            k.setSuggestions(shown, false); return
        }
        if (composing.isNotEmpty()) {
            val r = e.suggest(composing.toString(), prevLower, 3)
            result = r
            if (prefs.suggestions) { shown = r.words; stripKind = 1; k.setSuggestions(shown, r.auto != null && prefs.autoCorrect) }
            else { shown = emptyList(); stripKind = 0; k.setSuggestions(shown, false) }
        } else if (prefs.suggestions && prefs.nextWord) {
            prevLower = contextOf(ic?.getTextBeforeCursor(48, 0)?.toString() ?: "")
            shown = e.predict(prevLower, 3).map { shape(it) }
            stripKind = 2
            k.setSuggestions(shown, false)
        } else { shown = emptyList(); stripKind = 0; k.setSuggestions(shown, false) }
        k.post { k.dumpKeys() }
    }

    private fun updateShift() {
        val k = kv ?: return
        if (shift == 2 || manualShift) { k.shift = shift; return }
        var s = 0
        val ei = info
        val c = ic
        if (prefs.autoCap && ei != null && c != null && composing.isEmpty() && k.layout.name == "letters" &&
            ei.inputType and InputType.TYPE_MASK_CLASS == InputType.TYPE_CLASS_TEXT && c.getCursorCapsMode(ei.inputType) != 0) s = 1
        shift = s
        k.shift = s
    }

    // ---------------------------------------------------------------- suggestions and swipes
    override fun onPick(index: Int) {
        val w = shown.getOrNull(index) ?: return
        val c = ic ?: return
        onPress(0)
        c.beginBatchEdit()
        when (stripKind) {
            1 -> {
                val typed = composing.toString()
                c.commitText("$w ", 1)
                if (learnOk) engine?.learn(prevLower, w, capAuto && w != typed, strong = w == typed)
                composing.setLength(0); result = null
            }
            3 -> {
                c.deleteSurroundingText(swipeWord, 0)
                c.commitText("$w ", 1)
                if (learnOk) engine?.learn(null, w, false)
            }
            else -> {
                val b = c.getTextBeforeCursor(1, 0)
                val lead = if (b != null && b.isNotEmpty() && !b[0].isWhitespace()) " " else ""
                c.commitText("$lead$w ", 1)
                if (learnOk) engine?.learn(prevLower, w, shift == 1 && !manualShift)
            }
        }
        c.endBatchEdit()
        clearUndo(); swipeTotal = 0; autoSpaced = true
        if (shift == 1) shift = 0
        manualShift = false
        afterEdit()
    }

    override fun onForget(index: Int) {
        val w = shown.getOrNull(index) ?: return
        onPress(0)
        Data.forget(w)
        Toast.makeText(this, "“$w” will no longer be suggested", Toast.LENGTH_SHORT).show()
        if (stripKind == 3) { shown = shown.filter { it != w }; kv?.setSuggestions(shown, false) } else updateStrip()
    }

    override fun onSwipe(xs: FloatArray, ys: FloatArray, n: Int, keyX: FloatArray, keyY: FloatArray, keyW: Float) {
        val dec = decoder ?: return
        val c = ic ?: return
        c.beginBatchEdit()
        finishWord(true)
        val before = c.getTextBeforeCursor(48, 0)?.toString() ?: ""
        val prev = contextOf(before)
        val words = dec.decode(xs, ys, n, keyX, keyY, keyW, prev, 4)
        if (words.isEmpty()) { c.endBatchEdit(); afterEdit(); return }
        val lead = if (before.isNotEmpty() && !before.last().isWhitespace() && before.last() !in "([{\"'/-@#“") " " else ""
        val auto = shift == 1 && !manualShift
        val w = shape(words[0])
        c.commitText("$lead$w ", 1)
        c.endBatchEdit()
        swipeTotal = lead.length + w.length + 1; swipeWord = w.length + 1
        autoSpaced = true; clearUndo()
        if (learnOk) engine?.learn(prev, w, auto)
        prevLower = Dictionary.lower(w)
        shown = words.drop(1).map { shape(it) }
        stripKind = 3
        kv?.setSuggestions(shown, false)
        if (shift == 1) shift = 0
        manualShift = false
        lastEdit = SystemClock.uptimeMillis()
        updateShift()
        kv?.let { v -> v.post { v.dumpKeys() } }
    }

    // ---------------------------------------------------------------- cursor and editing keys
    override fun onCursor(dir: Int) {
        flushComposing()
        sendDownUpKeyEvents(if (dir < 0) KeyEvent.KEYCODE_DPAD_LEFT else KeyEvent.KEYCODE_DPAD_RIGHT)
    }

    private fun flushComposing() {
        if (composing.isNotEmpty()) { ic?.finishComposingText(); composing.setLength(0); result = null }
        clearUndo(); swipeTotal = 0; autoSpaced = false
    }

    private fun move(keyCode: Int) {
        flushComposing()
        sendKey(keyCode, if (selectMode) KeyEvent.META_SHIFT_ON or KeyEvent.META_SHIFT_LEFT_ON else 0)
    }

    private fun sendKey(keyCode: Int, meta: Int) {
        val c = ic ?: return
        val t = SystemClock.uptimeMillis()
        c.sendKeyEvent(KeyEvent(t, t, KeyEvent.ACTION_DOWN, keyCode, 0, meta))
        c.sendKeyEvent(KeyEvent(t, t, KeyEvent.ACTION_UP, keyCode, 0, meta))
    }

    private fun menu(id: Int) {
        flushComposing()
        ic?.performContextMenuAction(id)
        if (id != android.R.id.selectAll) { selectMode = false; kv?.selectMode = false }
    }

    override fun onUpdateSelection(oldSelStart: Int, oldSelEnd: Int, newSelStart: Int, newSelEnd: Int, candidatesStart: Int, candidatesEnd: Int) {
        super.onUpdateSelection(oldSelStart, oldSelEnd, newSelStart, newSelEnd, candidatesStart, candidatesEnd)
        selStart = newSelStart; selEnd = newSelEnd
        // changes the keyboard made itself arrive here too; only react to the user moving the cursor
        if (SystemClock.uptimeMillis() - lastEdit < 350) return
        if (composing.isNotEmpty() && (candidatesStart < 0 || newSelStart != candidatesEnd || newSelEnd != candidatesEnd)) {
            ic?.finishComposingText()
            composing.setLength(0); result = null
        }
        if (composing.isEmpty()) {
            clearUndo(); swipeTotal = 0; autoSpaced = false; manualShift = false
            updateShift()
            updateStrip()
        }
    }

}

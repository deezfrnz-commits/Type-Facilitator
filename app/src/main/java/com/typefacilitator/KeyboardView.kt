package com.typefacilitator

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.CornerPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.util.SparseArray
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * The keyboard itself: the suggestion/tool strip on top and a honeycomb of hexagon keys below,
 * drawn on one canvas. Handles taps, multi-finger typing, long-press popups, key repeat,
 * swipe typing, and sliding on the space bar to move the cursor.
 */
class KeyboardView(ctx: Context, private val prefs: Prefs, private val listener: Listener) : View(ctx) {
    interface Listener {
        fun onPress(code: Int)
        fun onKey(code: Int, text: String)
        fun onSwipe(xs: FloatArray, ys: FloatArray, n: Int, keyX: FloatArray, keyY: FloatArray, keyW: Float)
        fun onPick(index: Int)
        fun onForget(index: Int)
        fun onCursor(dir: Int)
        fun onFloatMove(dx: Float, dy: Float, done: Boolean)
        fun onFloatResize(dx: Float, done: Boolean)
    }

    var layout: Layout = Layouts.letters(false, Layouts.MODE_TEXT)
        set(v) { field = v; cancelTouches(); relayout(); requestLayout(); invalidate() }
    var shift = 0
        set(v) { if (field != v) { field = v; invalidate() } }
    var enterIcon = Icons.ENTER
        set(v) { if (field != v) { field = v; invalidate() } }
    var selectMode = false
        set(v) { field = v; invalidate() }
    var swipeAllowed = true
    var floating = false
        set(v) { field = v; relayout(); requestLayout() }
    var landscape = false

    private var suggestions: List<String> = emptyList()
    private var boldFirst = false
    private var toolbarForced = false

    private val d = resources.displayMetrics.density
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val trailPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
    private val tmpPath = Path()
    private val handler = Handler(Looper.getMainLooper())

    // geometry
    private var bx = 0f; private var bw = 0f          // board left and width (narrower in one-handed mode)
    private var gripH = 0f; private var stripH = 0f; private var keysTop = 0f
    private var hexH = 0f; private var pitch = 0f; private var kw = 0f
    private val gap = 1.6f * d
    private var keys: List<Key> = emptyList()
    private val stripKeys = ArrayList<Key>()
    private val sideKeys = ArrayList<Key>()
    private var contentX0 = 0f; private var contentX1 = 0f
    private val keyX = FloatArray(26); private val keyY = FloatArray(26)

    // touch state
    private class Ptr {
        var key: Key? = null; var sugg = -1; var x0 = 0f; var y0 = 0f; var fired = false
        var mode = 0; var lastX = 0f; var rawX = 0f; var rawY = 0f; var longPress: Runnable? = null
    }
    private val ptrs = SparseArray<Ptr>()
    private var popupKey: Key? = null; private var popupAlts: List<String> = emptyList(); private var popupSel = 0
    private var popX = 0f; private var popY = 0f; private var popCell = 0f; private var popCols = 1
    private val sx = FloatArray(512); private val sy = FloatArray(512); private var sn = 0
    private var swiping = false
    private val repeat = object : Runnable {
        override fun run() { listener.onKey(K.DEL, ""); handler.postDelayed(this, 45) }
    }

    fun setSuggestions(list: List<String>, autoFirst: Boolean) {
        suggestions = list; boldFirst = autoFirst; toolbarForced = false
        invalidate()
    }

    private val showToolbar: Boolean get() = toolbarForced || suggestions.isEmpty()

    // ---------------------------------------------------------------- layout
    private fun rowsHeight(): Float = pitch * (layout.rows.size - 1) + hexH

    private fun computeSizes(width: Int) {
        landscape = resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
        val pct = (if (landscape) prefs.heightL else prefs.heightP) / 100f
        var base = (if (landscape) 44f else 62f) * d * pct
        if (floating) base *= 0.82f * width / (resources.displayMetrics.widthPixels * 0.72f).coerceAtLeast(1f)
        hexH = base
        pitch = hexH * 0.75f
        gripH = if (floating) 26f * d else 0f
        stripH = min(44f * d, max(34f * d, hexH * 0.74f))
        keysTop = gripH + stripH + 3f * d
        val oneHand = prefs.oneHand && !floating && !landscape
        bw = if (oneHand) width * prefs.oneHandW / 100f else width.toFloat()
        bx = if (oneHand && prefs.oneHandRight) width - bw else 0f
    }

    override fun onMeasure(wSpec: Int, hSpec: Int) {
        val w = MeasureSpec.getSize(wSpec)
        computeSizes(w)
        val h = keysTop + rowsHeight() + 4f * d + (if (floating) 4f * d else prefs.bottomPad * d)
        setMeasuredDimension(w, h.toInt())
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) { relayout() }

    /** Pointy-top hexagon centred on (cx,cy). */
    private fun hex(p: Path, cx: Float, cy: Float, a: Float, b: Float) {
        p.reset()
        p.moveTo(cx, cy - b); p.lineTo(cx + a, cy - b / 2); p.lineTo(cx + a, cy + b / 2)
        p.lineTo(cx, cy + b); p.lineTo(cx - a, cy + b / 2); p.lineTo(cx - a, cy - b / 2); p.close()
    }

    /** Stretched hexagon with pointed left and right ends, for keys wider than one column. */
    private fun wideHex(p: Path, x0: Float, x1: Float, yt: Float, yb: Float) {
        val ym = (yt + yb) / 2
        val e = (yb - yt) * 0.32f
        p.reset()
        p.moveTo(x0, ym); p.lineTo(x0 + e, yt); p.lineTo(x1 - e, yt); p.lineTo(x1, ym)
        p.lineTo(x1 - e, yb); p.lineTo(x0 + e, yb); p.close()
    }

    private fun relayout() {
        if (width == 0) return
        computeSizes(width)
        kw = bw / layout.cols
        val list = ArrayList<Key>()
        val last = layout.rows.size - 1
        layout.rows.forEachIndexed { r, row ->
            var x = bx + if (r % 2 == 1) kw / 2 else 0f
            val y0 = keysTop + r * pitch
            for (k in row) {
                k.x0 = x; k.x1 = x + k.span * kw; k.y0 = y0; k.y1 = y0 + hexH
                k.cx = (k.x0 + k.x1) / 2; k.cy = y0 + hexH / 2
                if (k.span == 1f) hex(k.path, k.cx, k.cy, kw / 2 - gap, hexH / 2 - gap)
                else wideHex(k.path, k.x0 + gap, k.x1 - gap, k.cy - hexH / 4 + gap / 2, if (r == last) k.cy + hexH * 0.40f else k.cy + hexH / 4 - gap / 2)
                x = k.x1
                list.add(k)
                if (k.isLetter) { keyX[k.code - 'a'.code] = k.cx; keyY[k.code - 'a'.code] = k.cy }
            }
        }
        keys = list
        // strip: menu toggle on the left, then either tools or suggestions
        stripKeys.clear()
        val sh = stripH - 6f * d
        val sw = sh * 0.9f
        val cy = gripH + stripH / 2
        fun stripKey(code: Int, icon: Int, cx: Float): Key {
            val k = Key("", code, fn = true, icon = icon)
            k.cx = cx; k.cy = cy; k.x0 = cx - sw / 2; k.x1 = cx + sw / 2; k.y0 = cy - sh / 2; k.y1 = cy + sh / 2
            hex(k.path, cx, cy, sw / 2 - d, sh / 2 - d)
            stripKeys.add(k)
            return k
        }
        stripKey(K.TB_TOGGLE, Icons.MENU, bx + 4f * d + sw / 2)
        contentX0 = bx + 8f * d + sw
        contentX1 = bx + bw - 4f * d
        val tools = ArrayList<IntArray>()
        tools.add(intArrayOf(K.TB_CLIP, Icons.CLIP)); tools.add(intArrayOf(K.TB_EMOJI, Icons.EMOJI))
        tools.add(intArrayOf(K.SYMBOLS, Icons.SYMBOLS)); tools.add(intArrayOf(K.TB_EDIT, Icons.EDIT))
        if (!floating && !landscape) tools.add(intArrayOf(K.TB_ONEHAND, Icons.ONEHAND))
        tools.add(intArrayOf(K.TB_FLOAT, if (floating) Icons.DOCK else Icons.FLOAT))
        tools.add(intArrayOf(K.TB_SETTINGS, Icons.SETTINGS)); tools.add(intArrayOf(K.TB_HIDE, Icons.HIDE))
        val step = (contentX1 - contentX0) / tools.size
        tools.forEachIndexed { i, t -> stripKey(t[0], t[1], contentX0 + step * (i + 0.5f)) }
        if (floating) {
            val g = Key("", K.FL_RESIZE, fn = true, icon = Icons.RESIZE)
            g.cx = bx + bw - gripH * 0.8f; g.cy = gripH / 2 + d; g.x0 = g.cx - gripH; g.x1 = bx + bw; g.y0 = 0f; g.y1 = gripH
            hex(g.path, g.cx, g.cy, gripH * 0.42f, gripH * 0.46f)
            stripKeys.add(g)
        }
        // one-handed mode: two hexagons in the free space switch side or go back to full width
        sideKeys.clear()
        if (bw < width - 1f) {
            val free = width - bw
            val cxs = if (bx > 0f) free / 2 else bw + free / 2
            val a = min(free * 0.42f, 24f * d)
            val mid = keysTop + rowsHeight() / 2
            for ((i, spec) in listOf(intArrayOf(K.OH_SWITCH, Icons.SWITCH), intArrayOf(K.OH_EXIT, Icons.EXPAND)).withIndex()) {
                val k = Key("", spec[0], fn = true, icon = spec[1])
                k.cx = cxs; k.cy = mid + (i - 0.5f) * a * 2.8f
                k.x0 = k.cx - a; k.x1 = k.cx + a; k.y0 = k.cy - a * 1.2f; k.y1 = k.cy + a * 1.2f
                hex(k.path, k.cx, k.cy, a, a * 1.15f)
                sideKeys.add(k)
            }
        }
        if (debuggable) post { dumpKeys() }
    }

    private val debuggable = (ctx.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0

    /** Debug builds log where every key is on screen, so the emulator test can tap them. */
    fun dumpKeys() {
        if (!debuggable || width == 0 || !isShown) return
        val loc = IntArray(2)
        getLocationOnScreen(loc)
        val sb = StringBuilder("layout=${layout.name} shift=$shift")
        fun put(name: String, x: Float, y: Float) { sb.append(' ').append(name).append('@').append((loc[0] + x).toInt()).append(',').append((loc[1] + y).toInt()) }
        for (k in keys + stripKeys.filter { showToolbar || it.code == K.TB_TOGGLE || it.code == K.FL_RESIZE } + sideKeys) put(name(k), k.cx, k.cy)
        if (!showToolbar) for (i in 0 until min(suggestions.size, 3)) put("SG$i", suggX(i), gripH + stripH / 2)
        sb.append(" sugg=").append(suggestions.joinToString("|") { it.replace(' ', '_') })
        if (floating) put("GRIP", bx + bw / 2, gripH / 2)
        Log.i("TFKEYS", sb.toString())
    }

    private fun name(k: Key): String = when (k.code) {
        K.SHIFT -> "SHIFT"; K.DEL -> "DEL"; K.SYM -> "SYM"; K.ABC -> "ABC"; K.SYM2 -> "SYM2"; K.ENTER -> "ENTER"
        K.EMOJI -> "EMOJI"; K.SPACE -> "SPACE"; K.TB_TOGGLE -> "TOGGLE"; K.TB_CLIP -> "TB_CLIP"; K.TB_EMOJI -> "TB_EMOJI"
        K.TB_EDIT -> "TB_EDIT"; K.TB_ONEHAND -> "TB_ONEHAND"; K.TB_FLOAT -> "TB_FLOAT"; K.TB_SETTINGS -> "TB_SETTINGS"
        K.TB_HIDE -> "TB_HIDE"; K.SYMBOLS -> "TB_SYMBOLS"; K.FL_RESIZE -> "RESIZE"; K.OH_SWITCH -> "OH_SWITCH"; K.OH_EXIT -> "OH_EXIT"
        K.LEFT -> "LEFT"; K.RIGHT -> "RIGHT"; K.UP -> "UP"; K.DOWN -> "DOWN"
        else -> if (k.label.isNotEmpty()) k.label else "K${k.code}"
    }

    private fun suggX(i: Int): Float {
        val n = min(suggestions.size, 3)
        val cw = (contentX1 - contentX0) / 3
        // the best word sits in the middle, the second on the left, the third on the right
        val slot = if (n == 1) 1 else when (i) { 0 -> 1; 1 -> 0; else -> 2 }
        return contentX0 + cw * (slot + 0.5f)
    }

    // ---------------------------------------------------------------- drawing
    private fun contrast(c: Int): Int =
        if (Color.red(c) * 0.299 + Color.green(c) * 0.587 + Color.blue(c) * 0.114 > 140) Color.BLACK else Color.WHITE

    private fun isPressed(k: Key): Boolean {
        for (i in 0 until ptrs.size()) { val p = ptrs.valueAt(i); if (p.key === k && p.mode != 1) return true }
        return false
    }

    override fun onDraw(c: Canvas) {
        c.drawColor(prefs.cBg)
        val round = CornerPathEffect(3.5f * d)
        fill.pathEffect = round; stroke.pathEffect = round
        stroke.strokeWidth = 1.2f * d
        stroke.color = prefs.cOutline
        if (floating) drawGrip(c)
        drawStrip(c)
        for (k in keys) drawKey(c, k, isPressed(k))
        for (k in sideKeys) drawKey(c, k, isPressed(k))
        // swipe trail
        if (swiping && prefs.trail && sn > 1) {
            tmpPath.reset(); tmpPath.moveTo(sx[0], sy[0])
            for (i in 1 until sn) tmpPath.lineTo(sx[i], sy[i])
            trailPaint.color = prefs.cAccent; trailPaint.alpha = 190; trailPaint.strokeWidth = 5f * d
            c.drawPath(tmpPath, trailPaint)
        }
        // preview of the pressed key
        if (prefs.popup && popupKey == null && !swiping) {
            for (i in 0 until ptrs.size()) {
                val k = ptrs.valueAt(i).key ?: continue
                if (k.fn || k.code <= 32 || ptrs.valueAt(i).mode != 0) continue
                val py = max(k.cy - hexH * 0.92f, hexH * 0.5f)
                hex(tmpPath, k.cx, py, kw * 0.62f, hexH * 0.56f)
                fill.color = prefs.cAccent; c.drawPath(tmpPath, fill)
                c.drawPath(tmpPath, stroke)
                text.color = contrast(prefs.cAccent); text.typeface = Typeface.DEFAULT_BOLD
                text.textSize = min(kw * 0.7f, hexH * 0.46f)
                c.drawText(shown(k.label, k), k.cx, py - (text.ascent() + text.descent()) / 2, text)
            }
        }
        drawPopup(c)
    }

    private fun shown(label: String, k: Key): String = if (shift > 0 && k.isLetter) label.uppercase() else label

    private fun drawKey(c: Canvas, k: Key, pressed: Boolean) {
        val active = (k.code == K.SHIFT && shift > 0) || (k.code == K.SELECT && selectMode)
        val bg = when {
            pressed || active -> prefs.cAccent
            k.fn -> prefs.cFn
            else -> prefs.cKey
        }
        fill.color = bg
        c.drawPath(k.path, fill)
        if (prefs.outline) c.drawPath(k.path, stroke)
        val fg = if (pressed || active) contrast(prefs.cAccent) else if (k.fn) prefs.cFnText else prefs.cText
        val icon = when (k.code) {
            K.SHIFT -> if (shift == 2) Icons.SHIFT_LOCK else if (shift == 1) Icons.SHIFT_ON else Icons.SHIFT
            K.ENTER -> enterIcon
            else -> k.icon
        }
        text.color = fg
        if (icon != 0) {
            Icons.draw(c, icon, k.cx, (k.y0 + k.y1) / 2 + if (k.span > 1f && k.y1 - k.cy > hexH * 0.3f) hexH * 0.04f else 0f, min(min(kw, hexH) * 0.44f, 22f * d), text)
            return
        }
        val label = shown(k.label, k)
        val fs = prefs.fontScale / 100f
        text.typeface = if (k.fn) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        var size = if (label.length == 1) min(kw * 0.56f, hexH * 0.36f) * fs else min(kw * 0.34f, hexH * 0.24f) * fs
        text.textSize = size
        val avail = (k.x1 - k.x0) * 0.8f
        val tw = text.measureText(label)
        if (tw > avail) { size *= avail / tw; text.textSize = size }
        val hasHint = prefs.hints && k.hint.isNotEmpty()
        val cy = k.cy + if (hasHint) hexH * 0.05f else 0f
        c.drawText(label, k.cx, cy - (text.ascent() + text.descent()) / 2, text)
        if (hasHint) {
            text.textSize = hexH * 0.17f
            text.alpha = 150
            c.drawText(k.hint, k.cx, k.cy - hexH * 0.24f, text)
            text.alpha = 255
        }
    }

    private fun drawGrip(c: Canvas) {
        // a row of small hexagons marks the handle used to drag the floating keyboard
        fill.color = prefs.cOutline
        val cy = gripH / 2 + d
        for (i in -2..2) { hex(tmpPath, bx + bw / 2 + i * 9f * d, cy, 3.2f * d, 3.6f * d); c.drawPath(tmpPath, fill) }
    }

    private fun drawStrip(c: Canvas) {
        val cy = gripH + stripH / 2
        for (k in stripKeys) {
            if (k.code != K.TB_TOGGLE && k.code != K.FL_RESIZE && !showToolbar) continue
            val pressed = isPressed(k)
            fill.color = if (pressed) prefs.cAccent else prefs.cKey
            c.drawPath(k.path, fill)
            if (prefs.outline) c.drawPath(k.path, stroke)
            text.color = if (pressed) contrast(prefs.cAccent) else prefs.cText
            val icon = if (k.code == K.TB_TOGGLE && toolbarForced && suggestions.isNotEmpty()) Icons.BACK else k.icon
            Icons.draw(c, icon, k.cx, k.cy, (k.y1 - k.y0) * (if (k.code == K.FL_RESIZE) 0.36f else 0.42f), text)
        }
        if (showToolbar) return
        val n = min(suggestions.size, 3)
        val cw = (contentX1 - contentX0) / 3
        var pressedIdx = -1
        for (i in 0 until ptrs.size()) if (ptrs.valueAt(i).sugg >= 0) pressedIdx = ptrs.valueAt(i).sugg
        for (i in 0 until n) {
            val x = suggX(i)
            val bold = i == 0 && boldFirst
            text.typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
            text.color = if (i == pressedIdx) prefs.cAccent else prefs.cText
            text.alpha = if (i == 0 || i == pressedIdx) 255 else 200
            var size = min(17f * d, stripH * 0.42f) * prefs.fontScale / 100f
            text.textSize = size
            val tw = text.measureText(suggestions[i])
            if (tw > cw - 10f * d) { size *= (cw - 10f * d) / tw; text.textSize = max(size, 9f * d) }
            c.drawText(suggestions[i], x, cy - (text.ascent() + text.descent()) / 2, text)
            text.alpha = 255
            if (bold) {
                stroke.color = prefs.cAccent
                c.drawLine(x - cw * 0.2f, cy + stripH * 0.36f, x + cw * 0.2f, cy + stripH * 0.36f, stroke)
                stroke.color = prefs.cOutline
            }
        }
        // small hexagon dividers between the words
        fill.color = prefs.cOutline
        for (i in 1..2) { hex(tmpPath, contentX0 + cw * i, cy, 2.4f * d, 2.8f * d); c.drawPath(tmpPath, fill) }
    }

    private fun drawPopup(c: Canvas) {
        val k = popupKey ?: return
        for (i in popupAlts.indices) {
            val cx = popX + (i % popCols + 0.5f) * popCell
            val cy = popY + (i / popCols) * hexH * 0.8f + hexH * 0.45f
            hex(tmpPath, cx, cy, popCell / 2 - d, hexH * 0.45f)
            val sel = i == popupSel
            fill.color = if (sel) prefs.cAccent else prefs.cFn
            c.drawPath(tmpPath, fill); c.drawPath(tmpPath, stroke)
            text.color = if (sel) contrast(prefs.cAccent) else prefs.cFnText
            text.typeface = Typeface.DEFAULT
            val s = shown(popupAlts[i], k)
            text.textSize = min(popCell * 0.5f, hexH * 0.34f)
            val tw = text.measureText(s)
            if (tw > popCell * 0.8f) text.textSize *= popCell * 0.8f / tw
            c.drawText(s, cx, cy - (text.ascent() + text.descent()) / 2, text)
        }
    }

    // ---------------------------------------------------------------- touch
    private fun keyAt(x: Float, y: Float): Key? {
        for (k in sideKeys) if (x >= k.x0 && x <= k.x1 && y >= k.y0 && y <= k.y1) return k
        if (y < keysTop - 2 * d || x < bx || x > bx + bw) return null
        var best: Key? = null
        var bd = Float.MAX_VALUE
        val ys = kw * 0.866f / pitch // makes the honeycomb regular, so the nearest centre is the hexagon under the finger
        for (k in keys) {
            val ax = k.x0 + kw / 2
            val bxx = k.x1 - kw / 2
            val px = if (x < ax) ax else if (x > bxx) bxx else x
            val dd = hypot(x - px, (y - k.cy) * ys)
            if (dd < bd) { bd = dd; best = k }
        }
        return if (bd < kw * 1.2f) best else null
    }

    private fun stripAt(x: Float, y: Float, p: Ptr) {
        for (k in stripKeys) {
            if (k.code != K.TB_TOGGLE && k.code != K.FL_RESIZE && !showToolbar) continue
            if (x >= k.x0 - 2 * d && x <= k.x1 + 2 * d && y >= k.y0 - 4 * d && y <= k.y1 + 4 * d) { p.key = k; return }
        }
        if (!showToolbar && x >= contentX0 && x <= contentX1 && y >= gripH) {
            val cw = (contentX1 - contentX0) / 3
            for (i in 0 until min(suggestions.size, 3)) if (abs(x - suggX(i)) <= cw / 2) { p.sugg = i; return }
        }
    }

    private fun cancelLong(p: Ptr) { p.longPress?.let { handler.removeCallbacks(it) }; p.longPress = null }

    fun cancelTouches() {
        for (i in 0 until ptrs.size()) cancelLong(ptrs.valueAt(i))
        ptrs.clear(); popupKey = null; swiping = false; sn = 0
        handler.removeCallbacks(repeat)
        invalidate()
    }

    private fun fire(p: Ptr) {
        val k = p.key ?: return
        if (p.fired) return
        p.fired = true
        if (k.code == K.TB_TOGGLE) { toolbarForced = !toolbarForced; if (debuggable) post { dumpKeys() }; invalidate(); return }
        listener.onKey(k.code, k.label)
    }

    private fun startLong(p: Ptr, id: Int) {
        cancelLong(p)
        val r = Runnable {
            p.longPress = null
            val k = p.key
            if (p.sugg >= 0) { val i = p.sugg; p.sugg = -1; p.fired = true; listener.onForget(i); invalidate() }
            else if (k != null && p.mode == 0 && !p.fired && k.alts.isNotEmpty()) openPopup(k, p)
            else if (k != null && p.mode == 0 && !p.fired && k.code == K.SHIFT) { p.fired = true; listener.onPress(0); listener.onKey(K.CAPS, ""); invalidate() }
        }
        p.longPress = r
        handler.postDelayed(r, prefs.longPress.toLong())
    }

    private fun openPopup(k: Key, p: Ptr) {
        popupKey = k; popupAlts = k.alts; popupSel = 0; p.mode = 3
        popCell = max(kw, 40f * d)
        popCols = min(popupAlts.size, max(1, (bw / popCell).toInt()))
        val rows = (popupAlts.size + popCols - 1) / popCols
        popX = (k.cx - popCell / 2).coerceIn(bx, max(bx, bx + bw - popCols * popCell))
        popY = max(0f, k.y0 - rows * hexH * 0.8f + hexH * 0.05f)
        listener.onPress(0)
        invalidate()
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        val idx = e.actionIndex
        val id = e.getPointerId(idx)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                if (e.actionMasked == MotionEvent.ACTION_DOWN) cancelTouches()
                val x = e.getX(idx); val y = e.getY(idx)
                val p = Ptr().apply { x0 = x; y0 = y; lastX = x; rawX = e.rawX; rawY = e.rawY }
                if (floating && y < gripH) {
                    p.key = stripKeys.firstOrNull { it.code == K.FL_RESIZE && x >= it.x0 }
                    p.mode = if (p.key != null) 6 else 5
                    ptrs.put(id, p); invalidate(); return true
                }
                // a second finger landing finishes the key under the first one
                for (i in 0 until ptrs.size()) { val o = ptrs.valueAt(i); if (o.mode == 0 && o.key != null && o.sugg < 0 && !o.fired && o.key!!.code != K.SHIFT) { cancelLong(o); fire(o); o.key = null } }
                if (y < keysTop - 2 * d && keyAt(x, y) == null) stripAt(x, y, p) else p.key = keyAt(x, y)
                ptrs.put(id, p)
                val k = p.key
                if (k != null) {
                    listener.onPress(k.code)
                    if (k.code == K.DEL) { fire(p); handler.postDelayed(repeat, 380) } else startLong(p, id)
                } else if (p.sugg >= 0) startLong(p, id)
                invalidate()
            }
            MotionEvent.ACTION_MOVE -> {
                for (i in 0 until e.pointerCount) {
                    val p = ptrs.get(e.getPointerId(i)) ?: continue
                    move(p, e.getX(i), e.getY(i), e)
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                val p = ptrs.get(id) ?: return true
                ptrs.remove(id)
                cancelLong(p)
                val k = p.key
                when (p.mode) {
                    1 -> {
                        swiping = false
                        if (sn >= 3) listener.onSwipe(sx.copyOf(sn), sy.copyOf(sn), sn, keyX, keyY, kw) else fire(p)
                        sn = 0
                    }
                    3 -> {
                        val pk = popupKey
                        if (pk != null && popupSel in popupAlts.indices) listener.onKey(K.TEXT, shown(popupAlts[popupSel], pk))
                        popupKey = null
                    }
                    5 -> listener.onFloatMove(e.rawX - p.rawX, e.rawY - p.rawY, true)
                    6 -> listener.onFloatResize(e.rawX - p.rawX, true)
                    0 -> if (p.sugg >= 0) listener.onPick(p.sugg) else if (k != null) fire(p)
                }
                if (k != null && k.code == K.DEL) handler.removeCallbacks(repeat)
                invalidate()
            }
            MotionEvent.ACTION_CANCEL -> cancelTouches()
        }
        return true
    }

    private fun move(p: Ptr, x: Float, y: Float, e: MotionEvent) {
        when (p.mode) {
            5 -> { listener.onFloatMove(e.rawX - p.rawX, e.rawY - p.rawY, false); return }
            6 -> { listener.onFloatResize(e.rawX - p.rawX, false); return }
            3 -> {
                val col = ((x - popX) / popCell).toInt().coerceIn(0, popCols - 1)
                val rows = (popupAlts.size + popCols - 1) / popCols
                val row = ((y - popY) / (hexH * 0.8f)).toInt().coerceIn(0, rows - 1)
                val s = min(row * popCols + col, popupAlts.size - 1)
                if (s != popupSel) { popupSel = s; invalidate() }
                return
            }
            2 -> {
                val step = kw * 0.42f
                while (x - p.lastX > step) { listener.onCursor(1); p.lastX += step }
                while (p.lastX - x > step) { listener.onCursor(-1); p.lastX -= step }
                return
            }
            1 -> {
                if (sn < sx.size && hypot(x - sx[sn - 1], y - sy[sn - 1]) > 3 * d) { sx[sn] = x; sy[sn] = y; sn++; invalidate() }
                return
            }
        }
        if (p.sugg >= 0) {
            if (hypot(x - p.x0, y - p.y0) > 14 * d) { cancelLong(p); p.sugg = -1; p.fired = true; invalidate() }
            return
        }
        val k = p.key ?: return
        if (p.fired && k.code != K.DEL) return
        val dist = hypot(x - p.x0, y - p.y0)
        if (k.code == K.SPACE && prefs.spaceCursor && abs(x - p.x0) > kw * 0.7f) {
            cancelLong(p); p.mode = 2; p.lastX = x; p.fired = true; listener.onPress(0); return
        }
        if (k.code == K.DEL || k.code == K.SPACE || stripKeys.contains(k)) return
        val under = keyAt(x, y)
        if (k.isLetter && prefs.swipe && swipeAllowed && ptrs.size() == 1 && layout.name == "letters") {
            // on the letters a moving finger is a swipe as soon as it reaches another letter
            if (under != null && under !== k && under.isLetter && dist > kw * 0.4f) {
                cancelLong(p); p.mode = 1; swiping = true
                sx[0] = p.x0; sy[0] = p.y0; sx[1] = x; sy[1] = y; sn = 2
                invalidate(); return
            }
            if (under == null || under.isLetter) return
        }
        // sliding off a key before lifting moves the press to the key now under the finger
        if (under != null && under !== k && dist > kw * 0.5f && !sideKeys.contains(k)) {
            p.key = under; p.x0 = x; p.y0 = y
            startLong(p, 0)
            invalidate()
        }
    }
}

package com.typefacilitator

import android.content.Context
import android.graphics.Canvas
import android.graphics.CornerPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.widget.OverScroller
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** One tab of the emoji or symbol catalog. Each item is the glyph followed by its variants (skin tones). */
class GridCategory(val name: String, val tab: String, val items: List<Array<String>>)

object GridData {
    private var emoji: List<GridCategory>? = null
    private var symbols: List<GridCategory>? = null
    private val TABS = mapOf(
        "Smileys" to "😀", "People" to "👋", "Animals" to "🐻", "Food" to "🍔", "Travel" to "🚗",
        "Activities" to "⚽", "Objects" to "💡", "Symbols" to "🔣", "Flags" to "🏁",
    )

    private fun parse(ctx: Context, file: String): List<GridCategory> {
        val out = ArrayList<GridCategory>()
        var name = ""
        var tab = ""
        var items = ArrayList<Array<String>>()
        val probe = Paint()
        fun supported(s: String) = Build.VERSION.SDK_INT < 23 || probe.hasGlyph(s)
        fun flush() { if (items.isNotEmpty()) out.add(GridCategory(name, if (tab.isEmpty() || !supported(tab)) items[0][0] else tab, items)) }
        ctx.assets.open(file).bufferedReader().forEachLine { line ->
            if (line.startsWith("#") && line.length > 2 && line[1].isLetter()) {
                flush()
                val p = line.substring(1).split(' ')
                name = p[0]; tab = if (p.size > 1) p[1] else TABS[name] ?: ""
                items = ArrayList()
            } else if (line.isNotEmpty()) {
                val p = line.split(' ')
                if (file == "emoji.txt") {
                    // one emoji per line with its skin-tone variants; skip what this phone's font cannot draw
                    if (supported(p[0])) items.add(p.filter { supported(it) }.toTypedArray())
                } else for (s in p) if (s.isNotEmpty() && supported(s)) items.add(arrayOf(s))
            }
        }
        flush()
        return out
    }

    fun emoji(ctx: Context): List<GridCategory> = emoji ?: parse(ctx, "emoji.txt").also { emoji = it }
    fun symbols(ctx: Context): List<GridCategory> = symbols ?: parse(ctx, "symbols.txt").also { symbols = it }
}

/** Scrollable honeycomb of emoji or symbols with category tabs, recently used items and variant popups. */
class GridPanel(ctx: Context, private val prefs: Prefs, private val listener: Listener) : View(ctx) {
    interface Listener {
        fun onGridText(text: String)
        fun onPanelKey(code: Int)
        fun onPress(code: Int)
    }

    var panelHeight = 0
    private var emojiMode = true
    private var cats: List<GridCategory> = emptyList()
    private var cat = 0
    private var items: List<Array<String>> = emptyList()
    private var recents = ArrayList<String>()

    private val d = resources.displayMetrics.density
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val path = Path()
    private val scroller = OverScroller(ctx)
    private val slop = ViewConfiguration.get(ctx).scaledTouchSlop
    private val handler = Handler(Looper.getMainLooper())
    private var tracker: VelocityTracker? = null

    private var barH = 0f; private var cw = 0f; private var ch = 0f; private var pitch = 0f
    private val cols = 8
    private var scrollY0 = 0f; private var maxScroll = 0f
    private var downX = 0f; private var downY = 0f; private var lastY = 0f
    private var dragging = false; private var pressed = -1; private var pressedTab = -1
    private var popup: Array<String>? = null; private var popX = 0f; private var popY = 0f; private var popCols = 1
    private var longDone = false
    private val longPress = Runnable {
        val it = items.getOrNull(pressed) ?: return@Runnable
        if (it.size > 1) { openPopup(pressed, it); longDone = true; listener.onPress(0) }
    }

    // tab bar: ABC, emoji/symbols switch, categories..., backspace
    private val tabCount: Int get() = cats.size + 3 + if (emojiMode) 1 else 0

    fun show(emoji: Boolean) {
        emojiMode = emoji
        cats = if (emoji) GridData.emoji(context) else GridData.symbols(context)
        recents = ArrayList((prefs.sp.getString("recentEmoji", "") ?: "").split(' ').filter { it.isNotEmpty() })
        select(if (emoji && recents.isNotEmpty()) -1 else 0)
    }

    private fun select(c: Int) {
        cat = c
        items = if (c < 0) recents.map { arrayOf(it) } else cats.getOrNull(c)?.items ?: emptyList()
        scrollY0 = 0f; popup = null
        scroller.forceFinished(true)
        measureContent()
        invalidate()
        if ((context.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0) post { dump() }
    }

    private fun dump() {
        if (width == 0 || !isShown) return
        val loc = IntArray(2); getLocationOnScreen(loc)
        val sb = StringBuilder("layout=${if (emojiMode) "emoji" else "symbolgrid"} cat=$cat items=${items.size}")
        for (i in 0 until tabCount) sb.append(" TAB$i@${(loc[0] + tabX(i)).toInt()},${(loc[1] + barH / 2).toInt()}")
        for (i in 0 until min(items.size, 12)) sb.append(" CELL$i@${(loc[0] + cellX(i)).toInt()},${(loc[1] + cellY(i)).toInt()}")
        Log.i("TFKEYS", sb.toString())
    }

    private fun measureContent() {
        if (width == 0) return
        barH = 46f * d
        cw = width / (cols + 0.5f)
        ch = cw * 1.12f
        pitch = ch * 0.75f
        val rows = (items.size + cols - 1) / cols
        val content = if (rows == 0) 0f else pitch * (rows - 1) + ch + 6f * d
        maxScroll = max(0f, content - (height - barH))
    }

    override fun onMeasure(w: Int, h: Int) {
        setMeasuredDimension(MeasureSpec.getSize(w), if (panelHeight > 0) panelHeight else MeasureSpec.getSize(h))
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) { measureContent() }

    private fun tabX(i: Int): Float = width * (i + 0.5f) / tabCount
    private fun cellX(i: Int): Float = (i % cols + 0.5f) * cw + if ((i / cols) % 2 == 1) cw / 2 else 0f
    private fun cellY(i: Int): Float = barH + 3f * d + (i / cols) * pitch + ch / 2 - scrollY0

    private fun hex(cx: Float, cy: Float, a: Float, b: Float) {
        path.reset()
        path.moveTo(cx, cy - b); path.lineTo(cx + a, cy - b / 2); path.lineTo(cx + a, cy + b / 2)
        path.lineTo(cx, cy + b); path.lineTo(cx - a, cy + b / 2); path.lineTo(cx - a, cy - b / 2); path.close()
    }

    private fun glyph(c: Canvas, s: String, cx: Float, cy: Float, size: Float, maxW: Float) {
        text.textSize = size
        val tw = text.measureText(s)
        if (tw > maxW) text.textSize = size * maxW / tw
        c.drawText(s, cx, cy - (text.ascent() + text.descent()) / 2, text)
    }

    override fun onDraw(c: Canvas) {
        c.drawColor(prefs.cBg)
        val round = CornerPathEffect(3f * d)
        fill.pathEffect = round; stroke.pathEffect = round
        stroke.strokeWidth = 1.1f * d
        text.typeface = Typeface.DEFAULT
        // cells
        c.save()
        c.clipRect(0f, barH, width.toFloat(), height.toFloat())
        val first = max(0, ((scrollY0 - ch) / pitch).toInt() * cols)
        val last = min(items.size, (((scrollY0 + height - barH) / pitch).toInt() + 2) * cols)
        for (i in first until last) {
            val cx = cellX(i); val cy = cellY(i)
            hex(cx, cy, cw / 2 - 1.2f * d, ch / 2 - 1.2f * d)
            fill.color = if (i == pressed) prefs.cAccent else prefs.cKey
            c.drawPath(path, fill)
            if (prefs.outline) { stroke.color = prefs.cOutline; stroke.alpha = 110; c.drawPath(path, stroke); stroke.alpha = 255 }
            text.color = if (i == pressed) contrastOf(prefs.cAccent) else prefs.cText
            glyph(c, items[i][0], cx, cy, if (emojiMode) cw * 0.58f else cw * 0.5f, cw * 0.78f)
            if (items[i].size > 1) { // a corner mark: hold for skin tones
                fill.color = prefs.cOutline
                c.drawCircle(cx + cw * 0.22f, cy + ch * 0.27f, 1.6f * d, fill)
            }
        }
        if (items.isEmpty()) {
            text.color = prefs.cText; text.alpha = 170
            glyph(c, "Recently used emoji appear here", width / 2f, barH + 40f * d, 14f * d, width * 0.9f)
            text.alpha = 255
        }
        c.restore()
        // tab bar
        fill.color = prefs.cBg
        c.drawRect(0f, 0f, width.toFloat(), barH, fill)
        val a = min(width / tabCount / 2f - 1.5f * d, 20f * d)
        for (i in 0 until tabCount) {
            val cx = tabX(i)
            hex(cx, barH / 2, a, barH / 2 - 3f * d)
            val catIdx = tabCat(i)
            val sel = (catIdx != null && catIdx == cat) || i == pressedTab
            val fn = i < 2 || i == tabCount - 1
            fill.color = if (sel) prefs.cAccent else if (fn) prefs.cFn else prefs.cKey
            c.drawPath(path, fill)
            stroke.color = prefs.cOutline
            if (prefs.outline) c.drawPath(path, stroke)
            text.color = if (sel) contrastOf(prefs.cAccent) else if (fn) prefs.cFnText else prefs.cText
            when {
                i == 0 -> { text.typeface = Typeface.DEFAULT_BOLD; glyph(c, "ABC", cx, barH / 2, 11f * d, a * 1.5f); text.typeface = Typeface.DEFAULT }
                i == 1 -> Icons.draw(c, if (emojiMode) Icons.SYMBOLS else Icons.EMOJI, cx, barH / 2, a * 0.95f, text)
                i == tabCount - 1 -> Icons.draw(c, Icons.DEL, cx, barH / 2, a * 0.95f, text)
                catIdx == -1 -> Icons.draw(c, Icons.CLOCK, cx, barH / 2, a * 0.95f, text)
                else -> glyph(c, cats[catIdx!!].tab, cx, barH / 2, a * 0.95f, a * 1.5f)
            }
        }
        // variants popup
        val pop = popup ?: return
        for (i in pop.indices) {
            val cx = popX + (i % popCols + 0.5f) * cw
            val cy = popY + (i / popCols) * pitch + ch / 2
            hex(cx, cy, cw / 2 - d, ch / 2 - d)
            fill.color = prefs.cFn
            c.drawPath(path, fill)
            stroke.color = prefs.cAccent
            c.drawPath(path, stroke)
            text.color = prefs.cFnText
            glyph(c, pop[i], cx, cy, cw * 0.58f, cw * 0.78f)
        }
    }

    /** Category index for a tab: -1 = recents, null = not a category tab. */
    private fun tabCat(i: Int): Int? {
        if (i < 2 || i == tabCount - 1) return null
        return if (emojiMode) i - 3 else i - 2
    }

    private fun openPopup(i: Int, it: Array<String>) {
        popup = it
        popCols = min(it.size, 6)
        val rows = (it.size + popCols - 1) / popCols
        popX = (cellX(i) - popCols * cw / 2).coerceIn(0f, max(0f, width - popCols * cw))
        popY = (cellY(i) - ch / 2 - rows * pitch - ch * 0.25f).coerceIn(barH, max(barH, height - rows * pitch - ch * 0.3f))
        pressed = -1
        invalidate()
    }

    private fun cellAt(x: Float, y: Float): Int {
        if (y < barH) return -1
        var best = -1
        var bd = Float.MAX_VALUE
        val row = ((y + scrollY0 - barH - 3f * d) / pitch).toInt()
        for (r in max(0, row - 1)..row) for (col in 0 until cols) {
            val i = r * cols + col
            if (i >= items.size) break
            val dx = x - cellX(i); val dy = (y - cellY(i)) * (cw * 0.866f / pitch)
            val dd = dx * dx + dy * dy
            if (dd < bd) { bd = dd; best = i }
        }
        return if (bd < cw * cw * 0.5f) best else -1
    }

    private fun commit(s: String) {
        listener.onGridText(s)
        if (emojiMode) {
            recents.remove(s); recents.add(0, s)
            while (recents.size > 40) recents.removeAt(recents.size - 1)
            prefs.sp.edit().putString("recentEmoji", recents.joinToString(" ")).apply()
        }
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        val x = e.x; val y = e.y
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                scroller.forceFinished(true)
                tracker?.recycle(); tracker = VelocityTracker.obtain(); tracker?.addMovement(e)
                downX = x; downY = y; lastY = y; dragging = false; longDone = false
                val pop = popup
                if (pop != null) {
                    val col = ((x - popX) / cw).toInt(); val row = ((y - popY) / pitch).toInt()
                    val i = row * popCols + col
                    val inside = x >= popX && col in 0 until popCols && y >= popY && i in pop.indices
                    popup = null; pressed = -1; longDone = true
                    if (inside) { listener.onPress(0); commit(pop[i]) }
                    invalidate(); return true
                }
                if (y < barH) { pressedTab = (x / width * tabCount).toInt().coerceIn(0, tabCount - 1); pressed = -1 }
                else { pressedTab = -1; pressed = cellAt(x, y); if (pressed >= 0) handler.postDelayed(longPress, prefs.longPress.toLong()) }
                invalidate()
            }
            MotionEvent.ACTION_MOVE -> {
                tracker?.addMovement(e)
                if (!dragging && pressedTab < 0 && abs(y - downY) > slop) { dragging = true; pressed = -1; handler.removeCallbacks(longPress) }
                if (dragging && popup == null) { scrollY0 = (scrollY0 - (y - lastY)).coerceIn(0f, maxScroll); invalidate() }
                lastY = y
            }
            MotionEvent.ACTION_UP -> {
                handler.removeCallbacks(longPress)
                if (dragging) {
                    tracker?.computeCurrentVelocity(1000)
                    scroller.fling(0, scrollY0.toInt(), 0, -(tracker?.yVelocity ?: 0f).toInt(), 0, 0, 0, maxScroll.toInt())
                    postInvalidateOnAnimation()
                } else if (pressedTab >= 0) {
                    val i = pressedTab
                    listener.onPress(0)
                    when {
                        i == 0 -> listener.onPanelKey(K.ABC)
                        i == 1 -> show(!emojiMode)
                        i == tabCount - 1 -> listener.onPanelKey(K.DEL)
                        else -> select(tabCat(i)!!)
                    }
                } else if (pressed >= 0 && !longDone) {
                    listener.onPress(0)
                    commit(items[pressed][0])
                }
                pressed = -1; pressedTab = -1; dragging = false
                tracker?.recycle(); tracker = null
                invalidate()
            }
            MotionEvent.ACTION_CANCEL -> {
                handler.removeCallbacks(longPress); pressed = -1; pressedTab = -1; dragging = false
                tracker?.recycle(); tracker = null; invalidate()
            }
        }
        return true
    }

    override fun computeScroll() {
        if (scroller.computeScrollOffset()) {
            scrollY0 = scroller.currY.toFloat().coerceIn(0f, maxScroll)
            postInvalidateOnAnimation()
        }
    }
}

package com.typefacilitator

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.CornerPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.view.View

fun contrastOf(c: Int): Int =
    if (Color.red(c) * 0.299 + Color.green(c) * 0.587 + Color.blue(c) * 0.114 > 140) Color.BLACK else Color.WHITE

/** A hexagon button showing an icon or a short label. */
class HexButton(ctx: Context, private val icon: Int, private val label: String = "") : View(ctx) {
    var fillColor = Color.DKGRAY
    var lineColor = Color.LTGRAY
    var inkColor = Color.WHITE
    var checked = false
        set(v) { field = v; invalidate() }
    var checkedColor = Color.WHITE
    private val d = resources.displayMetrics.density
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()

    init { isClickable = true }

    fun colors(prefs: Prefs, fn: Boolean = false): HexButton {
        fillColor = if (fn) prefs.cFn else prefs.cKey; inkColor = if (fn) prefs.cFnText else prefs.cText
        lineColor = prefs.cOutline; checkedColor = prefs.cAccent
        return this
    }

    override fun onMeasure(w: Int, h: Int) {
        val dw = (40 * d).toInt(); val dh = (42 * d).toInt()
        setMeasuredDimension(resolveSize(dw, w), resolveSize(dh, h))
    }

    override fun onDraw(c: Canvas) {
        val cx = width / 2f; val cy = height / 2f
        val a = width / 2f - 2 * d; val b = height / 2f - 2 * d
        path.reset()
        path.moveTo(cx, cy - b); path.lineTo(cx + a, cy - b / 2); path.lineTo(cx + a, cy + b / 2)
        path.lineTo(cx, cy + b); path.lineTo(cx - a, cy + b / 2); path.lineTo(cx - a, cy - b / 2); path.close()
        p.pathEffect = CornerPathEffect(3 * d)
        val on = checked || isPressed
        p.style = Paint.Style.FILL; p.color = if (on) checkedColor else fillColor
        c.drawPath(path, p)
        p.style = Paint.Style.STROKE; p.strokeWidth = 1.2f * d; p.color = lineColor
        c.drawPath(path, p)
        p.pathEffect = null
        p.color = if (on) contrastOf(checkedColor) else inkColor
        if (icon != 0) Icons.draw(c, icon, cx, cy, minOf(width, height) * 0.4f, p)
        else {
            p.style = Paint.Style.FILL; p.textAlign = Paint.Align.CENTER; p.typeface = Typeface.DEFAULT_BOLD
            p.textSize = 12 * d
            val tw = p.measureText(label)
            if (tw > width * 0.72f) p.textSize *= width * 0.72f / tw
            c.drawText(label, cx, cy - (p.ascent() + p.descent()) / 2, p)
        }
    }

    override fun drawableStateChanged() { super.drawableStateChanged(); invalidate() }
}

/** Background shaped as a long hexagon with pointed ends (used for clip cards and settings rows). */
class HexDrawable(private val fillColor: Int, private val lineColor: Int, private val density: Float) : Drawable() {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    override fun draw(c: Canvas) {
        val b = bounds
        val x0 = b.left + density; val x1 = b.right - density
        val y0 = b.top + density; val y1 = b.bottom - density
        val e = minOf((y1 - y0) * 0.3f, 14 * density)
        val ym = (y0 + y1) / 2
        path.reset()
        path.moveTo(x0, ym); path.lineTo(x0 + e, y0); path.lineTo(x1 - e, y0); path.lineTo(x1, ym)
        path.lineTo(x1 - e, y1); path.lineTo(x0 + e, y1); path.close()
        p.pathEffect = CornerPathEffect(3 * density)
        p.style = Paint.Style.FILL; p.color = fillColor
        c.drawPath(path, p)
        p.style = Paint.Style.STROKE; p.strokeWidth = 1.2f * density; p.color = lineColor
        c.drawPath(path, p)
    }
    override fun setAlpha(alpha: Int) {}
    override fun setColorFilter(cf: ColorFilter?) {}
    @Deprecated("Deprecated in Java") override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}

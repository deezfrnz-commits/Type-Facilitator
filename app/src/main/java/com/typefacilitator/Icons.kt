package com.typefacilitator

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF

/** Small line icons drawn in code, so the APK carries no image files and they follow the theme colours. */
object Icons {
    const val SHIFT = 1; const val SHIFT_ON = 2; const val SHIFT_LOCK = 3; const val DEL = 4; const val ENTER = 5
    const val SEARCH = 6; const val SEND = 7; const val NEXT = 8; const val DONE = 9; const val EMOJI = 10
    const val SPACE = 11; const val CLIP = 12; const val EDIT = 13; const val ONEHAND = 14; const val FLOAT = 15
    const val SETTINGS = 16; const val HIDE = 17; const val MOVE = 18; const val RESIZE = 19; const val LEFT = 20
    const val RIGHT = 21; const val UP = 22; const val DOWN = 23; const val PIN = 24; const val TRASH = 25
    const val SWITCH = 26; const val EXPAND = 27; const val MENU = 28; const val BACK = 29; const val SYMBOLS = 30
    const val CLOCK = 31; const val DOCK = 32

    private val p = Path()
    private val r = RectF()

    /** Draws icon [id] centred on ([cx],[cy]) inside a square of side [s], in [paint]'s colour. */
    fun draw(c: Canvas, id: Int, cx: Float, cy: Float, s: Float, paint: Paint) {
        val h = s / 2
        val oldStyle = paint.style
        val oldW = paint.strokeWidth
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = maxOf(s / 11f, 1.5f)
        paint.strokeCap = Paint.Cap.ROUND
        paint.strokeJoin = Paint.Join.ROUND
        p.reset()
        fun m(x: Float, y: Float) = p.moveTo(cx + x * h, cy + y * h)
        fun l(x: Float, y: Float) = p.lineTo(cx + x * h, cy + y * h)
        fun arrow(dx: Float, dy: Float) { // arrow from centre-opposite to (dx,dy) direction
            m(-dx * 0.7f, -dy * 0.7f); l(dx * 0.7f, dy * 0.7f)
            m(dx * 0.7f - (dx + dy) * 0.4f, dy * 0.7f - (dy - dx) * 0.4f); l(dx * 0.7f, dy * 0.7f)
            l(dx * 0.7f - (dx - dy) * 0.4f, dy * 0.7f - (dy + dx) * 0.4f)
        }
        when (id) {
            SHIFT, SHIFT_ON, SHIFT_LOCK -> {
                m(0f, -0.8f); l(0.8f, 0.05f); l(0.35f, 0.05f); l(0.35f, 0.6f); l(-0.35f, 0.6f); l(-0.35f, 0.05f); l(-0.8f, 0.05f); p.close()
                if (id != SHIFT) paint.style = Paint.Style.FILL_AND_STROKE
                if (id == SHIFT_LOCK) { c.drawPath(p, paint); p.reset(); paint.style = Paint.Style.STROKE; m(-0.35f, 0.9f); l(0.35f, 0.9f) }
            }
            DEL -> {
                m(-0.9f, 0f); l(-0.35f, -0.55f); l(0.9f, -0.55f); l(0.9f, 0.55f); l(-0.35f, 0.55f); p.close()
                m(0.05f, -0.22f); l(0.5f, 0.22f); m(0.5f, -0.22f); l(0.05f, 0.22f)
            }
            ENTER -> { m(0.7f, -0.6f); l(0.7f, 0.2f); l(-0.7f, 0.2f); m(-0.3f, -0.2f); l(-0.7f, 0.2f); l(-0.3f, 0.6f) }
            SEARCH -> { p.addCircle(cx - 0.15f * h, cy - 0.15f * h, 0.5f * h, Path.Direction.CW); m(0.22f, 0.22f); l(0.75f, 0.75f) }
            SEND -> { m(-0.8f, -0.7f); l(0.85f, 0f); l(-0.8f, 0.7f); l(-0.5f, 0f); p.close(); m(-0.5f, 0f); l(0.2f, 0f) }
            NEXT, RIGHT -> arrow(1f, 0f)
            LEFT, BACK -> arrow(-1f, 0f)
            UP -> arrow(0f, -1f)
            DOWN -> arrow(0f, 1f)
            DONE -> { m(-0.7f, 0.05f); l(-0.2f, 0.55f); l(0.75f, -0.5f) }
            EMOJI -> {
                p.addCircle(cx, cy, 0.8f * h, Path.Direction.CW)
                r.set(cx - 0.45f * h, cy - 0.35f * h, cx + 0.45f * h, cy + 0.5f * h)
                p.addArc(r, 20f, 140f)
                m(-0.3f, -0.3f); l(-0.3f, -0.2f); m(0.3f, -0.3f); l(0.3f, -0.2f)
            }
            SPACE -> { m(-0.9f, -0.15f); l(-0.9f, 0.25f); l(0.9f, 0.25f); l(0.9f, -0.15f) }
            CLIP -> {
                r.set(cx - 0.6f * h, cy - 0.65f * h, cx + 0.6f * h, cy + 0.85f * h); p.addRoundRect(r, 0.12f * h, 0.12f * h, Path.Direction.CW)
                r.set(cx - 0.3f * h, cy - 0.9f * h, cx + 0.3f * h, cy - 0.45f * h); p.addRect(r, Path.Direction.CW)
                m(-0.3f, 0f); l(0.3f, 0f); m(-0.3f, 0.4f); l(0.3f, 0.4f)
            }
            EDIT -> {
                m(0f, -0.75f); l(0f, 0.75f); m(-0.25f, -0.75f); l(0.25f, -0.75f); m(-0.25f, 0.75f); l(0.25f, 0.75f)
                m(-0.5f, -0.25f); l(-0.85f, 0f); l(-0.5f, 0.25f); m(0.5f, -0.25f); l(0.85f, 0f); l(0.5f, 0.25f)
            }
            ONEHAND -> {
                r.set(cx - 0.1f * h, cy - 0.6f * h, cx + 0.9f * h, cy + 0.6f * h); p.addRoundRect(r, 0.1f * h, 0.1f * h, Path.Direction.CW)
                m(-0.45f, -0.3f); l(-0.8f, 0f); l(-0.45f, 0.3f)
            }
            FLOAT -> {
                r.set(cx - 0.85f * h, cy - 0.2f * h, cx + 0.35f * h, cy + 0.75f * h); p.addRoundRect(r, 0.1f * h, 0.1f * h, Path.Direction.CW)
                m(-0.3f, -0.45f); l(-0.3f, -0.75f); l(0.85f, -0.75f); l(0.85f, 0.2f); l(0.6f, 0.2f)
            }
            DOCK -> {
                r.set(cx - 0.85f * h, cy - 0.7f * h, cx + 0.85f * h, cy + 0.7f * h); p.addRoundRect(r, 0.1f * h, 0.1f * h, Path.Direction.CW)
                m(-0.85f, 0.2f); l(0.85f, 0.2f)
            }
            SETTINGS -> {
                p.addCircle(cx, cy, 0.3f * h, Path.Direction.CW)
                for (i in 0 until 6) {
                    val a = Math.PI / 3 * i
                    val dx = Math.cos(a).toFloat(); val dy = Math.sin(a).toFloat()
                    m(dx * 0.55f, dy * 0.55f); l(dx * 0.85f, dy * 0.85f)
                }
                p.addCircle(cx, cy, 0.6f * h, Path.Direction.CW)
            }
            HIDE -> { m(-0.6f, -0.3f); l(0f, 0.3f); l(0.6f, -0.3f) }
            MENU -> { m(-0.6f, -0.45f); l(0.6f, -0.45f); m(-0.6f, 0f); l(0.6f, 0f); m(-0.6f, 0.45f); l(0.6f, 0.45f) }
            MOVE -> {
                m(-0.85f, 0f); l(0.85f, 0f); m(0f, -0.85f); l(0f, 0.85f)
                m(-0.6f, -0.25f); l(-0.85f, 0f); l(-0.6f, 0.25f); m(0.6f, -0.25f); l(0.85f, 0f); l(0.6f, 0.25f)
                m(-0.25f, -0.6f); l(0f, -0.85f); l(0.25f, -0.6f); m(-0.25f, 0.6f); l(0f, 0.85f); l(0.25f, 0.6f)
            }
            RESIZE, EXPAND -> {
                m(-0.7f, 0.7f); l(0.7f, -0.7f); m(0.2f, -0.7f); l(0.7f, -0.7f); l(0.7f, -0.2f); m(-0.2f, 0.7f); l(-0.7f, 0.7f); l(-0.7f, 0.2f)
            }
            PIN -> { m(-0.35f, -0.8f); l(0.35f, -0.8f); l(0.25f, -0.1f); l(0.55f, 0.25f); l(-0.55f, 0.25f); l(-0.25f, -0.1f); p.close(); m(0f, 0.25f); l(0f, 0.85f) }
            TRASH -> {
                m(-0.7f, -0.5f); l(0.7f, -0.5f); m(-0.25f, -0.5f); l(-0.25f, -0.8f); l(0.25f, -0.8f); l(0.25f, -0.5f)
                m(-0.5f, -0.5f); l(-0.4f, 0.8f); l(0.4f, 0.8f); l(0.5f, -0.5f)
            }
            SWITCH -> {
                m(-0.8f, -0.3f); l(0.8f, -0.3f); m(0.5f, -0.6f); l(0.8f, -0.3f); l(0.5f, 0f)
                m(0.8f, 0.35f); l(-0.8f, 0.35f); m(-0.5f, 0.05f); l(-0.8f, 0.35f); l(-0.5f, 0.65f)
            }
            SYMBOLS -> { // omega
                r.set(cx - 0.6f * h, cy - 0.75f * h, cx + 0.6f * h, cy + 0.45f * h); p.addArc(r, 120f, 300f)
                m(-0.75f, 0.65f); l(-0.3f, 0.65f); l(-0.3f, 0.37f); m(0.75f, 0.65f); l(0.3f, 0.65f); l(0.3f, 0.37f)
            }
            CLOCK -> { p.addCircle(cx, cy, 0.8f * h, Path.Direction.CW); m(0f, -0.45f); l(0f, 0f); l(0.35f, 0.2f) }
        }
        c.drawPath(p, paint)
        paint.style = oldStyle
        paint.strokeWidth = oldW
    }
}

package com.typefacilitator

import android.content.Context
import android.text.TextUtils
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/** The clipboard history shown in place of the keys: tap a clip to type it, pin it or delete it. */
class ClipPanel(ctx: Context, private val prefs: Prefs, private val store: ClipStore, private val listener: Listener) : LinearLayout(ctx) {
    interface Listener {
        fun onClipText(text: String)
        fun onPanelKey(code: Int)
        fun onPress(code: Int)
    }

    var panelHeight = 0
    private val d = resources.displayMetrics.density
    private val list = LinearLayout(ctx).apply { orientation = VERTICAL }
    private val title = TextView(ctx)
    private var confirmClear = false
    private val clearBtn = HexButton(ctx, Icons.TRASH)

    init {
        orientation = VERTICAL
        val head = LinearLayout(ctx).apply { orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val abc = HexButton(ctx, 0, "ABC").colors(prefs, true)
        abc.setOnClickListener { listener.onPress(0); listener.onPanelKey(K.ABC) }
        head.addView(abc, LayoutParams((44 * d).toInt(), (42 * d).toInt()))
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        title.setPadding((10 * d).toInt(), 0, 0, 0)
        head.addView(title, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        clearBtn.setOnClickListener {
            listener.onPress(0)
            if (confirmClear) { store.clear(true); confirmClear = false } else confirmClear = true
            refresh()
        }
        head.addView(clearBtn, LayoutParams((44 * d).toInt(), (42 * d).toInt()))
        val del = HexButton(ctx, Icons.DEL).colors(prefs, true)
        del.setOnClickListener { listener.onPress(0); listener.onPanelKey(K.DEL) }
        head.addView(del, LayoutParams((44 * d).toInt(), (42 * d).toInt()))
        addView(head, LayoutParams(LayoutParams.MATCH_PARENT, (46 * d).toInt()))
        val scroll = ScrollView(ctx)
        scroll.addView(list)
        addView(scroll, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
    }

    override fun onMeasure(w: Int, h: Int) {
        super.onMeasure(w, if (panelHeight > 0) MeasureSpec.makeMeasureSpec(panelHeight, MeasureSpec.EXACTLY) else h)
    }

    fun refresh() {
        setBackgroundColor(prefs.cBg)
        setPadding((4 * d).toInt(), (2 * d).toInt(), (4 * d).toInt(), (2 * d).toInt())
        title.setTextColor(prefs.cText)
        title.text = if (confirmClear) "Tap the bin again to delete unpinned clips" else "Clipboard  ${store.items.size}/${ClipStore.MAX}"
        clearBtn.colors(prefs, true); clearBtn.checked = confirmClear
        list.removeAllViews()
        val clips = store.ordered()
        if (clips.isEmpty()) {
            val t = TextView(context)
            t.text = if (prefs.clip) "Text you copy is kept here.\nTap a clip to type it where the cursor is." else "Clipboard history is turned off in the settings."
            t.setTextColor(prefs.cText); t.alpha = 0.7f; t.gravity = Gravity.CENTER
            t.setPadding(0, (24 * d).toInt(), 0, 0)
            list.addView(t)
        }
        for ((i, c) in clips.withIndex()) {
            val row = LinearLayout(context).apply { orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            val t = TextView(context)
            t.text = if (c.text.length > 300) c.text.substring(0, 300) else c.text
            t.maxLines = 2; t.ellipsize = TextUtils.TruncateAt.END
            t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            t.setTextColor(prefs.cText)
            t.background = HexDrawable(prefs.cKey, if (c.pinned) prefs.cAccent else prefs.cOutline, d)
            t.setPadding((18 * d).toInt(), (7 * d).toInt(), (18 * d).toInt(), (7 * d).toInt())
            t.minHeight = (40 * d).toInt(); t.gravity = Gravity.CENTER_VERTICAL
            t.setOnClickListener { listener.onPress(0); listener.onClipText(c.text) }
            row.addView(t, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
            val pin = HexButton(context, Icons.PIN).colors(prefs)
            pin.checked = c.pinned
            pin.setOnClickListener { listener.onPress(0); store.togglePin(c); refresh() }
            row.addView(pin, LayoutParams((38 * d).toInt(), (40 * d).toInt()))
            val del = HexButton(context, Icons.TRASH).colors(prefs)
            del.setOnClickListener { listener.onPress(0); store.remove(c); refresh() }
            row.addView(del, LayoutParams((38 * d).toInt(), (40 * d).toInt()))
            val lp = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
            lp.topMargin = (3 * d).toInt()
            list.addView(row, lp)
            if (i == 0) t.tag = "clip0"
        }
        if ((context.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0) post {
            val loc = IntArray(2)
            val sb = StringBuilder("clips=${clips.size}")
            for (j in 0 until minOf(list.childCount, 3)) {
                val v = list.getChildAt(j)
                v.getLocationOnScreen(loc)
                sb.append(" CLIP$j@${loc[0] + v.width / 3},${loc[1] + v.height / 2}")
                sb.append(" PIN$j@${loc[0] + v.width - (57 * d).toInt()},${loc[1] + v.height / 2}")
                sb.append(" TRASH$j@${loc[0] + v.width - (19 * d).toInt()},${loc[1] + v.height / 2}")
            }
            getLocationOnScreen(loc)
            sb.append(" ABC@${loc[0] + (26 * d).toInt()},${loc[1] + (25 * d).toInt()}")
            Log.i("TFKEYS", "layout=clipboard $sb")
        }
    }
}

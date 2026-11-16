package com.typefacilitator

/** One key. [slot] and [span] are in honeycomb columns; every key is drawn as a hexagon. */
class Key(
    val label: String,
    val code: Int,
    val span: Float = 1f,
    val alts: List<String> = emptyList(),
    val hint: String = "",
    val fn: Boolean = false,
    val icon: Int = 0,
) {
    // geometry, filled in by the keyboard view
    var x0 = 0f; var x1 = 0f; var y0 = 0f; var y1 = 0f; var cx = 0f; var cy = 0f
    val path = android.graphics.Path()
    val isLetter: Boolean get() = code in 'a'.code..'z'.code
}

class Layout(val name: String, val cols: Float, val rows: List<List<Key>>)

object K {
    // special key codes (characters use their code point)
    const val SHIFT = -1; const val DEL = -2; const val SYM = -3; const val ABC = -4; const val SYM2 = -5
    const val ENTER = -6; const val EMOJI = -7; const val TEXT = -8 // TEXT: commit the label as is
    const val LEFT = -10; const val RIGHT = -11; const val UP = -12; const val DOWN = -13; const val HOME = -14
    const val END = -15; const val SELALL = -16; const val COPY = -17; const val CUT = -18; const val PASTE = -19
    const val UNDO = -20; const val REDO = -21; const val SELECT = -22
    const val TB_TOGGLE = -30; const val TB_CLIP = -31; const val TB_EMOJI = -32; const val TB_EDIT = -33
    const val TB_ONEHAND = -34; const val TB_FLOAT = -35; const val TB_SETTINGS = -36; const val TB_HIDE = -37
    const val FL_MOVE = -40; const val FL_RESIZE = -41; const val OH_SWITCH = -42; const val OH_EXIT = -43
    const val SYMBOLS = -44; const val CAPS = -45
    const val SPACE = 32
}

object Layouts {
    const val MODE_TEXT = 0; const val MODE_URL = 1; const val MODE_EMAIL = 2

    private val ALTS = mapOf(
        'q' to "1", 'w' to "2", 'e' to "3 è é ê ë ē ę", 'r' to "4", 't' to "5", 'y' to "6 ý ÿ", 'u' to "7 ù ú û ü ū",
        'i' to "8 ì í î ï ī", 'o' to "9 ò ó ô ö õ ø œ", 'p' to "0", 'a' to "à á â ä ã å æ ā", 's' to "ß ś š",
        'd' to "ð", 'c' to "ç ć č", 'n' to "ñ ń", 'l' to "ł", 'z' to "ž ź ż", 'g' to "ğ",
        '1' to "¹ ½ ⅓ ¼ ⅛", '2' to "² ⅔", '3' to "³ ¾ ⅜", '4' to "⁴", '5' to "⁵ ⅝", '6' to "⁶", '7' to "⁷ ⅞", '8' to "⁸",
        '9' to "⁹", '0' to "⁰ ° ∅", '$' to "¢ £ € ¥ ₹ ₩ ₽ ₿", '-' to "– — _ ~", '+' to "±", '(' to "[ { <", ')' to "] } >",
        '/' to "\\ ÷", '*' to "× ★ † ‡ •", '"' to "“ ” „ « »", '\'' to "’ ‘ ` \" ´", '!' to "¡", '?' to "¿ ‽", '%' to "‰ ℅",
        '&' to "§ ¶", '#' to "№", '=' to "≠ ≈ ∞ ≤ ≥", '@' to "© ® ™", ':' to ";", ';' to ":", '<' to "≤ « ‹", '>' to "≥ » ›",
        '^' to "↑ ↓ ← →", '°' to "℃ ℉", '•' to "· ◦ ‣", '√' to "∛ ∜", 'π' to "Π Ω μ", '∆' to "∇ ∑ ∏",
        ',' to "; : ! ? ' \" - _", '.' to ", ? ! : ; ' \" - @ / …",
    )

    private fun ch(c: Char, numbers: Boolean = false): Key {
        var a = ALTS[c]?.split(' ') ?: emptyList()
        var hint = ""
        if (c in 'a'..'z' && a.isNotEmpty() && a[0][0].isDigit()) {
            if (numbers) a = a.drop(1) else hint = a[0]
        }
        return Key(c.toString(), c.code, alts = a, hint = hint)
    }

    private fun row(s: String, numbers: Boolean = false) = s.map { ch(it, numbers) }
    private fun fn(label: String, code: Int, span: Float = 1f, icon: Int = 0) = Key(label, code, span, fn = true, icon = icon)

    private fun bottom(left: Key, mode: Int): List<Key> {
        val second = when (mode) {
            MODE_URL -> Key("/", '/'.code, alts = listOf(":", "//", "?", "=", "&", "#", "-", "_"))
            MODE_EMAIL -> Key("@", '@'.code, alts = listOf("_", "-", "+", ","))
            else -> ch(',')
        }
        val dot = if (mode == MODE_TEXT) ch('.') else Key(".", '.'.code, alts = listOf(".com", ".org", ".net", ".co", ".io", ".edu", ".gov"))
        return listOf(left, second, fn("", K.EMOJI, icon = Icons.EMOJI), Key("", K.SPACE, 5f, icon = Icons.SPACE), dot, fn("", K.ENTER, icon = Icons.ENTER))
    }

    fun letters(numberRow: Boolean, mode: Int): Layout {
        val rows = ArrayList<List<Key>>()
        if (numberRow) rows.add(row("1234567890"))
        rows.add(row("qwertyuiop", numberRow))
        rows.add(row("asdfghjkl") + ch('\''))
        rows.add(listOf(fn("", K.SHIFT, icon = Icons.SHIFT)) + row("zxcvbnm") + fn("", K.DEL, 2f, Icons.DEL))
        rows.add(bottom(fn("?123", K.SYM), mode))
        return Layout("letters", 10.5f, rows)
    }

    fun symbols(mode: Int) = Layout(
        "symbols", 10.5f, listOf(
            row("1234567890"),
            row("@#$%&-+()/"),
            listOf(fn("=\\<", K.SYM2)) + row("*\"':;!?") + fn("", K.DEL, 2f, Icons.DEL),
            bottom(fn("ABC", K.ABC), mode),
        )
    )

    fun symbols2(mode: Int) = Layout(
        "symbols2", 10.5f, listOf(
            row("~`|•√π÷×¶∆"),
            row("£¢€¥^°={}\\"),
            listOf(fn("?123", K.SYM)) + row("<>[]©®™") + fn("", K.DEL, 2f, Icons.DEL),
            bottom(fn("ABC", K.ABC), mode),
        )
    )

    /** For number, phone and date fields. */
    fun numpad(phone: Boolean) = Layout(
        "numpad", 4.5f, listOf(
            row("123") + (if (phone) Key("+", '+'.code, alts = listOf("(", ")", "-", "/")) else Key("-", '-'.code, alts = listOf("+", "/", ":", "(", ")"))),
            row("456") + (if (phone) Key("-", '-'.code, alts = listOf("(", ")", "/", " ")) else Key(".", '.'.code, alts = listOf(",", ":", "/"))),
            row("789") + fn("", K.DEL, 1f, Icons.DEL),
            listOf(
                if (phone) Key("*", '*'.code, alts = listOf(",", ";", "N")) else Key(",", ','.code, alts = listOf(" ", "%", "*", "=")),
                ch('0'),
                if (phone) Key("#", '#'.code) else fn("ABC", K.ABC),
                fn("", K.ENTER, icon = Icons.ENTER),
            ),
        )
    )

    /** Cursor movement and text editing. The arrows sit as a hexagon cluster. */
    val edit = Layout(
        "edit", 5.5f, listOf(
            listOf(fn("All", K.SELALL), fn("Cut", K.CUT), fn("", K.UP, icon = Icons.UP), fn("Copy", K.COPY), fn("Paste", K.PASTE)),
            listOf(fn("Home", K.HOME), fn("", K.LEFT, icon = Icons.LEFT), fn("", K.RIGHT, icon = Icons.RIGHT), fn("End", K.END), fn("", K.DEL, 1f, Icons.DEL)),
            listOf(fn("Undo", K.UNDO), fn("Select", K.SELECT), fn("", K.DOWN, icon = Icons.DOWN), fn("Redo", K.REDO), fn("", K.ENTER, icon = Icons.ENTER)),
            listOf(fn("ABC", K.ABC), Key("", K.SPACE, 3f, icon = Icons.SPACE), fn("", K.TB_CLIP, icon = Icons.CLIP)),
        )
    )
}

package hk.mixboard.ime

import android.graphics.RectF

/**
 * One key. Character keys have [text]; function keys have an [action].
 * Widths are in key units; every row adds up to 10 units or is centred.
 */
class Key(
    val label: String,
    val text: String? = null,
    val action: Int = 0,
    val width: Float = 1f,
    val longText: String? = null,
    val hint: String? = null,
    val radical: String? = null, // 倉頡字根 shown under the letter
) {
    val rect = RectF() // what gets drawn
    val hit = RectF()  // what responds to touch (fills the gaps between keys)

    val isFunction: Boolean
        get() = action != 0 && action != Layouts.SPACE && action != Layouts.COMMA && action != Layouts.PERIOD
}

object Layouts {
    // Modes
    const val LETTERS = 0
    const val SYMBOLS = 1
    const val MORE = 2

    // Actions
    const val SHIFT = 1
    const val DELETE = 2
    const val TO_SYMBOLS = 3
    const val TO_LETTERS = 4
    const val TO_MORE = 5
    const val SWITCH = 6
    const val ENTER = 7
    const val SPACE = 8
    const val COMMA = 9
    const val PERIOD = 10

    val LONG_PRESS_ACTIONS = setOf(COMMA, PERIOD, SWITCH)

    /** 倉頡字根 printed on each letter key, as on HK keyboards. 速成 uses the same radicals. */
    val RADICALS: Map<Char, String> = mapOf(
        'a' to "日", 'b' to "月", 'c' to "金", 'd' to "木", 'e' to "水", 'f' to "火", 'g' to "土",
        'h' to "竹", 'i' to "戈", 'j' to "十", 'k' to "大", 'l' to "中", 'm' to "一", 'n' to "弓",
        'o' to "人", 'p' to "心", 'q' to "手", 'r' to "口", 's' to "尸", 't' to "廿", 'u' to "山",
        'v' to "女", 'w' to "田", 'x' to "難", 'y' to "卜", 'z' to "重",
    )

    fun forMode(mode: Int): List<List<Key>> = when (mode) {
        SYMBOLS -> symbols
        MORE -> more
        else -> letters
    }

    private fun ch(label: String, text: String = label, long: String? = null) =
        Key(label, text = text, longText = long, hint = long)

    private fun letter(c: Char, long: String? = null) =
        Key(c.toString(), text = c.toString(), longText = long, hint = long, radical = RADICALS[c])

    private fun fn(label: String, action: Int, width: Float = 1f) = Key(label, action = action, width = width)

    private fun bottom(modeLabel: String, modeAction: Int) = listOf(
        fn(modeLabel, modeAction, 1.5f),
        fn("🌐", SWITCH),
        Key("，", action = COMMA, hint = ","),
        Key("速成 English", action = SPACE, width = 4f),
        Key("。", action = PERIOD, hint = "."),
        fn("⏎", ENTER, 1.5f),
    )

    private val letters: List<List<Key>> = listOf(
        "qwertyuiop".mapIndexed { i, c -> letter(c, long = ((i + 1) % 10).toString()) },
        "asdfghjkl".map { letter(it) },
        listOf(fn("⇧", SHIFT, 1.5f)) + "zxcvbnm".map { letter(it) } + fn("⌫", DELETE, 1.5f),
        bottom("?123", TO_SYMBOLS),
    )

    private val symbols: List<List<Key>> = listOf(
        "1234567890".map { ch(it.toString()) },
        listOf("@", "#", "$", "&", "-", "_", "+", "(", ")", "/").map { ch(it) },
        listOf(fn("中標", TO_MORE, 1.5f)) +
            listOf("*", "\"", "'", ":", ";", "!", "?").map { ch(it) } + fn("⌫", DELETE, 1.5f),
        bottom("ABC", TO_LETTERS),
    )

    private val more: List<List<Key>> = listOf(
        listOf(
            ch("「"), ch("」"), ch("『"), ch("』"), ch("、"),
            ch("："), ch("；"), ch("？"), ch("！"), ch("…", "……"),
        ),
        listOf(
            ch("～"), ch("—", "——"), ch("《"), ch("》"), ch("【"),
            ch("】"), ch("%"), ch("="), ch("\\"), ch("|"),
        ),
        listOf(fn("?123", TO_SYMBOLS, 1.5f)) +
            listOf("<", ">", "{", "}", "[", "]", "^").map { ch(it) } + fn("⌫", DELETE, 1.5f),
        bottom("ABC", TO_LETTERS),
    )
}

package com.itzsuli.smartkeyboard.ime

import android.graphics.RectF

object KeyCode {
    const val TEXT = 0
    const val SHIFT = -1
    const val DELETE = -2
    const val SYMBOLS = -3
    const val ENTER = -4
    const val LANGUAGE = -5
    const val EMOJI = -6
    const val ALPHABET = -7
    const val SYMBOLS_MORE = -8
    const val SPACE = 32
}

class Key(
    val label: String,
    val code: Int = KeyCode.TEXT,
    val text: String = label,
    val width: Float = 1f,
    val popup: List<String> = emptyList(),
    val hint: String? = null,
    val type: Type = Type.CHAR,
) {
    enum class Type { CHAR, FUNCTION, ACTION, SPACE }

    /** Position on screen, assigned by [KeyboardLayout.measure]. */
    val bounds = RectF()

    /** True for keys that type one letter: these take part in swipe typing and prediction. */
    val isLetter: Boolean get() = code == KeyCode.TEXT && text.length == 1 && Character.isLetter(text[0])
}

class Row(val keys: List<Key>, val heightWeight: Float = 1f)

class KeyboardLayout(
    val rows: List<Row>,
    val mode: Mode,
    val lang: String,
    val rtl: Boolean = false,
) {
    enum class Mode { ALPHABET, SYMBOLS, SYMBOLS_MORE, NUMBER, PHONE }

    val keys: List<Key> get() = rows.flatMap { it.keys }

    /**
     * Lays the keys out inside [width] x [height] pixels. Rows are centred; every row uses the
     * same unit width (that of the widest row), so letters line up like on a real keyboard.
     */
    fun measure(width: Float, height: Float, paddingX: Float, paddingY: Float) {
        val units = rows.maxOf { r -> r.keys.sumOf { it.width.toDouble() } }.toFloat()
        val unit = (width - paddingX * 2) / units
        val totalWeight = rows.sumOf { it.heightWeight.toDouble() }.toFloat()
        var y = paddingY
        val usableH = height - paddingY * 2
        for (row in rows) {
            val h = usableH * row.heightWeight / totalWeight
            val rowUnits = row.keys.sumOf { it.width.toDouble() }.toFloat()
            var x = paddingX + (units - rowUnits) * unit / 2
            for (k in row.keys) {
                k.bounds.set(x, y, x + k.width * unit, y + h)
                x += k.width * unit
            }
            y += h
        }
    }
}

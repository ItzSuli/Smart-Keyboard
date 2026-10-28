package com.itzsuli.smartkeyboard.ime

import com.itzsuli.smartkeyboard.data.Languages

/** Builds every keyboard layout: letters for each language, symbols and number pads. */
object Layouts {

    data class Options(
        val numberRow: Boolean = false,
        val emojiKey: Boolean = true,
        val languageKey: Boolean = false,
        val arabicDigits: Boolean = false,
        /** Label for the enter key ("⏎", "Go", "Send", ...). */
        val enterLabel: String = "⏎",
        val showHints: Boolean = true,
        /** Languages mixed into this keyboard: their letters come first on long-press (ä ö ü ß). */
        val mixedLanguages: List<String> = emptyList(),
        /** Space bar label. */
        val spaceLabel: String? = null,
    )

    private val LETTER_ROWS = mapOf(
        "en" to listOf("qwertyuiop", "asdfghjkl", "zxcvbnm"),
        "de" to listOf("qwertzuiopü", "asdfghjklöä", "yxcvbnm"),
        "fr" to listOf("azertyuiop", "qsdfghjklm", "wxcvbn'"),
        "es" to listOf("qwertyuiop", "asdfghjklñ", "zxcvbnm"),
        "it" to listOf("qwertyuiop", "asdfghjkl", "zxcvbnm"),
        "pt" to listOf("qwertyuiop", "asdfghjklç", "zxcvbnm"),
        "tr" to listOf("qwertyuıopğü", "asdfghjklşi", "zxcvbnmöç"),
        "nl" to listOf("qwertyuiop", "asdfghjkl", "zxcvbnm"),
        "ru" to listOf("йцукенгшщзх", "фывапролджэ", "ячсмитьбю"),
        "pl" to listOf("qwertyuiop", "asdfghjkl", "zxcvbnm"),
        "ar" to listOf("ضصثقفغعهخحج", "شسيبلاتنمكط", "ذءؤرىةوزظد"),
    )

    /** Long-press alternatives; the language's own letters come first. */
    private val ACCENTS = mapOf(
        'a' to "àáâäæãåā", 'c' to "çćč", 'e' to "éèêëēęė", 'i' to "íìîïīį", 'n' to "ñń", 'o' to "óòôöõøœō",
        's' to "ßśš", 'u' to "úùûüū", 'y' to "ýÿ", 'z' to "žźż", 'l' to "ł", 'g' to "ğ", 'd' to "ð",
    )
    private val PREFERRED = mapOf(
        "de" to mapOf('a' to "ä", 'o' to "ö", 'u' to "ü", 's' to "ß"),
        "fr" to mapOf('a' to "àâæ", 'e' to "éèêë", 'i' to "îï", 'o' to "ôœ", 'u' to "ùûü", 'c' to "ç", 'y' to "ÿ"),
        "es" to mapOf('a' to "á", 'e' to "é", 'i' to "í", 'o' to "ó", 'u' to "úü", 'n' to "ñ"),
        "it" to mapOf('a' to "à", 'e' to "èé", 'i' to "ìí", 'o' to "òó", 'u' to "ùú"),
        "pt" to mapOf('a' to "ãáàâ", 'e' to "éê", 'i' to "í", 'o' to "õóô", 'u' to "úü", 'c' to "ç"),
        "pl" to mapOf('a' to "ą", 'c' to "ć", 'e' to "ę", 'l' to "ł", 'n' to "ń", 'o' to "ó", 's' to "ś", 'z' to "żź"),
        "nl" to mapOf('e' to "éëè", 'i' to "ïí", 'o' to "öó", 'u' to "üú"),
        "tr" to mapOf('a' to "â", 'i' to "î", 'u' to "û"),
    )
    private val ARABIC_POPUPS = mapOf(
        'ا' to listOf("أ", "إ", "آ", "ء", "ٱ"), 'ي' to listOf("ئ", "ى"), 'و' to listOf("ؤ"), 'ه' to listOf("ة"),
        'ء' to listOf("ئ", "ؤ", "أ", "إ"), 'ل' to listOf("لا", "لأ", "لإ", "لآ"), 'ت' to listOf("ة"), 'ك' to listOf("گ"),
        'ب' to listOf("پ"), 'ج' to listOf("چ"), 'ز' to listOf("ژ"), 'ف' to listOf("ڤ"),
    )
    private val CYRILLIC_POPUPS = mapOf('е' to listOf("ё"), 'ь' to listOf("ъ"))

    private const val WESTERN_DIGITS = "1234567890"
    private const val ARABIC_INDIC_DIGITS = "١٢٣٤٥٦٧٨٩٠"

    fun letters(lang: String, o: Options): KeyboardLayout {
        val language = Languages.get(lang)
        val rows = LETTER_ROWS[lang] ?: LETTER_ROWS.getValue("en")
        val digits = if (o.arabicDigits && lang == "ar") ARABIC_INDIC_DIGITS else WESTERN_DIGITS
        val out = ArrayList<Row>()
        if (o.numberRow) out.add(Row(digits.map { Key(it.toString(), popup = numberPopup(it)) }, 0.8f))
        rows.forEachIndexed { r, letters ->
            val keys = ArrayList<Key>()
            if (r == 2 && lang != "ar") keys.add(Key("⇧", KeyCode.SHIFT, width = 1.5f, type = Key.Type.FUNCTION))
            letters.forEachIndexed { i, c ->
                val hint = if (r == 0 && !o.numberRow && i < 10) digits[i].toString() else null
                val popup = ArrayList<String>()
                if (hint != null) popup.add(hint)
                popup.addAll(popupFor(c, lang, o.mixedLanguages))
                keys.add(Key(c.toString(), popup = popup, hint = if (o.showHints) hint else null))
            }
            if (r == 2) keys.add(Key("⌫", KeyCode.DELETE, width = if (lang == "ar") 1f else 1.5f, type = Key.Type.FUNCTION))
            out.add(Row(keys))
        }
        val units = out.maxOf { r -> r.keys.sumOf { it.width.toDouble() } }.toFloat()
        out.add(bottomRow(lang, o, KeyCode.SYMBOLS, "?123", units))
        return KeyboardLayout(out, KeyboardLayout.Mode.ALPHABET, lang, language.rtl)
    }

    private fun popupFor(c: Char, lang: String, mixed: List<String> = emptyList()): List<String> {
        if (lang == "ar") return ARABIC_POPUPS[c] ?: emptyList()
        if (lang == "ru") return CYRILLIC_POPUPS[c] ?: emptyList()
        val preferred = (listOf(lang) + mixed).joinToString("") { PREFERRED[it]?.get(c) ?: "" }.toList().distinct().joinToString("")
        val all = ACCENTS[c] ?: ""
        return (preferred + all.filter { it !in preferred }).map { it.toString() }
    }

    private fun numberPopup(c: Char): List<String> = when (c) {
        '1' -> listOf("¹", "½", "⅓", "¼")
        '2' -> listOf("²", "⅔")
        '3' -> listOf("³", "¾")
        '0' -> listOf("°", "∅")
        else -> emptyList()
    }

    private fun bottomRow(lang: String, o: Options, switchCode: Int, switchLabel: String, units: Float): Row {
        val keys = ArrayList<Key>()
        keys.add(Key(switchLabel, switchCode, width = 1.5f, type = Key.Type.FUNCTION))
        if (o.emojiKey) keys.add(Key("☺", KeyCode.EMOJI, type = Key.Type.FUNCTION))
        val comma = if (lang == "ar") "،" else ","
        keys.add(Key(comma, popup = listOf(";", ":", "'", "\"", "-", "_"), type = Key.Type.FUNCTION))
        if (o.languageKey) keys.add(Key("🌐", KeyCode.LANGUAGE, type = Key.Type.FUNCTION))
        val fixed = keys.sumOf { it.width.toDouble() }.toFloat() + 1f + 1.5f
        keys.add(Key(o.spaceLabel ?: Languages.get(lang).nativeName, KeyCode.SPACE, text = " ", width = units - fixed, type = Key.Type.SPACE))
        // Hold the period: a comma first (right under the finger), then what's used most.
        // At most 8, so they fit between the key and the left edge.
        val period = Key(".", popup = if (lang == "ar") listOf("،", "؟", "!", "؛", "?", "%", "-", "@")
        else if (lang == "es") listOf(",", "?", "!", "¿", "¡", "%", "-", "@")
        else listOf(",", "?", "!", "%", "-", "/", "@", "#"), type = Key.Type.FUNCTION)
        keys.add(period)
        keys.add(Key(o.enterLabel, KeyCode.ENTER, width = 1.5f, type = Key.Type.ACTION))
        return Row(keys)
    }

    fun symbols(lang: String, o: Options, more: Boolean): KeyboardLayout {
        val language = Languages.get(lang)
        val digits = if (o.arabicDigits && lang == "ar") ARABIC_INDIC_DIGITS else WESTERN_DIGITS
        val rows = ArrayList<Row>()
        if (!more) {
            rows.add(Row(digits.map { Key(it.toString(), popup = numberPopup(it)) }))
            val cur = language.currency
            val currencies = listOf("$", "€", "£", "¥", "₺", "₽", "₹", "¢").filter { it != cur }
            rows.add(Row(listOf("@", "#", cur, "_", "&", "-", "+", "(", ")", "/").map { s ->
                Key(s, popup = when (s) {
                    cur -> currencies
                    "-" -> listOf("–", "—", "·")
                    "(" -> listOf("[", "{", "<")
                    ")" -> listOf("]", "}", ">")
                    "&" -> listOf("§")
                    else -> emptyList()
                })
            }))
            val third = ArrayList<Key>()
            third.add(Key("=\\<", KeyCode.SYMBOLS_MORE, width = 1.5f, type = Key.Type.FUNCTION))
            val q = if (lang == "ar") "؟" else "?"
            listOf("*", "\"", "'", ":", ";", "!", q).forEach { s ->
                third.add(Key(s, popup = when (s) {
                    "\"" -> listOf("“", "”", "„", "«", "»")
                    "'" -> listOf("‘", "’", "‚", "‹", "›")
                    "!" -> listOf("¡")
                    "?", "؟" -> listOf("¿", "?", "؟")
                    "*" -> listOf("★", "†", "‡")
                    else -> emptyList()
                }))
            }
            third.add(Key("⌫", KeyCode.DELETE, width = 1.5f, type = Key.Type.FUNCTION))
            rows.add(Row(third))
        } else {
            rows.add(Row(listOf("~", "`", "|", "•", "√", "π", "÷", "×", "¶", "∆").map { Key(it) }))
            rows.add(Row(listOf("£", "¥", "€", "¢", "^", "°", "=", "{", "}", "\\").map { Key(it) }))
            val third = ArrayList<Key>()
            third.add(Key("?123", KeyCode.SYMBOLS, width = 1.5f, type = Key.Type.FUNCTION))
            listOf("%", "©", "®", "™", "✓", "[", "]").forEach { third.add(Key(it)) }
            third.add(Key("⌫", KeyCode.DELETE, width = 1.5f, type = Key.Type.FUNCTION))
            rows.add(Row(third))
        }
        rows.add(bottomRow(lang, o.copy(languageKey = false), KeyCode.ALPHABET, "ABC", 10f))
        return KeyboardLayout(rows, if (more) KeyboardLayout.Mode.SYMBOLS_MORE else KeyboardLayout.Mode.SYMBOLS, lang, false)
    }

    fun numbers(lang: String, o: Options, phone: Boolean): KeyboardLayout {
        val d = if (o.arabicDigits && lang == "ar") ARABIC_INDIC_DIGITS else WESTERN_DIGITS
        fun n(i: Int) = Key(d[i].toString())
        val rows = listOf(
            Row(listOf(n(0), n(1), n(2), Key(if (phone) "+" else "-", type = Key.Type.FUNCTION))),
            Row(listOf(n(3), n(4), n(5), Key(if (phone) " " else ",", text = if (phone) " " else ",", type = Key.Type.FUNCTION))),
            Row(listOf(n(6), n(7), n(8), Key("⌫", KeyCode.DELETE, type = Key.Type.FUNCTION))),
            Row(listOf(
                if (phone) Key("*#", popup = listOf("*", "#", "(", ")", "/", "N", ",", ";"), text = "*", type = Key.Type.FUNCTION)
                else Key("ABC", KeyCode.ALPHABET, type = Key.Type.FUNCTION),
                n(9),
                Key(".", type = Key.Type.FUNCTION),
                Key(o.enterLabel, KeyCode.ENTER, type = Key.Type.ACTION),
            )),
        )
        return KeyboardLayout(rows, if (phone) KeyboardLayout.Mode.PHONE else KeyboardLayout.Mode.NUMBER, lang)
    }
}

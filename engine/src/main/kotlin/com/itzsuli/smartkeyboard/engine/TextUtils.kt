package com.itzsuli.smartkeyboard.engine

import java.text.Normalizer

/** Language-aware string helpers shared by every part of the engine. */
object TextUtils {

    fun isWordChar(c: Char): Boolean = Character.isLetter(c) || c == '\'' || c == '’' || isArabicMark(c)

    /** Hyphens only count as word characters between letters ("e-mail"). */
    fun isWordCharAt(text: CharSequence, i: Int): Boolean {
        val c = text[i]
        if (isWordChar(c)) return true
        if (c == '-' && i > 0 && i < text.length - 1) {
            return Character.isLetter(text[i - 1]) && Character.isLetter(text[i + 1])
        }
        return false
    }

    fun isArabicMark(c: Char): Boolean = c in 'ً'..'ٟ' || c == 'ٰ' || c == 'ـ'

    fun lowercase(word: String, lang: String): String =
        if (lang == "tr") word.replace('I', 'ı').replace('İ', 'i').lowercase() else word.lowercase()

    fun uppercase(word: String, lang: String): String =
        if (lang == "tr") word.replace('i', 'İ').replace('ı', 'I').uppercase() else word.uppercase()

    /** Lookup key: lowercase, NFC, unified apostrophes, no Arabic diacritics. */
    fun key(word: String, lang: String): String {
        var w = Normalizer.normalize(word, Normalizer.Form.NFC).replace('’', '\'').replace('ʼ', '\'')
        if (lang == "ar") w = w.filterNot { isArabicMark(it) }
        return lowercase(w, lang)
    }

    /**
     * Base letter used for "close enough" matches: é→e, ü→u, ł→l, and the Arabic letter
     * families that people mix up all the time (أ/إ/آ→ا, ة→ه, ى→ي, ؤ→و, ئ→ي).
     */
    fun fold(c: Char): Char {
        when (c) {
            'أ', 'إ', 'آ', 'ٱ' -> return 'ا'
            'ة' -> return 'ه'
            'ى' -> return 'ي'
            'ؤ' -> return 'و'
            'ئ' -> return 'ي'
            'ł' -> return 'l'
            'ı' -> return 'i'
            'ß' -> return 's'
            'ø' -> return 'o'
            'æ' -> return 'a'
            'œ' -> return 'o'
            'đ' -> return 'd'
            'ё' -> return 'е'
            'й' -> return 'и'
        }
        if (c.code < 0xC0) return c
        val decomposed = Normalizer.normalize(c.toString(), Normalizer.Form.NFD)
        return decomposed[0]
    }

    fun foldWord(word: String): String = buildString(word.length) { word.forEach { append(fold(it)) } }

    enum class Case { LOWER, CAPITALIZED, UPPER }

    fun caseOf(typed: String): Case {
        val letters = typed.filter { it.isLetter() }
        if (letters.isEmpty()) return Case.LOWER
        if (letters.length >= 2 && letters.all { !it.isLowerCase() } && letters.any { it.isUpperCase() }) return Case.UPPER
        return if (letters[0].isUpperCase()) Case.CAPITALIZED else Case.LOWER
    }

    /** Applies the case the user typed to a dictionary word, keeping the dictionary's own capitals. */
    fun applyCase(word: String, case: Case, lang: String): String = when (case) {
        Case.LOWER -> word
        Case.UPPER -> uppercase(word, lang)
        Case.CAPITALIZED -> capitalize(word, lang)
    }

    fun capitalize(word: String, lang: String): String {
        if (word.isEmpty()) return word
        val first = word.substring(0, 1)
        return uppercase(first, lang) + word.substring(1)
    }

    fun hasDigitsOrSymbols(word: String): Boolean = word.any { it.isDigit() || it == '@' || it == '_' || it == '/' || it == '.' || it == '#' }

    /** Splits text into words for context; sentence punctuation resets the context. */
    fun contextWords(textBefore: CharSequence, lang: String, max: Int = 2): ContextWords {
        val words = ArrayList<String>(max)
        var i = textBefore.length - 1
        var sentenceStart = false
        while (i >= 0 && words.size < max) {
            val c = textBefore[i]
            if (isWordCharAt(textBefore, i)) {
                val end = i + 1
                while (i >= 0 && isWordCharAt(textBefore, i)) i--
                words.add(key(textBefore.substring(i + 1, end), lang))
                continue
            }
            if (c == '.' || c == '!' || c == '?' || c == '\n' || c == '؟' || c == '。' || c == '¿' || c == '¡') {
                sentenceStart = true
                break
            }
            if (c.isDigit()) break
            i--
        }
        if (i < 0 && words.size < max) sentenceStart = true
        return ContextWords(words.getOrNull(1), words.getOrNull(0), sentenceStart && words.size < max)
    }
}

/** The two words before the cursor (lookup keys) and whether a new sentence starts there. */
data class ContextWords(val prev2: String?, val prev1: String?, val sentenceStart: Boolean) {
    companion object {
        val START = ContextWords(null, null, true)
    }
}

package com.itzsuli.smartkeyboard.engine

import java.lang.Character.UnicodeScript

/**
 * Checks an AI answer before it may touch the user's text. A model that misbehaves (a broken
 * on-device build, a confused small model, a bad server) can answer in the wrong alphabet,
 * sprinkle emoji or rewrite everything; none of that is a "fix" and none of it gets applied.
 */
object AiOutputCheck {

    enum class Kind {
        /** Typo and grammar fixes: same language, same words mostly, no new emoji. */
        PROOFREAD,
        /** Rewrites may change words freely, but not the alphabet. */
        REWRITE,
        /** Rewrites that are meant to add emoji. */
        REWRITE_EMOJI,
        /** Translations: the target language's alphabet is allowed too. */
        TRANSLATE,
    }

    /**
     * Null when [output] is a believable answer for [input], else a short reason.
     * [languages] are the keyboard's languages (their alphabets are always allowed).
     */
    fun problem(input: String, output: String, kind: Kind, languages: List<String>, targetLanguage: String? = null): String? {
        val out = output.trim()
        if (out.isEmpty()) return "empty answer"
        // Alphabets: whatever the text already uses, plus the languages being typed (and the target).
        val allowed = scriptsOf(input) + languages.flatMap { scriptsOfLanguage(it) } +
            (targetLanguage?.let { scriptsOfLanguage(it) } ?: emptyList())
        val foreign = scriptsOf(out) - allowed
        if (foreign.isNotEmpty()) return "answer in a different alphabet (${foreign.joinToString { it.name.lowercase() }})"
        if (kind == Kind.TRANSLATE) return null
        if (kind != Kind.REWRITE_EMOJI && emojiCount(out) > emojiCount(input)) return "emoji the text didn't have"
        if (kind != Kind.PROOFREAD) return null
        // A proofread keeps the text: similar length, mostly the same words.
        val inLen = input.trim().length
        if (inLen >= 12 && (out.length < inLen * 0.6 || out.length > inLen * 1.6 + 8)) return "answer much longer or shorter than the text"
        // Typo fixes change words a little ("wat" → "what", "u" → "you"); a different sentence
        // shares almost nothing with the text.
        val before = words(input)
        val after = words(out)
        if (after.size >= 4) {
            val kept = after.count { w -> before.any { v -> v == w || similarity(v, w) >= 0.5 } }
            if (kept * 2 < after.size) return "rewrote too much (${after.size - kept} of ${after.size} words are new)"
        }
        return null
    }

    private fun words(text: String) = TextDiff.tokenize(text).filter { t -> t.any { it.isLetterOrDigit() } }.map { it.lowercase() }

    /** 0..1: how much of two words is the same letters in the same order. */
    private fun similarity(a: String, b: String): Double {
        val lcs = Array(a.length + 1) { IntArray(b.length + 1) }
        for (i in a.indices.reversed()) for (j in b.indices.reversed()) {
            lcs[i][j] = if (a[i] == b[j]) lcs[i + 1][j + 1] + 1 else maxOf(lcs[i + 1][j], lcs[i][j + 1])
        }
        return 2.0 * lcs[0][0] / (a.length + b.length)
    }

    /** Alphabets of the letters in [text] (Latin, Arabic, Cyrillic, Han...). Marks and digits don't count. */
    fun scriptsOf(text: String): Set<UnicodeScript> {
        val out = HashSet<UnicodeScript>()
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            i += Character.charCount(cp)
            if (!Character.isLetter(cp)) continue
            val s = UnicodeScript.of(cp)
            if (s != UnicodeScript.COMMON && s != UnicodeScript.INHERITED && s != UnicodeScript.UNKNOWN) out.add(s)
        }
        return out
    }

    private fun scriptsOfLanguage(lang: String): List<UnicodeScript> = when (lang) {
        "ar" -> listOf(UnicodeScript.ARABIC)
        "ru" -> listOf(UnicodeScript.CYRILLIC)
        else -> listOf(UnicodeScript.LATIN)
    }

    /** Emoji-like symbols (pictographs, dingbats, flags...). */
    fun emojiCount(text: String): Int {
        var n = 0
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            i += Character.charCount(cp)
            if (cp in 0x1F000..0x1FAFF || cp in 0x2600..0x27BF || cp in 0x2B00..0x2BFF || cp == 0x2764) n++
        }
        return n
    }
}

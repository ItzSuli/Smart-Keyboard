package com.itzsuli.smartkeyboard.engine

/**
 * Arabizi, the Arabic chat alphabet: "7abibi" → "حبيبي", "3arabi" → "عربي", "2albi" → "قلبي".
 *
 * Every Latin letter, digit or letter pair is read as a sound with the Arabic letters it can
 * stand for (7 = ح, 3 = ع, 5 = kh, sh = ش ...). Short vowels may be left out, as Arabic writes
 * them. A search over the Arabic dictionary's prefix tree keeps only spellings that lead to
 * real words, and the language model (with the user's own Arabic words and the sentence so
 * far) picks the most likely one.
 */
class Arabizi(
    private val lm: LanguageModel,
    /** Everyday dialect/chat words (the dictionary's `ar.chat` list), preferred over formal Arabic. */
    private val chatWords: Set<String> = emptySet(),
    private val tuning: Tuning = Tuning(),
) {

    /** How much word frequency, spelling oddity and chat words count (log10 units), tuned on ArabiziTest. */
    data class Tuning(val lmWeight: Float = 0.8f, val costWeight: Float = 1.5f, val chatBonus: Float = 1.5f)

    class Candidate(val arabic: String, val cost: Float, val score: Float) {
        override fun toString() = "$arabic(%.2f/%.2f)".format(cost, score)
    }

    /** Best Arabic spellings for [latin], most likely first. Empty if it can't be Arabizi. */
    fun convert(latin: String, ctx: ContextWords, limit: Int = 3): List<Candidate> {
        val word = latin.lowercase().replace('’', '\'')
        if (word.isEmpty() || word.length > MAX_LENGTH || !word.all { isArabiziChar(it) } || word.none { it.isLetter() || it in DIGITS }) return emptyList()
        PHRASES[word]?.let { return listOf(Candidate(it, 0f, 0f)) }

        val trie = lm.dict.trie
        val n = word.length
        // states[i]: trie node → best cost of reading word[0 until i] as that Arabic prefix.
        // A trie node is one exact Arabic prefix, so keeping only the cheapest path is exact.
        val states = Array(n + 1) { HashMap<Int, Float>() }
        states[0][trie.root] = 0f
        for (i in 0 until n) {
            val here = states[i]
            if (here.isEmpty()) continue
            val pruned = if (here.size > BEAM) here.entries.sortedBy { it.value }.take(BEAM) else here.entries.toList()
            val opts = options(word, i)
            for ((node, cost) in pruned) {
                for (o in opts) {
                    var next = node
                    for (c in o.arabic) {
                        next = trie.child(next, c)
                        if (next < 0) break
                    }
                    if (next < 0) continue
                    val target = states[i + o.length]
                    val total = cost + o.cost
                    val old = target[next]
                    if (old == null || total < old) target[next] = total
                }
            }
        }
        val out = ArrayList<Candidate>()
        for ((node, cost) in states[n]) {
            val id = trie.word(node)
            if (id < 0) continue
            val key = lm.dict.keys[id]
            if (lm.user.isBlocked(key)) continue
            val score = lm.logP(key, ctx) * tuning.lmWeight - cost * tuning.costWeight + if (key in chatWords) tuning.chatBonus else 0f
            out.add(Candidate(lm.display(key), cost, score))
        }
        // What this person picked for this exact spelling before comes first.
        for ((target, count) in lm.user.correctionsFor(word)) {
            if (lm.user.isBlocked(target)) continue
            out.removeAll { TextUtils.key(it.arabic, "ar") == target }
            out.add(Candidate(lm.display(target), 0f, PERSONAL_SCORE + count))
        }
        out.sortByDescending { it.score }
        return if (out.size > limit) out.subList(0, limit) else out
    }

    /** Remembers that this person writes [latin] as [arabic] (they picked it). */
    fun learn(latin: String, arabic: String) = lm.user.learnCorrection(latin.lowercase().replace('’', '\''), TextUtils.key(arabic, "ar"))

    private class Option(val length: Int, val arabic: String, val cost: Float)

    /** Every way to read [w] at [i]: (Latin characters used, Arabic letters, cost). */
    private fun options(w: String, i: Int): List<Option> {
        val out = ArrayList<Option>(8)
        val start = i == 0
        fun rest(len: Int) = w.length - i - len
        fun at(s: String) = w.startsWith(s, i)
        fun add(len: Int, arabic: String, cost: Float) = out.add(Option(len, arabic, cost))
        val c = w[i]

        // Two-character sounds first.
        if (at("sh") || at("ch")) add(2, "ش", 0f)
        if (at("kh")) add(2, "خ", 0f)
        if (at("gh")) add(2, "غ", 0f)
        if (at("th")) { add(2, "ث", 0f); add(2, "ذ", 0.5f); add(2, "ت", 0.6f) }
        if (at("dh")) { add(2, "ذ", 0f); add(2, "ض", 0.4f); add(2, "ظ", 0.6f) }
        if (at("7'")) add(2, "خ", 0f)
        if (at("3'") || at("'3")) add(2, "غ", 0f)
        if (at("6'")) add(2, "ظ", 0f)
        if (at("9'")) add(2, "ض", 0f)
        if (at("d'")) add(2, "ض", 0.2f)
        if (at("aa")) { add(2, "ا", 0f); if (start) add(2, "آ", 0.4f) }
        if (at("ee") || at("ii")) add(2, "ي", 0f)
        if (at("oo") || at("ou") || at("uu")) add(2, "و", 0f)
        if (at("ai") || at("ay") || at("ei") || at("ey")) { add(2, "ي", 0.1f); add(2, "اي", 0.4f) }
        if (at("aw") || at("ow")) { add(2, "و", 0.1f); add(2, "او", 0.4f) }
        if (start && (at("al") || at("el") || at("il"))) add(2, "ال", 0.05f)
        if (rest(2) == 0 && (at("ah") || at("eh"))) { add(2, "ة", 0.1f); add(2, "ه", 0.2f); add(2, "اه", 0.6f) }
        // Tanween: "ahlan", "shukran" → أهلاً, شكراً (written with a final alif).
        if (rest(2) == 0 && at("an") && !start) add(2, "ا", 0.1f)
        // A doubled consonant is usually written once (with an unwritten shadda).
        if (i + 1 < w.length && w[i + 1] == c && c.isLetter() && c !in VOWELS) {
            single(c, start, false).forEach { (a, cost) -> add(2, a, cost) }
        }

        val end = rest(1) == 0
        when (c) {
            'a' -> when {
                start -> { add(1, "أ", 0.1f); add(1, "ا", 0.2f); add(1, "ع", 0.5f); add(1, "إ", 0.6f); add(1, "آ", 0.6f) }
                // A final "a" is nearly always written.
                end -> { add(1, "ا", 0.2f); add(1, "ة", 0.3f); add(1, "ى", 0.5f); add(1, "ه", 0.6f); add(1, "", 1.3f) }
                else -> { add(1, "", 0.15f); add(1, "ا", 0.25f); add(1, "ع", 0.9f) }
            }
            'e' -> when {
                start -> { add(1, "إ", 0.15f); add(1, "ا", 0.2f); add(1, "أ", 0.4f); add(1, "اي", 0.4f); add(1, "إي", 0.4f); add(1, "ع", 0.6f) }
                end -> { add(1, "ي", 0.3f); add(1, "ة", 0.4f); add(1, "ه", 0.5f); add(1, "", 1f) }
                else -> { add(1, "", 0.2f); add(1, "ي", 0.3f); add(1, "ا", 0.7f) }
            }
            'i' -> when {
                start -> { add(1, "إ", 0.15f); add(1, "ا", 0.25f); add(1, "ي", 0.6f) }
                end -> { add(1, "ي", 0.05f); add(1, "", 0.9f) }
                else -> { add(1, "ي", 0.2f); add(1, "", 0.3f) }
            }
            'o', 'u' -> when {
                start -> { add(1, "أ", 0.3f); add(1, "ا", 0.4f); add(1, "ع", 0.5f); add(1, "او", 0.5f) }
                end -> { add(1, "و", 0.1f); add(1, "ه", 0.7f) }
                else -> { add(1, "و", 0.25f); add(1, "", 0.3f) }
            }
            'y' -> { add(1, "ي", 0f); if (end) add(1, "ى", 0.4f) }
            'w' -> add(1, "و", 0f)
            '\'' -> { add(1, "ء", 0.5f); add(1, "", 0.3f) }
            else -> single(c, start, end).forEach { (a, cost) -> add(1, a, cost) }
        }
        return out
    }

    /** Arabic letters for a single consonant or digit. */
    private fun single(c: Char, start: Boolean, end: Boolean): List<Pair<String, Float>> = when (c) {
        'b' -> listOf("ب" to 0f)
        'p' -> listOf("ب" to 0.2f)
        't' -> if (end) listOf("ت" to 0f, "ة" to 0.4f, "ط" to 0.6f) else listOf("ت" to 0f, "ط" to 0.6f)
        'j' -> listOf("ج" to 0f)
        'g' -> listOf("ج" to 0.2f, "غ" to 0.5f, "ق" to 0.6f)
        'd' -> listOf("د" to 0f, "ض" to 0.6f)
        'r' -> listOf("ر" to 0f)
        'z' -> listOf("ز" to 0f, "ظ" to 0.6f, "ذ" to 0.6f)
        's' -> listOf("س" to 0f, "ص" to 0.6f)
        'f' -> listOf("ف" to 0f)
        'v' -> listOf("ف" to 0.2f)
        'q' -> listOf("ق" to 0f, "ك" to 0.6f)
        'k' -> listOf("ك" to 0f, "ق" to 0.5f)
        'l' -> listOf("ل" to 0f)
        'm' -> listOf("م" to 0f)
        'n' -> listOf("ن" to 0f)
        'h' -> listOf("ه" to 0f, "ح" to 0.4f)
        'c' -> listOf("ك" to 0.3f, "س" to 0.5f)
        'x' -> listOf("كس" to 0.3f)
        '2' -> if (start) listOf("أ" to 0f, "ء" to 0.2f, "إ" to 0.3f, "ا" to 0.3f, "آ" to 0.4f, "ق" to 0.3f)
            else listOf("ء" to 0f, "أ" to 0.1f, "ق" to 0.3f, "ئ" to 0.3f, "ؤ" to 0.3f)
        '3' -> listOf("ع" to 0f)
        '5' -> listOf("خ" to 0f)
        '6' -> listOf("ط" to 0f)
        '7' -> listOf("ح" to 0f)
        '8' -> listOf("غ" to 0.2f, "ق" to 0.2f)
        '9' -> listOf("ص" to 0.3f, "ق" to 0.3f)
        else -> emptyList()
    }

    companion object {
        private const val MAX_LENGTH = 24
        private const val PERSONAL_SCORE = 10f
        private const val BEAM = 600
        private const val VOWELS = "aeiou"
        /** Digits that stand for Arabic letters. */
        const val DIGITS = "2356789"

        fun isArabiziChar(c: Char): Boolean = c in 'a'..'z' || c in 'A'..'Z' || c in DIGITS || c == '\'' || c == '’'

        /** Clearly Arabizi: letters mixed with Arabizi digits ("7abibi", "3arabi", "mni7"). */
        fun hasArabiziDigits(word: String): Boolean =
            word.any { it.isLetter() } && word.any { it in DIGITS } && word.all { isArabiziChar(it) }

        /** English number-slang that looks like Arabizi but isn't ("gr8", "2day"). */
        val ENGLISH_LOOKALIKES = setOf(
            "gr8", "l8", "l8r", "l8er", "h8", "m8", "w8", "str8", "sk8", "n8", "d8", "f8", "g8", "2day", "2nite", "2night",
            "2morrow", "2moro", "2mrw", "2u", "ur2", "me2", "2b", "2go", "2nd", "3rd", "5th", "6th", "7th", "8th", "9th",
            "b2b", "p2p", "mp3", "a5", "x5", "k9", "y2k", "w2", "s3", "t2", "3d", "2d",
        )

        /** Whole phrases people type as one Arabizi word. */
        val PHRASES = mapOf(
            "inshallah" to "إن شاء الله", "inshalla" to "إن شاء الله", "insha2allah" to "إن شاء الله", "inchallah" to "إن شاء الله",
            "mashallah" to "ما شاء الله", "masha2allah" to "ما شاء الله", "mashalla" to "ما شاء الله",
            "alhamdulillah" to "الحمد لله", "alhamdulilah" to "الحمد لله", "el7amdella" to "الحمد لله", "il7amdillah" to "الحمد لله",
            "al7amdulillah" to "الحمد لله", "hamdella" to "الحمد لله", "bismillah" to "بسم الله", "subhanallah" to "سبحان الله",
            "astaghfirullah" to "أستغفر الله", "salamualaikum" to "السلام عليكم", "wallahi" to "والله", "wallah" to "والله",
            "yallah" to "يلا", "yalla" to "يلا", "khalas" to "خلاص",
        )
    }
}

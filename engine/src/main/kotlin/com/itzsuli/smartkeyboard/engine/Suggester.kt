package com.itzsuli.smartkeyboard.engine

import kotlin.math.max

enum class AutoCorrectMode { OFF, CHILL, NORMAL }

data class Suggestion(
    val text: String,
    val kind: Kind,
    val score: Float = 0f,
) {
    enum class Kind { TYPED, EXACT, CORRECTION, COMPLETION, PREDICTION, LEARNED, EMOJI, AI, ARABIZI, SHORTCUT }
}

class SuggestionResult(
    /** What the user literally typed. */
    val typed: String,
    /** Best candidates, best first. The first one is the strip's centre slot. */
    val candidates: List<Suggestion>,
    /** Applied when the user hits space or punctuation; null = keep what was typed. */
    val autoCorrect: Suggestion?,
    val typedIsValid: Boolean,
    /** Language-model score of the typed word itself ([LanguageModel.OOV] if unknown). */
    val typedScore: Float = LanguageModel.OOV,
) {
    companion object {
        val EMPTY = SuggestionResult("", emptyList(), null, false)
    }
}

/**
 * The keyboard's word brain: corrections, completions and next-word predictions built on the
 * language model plus everything learned from the user.
 *
 * Autocorrect is deliberately relaxed ("chill"): it only steps in for clear typos, never
 * touches words the user has typed on purpose before, never turns real words into other
 * real words in [AutoCorrectMode.CHILL], and remembers every correction the user undoes.
 */
class Suggester(
    val lm: LanguageModel,
    spatial: SpatialModel?,
    private val elisions: List<String> = defaultElisions(lm.lang),
) {
    private val corrector = Corrector(spatial)
    private val lang get() = lm.lang
    var fixCapitalization: Boolean = true

    fun predict(ctx: ContextWords, limit: Int = 3): List<Suggestion> =
        lm.predict(ctx, limit).map { Suggestion(it.display, Suggestion.Kind.PREDICTION, it.logP) }

    fun suggest(typedText: String, typed: List<TypedChar>, ctx: ContextWords, mode: AutoCorrectMode, limit: Int = 5): SuggestionResult {
        if (typedText.isEmpty()) return SuggestionResult.EMPTY
        // French/Italian elision: correct "l'homme" as "l'" + "homme".
        val prefix = elisionPrefix(typedText)
        val word = typedText.substring(prefix.length)
        val wordTyped = typed.drop(typed.size - word.length.coerceAtMost(typed.size))
        if (word.isEmpty()) return SuggestionResult(typedText, emptyList(), null, true)

        val typedKey = TextUtils.key(word, lang)
        val case = TextUtils.caseOf(word)
        val lowered = wordTyped.map { TypedChar(TextUtils.lowercase(it.char.toString(), lang).firstOrNull() ?: it.char, it.x, it.y) }
        val typedValid = lm.isKnown(typedKey)
        val typedScore = if (typedValid) lm.logP(typedKey, ctx) else LanguageModel.OOV

        class Cand(val key: String, val score: Float, val cost: Float, val kind: Suggestion.Kind)
        val cands = HashMap<String, Cand>()
        fun offer(c: Cand) {
            val old = cands[c.key]
            if (old == null || c.score > old.score) cands[c.key] = c
        }

        val dict = lm.dict
        for (m in corrector.search(dict.trie, dict.keys, lowered, completions = true)) {
            if (lm.user.isBlocked(m.key)) continue
            val kind = when {
                m.key == typedKey -> Suggestion.Kind.EXACT
                m.completion -> Suggestion.Kind.COMPLETION
                else -> Suggestion.Kind.CORRECTION
            }
            offer(Cand(m.key, lm.logP(m.key, ctx) - m.cost, m.cost, kind))
        }
        val budget = corrector.maxCostFor(lowered.size)
        for (w in lm.user.activeWords()) {
            if (w.key.length + 4 < lowered.size) continue
            val (cost, completion) = corrector.cost(lowered, w.key, allowCompletion = true)
            if (cost > budget) continue
            val kind = when {
                w.key == typedKey -> Suggestion.Kind.EXACT
                completion -> Suggestion.Kind.COMPLETION
                else -> Suggestion.Kind.CORRECTION
            }
            offer(Cand(w.key, lm.logP(w.key, ctx) - cost, cost, kind))
        }
        // Fixes the user taught us (accepted corrections, AI proofreading) are trusted.
        val learned = lm.user.correctionsFor(typedKey).maxByOrNull { it.value }
        if (learned != null && lm.isKnown(learned.key)) {
            offer(Cand(learned.key, max(typedScore, lm.logP(learned.key, ctx)) + 0.5f + 0.2f * learned.value, 0f, Suggestion.Kind.LEARNED))
        }

        val ranked = cands.values.sortedByDescending { it.score }
        fun render(key: String): String {
            val display = lm.display(key)
            val cased = if (case == TextUtils.Case.LOWER && !fixCapitalization) TextUtils.lowercase(display, lang)
            else TextUtils.applyCase(display, case, lang)
            return prefix + cased
        }

        // ---- autocorrect decision ----
        var auto: Cand? = null
        if (mode != AutoCorrectMode.OFF && mayAutoCorrect(word, typedKey)) {
            // Chill mode only fixes slips that are cheap to explain (about one or two small errors).
            val costLimit = if (mode == AutoCorrectMode.CHILL) budget * 0.75f else budget
            val best = ranked.firstOrNull { it.key != typedKey && it.kind != Suggestion.Kind.COMPLETION && it.cost <= costLimit }
            val bestCompletion = ranked.firstOrNull { it.kind == Suggestion.Kind.COMPLETION }
            // Looks like a word in progress ("becau"): don't force a different word on it.
            val unfinished = best != null && bestCompletion != null && best.kind != Suggestion.Kind.LEARNED &&
                bestCompletion.score > best.score + 0.5f
            val typedUnigram = lm.dict.idOf(typedKey).let { if (it >= 0) lm.dict.unigramLogP[it] else LanguageModel.OOV }
            if (best != null && !unfinished && lm.user.rejectionCount(typedKey, best.key) == 0) {
                // Spelling variants of a valid word (dont → don't, fur → für) are judged on their own.
                val variant = if (typedValid) ranked.firstOrNull {
                    it.key != typedKey && sameLetters(typedKey, it.key) && lm.user.rejectionCount(typedKey, it.key) == 0
                } else null
                auto = when {
                    best.kind == Suggestion.Kind.LEARNED -> best
                    !typedValid -> if (best.score + best.cost >= MIN_TARGET_LOGP) best else null
                    variant != null && variant.score - typedScore >= 1.3f -> variant
                    // A very rare "word" right next to a very common one (helo → hello).
                    !lm.user.isKnown(typedKey) && typedUnigram < RARE_LOGP && best.cost <= 1.5f &&
                        best.score + best.cost - typedScore >= (if (best.cost <= 0.7f) 1.2f else 2.0f) -> best
                    // Stricter keyboards also fix likely real-word slips.
                    mode == AutoCorrectMode.NORMAL && best.cost <= 1.0f && best.score - typedScore >= 2.2f -> best
                    else -> null
                }
            }
        }

        val out = ArrayList<Suggestion>(limit + 1)
        val seen = HashSet<String>()
        fun add(s: Suggestion) { if (seen.add(s.text) && out.size < limit) out.add(s) }
        val autoSuggestion = auto?.let { Suggestion(render(it.key), Suggestion.Kind.CORRECTION, it.score) }
        // Even when the typed word is valid, show it with dictionary capitalisation.
        if (autoSuggestion != null) add(autoSuggestion)
        else if (typedValid) add(Suggestion(render(typedKey), Suggestion.Kind.EXACT, typedScore))
        for (c in ranked) add(Suggestion(render(c.key), c.kind, c.score))

        val capitalisationFix = if (auto == null && typedValid && fixCapitalization && case == TextUtils.Case.LOWER &&
            mode != AutoCorrectMode.OFF && lm.user.rejectionCount(typedKey, typedKey) == 0) {
            val r = render(typedKey)
            val display = lm.display(typedKey)
            val capitalisedForm = display.length > 1 && display[0].isUpperCase() && display.substring(1).none { it.isUpperCase() }
            val common = lang == "de" || typedScore >= -6.5f || display.startsWith("I")
            if (r != typedText && (capitalisedForm || display == "I") && common) Suggestion(r, Suggestion.Kind.CORRECTION, typedScore) else null
        } else null

        return SuggestionResult(typedText, out, autoSuggestion ?: capitalisationFix, typedValid, typedScore)
    }

    private fun mayAutoCorrect(word: String, key: String): Boolean {
        if (TextUtils.hasDigitsOrSymbols(word)) return false
        if (word.length < 2 && !(lang == "en" && key == "i")) return false
        // Acronyms and shouting are left alone unless the dictionary knows them.
        if (TextUtils.caseOf(word) == TextUtils.Case.UPPER && !lm.isKnown(key)) return false
        val uw = lm.user.word(key)
        if (uw != null && uw.isConfirmed && !uw.isBlocked) return lm.user.correctionsFor(key).isNotEmpty()
        return true
    }

    /** Same word spelled differently: missing apostrophe/accents (dont, fur) or German ss/ae/oe/ue. */
    private fun sameLetters(a: String, b: String): Boolean {
        val fa = TextUtils.foldWord(a).filter { it.isLetter() }
        val fb = TextUtils.foldWord(b).filter { it.isLetter() }
        return fa == fb || transliterate(a) == transliterate(b)
    }

    private fun transliterate(w: String): String = buildString {
        for (c in w) when (c) {
            'ß' -> append("ss"); 'ä' -> append("ae"); 'ö' -> append("oe"); 'ü' -> append("ue")
            else -> if (c.isLetter()) append(c)
        }
    }

    private fun elisionPrefix(text: String): String {
        val low = TextUtils.lowercase(text, lang).replace('’', '\'')
        for (p in elisions) if (low.startsWith(p) && low.length >= p.length) return text.substring(0, p.length)
        return ""
    }

    companion object {
        /** Autocorrect never targets words rarer than this (log10 probability). */
        const val MIN_TARGET_LOGP = -6.6f
        /** Dictionary words rarer than this may be treated as typos of a common neighbour. */
        const val RARE_LOGP = -5.6f

        fun defaultElisions(lang: String): List<String> = when (lang) {
            "fr" -> listOf("jusqu'", "lorsqu'", "puisqu'", "qu'", "l'", "d'", "j'", "n'", "s'", "c'", "m'", "t'")
            "it" -> listOf("dell'", "nell'", "all'", "dall'", "sull'", "un'", "quest'", "quell'", "l'", "c'", "d'", "m'", "t'", "s'", "v'")
            else -> emptyList()
        }
    }
}

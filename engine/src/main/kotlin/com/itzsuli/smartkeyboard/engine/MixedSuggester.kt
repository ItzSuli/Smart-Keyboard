package com.itzsuli.smartkeyboard.engine

import kotlin.math.log10
import kotlin.math.max
import kotlin.math.pow

/**
 * Several languages typed on one keyboard (for example English and German on QWERTY).
 * The first model is the keyboard's own language.
 *
 * Which language is being written right now is estimated from the last few words: function
 * words like "ich", "und", "the" or "and" decide it almost immediately, words that exist in
 * both languages barely move it. The user's own habit (how much they write in each language)
 * is the starting point, so the keyboard leans towards what they use most.
 */
class LanguageMix(val models: List<LanguageModel>) {
    val size: Int get() = models.size

    /** Probability per language that the current text is written in it. Sums to 1. */
    fun weights(recent: List<String>): FloatArray {
        val n = models.size
        if (n == 1) return floatArrayOf(1f)
        val score = FloatArray(n)
        val totals = DoubleArray(n) { models[it].user.totalTokens.toDouble() + PRIOR_TOKENS }
        val sum = totals.sum()
        for (i in 0 until n) score[i] = log10(totals[i] / sum).toFloat() + if (i == 0) PRIMARY_BIAS else 0f
        var decay = 1f
        var words = 0
        for (w in recent.asReversed()) {
            // People mostly switch language between sentences: older sentences count less.
            if (w.isEmpty()) { decay *= SENTENCE_DECAY; continue }
            if (++words > MAX_WORDS) break
            val lp = FloatArray(n) { wordLogP(models[it], w) }
            val best = lp.max()
            // One word can shift the balance a lot, but never infinitely (names, typos).
            for (i in 0 until n) score[i] += decay * max(-MAX_WORD_EVIDENCE, lp[i] - best)
            decay *= DECAY
        }
        val top = score.max()
        val raw = FloatArray(n) { 10f.pow((score[it] - top) * SHARPNESS) }
        val total = raw.sum()
        // Keep every language in play a little: a mixed sentence can switch at any word.
        val clamped = FloatArray(n) { max(MIN_WEIGHT, raw[it] / total) }
        val s = clamped.sum()
        return FloatArray(n) { clamped[it] / s }
    }

    private fun wordLogP(m: LanguageModel, key: String): Float {
        val p = m.probability(key, NO_CONTEXT)
        return if (p <= 0.0) OOV_FLOOR else max(OOV_FLOOR, log10(p).toFloat())
    }

    fun dominant(weights: FloatArray): Int = weights.indices.maxBy { weights[it] }

    /**
     * The language whose user model should learn a committed word: where the word is most
     * likely, given how common it is in each language and which language is being written
     * ("and" belongs to English even right after a German sentence).
     */
    fun learnTarget(key: String, weights: FloatArray): Int {
        val knownIn = models.indices.filter { models[it].isKnown(key) }
        return when {
            knownIn.size == 1 -> knownIn[0]
            knownIn.isEmpty() -> dominant(weights)
            else -> knownIn.maxBy { wordLogP(models[it], key) + log10(weights[it]) }
        }
    }

    companion object {
        private val NO_CONTEXT = ContextWords(null, null, false)
        private const val PRIOR_TOKENS = 300.0
        private const val PRIMARY_BIAS = 0.4f
        private const val MAX_WORDS = 8
        private const val DECAY = 0.7f
        private const val SENTENCE_DECAY = 0.35f
        private const val MAX_WORD_EVIDENCE = 2f
        private const val SHARPNESS = 1f
        private const val OOV_FLOOR = -7.5f
        private const val MIN_WEIGHT = 0.03f
    }
}

/**
 * Suggestions and autocorrect across a [LanguageMix]. Each language's [Suggester] runs on the
 * same keystrokes; their results are merged with the current language weights.
 *
 * Mixing must never make the keyboard pushier, so a word that is real in *any* of the
 * languages is only replaced when the fix comes from the language being written right now
 * (no "und → and" in a German sentence, no "fur → für" in an English one).
 */
class MixedSuggester(val mix: LanguageMix, val suggesters: List<Suggester>) {

    class Result(
        val result: SuggestionResult,
        /** Index of the language whose correction [SuggestionResult.autoCorrect] is, or -1. */
        val autoLanguage: Int,
        val weights: FloatArray,
    )

    fun suggest(typedText: String, typed: List<TypedChar>, ctx: ContextWords, recent: List<String>, mode: AutoCorrectMode, limit: Int = 5): Result {
        if (suggesters.size == 1) {
            val r = suggesters[0].suggest(typedText, typed, ctx, mode, limit)
            return Result(r, if (r.autoCorrect != null) 0 else -1, floatArrayOf(1f))
        }
        val w = mix.weights(recent)
        val lw = FloatArray(w.size) { log10(w[it]) }
        val dominant = mix.dominant(w)
        val results = suggesters.map { it.suggest(typedText, typed, ctx, mode, limit + 2) }
        val valid = results.indices.filter { results[it].typedIsValid }
        val typedBest = valid.maxOfOrNull { results[it].typedScore + lw[it] } ?: LanguageModel.OOV
        // Your own words (slang, names) are yours in every language you mix.
        val personal = mix.models.any { it.user.isKnown(TextUtils.key(typedText, it.lang)) }

        var auto: Suggestion? = null
        var autoLang = -1
        var autoScore = Float.NEGATIVE_INFINITY
        for (i in results.indices) {
            val a = results[i].autoCorrect ?: continue
            val score = a.score + lw[i]
            val caseOnly = a.text.lowercase() == typedText.lowercase()
            // An undone correction stays undone, whichever language would make it again.
            val undone = mix.models.any { m -> m.user.rejectionCount(TextUtils.key(typedText, m.lang), TextUtils.key(a.text, m.lang)) > 0 }
            val ok = !undone && when {
                // "haus" → "Haus" only while writing German.
                caseOnly -> i in valid && i == dominant
                personal && i !in valid -> false
                // Not a word in any language: the best fix wins, weighted by language.
                valid.isEmpty() -> true
                // A real word somewhere: only the current language may change it, and only clearly.
                i !in valid && i != dominant -> false
                else -> score - typedBest >= if (i == dominant) SAME_LANGUAGE_MARGIN else OTHER_LANGUAGE_MARGIN
            }
            if (ok && score > autoScore) { auto = a; autoLang = i; autoScore = score }
        }

        // Merge everyone's candidates by weighted score.
        val merged = LinkedHashMap<String, Pair<Suggestion, Float>>()
        for (i in results.indices) {
            for (c in results[i].candidates) {
                if (c.kind == Suggestion.Kind.EXACT) continue
                val k = c.text.lowercase()
                val s = c.score + lw[i]
                val old = merged[k]
                if (old == null || s > old.second) merged[k] = c to s
            }
        }
        val out = ArrayList<Suggestion>(limit)
        val seen = HashSet<String>()
        fun add(s: Suggestion) { if (out.size < limit && seen.add(s.text.lowercase())) out.add(s) }
        if (auto != null) add(auto)
        else if (valid.isNotEmpty()) add(Suggestion(typedText, Suggestion.Kind.EXACT, typedBest))
        merged.values.sortedByDescending { it.second }.forEach { (c, s) -> add(c.copy(score = s)) }
        val r = SuggestionResult(typedText, out, auto, valid.isNotEmpty(), typedBest)
        return Result(r, autoLang, w)
    }

    fun predict(ctx: ContextWords, recent: List<String>, limit: Int = 3): List<Suggestion> {
        if (suggesters.size == 1) return suggesters[0].predict(ctx, limit)
        val w = mix.weights(recent)
        val merged = LinkedHashMap<String, Suggestion>()
        for (i in suggesters.indices) {
            val lw = log10(w[i])
            for (p in suggesters[i].predict(ctx, limit + 3)) {
                val s = p.copy(score = p.score + lw)
                val k = p.text.lowercase()
                val old = merged[k]
                if (old == null || s.score > old.score) merged[k] = s
            }
        }
        return merged.values.sortedByDescending { it.score }.take(limit)
    }

    /** Next-letter probabilities blended across languages (for adaptive hit boxes). */
    fun nextChars(predictors: List<KeyPredictor>, prefix: String, ctx: ContextWords, recent: List<String>): Map<Char, Float>? {
        if (predictors.size == 1) return predictors[0].nextChars(prefix, ctx)
        val w = mix.weights(recent)
        val out = HashMap<Char, Float>()
        var included = 0f
        for (i in predictors.indices) {
            val d = predictors[i].nextChars(prefix, ctx) ?: continue
            included += w[i]
            for ((c, p) in d) out[c] = (out[c] ?: 0f) + p * w[i]
        }
        if (included <= 0f) return null
        for (k in out.keys.toList()) out[k] = out[k]!! / included
        return out
    }

    /** Swipe candidates from every language, best first, as (word to type, language index). */
    fun decodeSwipe(
        decoders: List<SwipeDecoder>, xs: FloatArray, ys: FloatArray, count: Int,
        ctx: ContextWords, recent: List<String>, limit: Int = 6,
    ): List<Pair<String, Int>> {
        val w = if (decoders.size == 1) floatArrayOf(1f) else mix.weights(recent)
        val merged = LinkedHashMap<String, Triple<String, Int, Float>>()
        for (i in decoders.indices) {
            val m = mix.models[i]
            val lw = log10(w[i])
            for (c in decoders[i].decode(xs, ys, count, m, ctx, limit)) {
                val display = m.display(c.key)
                val s = c.score + lw
                val k = display.lowercase()
                val old = merged[k]
                if (old == null || s > old.third) merged[k] = Triple(display, i, s)
            }
        }
        return merged.values.sortedByDescending { it.third }.take(limit).map { it.first to it.second }
    }

    companion object {
        private const val SAME_LANGUAGE_MARGIN = 0f
        private const val OTHER_LANGUAGE_MARGIN = 1f
    }
}

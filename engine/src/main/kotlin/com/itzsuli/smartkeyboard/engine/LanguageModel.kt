package com.itzsuli.smartkeyboard.engine

import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * Context-aware word probabilities: the base language pack (stupid-backoff trigram model)
 * mixed with the user's own model. The more someone types, the more their own habits win.
 */
class LanguageModel(val dict: BinaryDictionary, val user: UserLexicon) {

    val lang: String get() = dict.lang

    class Prediction(val key: String, val display: String, val logP: Float, val fromUser: Boolean)

    /** Weight of the personal model, growing with the amount of text typed. */
    fun userWeight(): Double {
        val t = user.totalTokens.toDouble()
        return min(MAX_USER_WEIGHT, max(MIN_USER_WEIGHT, t / (t + USER_HALF_POINT)))
    }

    /** log10 P(word | context). Returns [OOV] for words neither model knows. */
    fun logP(key: String, ctx: ContextWords): Float {
        val p = probability(key, ctx)
        return if (p <= 0.0) OOV else log10(p).toFloat()
    }

    fun probability(key: String, ctx: ContextWords): Double {
        val uw = user.word(key)
        if (uw != null && uw.isBlocked) return 0.0
        var p = baseProbability(key, ctx)
        // Interpolate with the user's own statistics, from general to specific. The more
        // often the user has been in exactly this context, the more their habit counts.
        val total = user.totalTokens
        if (total > 0) {
            val lambda = userWeight()
            p = (1 - lambda) * p + lambda * ((uw?.count ?: 0).toDouble() / total)
        }
        val prev1 = ctx.prev1 ?: if (ctx.sentenceStart) START_TOKEN else null
        val prev2 = ctx.prev2 ?: if (ctx.prev1 != null && ctx.sentenceStart) START_TOKEN else null
        if (prev1 != null) {
            val heads = user.headCount(prev1)
            if (heads > 0) {
                val l2 = min(MAX_CONTEXT_WEIGHT, heads / (heads + CONTEXT_K))
                p = (1 - l2) * p + l2 * (user.bigramCount(prev1, key).toDouble() / heads)
            }
            if (prev2 != null) {
                val pair = user.followersOf(prev2, prev1)
                if (pair.isNotEmpty()) {
                    val n = pair.values.sum().toDouble()
                    val l3 = min(MAX_CONTEXT_WEIGHT, n / (n + CONTEXT_K))
                    p = (1 - l3) * p + l3 * ((pair[key] ?: 0) / n)
                }
            }
        }
        return p
    }

    private fun contextIds(ctx: ContextWords): Pair<Int, Int> {
        val p1 = when {
            ctx.prev1 != null -> dict.idOf(ctx.prev1)
            ctx.sentenceStart -> BinaryDictionary.START
            else -> BinaryDictionary.NONE
        }
        val p2 = when {
            ctx.prev2 != null -> dict.idOf(ctx.prev2)
            ctx.prev1 != null && ctx.sentenceStart -> BinaryDictionary.START
            else -> BinaryDictionary.NONE
        }
        return p2 to p1
    }

    fun baseProbability(key: String, ctx: ContextWords): Double {
        val id = dict.idOf(key)
        if (id < 0) return 0.0
        val (p2, p1) = contextIds(ctx)
        if (p1 != BinaryDictionary.NONE) {
            if (p2 != BinaryDictionary.NONE) {
                val t = dict.trigramLogP(p2, p1, id)
                if (!t.isNaN()) return 10.0.pow(t.toDouble())
            }
            val b = dict.bigramLogP(p1, id)
            if (!b.isNaN()) return BACKOFF * 10.0.pow(b.toDouble())
            return BACKOFF * BACKOFF * 10.0.pow(dict.unigramLogP[id].toDouble())
        }
        return 10.0.pow(dict.unigramLogP[id].toDouble())
    }

    /** The most likely next words after [ctx], best first. */
    fun predict(ctx: ContextWords, limit: Int): List<Prediction> {
        val candidates = HashSet<String>()
        val (p2, p1) = contextIds(ctx)
        if (p1 != BinaryDictionary.NONE) {
            if (p2 != BinaryDictionary.NONE) dict.forEachTrigram(p2, p1) { id, _ -> candidates.add(dict.keys[id]) }
            dict.forEachBigram(p1) { id, _ -> candidates.add(dict.keys[id]) }
        }
        val prev1 = ctx.prev1 ?: if (ctx.sentenceStart) START_TOKEN else null
        val prev2 = ctx.prev2 ?: if (ctx.prev1 != null && ctx.sentenceStart) START_TOKEN else null
        if (prev1 != null) {
            candidates.addAll(user.followersOf(prev1).keys)
            if (prev2 != null) candidates.addAll(user.followersOf(prev2, prev1).keys)
        }
        if (candidates.size < limit * 3) {
            for (i in 0 until min(dict.size, 40)) candidates.add(dict.keys[i])
        }
        val result = ArrayList<Prediction>(candidates.size)
        for (key in candidates) {
            if (key == START_TOKEN) continue
            val uw = user.word(key)
            if (uw != null && uw.isBlocked) continue
            val inBase = dict.idOf(key) >= 0
            if (!inBase && (uw == null || !uw.isConfirmed)) continue
            val p = probability(key, ctx)
            if (p <= 0) continue
            result.add(Prediction(key, display(key), log10(p).toFloat(), !inBase))
        }
        result.sortByDescending { it.logP }
        return if (result.size > limit) result.subList(0, limit) else result
    }

    /** Natural written form of a word: the user's habit if they have one, else the dictionary's. */
    fun display(key: String): String {
        val uw = user.word(key)
        if (uw != null && (uw.isSlang || uw.isManual || uw.count >= UserLexicon.CONFIRM_COUNT)) return uw.display
        val id = dict.idOf(key)
        return if (id >= 0) dict.words[id] else uw?.display ?: key
    }

    /** The language pack's spelling of [key], or null if it doesn't know the word. */
    fun dictDisplay(key: String): String? = dict.idOf(key).let { if (it >= 0) dict.words[it] else null }

    /**
     * The capitals a word typed in lowercase should get: the dictionary's (names, German nouns,
     * "I") or those of a name the user taught. A habit of capitalising an ordinary word is never
     * forced on it, and a user who writes a word in lowercase on purpose keeps it that way.
     */
    fun properCase(key: String): String {
        val base = dictDisplay(key) ?: return display(key)
        val uw = user.word(key)
        if (uw != null && uw.hasOwnCase && TextUtils.caseOf(uw.display) == TextUtils.Case.LOWER) return uw.display
        return base
    }

    /**
     * Older versions learned "What" from the start of a sentence and then forced it on every
     * "what". Puts the dictionary's form back on such words; capitals the user really writes
     * (all-caps like "LOL", names) stay. Returns how many words were repaired.
     */
    fun repairLearnedCase(): Int {
        var repaired = 0
        for (w in user.allWords()) {
            val base = dictDisplay(w.key) ?: continue
            if (w.isManual || w.display == base) continue
            val accidental = TextUtils.caseOf(w.display) == TextUtils.Case.CAPITALIZED && TextUtils.caseOf(base) == TextUtils.Case.LOWER
            if (accidental) repaired++
            user.setDisplay(w.key, if (accidental) base else w.display, base)
        }
        return repaired
    }

    fun isKnown(key: String): Boolean = (dict.idOf(key) >= 0 && !user.isBlocked(key)) || user.isKnown(key)

    /** Records a committed word plus its context so future predictions follow the user's habits. */
    fun learn(display: String, ctx: ContextWords, now: Long, confirmed: Boolean = false, shouting: Boolean = false) {
        val key = TextUtils.key(display, lang)
        if (key.isEmpty()) return
        // A capital at the start of a sentence (or capitals in a shouted message) are explained by
        // where the word is, not by how the user writes it.
        val atSentenceStart = ctx.sentenceStart && ctx.prev1 == null
        val caseNeutral = shouting || (atSentenceStart && TextUtils.caseOf(display) == TextUtils.Case.CAPITALIZED)
        user.learnWord(display, dictDisplay(key), now, confirmed, caseNeutral)
        val prev1 = ctx.prev1 ?: if (ctx.sentenceStart) START_TOKEN else null
        val prev2 = ctx.prev2 ?: if (ctx.prev1 != null && ctx.sentenceStart) START_TOKEN else null
        if (prev1 != null) {
            user.learnBigram(prev1, key)
            if (prev2 != null) user.learnTrigram(prev2, prev1, key)
        }
    }

    companion object {
        const val OOV = -12f
        const val START_TOKEN = "<s>"
        private const val BACKOFF = 0.4
        private const val MIN_USER_WEIGHT = 0.05
        private const val MAX_USER_WEIGHT = 0.6
        private const val USER_HALF_POINT = 1500.0
        private const val MAX_CONTEXT_WEIGHT = 0.7
        private const val CONTEXT_K = 5.0
    }
}

package com.itzsuli.smartkeyboard.engine

import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min

/**
 * Predicts which letter comes next, for adaptive touch targets: the likelier a key, the
 * larger its invisible hitbox. Combines the dictionary (what words start with the current
 * prefix), the sentence context (what words are likely here) and the user's own words.
 */
class KeyPredictor(private val lm: LanguageModel) {

    /** Marker for "the word is complete" (makes space easier to hit). */
    val endOfWord = ' '

    /**
     * Probability per next character, or null when the prefix is unknown (likely a typo,
     * in which case the keyboard falls back to plain geometry).
     */
    fun nextChars(prefix: String, ctx: ContextWords): Map<Char, Float>? {
        val key = TextUtils.key(prefix, lm.lang)
        val trie = lm.dict.trie
        val node = trie.find(key)
        val dist = HashMap<Char, Float>()

        if (node >= 0) {
            val total = trie.subtreeMass(node)
            if (total > 0f) {
                trie.forEachChild(node) { child, c -> dist[c] = (dist[c] ?: 0f) + trie.subtreeMass(child) / total * BASE_WEIGHT }
                val own = trie.word(node)
                if (own >= 0 && key.isNotEmpty()) {
                    val p = Math.pow(10.0, lm.dict.unigramLogP[own].toDouble()).toFloat() / total
                    dist[endOfWord] = (dist[endOfWord] ?: 0f) + p * BASE_WEIGHT
                }
            }
        }

        // Context: likely next words that continue this prefix.
        var ctxMass = 0f
        val ctxDist = HashMap<Char, Float>()
        for (p in lm.predict(ctx, 40)) {
            if (!p.key.startsWith(key)) continue
            val pr = Math.pow(10.0, p.logP.toDouble()).toFloat()
            val c = if (p.key.length == key.length) endOfWord else p.key[key.length]
            ctxDist[c] = (ctxDist[c] ?: 0f) + pr
            ctxMass += pr
        }
        if (ctxMass > 0f) {
            val weight = CONTEXT_WEIGHT * (ctxMass / (ctxMass + 0.02f))
            for ((c, v) in ctxDist) dist[c] = (dist[c] ?: 0f) + v / ctxMass * weight
        }

        // Personal vocabulary (slang, names) the base dictionary does not know.
        var userMass = 0f
        val userDist = HashMap<Char, Float>()
        for (w in lm.user.activeWords()) {
            if (!w.key.startsWith(key) || lm.dict.idOf(w.key) >= 0) continue
            val c = if (w.key.length == key.length) endOfWord else w.key[key.length]
            userDist[c] = (userDist[c] ?: 0f) + w.count
            userMass += w.count
        }
        if (userMass > 0f) {
            val weight = USER_WEIGHT * (userMass / (userMass + 3f))
            for ((c, v) in userDist) dist[c] = (dist[c] ?: 0f) + v / userMass * weight
        }

        val sum = dist.values.sum()
        if (sum <= 0f) return null
        for (k in dist.keys.toList()) dist[k] = dist[k]!! / sum
        return dist
    }

    companion object {
        private const val BASE_WEIGHT = 1f
        private const val CONTEXT_WEIGHT = 1f
        private const val USER_WEIGHT = 0.6f

        /** How far adaptive hit areas may reach. Sizes are fractions of the half key. */
        data class Tuning(
            /** Taps inside this part of a key always type it (only the border is shared). */
            val innerX: Float = 0.75f,
            val innerY: Float = 0.71f,
            /** How much letter likelihood counts against touch distance. */
            val priorWeight: Float = 0.2f,
            /** A likelier neighbour can never be favoured by more than this (natural log). */
            val maxLogRatio: Float = 3f,
        )

        val DEFAULT_TUNING = Tuning()

        /**
         * Picks the key for a touch: plain distance, nudged by letter likelihood. A touch well
         * inside a key always types that key; only the border zone between keys is shared, and
         * how far a likely neighbour can reach is bounded.
         *
         * @param strength 0 = off, 1 = balanced, 1.5 = strong.
         * @param damp learned trust per (key under the finger, neighbour) pair, 0..1: pairs whose
         *   "help" the user keeps undoing reach less far.
         */
        fun resolve(
            x: Float, y: Float,
            keys: List<KeyInfo>,
            probabilities: Map<Char, Float>?,
            spatial: SpatialModel,
            strength: Float,
            tuning: Tuning = DEFAULT_TUNING,
            damp: (from: Char, to: Char) -> Float = { _, _ -> 1f },
        ): KeyInfo? {
            if (keys.isEmpty()) return null
            val direct = keys.firstOrNull { x >= it.cx - it.width / 2 && x < it.cx + it.width / 2 && y >= it.cy - it.height / 2 && y < it.cy + it.height / 2 }
            if (probabilities == null || strength <= 0f) return direct ?: keys.minByOrNull { spatial.touchCost(x, y, it) }
            if (direct != null) {
                // Measured from where this person usually hits the key, not its drawn centre.
                val (ex, ey) = spatial.expectedPoint(direct)
                val rx = (x - ex) / (direct.width / 2)
                val ry = (y - ey) / (direct.height / 2)
                if (abs(rx) < tuning.innerX && abs(ry) < tuning.innerY) return direct
            }
            val floor = 0.002
            fun logP(k: KeyInfo) = ln(max(floor, (probabilities[k.char.lowercaseChar()] ?: 0f).toDouble()))
            val directLogP = direct?.let { logP(it) }
            var best: KeyInfo? = null
            var bestScore = Double.NEGATIVE_INFINITY
            for (k in keys) {
                // Only immediate neighbours compete.
                if (abs(k.cx - x) > k.width * 1.4f || abs(k.cy - y) > k.height * 1.2f) continue
                val geo = -spatial.touchCost(x, y, k) * LN10
                var prior = logP(k)
                if (direct != null && k !== direct) {
                    val gain = min(prior - directLogP!!, tuning.maxLogRatio.toDouble())
                    prior = directLogP + if (gain > 0) gain * damp(direct.char.lowercaseChar(), k.char.lowercaseChar()) else gain
                }
                val score = geo + strength * tuning.priorWeight * prior
                if (score > bestScore) { bestScore = score; best = k }
            }
            return best ?: direct
        }

        private val LN10 = ln(10.0)
    }
}

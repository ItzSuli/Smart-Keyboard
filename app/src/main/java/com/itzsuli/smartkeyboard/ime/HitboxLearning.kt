package com.itzsuli.smartkeyboard.ime

import com.itzsuli.smartkeyboard.data.LearningStore
import kotlin.math.max
import kotlin.math.min

/**
 * Teaches the adaptive keys how far they may reach for this person. When the keyboard types
 * a neighbour instead of the key under the finger ("kid" + d → "kis") and the user deletes
 * that letter, that pair of keys (d → s) reaches less far from then on, and the feature as a
 * whole gets a little more careful. Picks that survive into finished words slowly earn the
 * trust back.
 */
class HitboxLearning(private val store: LearningStore) {
    private val pairs = HashMap<String, Float>()
    private var trust = 1f
    private var dirty = false

    init {
        runCatching { store.getValue(KEY)?.let { restore(it) } }
    }

    /** Overall multiplier for the adaptive strength, 0.35..1. */
    @get:Synchronized val strengthFactor: Float get() = trust

    /** How far [to] may still reach into [from]'s key, 0..1. */
    @Synchronized fun damp(from: Char, to: Char): Float {
        val score = pairs[key(from, to)] ?: return 1f
        return 1f / (1f + UNDO_WEIGHT * score)
    }

    @Synchronized fun undone(from: Char, to: Char) {
        val k = key(from, to)
        pairs[k] = min(MAX_SCORE, (pairs[k] ?: 0f) + 1f)
        trust = max(MIN_TRUST, trust - 0.04f)
        dirty = true
    }

    @Synchronized fun kept(from: Char, to: Char) {
        val k = key(from, to)
        pairs[k]?.let { val v = it - 0.2f; if (v <= 0f) pairs.remove(k) else pairs[k] = v }
        trust = min(1f, trust + 0.004f)
        dirty = true
    }

    fun save() {
        val data = synchronized(this) {
            if (!dirty) return
            dirty = false
            "t=$trust;" + pairs.entries.joinToString(";") { "${it.key}=${it.value}" }
        }
        runCatching { store.putValue(KEY, data) }
    }

    @Synchronized fun reset() {
        pairs.clear()
        trust = 1f
        dirty = true
    }

    private fun restore(data: String) {
        for (part in data.split(';')) {
            val eq = part.lastIndexOf('=')
            if (eq <= 0) continue
            val v = part.substring(eq + 1).toFloatOrNull() ?: continue
            val k = part.substring(0, eq)
            if (k == "t") trust = v.coerceIn(MIN_TRUST, 1f) else if (k.length == 2) pairs[k] = v
        }
    }

    private fun key(from: Char, to: Char) = "${from.lowercaseChar()}${to.lowercaseChar()}"

    companion object {
        private const val KEY = "hitbox.learning"
        private const val UNDO_WEIGHT = 0.9f
        private const val MAX_SCORE = 20f
        private const val MIN_TRUST = 0.35f
    }
}

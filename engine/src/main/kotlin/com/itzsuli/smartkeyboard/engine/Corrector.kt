package com.itzsuli.smartkeyboard.engine

import kotlin.math.min

/**
 * Finds dictionary words close to what was typed, with keyboard-aware edit costs.
 * Costs are log10 "error probabilities", so they combine directly with language-model scores.
 */
class Corrector(private val spatial: SpatialModel?) {

    class Match(val key: String, val cost: Float, val completion: Boolean, val baseId: Int)

    fun maxCostFor(length: Int): Float = when {
        length <= 1 -> 0.6f
        length == 2 -> 1.3f
        length == 3 -> 1.9f
        length == 4 -> 2.5f
        length <= 6 -> 3.1f
        else -> 3.7f
    }

    private fun sub(typed: TypedChar, intended: Char, position: Int): Float {
        var base = spatial?.substitutionCost(typed, intended) ?: fallbackSubstitution(typed.char, intended)
        // Spelling slips swap vowels regardless of where they sit on the keyboard (seperate).
        if (base > VOWEL_COST && isVowel(typed.char) && isVowel(intended)) base = VOWEL_COST
        // People rarely get the first letter wrong.
        return if (position == 0 && base > SpatialModel.FOLD_COST) base + FIRST_LETTER_PENALTY else base
    }

    private fun isVowel(c: Char) = TextUtils.fold(c) in VOWELS

    private fun fallbackSubstitution(t: Char, i: Char): Float = when {
        t == i -> 0f
        TextUtils.fold(t) == TextUtils.fold(i) -> SpatialModel.FOLD_COST
        else -> 2.2f
    }

    /** Typed an extra character. */
    private fun insertion(typed: List<TypedChar>, j: Int): Float {
        val c = typed[j].char
        if (c == '\'' || c == '-') return 0.6f
        if (j > 0 && typed[j - 1].char == c) return 0.7f
        return INSERTION
    }

    /** Missed a character the word has. */
    private fun deletion(word: CharSequence, d: Int): Float {
        val c = word[d]
        if (c == '\'') return 0.08f
        if (c == '-') return 0.35f
        if (d > 0 && word[d - 1] == c) return 0.65f
        return DELETION
    }

    /**
     * Searches the base dictionary trie. Corrections are words whose full spelling is within
     * budget; completions are words whose beginning matches what was typed so far.
     */
    fun search(trie: Trie, keys: Array<String>, typed: List<TypedChar>, completions: Boolean, maxResults: Int = 40): List<Match> {
        val n = typed.size
        if (n == 0) return emptyList()
        val budget = maxCostFor(n)
        val completionBudget = min(budget, maxCostFor(n) * 0.7f)
        val best = HashMap<Int, Match>()

        val maxDepth = n + 4
        val rows = Array(maxDepth + 2) { FloatArray(n + 1) }
        val path = CharArray(maxDepth + 2)
        for (j in 0..n) rows[0][j] = if (j == 0) 0f else rows[0][j - 1] + insertion(typed, j - 1)

        fun consider(m: Match) {
            val old = best[m.baseId]
            if (old == null || m.cost < old.cost) best[m.baseId] = m
        }

        // Explicit DFS stack of (node, depth).
        val stackNode = IntArray(4096)
        val stackDepth = IntArray(4096)
        var sp = 0
        trie.forEachChild(trie.root) { child, _ -> stackNode[sp] = child; stackDepth[sp] = 1; sp++ }
        val word = StringBuilder()
        while (sp > 0) {
            sp--
            val node = stackNode[sp]
            val depth = stackDepth[sp]
            val c = trie.charOf(node)
            path[depth - 1] = c
            word.setLength(0); word.append(path, 0, depth)
            val prev = rows[depth - 1]
            val row = rows[depth]
            row[0] = prev[0] + deletion(word, depth - 1)
            var rowMin = row[0]
            for (j in 1..n) {
                var v = prev[j - 1] + sub(typed[j - 1], c, j - 1)
                val del = prev[j] + deletion(word, depth - 1)
                if (del < v) v = del
                val ins = row[j - 1] + insertion(typed, j - 1)
                if (ins < v) v = ins
                if (j > 1 && depth > 1 && typed[j - 1].char == path[depth - 2] && typed[j - 2].char == c) {
                    val tr = rows[depth - 2][j - 2] + TRANSPOSITION
                    if (tr < v) v = tr
                }
                row[j] = v
                if (v < rowMin) rowMin = v
            }
            val id = trie.word(node)
            if (id >= 0 && row[n] <= budget) consider(Match(keys[id], row[n], false, id))
            if (completions && (depth == n || depth == n + 1) && row[n] <= completionBudget) {
                // Everything below this node starts with (roughly) what was typed.
                for (w in trie.topWords(node, 4)) {
                    if (w != id) consider(Match(keys[w], row[n] + COMPLETION_COST, true, w))
                }
            }
            if (rowMin > budget || depth >= maxDepth) continue
            val start = trie.childStartOf(node)
            val count = trie.childCountOf(node)
            if (sp + count >= stackNode.size) continue
            for (i in start until start + count) {
                stackNode[sp] = i; stackDepth[sp] = depth + 1; sp++
            }
        }
        return best.values.sortedBy { it.cost }.take(maxResults)
    }

    /** Full edit cost of typing [typed] for [word] (used for the small user vocabulary). */
    fun cost(typed: List<TypedChar>, word: String, allowCompletion: Boolean): Pair<Float, Boolean> {
        val n = typed.size
        val m = word.length
        val d = Array(m + 1) { FloatArray(n + 1) }
        for (j in 1..n) d[0][j] = d[0][j - 1] + insertion(typed, j - 1)
        var bestPrefix = Float.MAX_VALUE
        for (i in 1..m) {
            d[i][0] = d[i - 1][0] + deletion(word, i - 1)
            for (j in 1..n) {
                var v = d[i - 1][j - 1] + sub(typed[j - 1], word[i - 1], j - 1)
                v = min(v, d[i - 1][j] + deletion(word, i - 1))
                v = min(v, d[i][j - 1] + insertion(typed, j - 1))
                if (i > 1 && j > 1 && typed[j - 1].char == word[i - 2] && typed[j - 2].char == word[i - 1]) {
                    v = min(v, d[i - 2][j - 2] + TRANSPOSITION)
                }
                d[i][j] = v
            }
            if (i >= n && i < m) bestPrefix = min(bestPrefix, d[i][n])
        }
        val full = d[m][n]
        if (allowCompletion && bestPrefix + COMPLETION_COST < full) return bestPrefix + COMPLETION_COST to true
        return full to false
    }

    companion object {
        const val INSERTION = 1.3f
        const val DELETION = 1.2f
        const val VOWEL_COST = 1.1f
        private const val VOWELS = "aeiouyаеиоуыэюяاوي"
        const val TRANSPOSITION = 0.95f
        const val COMPLETION_COST = 0.25f
        const val FIRST_LETTER_PENALTY = 0.45f
    }
}

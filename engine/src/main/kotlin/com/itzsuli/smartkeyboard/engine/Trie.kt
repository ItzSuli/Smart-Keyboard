package com.itzsuli.smartkeyboard.engine

import java.util.PriorityQueue
import kotlin.math.max
import kotlin.math.pow

/**
 * Compact, immutable prefix tree over dictionary keys. Children of a node are stored
 * contiguously and sorted by character, so the whole structure is a handful of primitive
 * arrays (about 4 MB for 60k words) instead of hundreds of thousands of objects.
 */
class Trie private constructor(
    internal val chars: CharArray,
    internal val childStart: IntArray,
    internal val childCount: IntArray,
    internal val wordId: IntArray,
    /** Sum of unigram probabilities of every word below the node. */
    internal val mass: FloatArray,
    /** Best unigram log10 probability below the node (for best-first search). */
    internal val maxLogP: FloatArray,
    val nodeCount: Int,
) {
    val root: Int get() = 0

    fun child(node: Int, c: Char): Int {
        var lo = childStart[node]
        var hi = lo + childCount[node] - 1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            val mc = chars[mid]
            if (mc < c) lo = mid + 1 else if (mc > c) hi = mid - 1 else return mid
        }
        return -1
    }

    fun find(prefix: String): Int {
        var node = root
        for (c in prefix) {
            node = child(node, c)
            if (node < 0) return -1
        }
        return node
    }

    fun word(node: Int): Int = wordId[node]

    fun subtreeMass(node: Int): Float = mass[node]

    inline fun forEachChild(node: Int, action: (child: Int, c: Char) -> Unit) {
        val start = childStartOf(node)
        for (i in start until start + childCountOf(node)) action(i, charOf(i))
    }

    fun childStartOf(node: Int) = childStart[node]
    fun childCountOf(node: Int) = childCount[node]
    fun charOf(node: Int) = chars[node]

    /** The [k] most frequent words in the subtree of [node], best first. */
    fun topWords(node: Int, k: Int): IntArray {
        if (node < 0 || k <= 0) return IntArray(0)
        val result = ArrayList<Int>(k)
        val queue = PriorityQueue<Entry>(32) { a, b -> java.lang.Float.compare(b.score, a.score) }
        queue.add(Entry(node, false, maxLogP[node]))
        while (queue.isNotEmpty() && result.size < k) {
            val e = queue.poll()
            if (e.isWord) {
                result.add(wordId[e.node])
                continue
            }
            val n = e.node
            if (wordId[n] >= 0) queue.add(Entry(n, true, unigram[wordId[n]]))
            val start = childStart[n]
            for (i in start until start + childCount[n]) queue.add(Entry(i, false, maxLogP[i]))
        }
        return result.toIntArray()
    }

    private class Entry(val node: Int, val isWord: Boolean, val score: Float)

    private lateinit var unigram: FloatArray

    companion object {
        private const val STACK = 16384

        fun build(keys: Array<String>, logP: FloatArray): Trie {
            val order = keys.indices.sortedWith { a, b -> keys[a].compareTo(keys[b]) }.toIntArray()
            var capacity = 1
            for (k in keys) capacity += k.length
            val chars = CharArray(capacity)
            val childStart = IntArray(capacity)
            val childCount = IntArray(capacity)
            val wordId = IntArray(capacity) { -1 }
            val mass = FloatArray(capacity)
            val maxLogP = FloatArray(capacity) { Float.NEGATIVE_INFINITY }
            var next = 1

            // Iterative construction: each frame is (node, lo, hi, depth) over the sorted keys.
            val stackNode = IntArray(STACK)
            val stackLo = IntArray(STACK)
            val stackHi = IntArray(STACK)
            val stackDepth = IntArray(STACK)
            var sp = 0
            stackNode[0] = 0; stackLo[0] = 0; stackHi[0] = order.size; stackDepth[0] = 0; sp = 1
            while (sp > 0) {
                sp--
                val node = stackNode[sp]
                var lo = stackLo[sp]
                val hi = stackHi[sp]
                val depth = stackDepth[sp]
                if (lo < hi && keys[order[lo]].length == depth) {
                    wordId[node] = order[lo]
                    lo++
                }
                // Count distinct next characters.
                var groups = 0
                var i = lo
                while (i < hi) {
                    val c = keys[order[i]][depth]
                    var j = i + 1
                    while (j < hi && keys[order[j]][depth] == c) j++
                    groups++
                    i = j
                }
                childStart[node] = next
                childCount[node] = groups
                val first = next
                next += groups
                i = lo
                var g = 0
                while (i < hi) {
                    val c = keys[order[i]][depth]
                    var j = i + 1
                    while (j < hi && keys[order[j]][depth] == c) j++
                    val childNode = first + g
                    chars[childNode] = c
                    if (sp >= stackNode.size) error("Trie too deep")
                    stackNode[sp] = childNode; stackLo[sp] = i; stackHi[sp] = j; stackDepth[sp] = depth + 1
                    sp++
                    g++
                    i = j
                }
            }
            // Children always have higher indices than parents, so a reverse sweep aggregates.
            for (n in next - 1 downTo 0) {
                var m = 0f
                var best = Float.NEGATIVE_INFINITY
                val w = wordId[n]
                if (w >= 0) {
                    m += 10.0.pow(logP[w].toDouble()).toFloat()
                    best = logP[w]
                }
                val start = childStart[n]
                for (c in start until start + childCount[n]) {
                    m += mass[c]
                    best = max(best, maxLogP[c])
                }
                mass[n] = m
                maxLogP[n] = best
            }
            return Trie(
                chars.copyOf(next), childStart.copyOf(next), childCount.copyOf(next), wordId.copyOf(next),
                mass.copyOf(next), maxLogP.copyOf(next), next,
            ).also { it.unigram = logP }
        }
    }
}

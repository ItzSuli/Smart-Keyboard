package com.itzsuli.smartkeyboard.engine

import java.io.BufferedInputStream
import java.io.DataInputStream
import java.io.InputStream
import java.util.zip.GZIPInputStream

/**
 * Read-only base language pack produced by tools/build_dictionaries.py: words ranked by
 * everyday frequency, plus bigram/trigram continuations for context-aware predictions.
 * All probabilities are log10 values.
 */
class BinaryDictionary private constructor(
    val lang: String,
    /** Display forms with natural capitalisation ("I'm", "Haus", "Monday"). */
    val words: Array<String>,
    /** Lookup keys (see [TextUtils.key]). */
    val keys: Array<String>,
    val unigramLogP: FloatArray,
    private val biOffsets: IntArray,
    private val biIds: IntArray,
    private val biLogP: FloatArray,
    private val triKeys: LongArray,
    private val triOffsets: IntArray,
    private val triIds: IntArray,
    private val triLogP: FloatArray,
) {
    private val index: HashMap<String, Int> = HashMap<String, Int>(keys.size * 2).also { map ->
        for (i in keys.indices) map.putIfAbsent(keys[i], i)
    }

    val size: Int get() = words.size

    val trie: Trie by lazy { Trie.build(keys, unigramLogP) }

    fun idOf(key: String): Int = index[key] ?: -1

    /** Continuations of [prev] (or of a sentence start when [prev] is [START]). */
    fun forEachBigram(prev: Int, action: (id: Int, logP: Float) -> Unit) {
        val head = if (prev == START) words.size else prev
        if (head < 0 || head >= biOffsets.size - 1) return
        for (j in biOffsets[head] until biOffsets[head + 1]) action(biIds[j], biLogP[j])
    }

    fun bigramLogP(prev: Int, id: Int): Float {
        val head = if (prev == START) words.size else prev
        if (head < 0 || head >= biOffsets.size - 1) return Float.NaN
        for (j in biOffsets[head] until biOffsets[head + 1]) if (biIds[j] == id) return biLogP[j]
        return Float.NaN
    }

    fun forEachTrigram(prev2: Int, prev1: Int, action: (id: Int, logP: Float) -> Unit) {
        val slot = triSlot(prev2, prev1)
        if (slot < 0) return
        for (j in triOffsets[slot] until triOffsets[slot + 1]) action(triIds[j], triLogP[j])
    }

    fun trigramLogP(prev2: Int, prev1: Int, id: Int): Float {
        val slot = triSlot(prev2, prev1)
        if (slot < 0) return Float.NaN
        for (j in triOffsets[slot] until triOffsets[slot + 1]) if (triIds[j] == id) return triLogP[j]
        return Float.NaN
    }

    private fun triSlot(prev2: Int, prev1: Int): Int {
        if (prev2 == NONE || prev1 == NONE || prev1 == START) return -1
        val k = pairKey(prev2, prev1)
        val pos = triKeys.binarySearch(k)
        return if (pos >= 0) pos else -1
    }

    companion object {
        /** Pseudo word id for "beginning of a sentence". */
        const val START = 0xFFFF
        const val NONE = -1

        private fun pairKey(a: Int, b: Int): Long = (a.toLong() shl 16) or b.toLong()

        fun load(input: InputStream): BinaryDictionary {
            DataInputStream(BufferedInputStream(GZIPInputStream(input, 1 shl 16), 1 shl 16)).use { din ->
                val magic = ByteArray(4).also { din.readFully(it) }
                require(String(magic, Charsets.US_ASCII) == "SKD2") { "Not a Smart Keyboard dictionary" }
                din.readUnsignedByte()
                val lang = String(ByteArray(din.readUnsignedShort()).also { din.readFully(it) }, Charsets.UTF_8)

                val n = din.readInt()
                val words = arrayOfNulls<String>(n)
                val keys = arrayOfNulls<String>(n)
                val uni = FloatArray(n)
                val buf = ByteArray(256)
                for (i in 0 until n) {
                    val len = din.readUnsignedByte()
                    din.readFully(buf, 0, len)
                    val w = String(buf, 0, len, Charsets.UTF_8)
                    words[i] = w
                    keys[i] = TextUtils.key(w, lang)
                    uni[i] = -din.readUnsignedByte() / 16f
                }

                // Bigram heads arrive sorted by id; the sentence-start head (0xFFFF) is last.
                val nHeads = din.readInt()
                val biOffsets = IntArray(n + 2)
                val headIds = IntArray(nHeads)
                val headCounts = IntArray(nHeads)
                var ids = IntArray(nHeads * 8)
                var logs = FloatArray(nHeads * 8)
                var total = 0
                for (h in 0 until nHeads) {
                    val head = din.readUnsignedShort()
                    val k = din.readUnsignedByte()
                    headIds[h] = if (head == START) n else head
                    headCounts[h] = k
                    if (total + k > ids.size) {
                        ids = ids.copyOf((total + k) * 2)
                        logs = logs.copyOf((total + k) * 2)
                    }
                    for (j in 0 until k) {
                        ids[total] = din.readUnsignedShort()
                        logs[total] = -din.readUnsignedByte() / 16f
                        total++
                    }
                }
                // Convert to CSR offsets indexed by head id.
                val counts = IntArray(n + 1)
                for (h in 0 until nHeads) counts[headIds[h]] = headCounts[h]
                for (i in 0..n) biOffsets[i + 1] = biOffsets[i] + counts[i]

                val nTri = din.readInt()
                val triKeys = LongArray(nTri)
                val triOffsets = IntArray(nTri + 1)
                var tIds = IntArray(nTri * 4)
                var tLogs = FloatArray(nTri * 4)
                var tTotal = 0
                for (t in 0 until nTri) {
                    val a = din.readUnsignedShort()
                    val b = din.readUnsignedShort()
                    val k = din.readUnsignedByte()
                    triKeys[t] = pairKey(a, b)
                    if (tTotal + k > tIds.size) {
                        tIds = tIds.copyOf((tTotal + k) * 2)
                        tLogs = tLogs.copyOf((tTotal + k) * 2)
                    }
                    for (j in 0 until k) {
                        tIds[tTotal] = din.readUnsignedShort()
                        tLogs[tTotal] = -din.readUnsignedByte() / 16f
                        tTotal++
                    }
                    triOffsets[t + 1] = tTotal
                }
                @Suppress("UNCHECKED_CAST")
                return BinaryDictionary(
                    lang, words as Array<String>, keys as Array<String>, uni,
                    biOffsets, ids.copyOf(total), logs.copyOf(total),
                    triKeys, triOffsets, tIds.copyOf(tTotal), tLogs.copyOf(tTotal),
                )
            }
        }
    }
}

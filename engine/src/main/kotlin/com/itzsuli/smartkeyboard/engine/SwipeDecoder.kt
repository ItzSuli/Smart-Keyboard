package com.itzsuli.smartkeyboard.engine

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Gesture ("swipe") typing decoder.
 *
 * 1. The finger path is resampled to [N] equidistant points (speed does not matter).
 * 2. Candidate words must start near the first point and end near the last one
 *    (pre-indexed by first/last key, so only a few thousand words are looked at).
 * 3. A word survives only if the path passes close to each of its letters, in order.
 * 4. Survivors are scored by dynamic time warping against the word's ideal path through
 *    key centres plus how tightly the path hugs its letters, then combined with the
 *    context language model (which also includes everything learned from the user).
 */
class SwipeDecoder(val geometry: KeyGeometry, val tuning: Tuning = Tuning()) {

    /** Score weights (log10 units), tuned with SwipeDecoderTest's synthetic gestures. */
    data class Tuning(
        val dtw: Float = 16f,
        val keys: Float = 3f,
        val length: Float = 1.2f,
        val lm: Float = 1f,
        val passRadius: Float = 0.95f,
        val relaxedRadius: Float = 1.35f,
    )

    class Candidate(val key: String, val geoScore: Float, val lmScore: Float, val score: Float)

    private val keys = geometry.keys
    private val kw = geometry.keyWidth
    private val kh = geometry.keyHeight
    private val keyIndex = HashMap<Char, Int>().also { m ->
        keys.forEachIndexed { i, k -> m.putIfAbsent(k.char.lowercaseChar(), i) }
    }

    // Per-dictionary index (rebuilt when the dictionary changes).
    private var indexedDict: BinaryDictionary? = null
    private var seqStart = IntArray(0)
    private var seqData = ByteArray(0)
    private var buckets = HashMap<Int, IntArray>()

    private fun keyOf(c: Char): Int {
        keyIndex[c]?.let { return it }
        return keyIndex[TextUtils.fold(c)] ?: -1
    }

    /** Key sequence of a word with repeated keys collapsed, or null if it cannot be swiped here. */
    fun sequence(word: String): IntArray? {
        val out = ArrayList<Int>(word.length)
        for (c in word) {
            if (c == '\'' || c == '-') continue
            val k = keyOf(c)
            if (k < 0) return null
            if (out.isEmpty() || out[out.size - 1] != k) out.add(k)
        }
        return if (out.isEmpty()) null else out.toIntArray()
    }

    @Synchronized
    private fun ensureIndex(dict: BinaryDictionary) {
        if (indexedDict === dict) return
        val starts = IntArray(dict.size + 1)
        val data = java.io.ByteArrayOutputStream(dict.size * 8)
        val tmp = HashMap<Int, ArrayList<Int>>()
        for (id in 0 until dict.size) {
            starts[id] = data.size()
            val seq = sequence(dict.keys[id])
            if (seq == null || dict.keys[id].length < 2) continue
            for (k in seq) data.write(k)
            tmp.getOrPut(bucket(seq.first(), seq.last())) { ArrayList() }.add(id)
        }
        starts[dict.size] = data.size()
        seqStart = starts
        seqData = data.toByteArray()
        buckets = HashMap<Int, IntArray>(tmp.size * 2).also { m -> tmp.forEach { (k, v) -> m[k] = v.toIntArray() } }
        indexedDict = dict
    }

    private fun bucket(first: Int, last: Int) = first * 256 + last

    /** Builds the index ahead of time (call from a background thread after loading). */
    fun prepare(dict: BinaryDictionary) = ensureIndex(dict)

    fun decode(xs: FloatArray, ys: FloatArray, count: Int, lm: LanguageModel, ctx: ContextWords, limit: Int = 8): List<Candidate> {
        if (count < 2 || keys.isEmpty()) return emptyList()
        val dict = lm.dict
        ensureIndex(dict)

        // Normalised coordinates: one unit = one key width / height.
        val px = FloatArray(count) { xs[it] / kw }
        val py = FloatArray(count) { ys[it] / kh }
        val pathLen = length(px, py, count)
        if (pathLen < 0.35f) return emptyList()
        val ux = FloatArray(N)
        val uy = FloatArray(N)
        resample(px, py, count, ux, uy, N)

        val cx = FloatArray(keys.size) { keys[it].cx / kw }
        val cy = FloatArray(keys.size) { keys[it].cy / kh }

        val startKeys = nearestKeys(ux[0], uy[0], cx, cy, START_RADIUS, 3)
        val endKeys = nearestKeys(ux[N - 1], uy[N - 1], cx, cy, END_RADIUS, 4)
        if (startKeys.isEmpty() || endKeys.isEmpty()) return emptyList()

        class Survivor(val key: String, val seq: IntArray, val keyFit: Float, val lengthPenalty: Float, val pre: Float)
        val survivors = ArrayList<Survivor>(256)

        var radius = tuning.passRadius
        fun evaluate(key: String, data: ByteArray, from: Int, len: Int) {
            if (len < 2 && pathLen > 1.2f) return
            val idealLen = idealLength(data, from, len, cx, cy)
            val ratio = pathLen / max(idealLen, 0.5f)
            if (ratio < MIN_RATIO || ratio > MAX_RATIO) return
            val fit = passThrough(data, from, len, ux, uy, cx, cy, radius) ?: return
            val lmScore = lm.logP(key, ctx)
            if (lmScore <= LanguageModel.OOV) return
            val lengthPenalty = abs(kotlin.math.ln(ratio.toDouble())).toFloat()
            val seq = IntArray(len) { data[from + it].toInt() }
            survivors.add(Survivor(key, seq, fit, lengthPenalty, -fit * tuning.keys - lengthPenalty * tuning.length + lmScore * tuning.lm))
        }

        val startSet = startKeys.toSet()
        val endSet = endKeys.toSet()
        fun generate() {
            for (s in startKeys) for (e in endKeys) {
                val ids = buckets[bucket(s, e)] ?: continue
                for (id in ids) {
                    val from = seqStart[id]
                    val len = seqStart[id + 1] - from
                    if (lm.user.isBlocked(dict.keys[id])) continue
                    evaluate(dict.keys[id], seqData, from, len)
                }
            }
            // The user's own words (slang, names) are swipeable too.
            for (w in lm.user.activeWords()) {
                if (dict.idOf(w.key) >= 0) continue
                val seq = sequence(w.key) ?: continue
                if (seq.first() in startSet && seq.last() in endSet) {
                    evaluate(w.key, ByteArray(seq.size) { seq[it].toByte() }, 0, seq.size)
                }
            }
        }
        generate()
        // Sloppy gesture: accept paths that pass a bit further from the letters.
        if (survivors.size < 3) {
            survivors.clear()
            radius = tuning.relaxedRadius
            generate()
        }
        if (survivors.isEmpty()) return emptyList()

        survivors.sortByDescending { it.pre }
        val top = survivors.subList(0, min(survivors.size, MAX_DTW))
        val ix = FloatArray(N)
        val iy = FloatArray(N)
        val result = ArrayList<Candidate>(top.size)
        for (s in top) {
            idealPath(s.seq, cx, cy, ix, iy)
            val dtw = dtw(ux, uy, ix, iy)
            val geo = -(dtw * tuning.dtw + s.keyFit * tuning.keys + s.lengthPenalty * tuning.length * 0.5f)
            val lmScore = lm.logP(s.key, ctx)
            result.add(Candidate(s.key, geo, lmScore, geo + lmScore * tuning.lm))
        }
        result.sortByDescending { it.score }
        return if (result.size > limit) result.subList(0, limit) else result
    }

    // ---- geometry helpers ------------------------------------------------------------------

    private fun nearestKeys(x: Float, y: Float, cx: FloatArray, cy: FloatArray, radius: Float, max: Int): List<Int> {
        val d = cx.indices.map { it to dist(x, y, cx[it], cy[it]) }.sortedBy { it.second }
        val within = d.filter { it.second <= radius }.take(max).map { it.first }
        return within.ifEmpty { listOf(d.first().first) }
    }

    /**
     * Checks that the path visits every key of the word in order and returns the mean squared
     * closest distance (0 = the path crosses every key centre), or null if a key is missed.
     */
    private fun passThrough(data: ByteArray, from: Int, len: Int, ux: FloatArray, uy: FloatArray, cx: FloatArray, cy: FloatArray, radius: Float): Float? {
        var j = 0
        var sum = 0f
        for (i in 0 until len) {
            val k = data[from + i].toInt()
            val last = i == len - 1
            if (i == 0) {
                val d = dist(ux[0], uy[0], cx[k], cy[k])
                sum += d * d
                continue
            }
            // Find the first point near the key, then the closest point within that visit.
            var found = -1
            while (j < N) {
                if (dist(ux[j], uy[j], cx[k], cy[k]) <= radius) { found = j; break }
                j++
            }
            if (found < 0) return null
            var bestD = dist(ux[found], uy[found], cx[k], cy[k])
            var bestJ = found
            var t = found + 1
            while (t < N) {
                val d = dist(ux[t], uy[t], cx[k], cy[k])
                if (d > radius) break
                if (d < bestD) { bestD = d; bestJ = t }
                // Moving on: the visit ends here even if the next letter is a neighbour and the
                // path never leaves this key's radius ("there": e → r → e).
                else if (d > bestD + LEAVE) break
                t++
            }
            if (last) {
                val d = dist(ux[N - 1], uy[N - 1], cx[k], cy[k])
                bestD = min(bestD, d)
            }
            sum += bestD * bestD
            j = bestJ
        }
        return sum / len
    }

    private fun idealLength(data: ByteArray, from: Int, len: Int, cx: FloatArray, cy: FloatArray): Float {
        var l = 0f
        for (i in 1 until len) {
            val a = data[from + i - 1].toInt()
            val b = data[from + i].toInt()
            l += dist(cx[a], cy[a], cx[b], cy[b])
        }
        return l
    }

    private fun idealPath(seq: IntArray, cx: FloatArray, cy: FloatArray, ox: FloatArray, oy: FloatArray) {
        val sx = FloatArray(seq.size) { cx[seq[it]] }
        val sy = FloatArray(seq.size) { cy[seq[it]] }
        if (seq.size == 1) {
            ox.fill(sx[0]); oy.fill(sy[0]); return
        }
        resample(sx, sy, seq.size, ox, oy, N)
    }

    /** Dynamic time warping with a Sakoe-Chiba band; returns the mean matched distance. */
    private fun dtw(ax: FloatArray, ay: FloatArray, bx: FloatArray, by: FloatArray): Float {
        val inf = Float.MAX_VALUE
        var prev = FloatArray(N) { inf }
        var cur = FloatArray(N) { inf }
        for (i in 0 until N) {
            java.util.Arrays.fill(cur, inf)
            val lo = max(0, i - BAND)
            val hi = min(N - 1, i + BAND)
            for (j in lo..hi) {
                val dx = ax[i] - bx[j]
                val dy = ay[i] - by[j]
                val d = dx * dx + dy * dy
                val best = when {
                    i == 0 && j == 0 -> 0f
                    i == 0 -> cur[j - 1]
                    j == 0 -> prev[j]
                    else -> min(prev[j - 1], min(prev[j], cur[j - 1]))
                }
                cur[j] = if (best == inf) inf else best + d
            }
            val t = prev; prev = cur; cur = t
        }
        return prev[N - 1] / N
    }

    companion object {
        const val N = 64
        private const val BAND = 12
        private const val START_RADIUS = 1.05f
        private const val END_RADIUS = 1.25f
        private const val MIN_RATIO = 0.5f
        private const val MAX_RATIO = 2.4f
        private const val MAX_DTW = 160
        /** How far (key widths) the path must move away from a key to count as having left it. */
        private const val LEAVE = 0.3f

        fun dist(x1: Float, y1: Float, x2: Float, y2: Float): Float {
            val dx = x1 - x2
            val dy = y1 - y2
            return sqrt(dx * dx + dy * dy)
        }

        fun length(xs: FloatArray, ys: FloatArray, count: Int): Float {
            var l = 0f
            for (i in 1 until count) l += dist(xs[i - 1], ys[i - 1], xs[i], ys[i])
            return l
        }

        /** Resamples a polyline to [n] points spaced evenly along its length. */
        fun resample(xs: FloatArray, ys: FloatArray, count: Int, ox: FloatArray, oy: FloatArray, n: Int) {
            val total = length(xs, ys, count)
            if (total <= 0f || count < 2) {
                ox.fill(xs[0], 0, n); oy.fill(ys[0], 0, n); return
            }
            val step = total / (n - 1)
            ox[0] = xs[0]; oy[0] = ys[0]
            var out = 1
            var acc = 0f
            var lx = xs[0]
            var ly = ys[0]
            var i = 1
            while (i < count && out < n) {
                val d = dist(lx, ly, xs[i], ys[i])
                if (acc + d >= step && d > 0f) {
                    val t = (step - acc) / d
                    val nx = lx + t * (xs[i] - lx)
                    val ny = ly + t * (ys[i] - ly)
                    ox[out] = nx; oy[out] = ny; out++
                    lx = nx; ly = ny; acc = 0f
                } else {
                    acc += d
                    lx = xs[i]; ly = ys[i]
                    i++
                }
            }
            while (out < n) { ox[out] = xs[count - 1]; oy[out] = ys[count - 1]; out++ }
        }
    }
}

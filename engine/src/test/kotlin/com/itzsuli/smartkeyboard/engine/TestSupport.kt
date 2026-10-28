package com.itzsuli.smartkeyboard.engine

import java.io.File
import java.util.Random

object TestSupport {
    private val cache = HashMap<String, BinaryDictionary>()

    fun dict(lang: String): BinaryDictionary = synchronized(cache) {
        cache.getOrPut(lang) {
            val root = System.getProperty("sk.assets") ?: "../app/src/main/assets"
            File(root, "dict/$lang.dict").inputStream().use { BinaryDictionary.load(it) }
        }
    }

    const val KW = 100f
    const val KH = 150f

    /** Builds a phone-like grid from row strings (row 2 indented by half a key, row 3 by 1.5). */
    fun geometry(vararg rows: String, offsets: FloatArray = floatArrayOf(0f, 0.5f, 1.5f)): KeyGeometry {
        val keys = ArrayList<KeyInfo>()
        rows.forEachIndexed { r, row ->
            row.forEachIndexed { i, c ->
                keys.add(KeyInfo(c, (offsets.getOrElse(r) { 0f } + i + 0.5f) * KW, (r + 0.5f) * KH, KW, KH))
            }
        }
        return KeyGeometry(keys)
    }

    val qwerty by lazy { geometry("qwertyuiop", "asdfghjkl", "zxcvbnm") }
    val qwertz by lazy { geometry("qwertzuiopü", "asdfghjklöä", "yxcvbnm") }

    fun typed(word: String, geo: KeyGeometry? = null): List<TypedChar> = word.map { c ->
        val k = geo?.keyFor(c.lowercaseChar())
        if (k != null) TypedChar(c, k.cx, k.cy) else TypedChar(c)
    }

    /** Arabic everyday/dialect words shipped next to the Arabic dictionary. */
    val arabicChat: Set<String> by lazy {
        val root = System.getProperty("sk.assets") ?: "../app/src/main/assets"
        File(root, "dict/ar.chat").readLines().filter { it.isNotBlank() }.toSet()
    }

    /** Fresh model with an empty user lexicon. */
    fun model(lang: String = "en") = LanguageModel(dict(lang), UserLexicon(lang))

    /**
     * A human-ish gesture for [word]: wobbly key targets, rounded corners and jitter.
     * [noise] and [cut] are in key widths.
     */
    fun gesture(word: String, geo: KeyGeometry, rnd: Random, noise: Float = 0.22f, cut: Float = 0.35f): Pair<FloatArray, FloatArray> {
        val seq = ArrayList<KeyInfo>()
        for (c in word) {
            if (c == '\'' || c == '-') continue
            val k = geo.keyFor(c) ?: continue
            if (seq.isEmpty() || seq.last() != k) seq.add(k)
        }
        val pts = seq.map { k ->
            (k.cx + rnd.nextGaussian().toFloat() * noise * KW) to (k.cy + rnd.nextGaussian().toFloat() * noise * KH * 0.8f)
        }
        val xs = ArrayList<Float>()
        val ys = ArrayList<Float>()
        fun line(ax: Float, ay: Float, bx: Float, by: Float) {
            val steps = (kotlin.math.hypot((bx - ax).toDouble(), (by - ay).toDouble()) / 6.0).toInt().coerceAtLeast(1)
            for (s in 0..steps) {
                val t = s.toFloat() / steps
                xs.add(ax + (bx - ax) * t + rnd.nextGaussian().toFloat() * 2f)
                ys.add(ay + (by - ay) * t + rnd.nextGaussian().toFloat() * 2f)
            }
        }
        if (pts.size == 1) {
            line(pts[0].first, pts[0].second, pts[0].first + 30f, pts[0].second + 10f)
        }
        var cur = pts[0]
        for (i in 1 until pts.size) {
            val p = pts[i]
            if (i < pts.size - 1) {
                // Round the corner: stop short of the key and curve towards the next one.
                val n = pts[i + 1]
                // People round corners by a bounded amount, not by a fraction of long strokes.
                val inLen = kotlin.math.hypot((p.first - cur.first).toDouble(), (p.second - cur.second).toDouble()).toFloat()
                val outLen = kotlin.math.hypot((n.first - p.first).toDouble(), (n.second - p.second).toDouble()).toFloat()
                val ca = if (inLen > 0f) kotlin.math.min(cut, cut * KW / inLen) else 0f
                val cb = if (outLen > 0f) kotlin.math.min(cut, cut * KW / outLen) else 0f
                val ax = p.first - (p.first - cur.first) * ca
                val ay = p.second - (p.second - cur.second) * ca
                val bx = p.first + (n.first - p.first) * cb
                val by = p.second + (n.second - p.second) * cb
                line(cur.first, cur.second, ax, ay)
                for (s in 1..8) {
                    val t = s / 8f
                    val qx = (1 - t) * (1 - t) * ax + 2 * (1 - t) * t * p.first + t * t * bx
                    val qy = (1 - t) * (1 - t) * ay + 2 * (1 - t) * t * p.second + t * t * by
                    xs.add(qx); ys.add(qy)
                }
                cur = bx to by
            } else {
                line(cur.first, cur.second, p.first, p.second)
            }
        }
        return xs.toFloatArray() to ys.toFloatArray()
    }
}

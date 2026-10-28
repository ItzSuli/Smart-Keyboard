package com.itzsuli.smartkeyboard.engine

import kotlin.math.ln
import kotlin.math.min
import kotlin.math.sqrt

/** A letter key on screen (pixels). Only keys that type a single character are included. */
data class KeyInfo(val char: Char, val cx: Float, val cy: Float, val width: Float, val height: Float)

class KeyGeometry(val keys: List<KeyInfo>) {
    private val byChar = HashMap<Char, KeyInfo>(keys.size * 2).also { m ->
        for (k in keys) {
            m[k.char] = k
            m.putIfAbsent(k.char.lowercaseChar(), k)
        }
    }

    val keyWidth: Float = keys.map { it.width }.sorted().let { if (it.isEmpty()) 1f else it[it.size / 2] }
    val keyHeight: Float = keys.map { it.height }.sorted().let { if (it.isEmpty()) 1f else it[it.size / 2] }

    /** Key that types [c], directly or through its base letter (é is typed on the e key). */
    fun keyFor(c: Char): KeyInfo? = byChar[c] ?: byChar[TextUtils.fold(c)]

    fun hasKey(c: Char): Boolean = byChar.containsKey(c)

    fun nearest(x: Float, y: Float): KeyInfo? = keys.minByOrNull { sq(it.cx - x) + sq(it.cy - y * 1f) }

    private fun sq(v: Float) = v * v
}

/**
 * Learns where this person actually hits each key, relative to the key centre, in units of
 * key width/height so the model survives keyboard resizing.
 */
class TouchModel {
    private val dx = HashMap<Char, Float>()
    private val dy = HashMap<Char, Float>()
    private val n = HashMap<Char, Int>()

    @Synchronized fun offset(c: Char): Pair<Float, Float> {
        val count = n[c] ?: return 0f to 0f
        // Shrink towards zero until enough samples exist.
        val trust = min(1f, count / 40f)
        return (dx[c] ?: 0f) * trust to (dy[c] ?: 0f) * trust
    }

    @Synchronized fun update(c: Char, relX: Float, relY: Float) {
        if (relX !in -1.2f..1.2f || relY !in -1.2f..1.2f) return
        val count = (n[c] ?: 0) + 1
        n[c] = min(count, 10_000)
        val alpha = if (count < 50) 1f / count else 0.02f
        dx[c] = (dx[c] ?: 0f) * (1 - alpha) + relX * alpha
        dy[c] = (dy[c] ?: 0f) * (1 - alpha) + relY * alpha
    }

    @Synchronized fun samples(): Int = n.values.sum()

    @Synchronized fun serialize(): String = n.keys.joinToString(";") { c -> "${c.code},${dx[c]},${dy[c]},${n[c]}" }

    @Synchronized fun restore(data: String) {
        for (part in data.split(';')) {
            val f = part.split(',')
            if (f.size != 4) continue
            val c = f[0].toIntOrNull()?.toChar() ?: continue
            dx[c] = f[1].toFloatOrNull() ?: continue
            dy[c] = f[2].toFloatOrNull() ?: continue
            n[c] = f[3].toIntOrNull() ?: continue
        }
    }

    @Synchronized fun clear() { dx.clear(); dy.clear(); n.clear() }
}

/** A typed character together with where the finger actually landed (if known). */
data class TypedChar(val char: Char, val x: Float = Float.NaN, val y: Float = Float.NaN) {
    val hasPoint get() = !x.isNaN()
}

/**
 * Converts "the finger landed here" into edit costs (log10 units) for the corrector.
 * Hitting a neighbouring key is cheap, hitting a key across the keyboard is expensive.
 */
class SpatialModel(val geometry: KeyGeometry, private val touch: TouchModel?) {

    private val sigmaX = geometry.keyWidth * 0.55f
    private val sigmaY = geometry.keyHeight * 0.6f

    fun expectedPoint(key: KeyInfo): Pair<Float, Float> {
        val (ox, oy) = touch?.offset(key.char) ?: (0f to 0f)
        return key.cx + ox * key.width to key.cy + oy * key.height
    }

    /** Negative log10 likelihood that a touch at (x, y) was meant for [key]. */
    fun touchCost(x: Float, y: Float, key: KeyInfo): Float {
        val (ex, ey) = expectedPoint(key)
        val nx = (x - ex) / sigmaX
        val ny = (y - ey) / sigmaY
        return ((nx * nx + ny * ny) / 2f / LN10).toFloat()
    }

    /** Cost of the user producing [typed] while meaning [intended]. */
    fun substitutionCost(typed: TypedChar, intended: Char): Float {
        if (typed.char == intended) return 0f
        val ft = TextUtils.fold(typed.char)
        val fi = TextUtils.fold(intended)
        if (ft == fi) return FOLD_COST
        val target = geometry.keyFor(intended) ?: return FAR_COST
        val px: Float
        val py: Float
        if (typed.hasPoint) {
            px = typed.x; py = typed.y
        } else {
            val k = geometry.keyFor(typed.char) ?: return FAR_COST
            px = k.cx; py = k.cy
        }
        val own = geometry.keyFor(typed.char)?.let { touchCost(px, py, it) } ?: 0f
        val cost = touchCost(px, py, target) - own + SUBSTITUTION_BASE
        return cost.coerceIn(SUBSTITUTION_BASE, FAR_COST)
    }

    /** Euclidean key-centre distance in key widths (used by the swipe decoder and tests). */
    fun keyDistance(a: Char, b: Char): Float {
        val ka = geometry.keyFor(a) ?: return 10f
        val kb = geometry.keyFor(b) ?: return 10f
        val dx = (ka.cx - kb.cx) / geometry.keyWidth
        val dy = (ka.cy - kb.cy) / geometry.keyHeight
        return sqrt(dx * dx + dy * dy)
    }

    companion object {
        private val LN10 = ln(10.0)
        const val FOLD_COST = 0.3f
        const val SUBSTITUTION_BASE = 0.55f
        const val FAR_COST = 4.5f
    }
}

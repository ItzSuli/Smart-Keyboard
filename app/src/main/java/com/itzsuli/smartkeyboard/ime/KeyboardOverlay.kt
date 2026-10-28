package com.itzsuli.smartkeyboard.ime

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.os.SystemClock
import android.view.View
import com.itzsuli.smartkeyboard.data.TrailStyle
import kotlin.math.max
import kotlin.math.min

/**
 * Transparent layer above the whole keyboard (suggestion strip included) that draws things
 * which must not be clipped to a single key row: key previews, long-press popups and the
 * swipe trail. It never receives touches.
 */
@SuppressLint("ViewConstructor")
class KeyboardOverlay(context: Context) : View(context) {

    var theme: KeyboardTheme = KeyboardTheme.DARK
        set(value) { field = value; invalidate() }
    var trailStyle: TrailStyle = TrailStyle.ACCENT
    var trailWidthDp: Float = 6f
    private val hsv = FloatArray(3)

    private val density = resources.displayMetrics.density
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val trailPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    // ---- key preview ----
    private var previewLabel: String? = null
    private val previewRect = RectF()

    fun showPreview(label: String, keyRect: RectF) {
        val w = max(keyRect.width() * 1.15f, 44 * density)
        val h = keyRect.height() * 1.2f
        val cx = keyRect.centerX().coerceIn(w / 2, width - w / 2f)
        previewRect.set(cx - w / 2, keyRect.top - h - 4 * density, cx + w / 2, keyRect.top - 4 * density)
        previewLabel = label
        invalidate()
    }

    fun hidePreview() {
        if (previewLabel != null) {
            previewLabel = null
            invalidate()
        }
    }

    // ---- long-press popup ----
    private var popupOptions: List<String> = emptyList()
    private val popupRect = RectF()
    private var popupCell = 0f
    var popupSelected = -1
        private set

    /** Where the finger was when the popup opened, and the option picked there. */
    private var popupAnchorX = Float.NaN
    private var popupFirst = 0

    /**
     * Shows [options] above the key. The first option sits right above the finger and is picked
     * if the finger doesn't move; the others go toward the middle of the keyboard, where there's
     * room (to the left for keys on the right half).
     */
    fun showPopup(options: List<String>, keyRect: RectF) {
        popupCell = max(keyRect.width(), 38 * density)
        val w = popupCell * options.size
        val h = keyRect.height() * 1.05f
        val leftward = options.size > 1 && keyRect.centerX() > width / 2f
        popupOptions = if (leftward) options.reversed() else options
        popupFirst = if (leftward) options.size - 1 else 0
        val firstLeft = keyRect.centerX() - popupCell / 2
        val left = (firstLeft - popupCell * popupFirst).coerceIn(4 * density, max(4 * density, width - w - 4 * density))
        popupRect.set(left, keyRect.top - h - 6 * density, left + w, keyRect.top - 6 * density)
        popupSelected = popupFirst
        popupAnchorX = Float.NaN
        invalidate()
    }

    /**
     * Updates the highlighted option for a finger at overlay x; returns the index. The first call
     * after [showPopup] marks where the finger is: from there, each key-width of sliding moves one
     * option, so the first option is always the one picked without moving.
     */
    fun selectPopupAt(x: Float): Int {
        if (popupOptions.isEmpty()) return -1
        if (popupAnchorX.isNaN()) popupAnchorX = x
        val i = (popupFirst + Math.round((x - popupAnchorX) / popupCell)).coerceIn(0, popupOptions.size - 1)
        if (i != popupSelected) {
            popupSelected = i
            invalidate()
        }
        return i
    }

    fun popupValue(): String? = popupOptions.getOrNull(popupSelected)

    fun hidePopup() {
        popupOptions = emptyList()
        popupSelected = -1
        invalidate()
    }

    val isPopupShowing get() = popupOptions.isNotEmpty()

    // ---- swipe trail ----
    private val tx = FloatArray(MAX_TRAIL)
    private val ty = FloatArray(MAX_TRAIL)
    private val tt = LongArray(MAX_TRAIL)
    private var tStart = 0
    private var tCount = 0
    private var trailReleasedAt = 0L
    var trailEnabled = true

    fun trailStart() {
        tStart = 0; tCount = 0; trailReleasedAt = 0L
    }

    fun trailAdd(x: Float, y: Float) {
        if (!trailEnabled) return
        val idx = (tStart + tCount) % MAX_TRAIL
        tx[idx] = x; ty[idx] = y; tt[idx] = SystemClock.uptimeMillis()
        if (tCount < MAX_TRAIL) tCount++ else tStart = (tStart + 1) % MAX_TRAIL
        postInvalidateOnAnimation()
    }

    fun trailEnd() {
        trailReleasedAt = SystemClock.uptimeMillis()
        postInvalidateOnAnimation()
    }

    override fun onDraw(canvas: Canvas) {
        drawTrail(canvas)
        previewLabel?.let { drawBubble(canvas, previewRect, it) }
        if (popupOptions.isNotEmpty()) drawPopup(canvas)
    }

    private fun drawBubble(canvas: Canvas, r: RectF, label: String) {
        val radius = 10 * density
        fill.color = theme.keyShadow.takeIf { it != Color.TRANSPARENT } ?: Color.argb(60, 0, 0, 0)
        canvas.drawRoundRect(r.left, r.top + 2 * density, r.right, r.bottom + 2 * density, radius, radius, fill)
        fill.color = if (theme.key == Color.TRANSPARENT || Color.alpha(theme.key) < 200) opaque(theme.keyPressed) else theme.key
        canvas.drawRoundRect(r, radius, radius, fill)
        text.color = theme.keyText
        text.textSize = r.height() * 0.5f
        canvas.drawText(label, r.centerX(), r.centerY() - (text.descent() + text.ascent()) / 2, text)
    }

    private fun drawPopup(canvas: Canvas) {
        val r = popupRect
        val radius = 12 * density
        fill.color = Color.argb(70, 0, 0, 0)
        canvas.drawRoundRect(r.left, r.top + 3 * density, r.right, r.bottom + 3 * density, radius, radius, fill)
        fill.color = opaque(if (theme.isDark) theme.functionKey else theme.key)
        canvas.drawRoundRect(r, radius, radius, fill)
        text.textSize = r.height() * 0.42f
        for ((i, opt) in popupOptions.withIndex()) {
            val cx = r.left + popupCell * (i + 0.5f)
            if (i == popupSelected) {
                fill.color = theme.accent
                canvas.drawRoundRect(cx - popupCell / 2 + 3 * density, r.top + 3 * density, cx + popupCell / 2 - 3 * density, r.bottom - 3 * density, radius * 0.7f, radius * 0.7f, fill)
                text.color = if (KeyboardTheme.isLight(theme.accent)) Color.BLACK else Color.WHITE
            } else text.color = theme.keyText
            canvas.drawText(opt, cx, r.centerY() - (text.descent() + text.ascent()) / 2, text)
        }
    }

    private val trailPath = android.graphics.Path()

    /**
     * Draws the trail as a few smooth curve chunks: each chunk is one path (so semi-transparent
     * strokes never overlap into dots), older chunks get thinner and fainter like a comet tail.
     */
    private fun drawTrail(canvas: Canvas) {
        if (tCount < 2) return
        val now = SystemClock.uptimeMillis()
        val releaseFade = if (trailReleasedAt > 0) ((now - trailReleasedAt) / RELEASE_FADE_MS.toFloat()).coerceIn(0f, 1f) else 0f
        if (releaseFade >= 1f) { tCount = 0; return }
        // Skip points that have fully faded.
        var first = 0
        while (first < tCount - 1 && now - tt[(tStart + first) % MAX_TRAIL] > TRAIL_LIFE_MS) first++
        val visible = tCount - first
        if (visible < 2) { if (trailReleasedAt > 0) postInvalidateOnAnimation(); return }
        val color = if (trailStyle == TrailStyle.MONO) theme.keyText else theme.accent
        val maxW = trailWidthDp * density
        // Rainbow needs finer pieces so the colours flow instead of stepping.
        val chunk = maxOf(if (trailStyle == TrailStyle.RAINBOW) 2 else 4, visible / if (trailStyle == TrailStyle.RAINBOW) TRAIL_CHUNKS * 3 else TRAIL_CHUNKS)
        var i = first
        while (i < tCount - 1) {
            val end = minOf(tCount - 1, i + chunk)
            val head = end == tCount - 1
            val pos = (end - first) / visible.toFloat()
            val age = now - tt[(tStart + end) % MAX_TRAIL]
            val life = 1f - (age / TRAIL_LIFE_MS.toFloat()).coerceIn(0f, 1f)
            val alpha = (255 * (0.25f + 0.75f * pos) * (0.4f + 0.6f * life) * (1f - releaseFade)).toInt()
            if (alpha > 2) {
                trailPath.reset()
                var px = tx[(tStart + i) % MAX_TRAIL]
                var py = ty[(tStart + i) % MAX_TRAIL]
                trailPath.moveTo(px, py)
                for (j in i + 1..end) {
                    val x = tx[(tStart + j) % MAX_TRAIL]
                    val y = ty[(tStart + j) % MAX_TRAIL]
                    // Quadratic curves through midpoints smooth out the touch sampling.
                    trailPath.quadTo(px, py, (px + x) / 2, (py + y) / 2)
                    px = x; py = y
                }
                trailPath.lineTo(px, py)
                trailPaint.strokeCap = if (head) Paint.Cap.ROUND else Paint.Cap.BUTT
                trailPaint.color = if (trailStyle == TrailStyle.RAINBOW) {
                    // Hue runs along the trail and slowly drifts while swiping.
                    hsv[0] = (pos * 300f + (now / 8L % 360L)) % 360f
                    hsv[1] = 0.75f
                    hsv[2] = if (theme.isDark) 1f else 0.9f
                    Color.HSVToColor(alpha, hsv)
                } else Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color))
                trailPaint.strokeWidth = maxW * (0.3f + 0.7f * pos)
                canvas.drawPath(trailPath, trailPaint)
            }
            i = end
        }
        postInvalidateOnAnimation()
    }

    private fun opaque(c: Int) = Color.rgb(Color.red(c), Color.green(c), Color.blue(c))

    companion object {
        private const val MAX_TRAIL = 512
        private const val TRAIL_LIFE_MS = 650L
        private const val RELEASE_FADE_MS = 220L
        private const val TRAIL_CHUNKS = 10
    }
}

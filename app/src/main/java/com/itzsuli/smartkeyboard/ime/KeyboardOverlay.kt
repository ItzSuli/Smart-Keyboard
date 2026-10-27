package com.itzsuli.smartkeyboard.ime

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.os.SystemClock
import android.view.View
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

    fun showPopup(options: List<String>, keyRect: RectF) {
        popupOptions = options
        popupCell = max(keyRect.width(), 38 * density)
        val w = popupCell * options.size
        val h = keyRect.height() * 1.05f
        val left = (keyRect.centerX() - popupCell / 2).coerceIn(4 * density, max(4 * density, width - w - 4 * density))
        popupRect.set(left, keyRect.top - h - 6 * density, left + w, keyRect.top - 6 * density)
        popupSelected = 0
        invalidate()
    }

    /** Updates the highlighted option for a finger at overlay x; returns the index. */
    fun selectPopupAt(x: Float): Int {
        if (popupOptions.isEmpty()) return -1
        val i = ((x - popupRect.left) / popupCell).toInt().coerceIn(0, popupOptions.size - 1)
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

    private fun drawTrail(canvas: Canvas) {
        if (tCount < 2) return
        val now = SystemClock.uptimeMillis()
        val releaseFade = if (trailReleasedAt > 0) ((now - trailReleasedAt) / RELEASE_FADE_MS.toFloat()).coerceIn(0f, 1f) else 0f
        if (releaseFade >= 1f) { tCount = 0; return }
        val color = theme.accent
        val maxW = 5.5f * density
        var animating = trailReleasedAt > 0
        for (i in 1 until tCount) {
            val a = (tStart + i - 1) % MAX_TRAIL
            val b = (tStart + i) % MAX_TRAIL
            val age = now - tt[b]
            // Older segments thin out and fade, which gives the trail its comet-like tail.
            val life = 1f - (age / TRAIL_LIFE_MS.toFloat()).coerceIn(0f, 1f)
            if (life <= 0f) continue
            if (life < 1f) animating = true
            val pos = i / tCount.toFloat()
            val alpha = (255 * life * (0.35f + 0.65f * pos) * (1f - releaseFade)).toInt()
            if (alpha <= 2) continue
            trailPaint.color = Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color))
            trailPaint.strokeWidth = maxW * (0.35f + 0.65f * min(1f, life * 1.2f)) * (0.6f + 0.4f * pos)
            canvas.drawLine(tx[a], ty[a], tx[b], ty[b], trailPaint)
        }
        if (animating) postInvalidateOnAnimation()
    }

    private fun opaque(c: Int) = Color.rgb(Color.red(c), Color.green(c), Color.blue(c))

    companion object {
        private const val MAX_TRAIL = 512
        private const val TRAIL_LIFE_MS = 650L
        private const val RELEASE_FADE_MS = 220L
    }
}

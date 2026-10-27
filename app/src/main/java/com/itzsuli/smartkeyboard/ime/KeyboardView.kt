package com.itzsuli.smartkeyboard.ime

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import com.itzsuli.smartkeyboard.data.Settings
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * Draws the keys and turns touches into actions: taps (with multi-finger rollover),
 * long-press accents, delete repeat, swipe typing, space-bar cursor control and
 * swipe-to-delete. Which key a tap means is decided by [keyResolver], so the
 * keyboard can grow the invisible hitbox of likely letters.
 */
@SuppressLint("ViewConstructor")
class KeyboardView(context: Context, private val overlay: KeyboardOverlay) : View(context) {

    interface Listener {
        fun onKeyTap(key: Key, x: Float, y: Float)
        fun onPopupText(text: String)
        fun onDelete()
        fun onDeleteWordsPreview(words: Int)
        fun onDeleteWordsCommit(words: Int)
        fun onCursorMove(steps: Int)
        fun onSwipeStart()
        fun onSwipeMove(xs: FloatArray, ys: FloatArray, count: Int)
        fun onSwipeEnd(xs: FloatArray, ys: FloatArray, count: Int)
        fun onLongPress(key: Key): Boolean
        fun onFeedback(key: Key)
        fun onShiftTap()
        fun onShiftLock()
    }

    /** Picks the intended key for a tap; returns null to use plain geometry. */
    var keyResolver: ((x: Float, y: Float, candidates: List<Key>) -> Key?)? = null

    var listener: Listener? = null
    var layout: KeyboardLayout? = null
        set(value) {
            field = value
            measureKeys()
            invalidate()
        }
    var theme: KeyboardTheme = KeyboardTheme.DARK
        set(value) { field = value; gradientShader = null; invalidate() }
    var settings: Settings? = null
        set(value) { field = value; invalidate() }

    var shiftState = ShiftState.OFF
        set(value) { if (field != value) { field = value; invalidate() } }
    var enterIcon: KeyIcons.Icon? = KeyIcons.Icon.ENTER
        set(value) { field = value; invalidate() }
    var swipeEnabled = true
    var languageLabel: String = ""
        set(value) { field = value; invalidate() }
    var backgroundBitmap: Bitmap? = null
        set(value) { field = value; invalidate() }

    enum class ShiftState { OFF, ON, LOCKED }

    private val density = resources.displayMetrics.density
    private val handler = Handler(Looper.getMainLooper())

    private val keyPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val hintPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.RIGHT }
    private val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private var gradientShader: Shader? = null
    private val drawRect = RectF()
    private val pressedKeys = HashSet<Key>()

    // ---- per-pointer touch tracking ----
    private enum class Mode { TAP, POPUP, SWIPE, SPACE_CURSOR, DELETE_SWIPE, REPEAT, DONE }

    private inner class Pointer(val id: Int, val key: Key?, val x: Float, val y: Float) {
        var mode = Mode.TAP
        var lastX = x
        var lastY = y
        val downTime = SystemClock.uptimeMillis()
        var cursorAnchor = x
        var deleteWords = 0
        var longPress: Runnable? = null
    }

    private val pointers = HashMap<Int, Pointer>()
    private var swipeXs = FloatArray(1024)
    private var swipeYs = FloatArray(1024)
    private var swipeCount = 0
    private var lastSwipeNotify = 0L
    private var lastShiftTap = 0L

    private val unit get() = layout?.let { l -> l.keys.firstOrNull { it.isLetter }?.bounds?.width() } ?: (width / 10f)

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        measureKeys()
        gradientShader = null
    }

    private fun measureKeys() {
        val l = layout ?: return
        if (width == 0 || height == 0) return
        l.measure(width.toFloat(), height.toFloat(), 3 * density, 4 * density)
    }

    // ---------------------------------------------------------------- drawing

    override fun onDraw(canvas: Canvas) {
        drawBackground(canvas)
        val l = layout ?: return
        val s = settings
        val gap = (s?.keyGapDp ?: 5) * density / 2f
        val radius = (s?.keyRadiusDp ?: 9) * density
        val showKeys = s?.keyBorders ?: true
        val labelScale = (s?.labelScalePercent ?: 100) / 100f
        for (key in l.keys) {
            drawRect.set(key.bounds.left + gap, key.bounds.top + gap * 1.15f, key.bounds.right - gap, key.bounds.bottom - gap * 1.15f)
            val pressed = key in pressedKeys
            val fillColor = when {
                pressed -> theme.keyPressed
                key.type == Key.Type.ACTION -> theme.actionKey
                key.type == Key.Type.FUNCTION -> theme.functionKey
                else -> theme.key
            }
            val drawShape = showKeys || pressed || key.type == Key.Type.ACTION
            if (drawShape) {
                if (theme.keyShadow != Color.TRANSPARENT && !pressed) {
                    keyPaint.color = theme.keyShadow
                    canvas.drawRoundRect(drawRect.left, drawRect.top + 1.2f * density, drawRect.right, drawRect.bottom + 1.2f * density, radius, radius, keyPaint)
                }
                keyPaint.color = fillColor
                canvas.drawRoundRect(drawRect, radius, radius, keyPaint)
                if (theme.keyBorder != Color.TRANSPARENT) {
                    borderPaint.color = theme.keyBorder
                    borderPaint.strokeWidth = density
                    canvas.drawRoundRect(drawRect, radius, radius, borderPaint)
                }
            }
            drawLabel(canvas, key, drawRect, labelScale)
        }
    }

    private fun drawBackground(canvas: Canvas) {
        val bmp = backgroundBitmap
        if (bmp != null) {
            // Centre-crop the user's picture and dim it so keys stay readable.
            val scale = max(width / bmp.width.toFloat(), height / bmp.height.toFloat())
            val sw = (width / scale).toInt()
            val sh = (height / scale).toInt()
            val sx = (bmp.width - sw) / 2
            val sy = (bmp.height - sh) / 2
            canvas.drawBitmap(bmp, Rect(sx, sy, sx + sw, sy + sh), Rect(0, 0, width, height), bgPaint)
            val dim = ((settings?.backgroundDim ?: 35) * 2.55f).toInt()
            canvas.drawColor(Color.argb(dim, 0, 0, 0))
            return
        }
        val end = theme.backgroundEnd
        if (end == null) {
            canvas.drawColor(theme.background)
            return
        }
        if (gradientShader == null) {
            gradientShader = LinearGradient(0f, 0f, width.toFloat(), height.toFloat(), theme.background, end, Shader.TileMode.CLAMP)
        }
        bgPaint.shader = gradientShader
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), bgPaint)
        bgPaint.shader = null
    }

    private fun drawLabel(canvas: Canvas, key: Key, r: RectF, scale: Float) {
        val textColor = when (key.type) {
            Key.Type.ACTION -> theme.actionText
            Key.Type.FUNCTION -> theme.functionText
            else -> theme.keyText
        }
        val iconSize = KeyIcons.iconSize(r)
        iconPaint.color = textColor
        when (key.code) {
            KeyCode.SHIFT -> {
                val icon = when (shiftState) {
                    ShiftState.OFF -> KeyIcons.Icon.SHIFT
                    ShiftState.ON -> KeyIcons.Icon.SHIFT_ON
                    ShiftState.LOCKED -> KeyIcons.Icon.CAPS_LOCK
                }
                if (shiftState != ShiftState.OFF) iconPaint.color = theme.accent
                KeyIcons.draw(canvas, icon, r.centerX(), r.centerY(), iconSize, iconPaint)
                return
            }
            KeyCode.DELETE -> { KeyIcons.draw(canvas, KeyIcons.Icon.DELETE, r.centerX(), r.centerY(), iconSize, iconPaint); return }
            KeyCode.EMOJI -> { KeyIcons.draw(canvas, KeyIcons.Icon.EMOJI, r.centerX(), r.centerY(), iconSize, iconPaint); return }
            KeyCode.LANGUAGE -> { KeyIcons.draw(canvas, KeyIcons.Icon.GLOBE, r.centerX(), r.centerY(), iconSize, iconPaint); return }
            KeyCode.ENTER -> {
                val icon = enterIcon
                if (icon != null) {
                    KeyIcons.draw(canvas, icon, r.centerX(), r.centerY(), iconSize, iconPaint)
                    return
                }
            }
            KeyCode.SPACE -> {
                labelPaint.color = theme.hintText
                labelPaint.typeface = Typeface.DEFAULT
                labelPaint.textSize = r.height() * 0.3f * scale
                val label = languageLabel.ifEmpty { key.label }
                canvas.drawText(label, r.centerX(), r.centerY() - (labelPaint.descent() + labelPaint.ascent()) / 2, labelPaint)
                return
            }
        }
        val label = displayLabel(key)
        labelPaint.color = textColor
        val isLetterLike = key.code == KeyCode.TEXT && label.length <= 2
        labelPaint.typeface = if (isLetterLike) Typeface.DEFAULT else Typeface.DEFAULT_BOLD
        labelPaint.textSize = r.height() * (if (isLetterLike) 0.44f else 0.3f) * scale
        val maxWidth = r.width() * 0.86f
        while (labelPaint.measureText(label) > maxWidth && labelPaint.textSize > 8 * density) labelPaint.textSize *= 0.9f
        canvas.drawText(label, r.centerX(), r.centerY() - (labelPaint.descent() + labelPaint.ascent()) / 2, labelPaint)
        val hint = key.hint
        if (hint != null) {
            hintPaint.color = theme.hintText
            hintPaint.textSize = r.height() * 0.2f * scale
            canvas.drawText(hint, r.right - r.width() * 0.12f, r.top + hintPaint.textSize * 1.15f, hintPaint)
        }
    }

    fun displayLabel(key: Key): String {
        if (key.code != KeyCode.TEXT || !key.isLetter || shiftState == ShiftState.OFF) return key.label
        val lang = layout?.lang ?: "en"
        return com.itzsuli.smartkeyboard.engine.TextUtils.uppercase(key.label, lang)
    }

    // ---------------------------------------------------------------- hit testing

    fun keyAt(x: Float, y: Float): Key? {
        val l = layout ?: return null
        var best: Key? = null
        var bestD = Float.MAX_VALUE
        for (k in l.keys) {
            if (k.bounds.contains(x, y)) return k
            val dx = max(0f, max(k.bounds.left - x, x - k.bounds.right))
            val dy = max(0f, max(k.bounds.top - y, y - k.bounds.bottom))
            val d = dx * dx + dy * dy
            if (d < bestD) { bestD = d; best = k }
        }
        return best
    }

    private fun resolveTap(p: Pointer): Key? {
        val key = p.key ?: return null
        val l = layout ?: return key
        if (!(key.isLetter || key.code == KeyCode.SPACE) || l.mode != KeyboardLayout.Mode.ALPHABET) return key
        val resolver = keyResolver ?: return key
        val candidates = l.keys.filter { it.isLetter || it.code == KeyCode.SPACE }
        return resolver(p.x, p.y, candidates) ?: key
    }

    // ---------------------------------------------------------------- touch

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val idx = event.actionIndex
                // A second finger while the first is still tapping: type the first key now.
                if (pointers.values.any { it.mode == Mode.SWIPE }) return true
                for (other in pointers.values.toList()) {
                    if (other.mode == Mode.TAP) finishTap(other)
                }
                down(event.getPointerId(idx), event.getX(idx), event.getY(idx))
            }
            MotionEvent.ACTION_MOVE -> {
                for (i in 0 until event.pointerCount) {
                    val p = pointers[event.getPointerId(i)] ?: continue
                    if (p.mode == Mode.SWIPE) {
                        for (h in 0 until event.historySize) addSwipePoint(event.getHistoricalX(i, h), event.getHistoricalY(i, h))
                    }
                    move(p, event.getX(i), event.getY(i))
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                val idx = event.actionIndex
                val p = pointers.remove(event.getPointerId(idx)) ?: return true
                up(p, event.getX(idx), event.getY(idx))
            }
            MotionEvent.ACTION_CANCEL -> {
                pointers.values.forEach { cancel(it) }
                pointers.clear()
                pressedKeys.clear()
                overlay.hidePreview(); overlay.hidePopup(); overlay.trailEnd()
                invalidate()
            }
        }
        return true
    }

    private fun down(id: Int, x: Float, y: Float) {
        val key = keyAt(x, y)
        val p = Pointer(id, key, x, y)
        pointers[id] = p
        if (key == null) return
        pressedKeys.add(key)
        listener?.onFeedback(key)
        if (settings?.keyPopup != false && key.type == Key.Type.CHAR && key.code == KeyCode.TEXT) {
            overlay.showPreview(displayLabel(key), toOverlay(key.bounds))
        }
        val delay = (settings?.longPressMs ?: 300).toLong()
        val lp = Runnable { onLongPress(p) }
        p.longPress = lp
        handler.postDelayed(lp, if (key.code == KeyCode.DELETE) DELETE_REPEAT_START else delay)
        invalidate()
    }

    private fun onLongPress(p: Pointer) {
        if (p.mode != Mode.TAP || pointers[p.id] !== p) return
        val key = p.key ?: return
        when {
            key.code == KeyCode.DELETE -> {
                p.mode = Mode.REPEAT
                repeatDelete(p, 0)
            }
            key.code == KeyCode.SHIFT -> {
                p.mode = Mode.DONE
                listener?.onShiftLock()
            }
            key.popup.isNotEmpty() -> {
                p.mode = Mode.POPUP
                overlay.hidePreview()
                val options = key.popup.map { if (shiftState != ShiftState.OFF && it.length == 1 && it[0].isLetter()) it.uppercase() else it }
                overlay.showPopup(options, toOverlay(key.bounds))
                overlay.selectPopupAt(toOverlayX(p.lastX))
                listener?.onFeedback(key)
            }
            else -> if (listener?.onLongPress(key) == true) {
                p.mode = Mode.DONE
                overlay.hidePreview()
                pressedKeys.remove(key)
                invalidate()
            }
        }
    }

    private fun repeatDelete(p: Pointer, n: Int) {
        if (pointers[p.id] !== p || p.mode != Mode.REPEAT) return
        listener?.onDelete()
        if (n % 3 == 0) p.key?.let { listener?.onFeedback(it) }
        // Speeds up the longer it is held.
        val next = when {
            n < 4 -> 90L
            n < 16 -> 55L
            else -> 35L
        }
        handler.postDelayed({ repeatDelete(p, n + 1) }, next)
    }

    private fun move(p: Pointer, x: Float, y: Float) {
        p.lastX = x; p.lastY = y
        val key = p.key ?: return
        val dx = x - p.x
        val dy = y - p.y
        when (p.mode) {
            Mode.TAP -> {
                val l = layout ?: return
                val dist = hypot(dx, dy)
                when {
                    key.isLetter && swipeEnabled && l.mode == KeyboardLayout.Mode.ALPHABET && pointers.size == 1 &&
                        dist > unit * SWIPE_START_UNITS && !key.bounds.contains(x, y) -> startSwipe(p, x, y)
                    key.code == KeyCode.SPACE && settings?.spaceCursor != false && abs(dx) > unit * 0.6f -> {
                        p.mode = Mode.SPACE_CURSOR
                        p.cursorAnchor = x
                        cancelLongPress(p)
                        overlay.hidePreview()
                    }
                    key.code == KeyCode.DELETE && settings?.deleteSwipe != false && dx < -unit * 0.7f -> {
                        p.mode = Mode.DELETE_SWIPE
                        cancelLongPress(p)
                    }
                    !swipeEnabled && key.type == Key.Type.CHAR && !key.bounds.contains(x, y) && dist > unit * 0.5f -> {
                        // Without swipe typing, sliding moves the press to the key under the finger.
                        val other = keyAt(x, y)
                        if (other != null && other != key && other.type == Key.Type.CHAR) {
                            pointers[p.id] = Pointer(p.id, other, x, y)
                            cancelLongPress(p)
                            pressedKeys.remove(key); pressedKeys.add(other)
                            overlay.showPreview(displayLabel(other), toOverlay(other.bounds))
                            invalidate()
                        }
                    }
                }
            }
            Mode.POPUP -> overlay.selectPopupAt(toOverlayX(x))
            Mode.SWIPE -> {
                addSwipePoint(x, y)
                val now = SystemClock.uptimeMillis()
                if (now - lastSwipeNotify > SWIPE_PREVIEW_MS) {
                    lastSwipeNotify = now
                    listener?.onSwipeMove(swipeXs, swipeYs, swipeCount)
                }
            }
            Mode.SPACE_CURSOR -> {
                val step = unit * 0.42f
                val steps = ((x - p.cursorAnchor) / step).toInt()
                if (steps != 0) {
                    p.cursorAnchor += steps * step
                    val rtl = layout?.rtl == true
                    listener?.onCursorMove(if (rtl) -steps else steps)
                    listener?.onFeedback(key)
                }
            }
            Mode.DELETE_SWIPE -> {
                val words = max(0, ((-dx - unit * 0.4f) / (unit * 0.9f)).toInt() + 1)
                if (words != p.deleteWords) {
                    p.deleteWords = words
                    listener?.onDeleteWordsPreview(words)
                    listener?.onFeedback(key)
                }
            }
            else -> {}
        }
    }

    private fun startSwipe(p: Pointer, x: Float, y: Float) {
        p.mode = Mode.SWIPE
        cancelLongPress(p)
        overlay.hidePreview()
        p.key?.let { pressedKeys.remove(it) }
        invalidate()
        swipeCount = 0
        overlay.trailEnabled = settings?.swipeTrail != false
        overlay.trailStart()
        addSwipePoint(p.x, p.y)
        addSwipePoint(x, y)
        listener?.onSwipeStart()
    }

    private fun addSwipePoint(x: Float, y: Float) {
        if (swipeCount > 0) {
            val px = swipeXs[swipeCount - 1]
            val py = swipeYs[swipeCount - 1]
            if (abs(px - x) < 0.5f && abs(py - y) < 0.5f) return
        }
        if (swipeCount == swipeXs.size) {
            swipeXs = swipeXs.copyOf(swipeCount * 2)
            swipeYs = swipeYs.copyOf(swipeCount * 2)
        }
        swipeXs[swipeCount] = x
        swipeYs[swipeCount] = y
        swipeCount++
        overlay.trailAdd(toOverlayX(x), toOverlayY(y))
    }

    private fun up(p: Pointer, x: Float, y: Float) {
        cancelLongPress(p)
        p.key?.let { pressedKeys.remove(it) }
        overlay.hidePreview()
        when (p.mode) {
            Mode.TAP -> finishTap(p, alreadyRemoved = true)
            Mode.POPUP -> {
                val v = overlay.popupValue()
                overlay.hidePopup()
                if (v != null) listener?.onPopupText(v)
            }
            Mode.SWIPE -> {
                addSwipePoint(x, y)
                overlay.trailEnd()
                val quick = SystemClock.uptimeMillis() - p.downTime < SLIP_MAX_MS
                if (quick && swipeLength() < unit * SLIP_MAX_UNITS) {
                    // A fast finger slip across a key border while typing is a tap, not a gesture.
                    finishTap(p, alreadyRemoved = true)
                } else {
                    listener?.onSwipeEnd(swipeXs.copyOf(swipeCount), swipeYs.copyOf(swipeCount), swipeCount)
                }
            }
            Mode.DELETE_SWIPE -> listener?.onDeleteWordsCommit(p.deleteWords)
            else -> {}
        }
        invalidate()
    }

    private fun swipeLength(): Float {
        var l = 0f
        for (i in 1 until swipeCount) l += hypot(swipeXs[i] - swipeXs[i - 1], swipeYs[i] - swipeYs[i - 1])
        return l
    }

    private fun finishTap(p: Pointer, alreadyRemoved: Boolean = false) {
        if (!alreadyRemoved) {
            cancelLongPress(p)
            p.mode = Mode.DONE
            p.key?.let { pressedKeys.remove(it) }
            overlay.hidePreview()
            invalidate()
        }
        val key = resolveTap(p) ?: return
        when (key.code) {
            KeyCode.DELETE -> listener?.onDelete()
            KeyCode.SHIFT -> {
                val now = SystemClock.uptimeMillis()
                if (now - lastShiftTap < DOUBLE_TAP_MS) listener?.onShiftLock() else listener?.onShiftTap()
                lastShiftTap = now
            }
            else -> listener?.onKeyTap(key, p.x, p.y)
        }
    }

    private fun cancel(p: Pointer) {
        cancelLongPress(p)
        p.mode = Mode.DONE
    }

    private fun cancelLongPress(p: Pointer) {
        p.longPress?.let { handler.removeCallbacks(it) }
        p.longPress = null
    }

    fun cancelAll() {
        handler.removeCallbacksAndMessages(null)
        pointers.clear()
        pressedKeys.clear()
        overlay.hidePreview()
        overlay.hidePopup()
        invalidate()
    }

    // ---------------------------------------------------------------- coordinates

    private val tmpLoc = IntArray(2)
    private val overlayLoc = IntArray(2)

    private fun offset(): Pair<Float, Float> {
        getLocationInWindow(tmpLoc)
        overlay.getLocationInWindow(overlayLoc)
        return (tmpLoc[0] - overlayLoc[0]).toFloat() to (tmpLoc[1] - overlayLoc[1]).toFloat()
    }

    private fun toOverlay(r: RectF): RectF {
        val (ox, oy) = offset()
        return RectF(r.left + ox, r.top + oy, r.right + ox, r.bottom + oy)
    }

    private fun toOverlayX(x: Float) = x + offset().first
    private fun toOverlayY(y: Float) = y + offset().second

    companion object {
        private const val SWIPE_START_UNITS = 0.55f
        private const val SWIPE_PREVIEW_MS = 90L
        private const val DOUBLE_TAP_MS = 320L
        private const val DELETE_REPEAT_START = 380L
        private const val SLIP_MAX_MS = 220L
        private const val SLIP_MAX_UNITS = 1.15f
    }
}

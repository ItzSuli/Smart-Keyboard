package com.itzsuli.smartkeyboard.ime

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.text.TextUtils as AndroidTextUtils
import android.text.TextPaint
import android.view.MotionEvent
import android.view.View
import com.itzsuli.smartkeyboard.engine.Suggestion

/**
 * The bar above the keys. While typing it shows three suggestions (best in the middle);
 * otherwise a toolbar. It can also show an AI proofreading chip or a short message.
 */
@SuppressLint("ViewConstructor")
class SuggestionStripView(context: Context) : View(context) {

    interface Listener {
        fun onSuggestionPicked(index: Int, suggestion: Suggestion)
        fun onSuggestionLongPressed(suggestion: Suggestion)
        fun onToolbarAction(action: ToolbarAction)
        fun onAiChipTapped()
        fun onAiChipDismissed()
    }

    enum class ToolbarAction { AI, CLIPBOARD, EMOJI, ONE_HANDED, INCOGNITO, SETTINGS, HIDE }

    var listener: Listener? = null
    var theme: KeyboardTheme = KeyboardTheme.DARK
        set(value) { field = value; invalidate() }
    var incognito = false
        set(value) { field = value; invalidate() }
    var aiAvailable = false
        set(value) { field = value; invalidate() }
    var aiBusy = false
        set(value) { field = value; invalidate(); if (value) postInvalidateOnAnimation() }

    private var suggestions: List<Suggestion> = emptyList()
    /** Index into [suggestions] that will be applied on space (drawn emphasised). */
    private var autoIndex = -1
    private var aiChip: String? = null
    private var message: String? = null
    private val density = resources.displayMetrics.density
    private val handler = Handler(Looper.getMainLooper())

    private val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val slots = Array(3) { RectF() }
    private val slotIndex = IntArray(3) { -1 }
    private val toolbarActions = ArrayList<Pair<ToolbarAction, RectF>>()
    private val aiButton = RectF()
    private var pressedSlot = -1
    private var downX = 0f
    private var longPressFired = false
    private val longPress = Runnable {
        val i = pressedSlot
        if (i >= 0 && slotIndex[i] >= 0) {
            longPressFired = true
            listener?.onSuggestionLongPressed(suggestions[slotIndex[i]])
        }
    }

    fun setSuggestions(list: List<Suggestion>, autoCorrectIndex: Int) {
        suggestions = list
        autoIndex = autoCorrectIndex
        invalidate()
    }

    fun clearSuggestions() = setSuggestions(emptyList(), -1)

    val hasSuggestions get() = suggestions.isNotEmpty()

    fun showAiChip(text: String?) {
        aiChip = text
        invalidate()
    }

    fun showMessage(text: String, durationMs: Long = 2200) {
        message = text
        invalidate()
        handler.removeCallbacksAndMessages(MESSAGE_TOKEN)
        handler.postAtTime({ message = null; invalidate() }, MESSAGE_TOKEN, android.os.SystemClock.uptimeMillis() + durationMs)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), (44 * density).toInt())
    }

    override fun onDraw(canvas: Canvas) {
        val h = height.toFloat()
        val w = width.toFloat()
        // AI button on the left.
        val btn = h * 0.78f
        aiButton.set(6 * density, (h - btn) / 2, 6 * density + btn, (h + btn) / 2)
        fill.color = if (aiAvailable) withAlpha(theme.accent, 46) else withAlpha(theme.stripText, 20)
        canvas.drawRoundRect(aiButton, btn / 2, btn / 2, fill)
        iconPaint.color = if (aiAvailable) theme.accent else withAlpha(theme.stripText, 140)
        val pulse = if (aiBusy) 0.85f + 0.15f * kotlin.math.sin(android.os.SystemClock.uptimeMillis() / 120.0).toFloat() else 1f
        KeyIcons.draw(canvas, KeyIcons.Icon.SPARKLE, aiButton.centerX(), aiButton.centerY(), btn * 0.62f * pulse, iconPaint)
        if (aiBusy) postInvalidateOnAnimation()

        val left = aiButton.right + 4 * density
        val msg = message
        val chip = aiChip
        when {
            msg != null -> drawCentered(canvas, msg, left, w, theme.stripText, false)
            chip != null -> drawChip(canvas, chip, left, w)
            suggestions.isNotEmpty() -> drawSuggestions(canvas, left, w)
            else -> drawToolbar(canvas, left, w)
        }
    }

    private fun drawCentered(canvas: Canvas, text: String, left: Float, right: Float, color: Int, bold: Boolean) {
        textPaint.color = color
        textPaint.typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        textPaint.textSize = 15 * density
        val avail = right - left - 16 * density
        val t = AndroidTextUtils.ellipsize(text, textPaint, avail, AndroidTextUtils.TruncateAt.END).toString()
        canvas.drawText(t, (left + right) / 2, height / 2f - (textPaint.descent() + textPaint.ascent()) / 2, textPaint)
    }

    private val chipRect = RectF()
    private val chipClose = RectF()

    private fun drawChip(canvas: Canvas, text: String, left: Float, right: Float) {
        val h = height.toFloat()
        chipRect.set(left + 2 * density, h * 0.14f, right - 6 * density, h * 0.86f)
        fill.color = withAlpha(theme.accent, 52)
        canvas.drawRoundRect(chipRect, chipRect.height() / 2, chipRect.height() / 2, fill)
        val closeSize = chipRect.height()
        chipClose.set(chipRect.right - closeSize, chipRect.top, chipRect.right, chipRect.bottom)
        iconPaint.color = withAlpha(theme.stripText, 170)
        KeyIcons.draw(canvas, KeyIcons.Icon.CLOSE, chipClose.centerX(), chipClose.centerY(), closeSize * 0.5f, iconPaint)
        textPaint.color = theme.stripText
        textPaint.typeface = Typeface.DEFAULT
        textPaint.textSize = 14 * density
        val avail = chipRect.width() - closeSize - 18 * density
        val t = AndroidTextUtils.ellipsize(text, textPaint, avail, AndroidTextUtils.TruncateAt.END).toString()
        textPaint.textAlign = Paint.Align.LEFT
        canvas.drawText(t, chipRect.left + 14 * density, h / 2 - (textPaint.descent() + textPaint.ascent()) / 2, textPaint)
        textPaint.textAlign = Paint.Align.CENTER
    }

    private fun drawSuggestions(canvas: Canvas, left: Float, right: Float) {
        val h = height.toFloat()
        val slotW = (right - left) / 3
        // Display order: second best | best | third best.
        val order = intArrayOf(1, 0, 2)
        for (i in 0..2) {
            slots[i].set(left + slotW * i, 0f, left + slotW * (i + 1), h)
            slotIndex[i] = if (order[i] < suggestions.size) order[i] else -1
        }
        if (suggestions.size == 1) { slotIndex[0] = -1; slotIndex[2] = -1 }
        for (i in 0..2) {
            val idx = slotIndex[i]
            if (i == pressedSlot && idx >= 0) {
                fill.color = withAlpha(theme.stripText, 28)
                canvas.drawRoundRect(slots[i].left + 2 * density, h * 0.12f, slots[i].right - 2 * density, h * 0.88f, 10 * density, 10 * density, fill)
            }
            if (i > 0) {
                fill.color = withAlpha(theme.stripText, 40)
                canvas.drawRect(slots[i].left, h * 0.3f, slots[i].left + density, h * 0.7f, fill)
            }
            if (idx < 0) continue
            val s = suggestions[idx]
            val emphasised = idx == autoIndex
            textPaint.typeface = if (emphasised || (idx == 0 && autoIndex < 0)) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
            textPaint.color = when {
                emphasised -> theme.accent
                s.kind == Suggestion.Kind.AI -> theme.accent
                else -> theme.stripText
            }
            textPaint.textSize = 16 * density
            var label = s.text
            if (s.kind == Suggestion.Kind.TYPED) label = "“$label”"
            if (s.kind == Suggestion.Kind.AI) label = "✦ $label"
            val t = AndroidTextUtils.ellipsize(label, textPaint, slotW - 10 * density, AndroidTextUtils.TruncateAt.MIDDLE).toString()
            canvas.drawText(t, slots[i].centerX(), h / 2 - (textPaint.descent() + textPaint.ascent()) / 2, textPaint)
        }
    }

    private fun drawToolbar(canvas: Canvas, left: Float, right: Float) {
        toolbarActions.clear()
        val actions = listOf(ToolbarAction.CLIPBOARD, ToolbarAction.EMOJI, ToolbarAction.ONE_HANDED, ToolbarAction.INCOGNITO, ToolbarAction.SETTINGS, ToolbarAction.HIDE)
        val h = height.toFloat()
        val cell = (right - left) / actions.size
        for ((i, a) in actions.withIndex()) {
            val r = RectF(left + cell * i, 0f, left + cell * (i + 1), h)
            toolbarActions.add(a to r)
            val active = a == ToolbarAction.INCOGNITO && incognito
            if (active) {
                fill.color = withAlpha(theme.accent, 50)
                canvas.drawCircle(r.centerX(), r.centerY(), h * 0.36f, fill)
            }
            iconPaint.color = if (active) theme.accent else withAlpha(theme.stripText, 190)
            val icon = when (a) {
                ToolbarAction.CLIPBOARD -> KeyIcons.Icon.CLIPBOARD
                ToolbarAction.EMOJI -> KeyIcons.Icon.EMOJI
                ToolbarAction.ONE_HANDED -> KeyIcons.Icon.ONE_HAND
                ToolbarAction.INCOGNITO -> KeyIcons.Icon.INCOGNITO
                ToolbarAction.SETTINGS -> KeyIcons.Icon.SETTINGS
                ToolbarAction.HIDE -> KeyIcons.Icon.KEYBOARD
                ToolbarAction.AI -> KeyIcons.Icon.SPARKLE
            }
            KeyIcons.draw(canvas, icon, r.centerX(), r.centerY(), h * 0.46f, iconPaint)
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = e.x
                longPressFired = false
                pressedSlot = if (message == null && aiChip == null && suggestions.isNotEmpty()) slots.indexOfFirst { it.contains(e.x, e.y) } else -1
                if (pressedSlot >= 0) handler.postDelayed(longPress, 450)
                invalidate()
            }
            MotionEvent.ACTION_UP -> {
                handler.removeCallbacks(longPress)
                val slot = pressedSlot
                pressedSlot = -1
                invalidate()
                if (longPressFired) return true
                val x = e.x
                val y = e.y
                when {
                    aiButton.contains(x, y) || (x < aiButton.right + 4 * density) -> listener?.onToolbarAction(ToolbarAction.AI)
                    message != null -> { message = null; invalidate() }
                    aiChip != null -> if (chipClose.contains(x, y)) listener?.onAiChipDismissed() else listener?.onAiChipTapped()
                    slot >= 0 && slots[slot].contains(x, y) -> {
                        val idx = slotIndex[slot]
                        if (idx >= 0) listener?.onSuggestionPicked(idx, suggestions[idx])
                    }
                    suggestions.isEmpty() -> toolbarActions.firstOrNull { it.second.contains(x, y) }?.let { listener?.onToolbarAction(it.first) }
                }
            }
            MotionEvent.ACTION_CANCEL -> {
                handler.removeCallbacks(longPress)
                pressedSlot = -1
                invalidate()
            }
        }
        return true
    }

    private fun withAlpha(c: Int, a: Int) = Color.argb(a, Color.red(c), Color.green(c), Color.blue(c))

    companion object {
        private val MESSAGE_TOKEN = Any()
    }
}

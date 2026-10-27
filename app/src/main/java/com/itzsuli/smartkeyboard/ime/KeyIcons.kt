package com.itzsuli.smartkeyboard.ime

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import kotlin.math.min

/** Crisp vector icons for function keys (font glyphs look different on every phone). */
object KeyIcons {
    enum class Icon { SHIFT, SHIFT_ON, CAPS_LOCK, DELETE, ENTER, SEARCH, SEND, DONE, NEXT, GLOBE, EMOJI, SPARKLE, CLIPBOARD, SETTINGS, KEYBOARD, MIC, UNDO, ONE_HAND, CLOSE, INCOGNITO }

    private val path = Path()

    fun draw(canvas: Canvas, icon: Icon, cx: Float, cy: Float, size: Float, paint: Paint) {
        val s = size / 2f
        val oldStyle = paint.style
        val oldWidth = paint.strokeWidth
        paint.strokeWidth = size * 0.085f
        paint.strokeCap = Paint.Cap.ROUND
        paint.strokeJoin = Paint.Join.ROUND
        paint.style = Paint.Style.STROKE
        path.reset()
        when (icon) {
            Icon.SHIFT, Icon.SHIFT_ON, Icon.CAPS_LOCK -> {
                path.moveTo(cx, cy - s * 0.85f)
                path.lineTo(cx + s * 0.75f, cy - s * 0.05f)
                path.lineTo(cx + s * 0.35f, cy - s * 0.05f)
                path.lineTo(cx + s * 0.35f, cy + s * 0.5f)
                path.lineTo(cx - s * 0.35f, cy + s * 0.5f)
                path.lineTo(cx - s * 0.35f, cy - s * 0.05f)
                path.lineTo(cx - s * 0.75f, cy - s * 0.05f)
                path.close()
                if (icon != Icon.SHIFT) paint.style = Paint.Style.FILL_AND_STROKE
                canvas.drawPath(path, paint)
                if (icon == Icon.CAPS_LOCK) {
                    paint.style = Paint.Style.STROKE
                    canvas.drawLine(cx - s * 0.4f, cy + s * 0.85f, cx + s * 0.4f, cy + s * 0.85f, paint)
                }
            }
            Icon.DELETE -> {
                path.moveTo(cx - s * 0.95f, cy)
                path.lineTo(cx - s * 0.45f, cy - s * 0.6f)
                path.lineTo(cx + s * 0.9f, cy - s * 0.6f)
                path.lineTo(cx + s * 0.9f, cy + s * 0.6f)
                path.lineTo(cx - s * 0.45f, cy + s * 0.6f)
                path.close()
                canvas.drawPath(path, paint)
                val x0 = cx + s * 0.22f
                canvas.drawLine(x0 - s * 0.25f, cy - s * 0.25f, x0 + s * 0.25f, cy + s * 0.25f, paint)
                canvas.drawLine(x0 - s * 0.25f, cy + s * 0.25f, x0 + s * 0.25f, cy - s * 0.25f, paint)
            }
            Icon.ENTER -> {
                path.moveTo(cx + s * 0.7f, cy - s * 0.6f)
                path.lineTo(cx + s * 0.7f, cy + s * 0.1f)
                path.lineTo(cx - s * 0.7f, cy + s * 0.1f)
                canvas.drawPath(path, paint)
                path.reset()
                path.moveTo(cx - s * 0.3f, cy - s * 0.3f)
                path.lineTo(cx - s * 0.7f, cy + s * 0.1f)
                path.lineTo(cx - s * 0.3f, cy + s * 0.5f)
                canvas.drawPath(path, paint)
            }
            Icon.SEARCH -> {
                canvas.drawCircle(cx - s * 0.15f, cy - s * 0.15f, s * 0.5f, paint)
                canvas.drawLine(cx + s * 0.22f, cy + s * 0.22f, cx + s * 0.7f, cy + s * 0.7f, paint)
            }
            Icon.SEND -> {
                path.moveTo(cx - s * 0.75f, cy - s * 0.65f)
                path.lineTo(cx + s * 0.8f, cy)
                path.lineTo(cx - s * 0.75f, cy + s * 0.65f)
                path.lineTo(cx - s * 0.45f, cy)
                path.close()
                paint.style = Paint.Style.FILL_AND_STROKE
                canvas.drawPath(path, paint)
            }
            Icon.DONE -> {
                path.moveTo(cx - s * 0.65f, cy)
                path.lineTo(cx - s * 0.2f, cy + s * 0.45f)
                path.lineTo(cx + s * 0.7f, cy - s * 0.5f)
                canvas.drawPath(path, paint)
            }
            Icon.NEXT -> {
                canvas.drawLine(cx - s * 0.7f, cy, cx + s * 0.6f, cy, paint)
                path.moveTo(cx + s * 0.15f, cy - s * 0.45f)
                path.lineTo(cx + s * 0.65f, cy)
                path.lineTo(cx + s * 0.15f, cy + s * 0.45f)
                canvas.drawPath(path, paint)
            }
            Icon.GLOBE -> {
                canvas.drawCircle(cx, cy, s * 0.75f, paint)
                canvas.drawOval(RectF(cx - s * 0.32f, cy - s * 0.75f, cx + s * 0.32f, cy + s * 0.75f), paint)
                canvas.drawLine(cx - s * 0.75f, cy, cx + s * 0.75f, cy, paint)
            }
            Icon.EMOJI -> {
                canvas.drawCircle(cx, cy, s * 0.75f, paint)
                paint.style = Paint.Style.FILL
                canvas.drawCircle(cx - s * 0.27f, cy - s * 0.18f, s * 0.09f, paint)
                canvas.drawCircle(cx + s * 0.27f, cy - s * 0.18f, s * 0.09f, paint)
                paint.style = Paint.Style.STROKE
                canvas.drawArc(RectF(cx - s * 0.4f, cy - s * 0.25f, cx + s * 0.4f, cy + s * 0.45f), 20f, 140f, false, paint)
            }
            Icon.SPARKLE -> {
                paint.style = Paint.Style.FILL
                star(cx - s * 0.12f, cy + s * 0.08f, s * 0.72f)
                canvas.drawPath(path, paint)
                path.reset()
                star(cx + s * 0.58f, cy - s * 0.55f, s * 0.3f)
                canvas.drawPath(path, paint)
            }
            Icon.CLIPBOARD -> {
                canvas.drawRoundRect(RectF(cx - s * 0.55f, cy - s * 0.6f, cx + s * 0.55f, cy + s * 0.8f), s * 0.12f, s * 0.12f, paint)
                canvas.drawRoundRect(RectF(cx - s * 0.25f, cy - s * 0.8f, cx + s * 0.25f, cy - s * 0.45f), s * 0.08f, s * 0.08f, paint)
                canvas.drawLine(cx - s * 0.28f, cy, cx + s * 0.28f, cy, paint)
                canvas.drawLine(cx - s * 0.28f, cy + s * 0.35f, cx + s * 0.15f, cy + s * 0.35f, paint)
            }
            Icon.SETTINGS -> {
                canvas.drawCircle(cx, cy, s * 0.28f, paint)
                for (i in 0 until 8) {
                    val a = Math.toRadians(i * 45.0)
                    val c = kotlin.math.cos(a).toFloat()
                    val d = kotlin.math.sin(a).toFloat()
                    canvas.drawLine(cx + c * s * 0.5f, cy + d * s * 0.5f, cx + c * s * 0.78f, cy + d * s * 0.78f, paint)
                }
                canvas.drawCircle(cx, cy, s * 0.52f, paint)
            }
            Icon.KEYBOARD -> {
                canvas.drawRoundRect(RectF(cx - s * 0.85f, cy - s * 0.55f, cx + s * 0.85f, cy + s * 0.55f), s * 0.15f, s * 0.15f, paint)
                paint.style = Paint.Style.FILL
                for (r in 0..1) for (c in 0..3) {
                    canvas.drawCircle(cx - s * 0.48f + c * s * 0.32f, cy - s * 0.22f + r * s * 0.3f, s * 0.06f, paint)
                }
                canvas.drawRect(cx - s * 0.35f, cy + s * 0.28f, cx + s * 0.35f, cy + s * 0.36f, paint)
            }
            Icon.MIC -> {
                canvas.drawRoundRect(RectF(cx - s * 0.25f, cy - s * 0.75f, cx + s * 0.25f, cy + s * 0.2f), s * 0.25f, s * 0.25f, paint)
                canvas.drawArc(RectF(cx - s * 0.5f, cy - s * 0.35f, cx + s * 0.5f, cy + s * 0.45f), 0f, 180f, false, paint)
                canvas.drawLine(cx, cy + s * 0.45f, cx, cy + s * 0.75f, paint)
            }
            Icon.UNDO -> {
                canvas.drawArc(RectF(cx - s * 0.55f, cy - s * 0.4f, cx + s * 0.65f, cy + s * 0.7f), 200f, 250f, false, paint)
                path.moveTo(cx - s * 0.75f, cy - s * 0.35f)
                path.lineTo(cx - s * 0.5f, cy + s * 0.05f)
                path.lineTo(cx - s * 0.12f, cy - s * 0.25f)
                canvas.drawPath(path, paint)
            }
            Icon.ONE_HAND -> {
                canvas.drawRoundRect(RectF(cx - s * 0.4f, cy - s * 0.8f, cx + s * 0.4f, cy + s * 0.8f), s * 0.15f, s * 0.15f, paint)
                canvas.drawLine(cx - s * 0.15f, cy + s * 0.55f, cx + s * 0.15f, cy + s * 0.55f, paint)
            }
            Icon.CLOSE -> {
                canvas.drawLine(cx - s * 0.5f, cy - s * 0.5f, cx + s * 0.5f, cy + s * 0.5f, paint)
                canvas.drawLine(cx - s * 0.5f, cy + s * 0.5f, cx + s * 0.5f, cy - s * 0.5f, paint)
            }
            Icon.INCOGNITO -> {
                canvas.drawCircle(cx - s * 0.38f, cy + s * 0.3f, s * 0.25f, paint)
                canvas.drawCircle(cx + s * 0.38f, cy + s * 0.3f, s * 0.25f, paint)
                canvas.drawLine(cx - s * 0.13f, cy + s * 0.3f, cx + s * 0.13f, cy + s * 0.3f, paint)
                path.moveTo(cx - s * 0.8f, cy - s * 0.1f)
                path.lineTo(cx + s * 0.8f, cy - s * 0.1f)
                path.moveTo(cx - s * 0.5f, cy - s * 0.1f)
                path.lineTo(cx - s * 0.35f, cy - s * 0.7f)
                path.lineTo(cx + s * 0.35f, cy - s * 0.7f)
                path.lineTo(cx + s * 0.5f, cy - s * 0.1f)
                canvas.drawPath(path, paint)
            }
        }
        paint.style = oldStyle
        paint.strokeWidth = oldWidth
    }

    private fun star(cx: Float, cy: Float, r: Float) {
        val i = r * 0.28f
        path.moveTo(cx, cy - r)
        path.quadTo(cx + i * 0.3f, cy - i * 0.3f, cx + r, cy)
        path.quadTo(cx + i * 0.3f, cy + i * 0.3f, cx, cy + r)
        path.quadTo(cx - i * 0.3f, cy + i * 0.3f, cx - r, cy)
        path.quadTo(cx - i * 0.3f, cy - i * 0.3f, cx, cy - r)
        path.close()
    }

    fun iconSize(rect: RectF): Float = min(rect.width(), rect.height()) * 0.42f
}

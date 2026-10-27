package com.itzsuli.smartkeyboard.ime

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextUtils
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.text.style.StrikethroughSpan
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import com.itzsuli.smartkeyboard.ai.Prompts
import com.itzsuli.smartkeyboard.data.Clip
import com.itzsuli.smartkeyboard.data.ClipboardHistory
import com.itzsuli.smartkeyboard.data.Languages
import com.itzsuli.smartkeyboard.engine.TextDiff

private fun Context.dp(v: Float) = v * resources.displayMetrics.density

private fun pill(context: Context, text: String, theme: KeyboardTheme, accent: Boolean = false, onClick: () -> Unit) =
    TextView(context).apply {
        this.text = text
        gravity = Gravity.CENTER
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        typeface = Typeface.DEFAULT_BOLD
        val padH = context.dp(14f).toInt()
        setPadding(padH, context.dp(8f).toInt(), padH, context.dp(8f).toInt())
        setTextColor(if (accent) theme.actionText else theme.functionText)
        background = GradientDrawable().apply {
            cornerRadius = context.dp(20f)
            setColor(if (accent) theme.accent else theme.functionKey)
        }
        setOnClickListener { onClick() }
    }

private fun header(context: Context, title: String, theme: KeyboardTheme, onClose: () -> Unit): LinearLayout =
    LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(context.dp(12f).toInt(), context.dp(6f).toInt(), context.dp(6f).toInt(), context.dp(2f).toInt())
        addView(TextView(context).apply {
            text = title
            setTextColor(theme.keyText)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            typeface = Typeface.DEFAULT_BOLD
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        addView(pill(context, "ABC", theme) { onClose() })
    }

/** Copied texts: tap to paste, long-press to pin or remove. */
@SuppressLint("ViewConstructor")
class ClipboardPanel(
    context: Context,
    private val history: ClipboardHistory,
    private val theme: KeyboardTheme,
    private val onPaste: (String) -> Unit,
    private val onClose: () -> Unit,
) : LinearLayout(context) {
    private val list = LinearLayout(context).apply { orientation = VERTICAL }

    init {
        orientation = VERTICAL
        setBackgroundColor(theme.background)
        addView(header(context, "Clipboard", theme, onClose).apply {
            addView(pill(context, "Clear", theme) { history.clearUnpinned(); refresh() }, 1)
        })
        val scroll = ScrollView(context)
        scroll.addView(list)
        addView(scroll, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
        refresh()
    }

    private fun refresh() {
        list.removeAllViews()
        val clips = history.all()
        if (clips.isEmpty()) {
            list.addView(TextView(context).apply {
                text = "Things you copy show up here.\nLong-press a clip to pin it."
                setTextColor(theme.hintText)
                gravity = Gravity.CENTER
                setPadding(0, context.dp(40f).toInt(), 0, 0)
            })
            return
        }
        for (c in clips) list.addView(item(c))
    }

    private fun item(c: Clip): View = TextView(context).apply {
        text = (if (c.pinned) "📌 " else "") + c.text
        maxLines = 3
        ellipsize = TextUtils.TruncateAt.END
        setTextColor(theme.keyText)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        val p = context.dp(12f).toInt()
        setPadding(p, p, p, p)
        background = GradientDrawable().apply { cornerRadius = context.dp(12f); setColor(theme.key) }
        layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
            setMargins(context.dp(8f).toInt(), context.dp(4f).toInt(), context.dp(8f).toInt(), context.dp(4f).toInt())
        }
        setOnClickListener { onPaste(c.text) }
        setOnLongClickListener {
            history.togglePin(c)
            refresh()
            true
        }
    }
}

/**
 * The AI panel: proofread or rewrite what is in the text field, see exactly what changes,
 * then apply it with one tap.
 */
@SuppressLint("ViewConstructor")
class AiPanel(
    context: Context,
    private val theme: KeyboardTheme,
    private val languages: List<String>,
    private val listener: Listener,
) : LinearLayout(context) {

    interface Listener {
        fun onAiProofread()
        fun onAiRewrite(mode: Prompts.Rewrite)
        fun onAiTranslate(lang: String)
        fun onAiApply(result: String)
        fun onAiOpenSettings()
        fun onAiClose()
    }

    private val body = LinearLayout(context).apply { orientation = VERTICAL }
    private var lastResult: String? = null

    init {
        orientation = VERTICAL
        setBackgroundColor(theme.background)
        addView(header(context, "✨ AI", theme) { listener.onAiClose() })
        val scroll = ScrollView(context)
        scroll.addView(body)
        addView(scroll, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
    }

    fun showActions(configured: Boolean) {
        body.removeAllViews()
        if (!configured) {
            body.addView(note("Connect an AI provider to proofread and rewrite your messages. Google Gemini has a free tier."))
            body.addView(row(pill(context, "Open AI settings", theme, accent = true) { listener.onAiOpenSettings() }))
            return
        }
        body.addView(note("Works on the text you're typing (or the selected text)."))
        body.addView(row(pill(context, "✨ Fix it (chill)", theme, accent = true) { listener.onAiProofread() }))
        body.addView(scrollRow(Prompts.Rewrite.entries.map { m -> pill(context, m.label, theme) { listener.onAiRewrite(m) } }))
        val targets = (languages + listOf("en", "de", "ar")).distinct()
        body.addView(scrollRow(targets.map { code -> pill(context, "→ ${Languages.get(code).nativeName}", theme) { listener.onAiTranslate(code) } }))
    }

    fun showLoading(what: String) {
        body.removeAllViews()
        body.addView(LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, context.dp(36f).toInt(), 0, 0)
            addView(ProgressBar(context).apply { isIndeterminate = true }, LayoutParams(context.dp(28f).toInt(), context.dp(28f).toInt()))
            addView(TextView(context).apply {
                text = "  $what…"
                setTextColor(theme.keyText)
            })
        })
    }

    fun showResult(before: String, after: String) {
        lastResult = after
        body.removeAllViews()
        if (before == after) {
            body.addView(note("Looks good already — nothing to fix ✓"))
            body.addView(row(pill(context, "Back", theme) { showActions(true) }))
            return
        }
        body.addView(TextView(context).apply {
            text = highlight(before, after)
            setTextColor(theme.keyText)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            val p = context.dp(12f).toInt()
            setPadding(p, p, p, p)
            background = GradientDrawable().apply { cornerRadius = context.dp(14f); setColor(theme.key) }
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                setMargins(context.dp(10f).toInt(), context.dp(6f).toInt(), context.dp(10f).toInt(), context.dp(6f).toInt())
            }
        })
        body.addView(row(
            pill(context, "Apply", theme, accent = true) { listener.onAiApply(after) },
            pill(context, "Back", theme) { showActions(true) },
        ))
    }

    fun showError(message: String) {
        body.removeAllViews()
        body.addView(note("Couldn't reach the AI: $message"))
        body.addView(row(pill(context, "Back", theme) { showActions(true) }, pill(context, "AI settings", theme) { listener.onAiOpenSettings() }))
    }

    /** Removed words struck through, new words highlighted in the accent colour. */
    private fun highlight(before: String, after: String): CharSequence {
        val sb = SpannableStringBuilder()
        val bg = Color.argb(60, Color.red(theme.accent), Color.green(theme.accent), Color.blue(theme.accent))
        for (s in TextDiff.diff(before, after)) when (s) {
            is TextDiff.Segment.Same -> sb.append(s.text)
            is TextDiff.Segment.Changed -> {
                if (s.before.isNotBlank()) {
                    val st = sb.length
                    sb.append(s.before)
                    sb.setSpan(StrikethroughSpan(), st, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    sb.setSpan(ForegroundColorSpan(theme.hintText), st, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    if (s.after.isNotEmpty()) sb.append(" ")
                }
                val st = sb.length
                sb.append(s.after)
                sb.setSpan(BackgroundColorSpan(bg), st, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                sb.setSpan(ForegroundColorSpan(theme.accent), st, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }
        return sb
    }

    private fun note(text: String) = TextView(context).apply {
        this.text = text
        setTextColor(theme.hintText)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        setPadding(context.dp(14f).toInt(), context.dp(8f).toInt(), context.dp(14f).toInt(), context.dp(4f).toInt())
    }

    private fun row(vararg views: View) = LinearLayout(context).apply {
        orientation = HORIZONTAL
        setPadding(context.dp(10f).toInt(), context.dp(6f).toInt(), context.dp(10f).toInt(), context.dp(6f).toInt())
        for (v in views) addView(v, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply { marginEnd = context.dp(8f).toInt() })
    }

    private fun scrollRow(views: List<View>) = HorizontalScrollView(context).apply {
        isHorizontalScrollBarEnabled = false
        addView(row(*views.toTypedArray()))
    }
}

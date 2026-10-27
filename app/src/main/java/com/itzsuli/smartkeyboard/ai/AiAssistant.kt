package com.itzsuli.smartkeyboard.ai

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.itzsuli.smartkeyboard.SmartKeyboardApp
import com.itzsuli.smartkeyboard.data.Languages
import com.itzsuli.smartkeyboard.data.Settings
import com.itzsuli.smartkeyboard.engine.TextDiff
import com.itzsuli.smartkeyboard.engine.TextUtils
import com.itzsuli.smartkeyboard.engine.UserLexicon
import org.json.JSONArray
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * Runs every AI request off the main thread and feeds results back into learning:
 * the user's own words are sent along so the AI never "fixes" their slang, and typo fixes
 * the user accepts teach the on-device autocorrect.
 */
class AiAssistant(private val context: Context, private val settings: Settings) {
    private val executor = Executors.newFixedThreadPool(2) { r -> Thread(r, "ai").apply { isDaemon = true } }
    private val main = Handler(Looper.getMainLooper())
    private val predictionGeneration = AtomicInteger()
    private val predictionCache = object : LinkedHashMap<String, List<String>>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<String>>?) = size > 64
    }
    private var cachedProvider: Pair<String, AiProvider>? = null

    val isConfigured: Boolean get() = settings.aiEnabled

    private fun provider(): AiProvider {
        val preset = ProviderPreset.of(settings.aiProvider)
        val signature = listOf(preset.id, settings.aiApiKey, settings.aiModel, settings.aiBaseUrl).joinToString("|")
        cachedProvider?.let { if (it.first == signature) return it.second }
        if (settings.aiApiKey.isBlank() && preset != ProviderPreset.CUSTOM) throw AiException("Add an API key in AI settings")
        val p = AiProviders.create(preset, settings.aiApiKey, settings.aiModel, settings.aiBaseUrl)
        cachedProvider = signature to p
        return p
    }

    private fun slangFor(lang: String): List<String> {
        val lexicon = SmartKeyboardApp.of(context).languages.lexicon(lang)
        return lexicon.allWords()
            .filter { (it.isSlang || it.isManual) && it.isConfirmed && !it.isBlocked }
            .sortedByDescending { it.count }
            .take(60)
            .map { it.display }
    }

    private fun <T> run(task: () -> T, onResult: (Result<T>) -> Unit) {
        executor.execute {
            val r = runCatching(task)
            main.post { onResult(r) }
        }
    }

    fun proofread(text: String, lang: String, onResult: (Result<String>) -> Unit) = run({
        val language = Languages.get(lang).englishName
        val out = provider().complete(Prompts.proofread(language, slangFor(lang)), Prompts.wrap(text), maxTokensFor(text), fast = false)
        Prompts.clean(out).ifEmpty { text }
    }, onResult)

    fun rewrite(text: String, mode: Prompts.Rewrite, lang: String, onResult: (Result<String>) -> Unit) = run({
        val language = Languages.get(lang).englishName
        Prompts.clean(provider().complete(Prompts.rewrite(mode, language, slangFor(lang)), Prompts.wrap(text), maxTokensFor(text) * 2, fast = false))
    }, onResult)

    fun translate(text: String, targetLang: String, onResult: (Result<String>) -> Unit) = run({
        Prompts.clean(provider().complete(Prompts.translate(Languages.get(targetLang).englishName), Prompts.wrap(text), maxTokensFor(text) * 2, fast = false))
    }, onResult)

    /**
     * Context-aware next-word ideas. Only the latest request is delivered, so fast typing
     * never shows stale words; results are cached per context.
     */
    fun predictNext(textBefore: String, lang: String, onResult: (List<String>) -> Unit) {
        val gen = predictionGeneration.incrementAndGet()
        val key = "$lang|${textBefore.takeLast(160)}"
        synchronized(predictionCache) { predictionCache[key] }?.let { onResult(it); return }
        executor.execute {
            if (gen != predictionGeneration.get()) return@execute
            val words = runCatching {
                val language = Languages.get(lang).englishName
                val out = provider().complete(Prompts.nextWords(language, slangFor(lang)), textBefore.takeLast(400), 40, fast = true)
                parseWords(out)
            }.getOrDefault(emptyList())
            synchronized(predictionCache) { if (words.isNotEmpty()) predictionCache[key] = words }
            main.post { if (gen == predictionGeneration.get() && words.isNotEmpty()) onResult(words) }
        }
    }

    fun cancelPredictions() { predictionGeneration.incrementAndGet() }

    private fun parseWords(out: String): List<String> {
        val start = out.indexOf('[')
        val end = out.lastIndexOf(']')
        if (start < 0 || end <= start) return emptyList()
        val arr = runCatching { JSONArray(out.substring(start, end + 1)) }.getOrNull() ?: return emptyList()
        return (0 until arr.length()).mapNotNull { arr.optString(it).trim().takeIf { w -> w.isNotEmpty() && w.length < 30 } }.take(3)
    }

    /**
     * The user applied an AI fix: single-word typo fixes become personal autocorrect rules.
     * Words the user typed on purpose (known words, slang) are never turned into rules.
     */
    fun learnFromAcceptedFix(before: String, after: String, lexicon: UserLexicon, isKnownWord: (String) -> Boolean) {
        for ((old, new) in TextDiff.wordFixes(before, after)) {
            val oldKey = TextUtils.key(old, lexicon.lang)
            val newKey = TextUtils.key(new, lexicon.lang)
            if (oldKey == newKey || isKnownWord(oldKey)) continue
            lexicon.learnCorrection(oldKey, newKey)
        }
        SmartKeyboardApp.of(context).store.incrementStat("ai_fixes_applied")
    }

    fun testConnection(onResult: (Result<String>) -> Unit) = run({
        val out = provider().complete("Reply with exactly: ok", "ping", 10, fast = true)
        "Connected ✓ (model replied: ${out.take(40)})"
    }, onResult)

    fun listModels(onResult: (Result<List<String>>) -> Unit) = run({ provider().listModels() }, onResult)

    private fun maxTokensFor(text: String) = (text.length / 2 + 64).coerceAtMost(4000)
}

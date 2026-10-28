package com.itzsuli.smartkeyboard.ai

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.itzsuli.smartkeyboard.SmartKeyboardApp
import com.itzsuli.smartkeyboard.data.AiTone
import com.itzsuli.smartkeyboard.data.Languages
import com.itzsuli.smartkeyboard.data.Settings
import com.itzsuli.smartkeyboard.engine.AiOutputCheck
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
    // Below the phone's own work: an offline model computes for seconds on these threads.
    private val executor = Executors.newFixedThreadPool(2) { r ->
        Thread({ android.os.Process.setThreadPriority(LocalLlm.WORK_PRIORITY); r.run() }, "ai").apply { isDaemon = true }
    }
    private val main = Handler(Looper.getMainLooper())
    private val predictionGeneration = AtomicInteger()
    private val predictionCache = object : LinkedHashMap<String, List<String>>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<String>>?) = size > 64
    }
    private var cachedProvider: Pair<String, AiProvider>? = null

    val isConfigured: Boolean get() = settings.aiEnabled

    /**
     * Runs a request on the chosen provider. If a cloud provider can't be reached (no
     * internet) and an offline model is ready, the phone answers instead.
     */
    private fun complete(system: String, user: String, maxTokens: Int, fast: Boolean): String {
        val p = provider()
        return try {
            p.complete(system, user, maxTokens, fast)
        } catch (e: Exception) {
            val offline = settings.offlineEngine
            val noInternet = generateSequence<Throwable>(e) { it.cause }.any { it is java.io.IOException }
            if (p is OnDeviceProvider || !noInternet || !settings.offlineFallback || !OnDeviceProvider.isReady(context, offline)) throw e
            OnDeviceProvider(context, offline).complete(system, user, maxTokens, fast)
        }
    }

    /** Loads the offline model in the background so the first fix is quick. */
    fun warmUp() {
        if (ProviderPreset.of(settings.aiProvider) != ProviderPreset.ON_DEVICE || !settings.aiEnabled) return
        val model = com.itzsuli.smartkeyboard.data.OfflineModel.of(settings.offlineEngine) ?: return
        // Runs on its own low-priority thread; the keyboard never waits for it.
        LocalLlm.warmUpAsync(context, model)
    }

    private fun provider(): AiProvider {
        val preset = ProviderPreset.of(settings.aiProvider)
        if (preset == ProviderPreset.ON_DEVICE) return OnDeviceProvider(context, settings.offlineEngine.ifBlank { throw AiException("Pick an offline model in AI settings") })
        val signature = listOf(preset.id, settings.aiApiKey, settings.aiModel, settings.aiBaseUrl).joinToString("|")
        cachedProvider?.let { if (it.first == signature) return it.second }
        if (settings.aiApiKey.isBlank() && preset != ProviderPreset.CUSTOM) throw AiException("Add an API key in AI settings")
        val p = AiProviders.create(preset, settings.aiApiKey, settings.aiModel, settings.aiBaseUrl)
        cachedProvider = signature to p
        return p
    }

    /** The user's own words in every language being typed (mixed typing shares them). */
    private fun slangFor(langs: List<String>): List<String> = langs.flatMap { lang ->
        SmartKeyboardApp.of(context).languages.lexicon(lang).allWords()
            .filter { (it.isSlang || it.isManual) && it.isConfirmed && !it.isBlocked }
    }.sortedByDescending { it.count }.map { it.display }.distinct().take(60)

    /** "English", or "English and German" when languages are mixed on one keyboard. */
    private fun languageName(langs: List<String>) = langs.map { Languages.get(it).englishName }.distinct().joinToString(" and ")

    private fun <T> run(task: () -> T, onResult: (Result<T>) -> Unit) {
        executor.execute {
            val r = runCatching(task)
            main.post { onResult(r) }
        }
    }

    fun proofread(text: String, langs: List<String>, tone: AiTone = settings.aiTone, onResult: (Result<String>) -> Unit) = run({
        val out = Prompts.clean(complete(Prompts.proofread(languageName(langs), slangFor(langs), tone), Prompts.wrap(text), maxTokensFor(text), fast = false))
        if (out.isEmpty()) text else checked(text, out, AiOutputCheck.Kind.PROOFREAD, langs)
    }, onResult)

    fun rewrite(text: String, mode: Prompts.Rewrite, langs: List<String>, onResult: (Result<String>) -> Unit) = run({
        val out = Prompts.clean(complete(Prompts.rewrite(mode, languageName(langs), slangFor(langs)), Prompts.wrap(text), maxTokensFor(text) * 2, fast = false))
        checked(text, out, if (mode == Prompts.Rewrite.EMOJI) AiOutputCheck.Kind.REWRITE_EMOJI else AiOutputCheck.Kind.REWRITE, langs)
    }, onResult)

    fun translate(text: String, targetLang: String, onResult: (Result<String>) -> Unit) = run({
        val out = Prompts.clean(complete(Prompts.translate(Languages.get(targetLang).englishName), Prompts.wrap(text), maxTokensFor(text) * 2, fast = false))
        checked(text, out, AiOutputCheck.Kind.TRANSLATE, emptyList(), targetLang)
    }, onResult)

    /** Answers that are clearly broken (another alphabet, random emoji, a different text) never reach the text field. */
    private fun checked(text: String, out: String, kind: AiOutputCheck.Kind, langs: List<String>, target: String? = null): String {
        val problem = AiOutputCheck.problem(text, out, kind, langs, target) ?: return out
        throw AiException("The AI's answer didn't look right ($problem), so nothing was changed. If it keeps happening, try another model.")
    }

    /**
     * Context-aware next-word ideas. Only the latest request is delivered, so fast typing
     * never shows stale words; results are cached per context.
     */
    fun predictNext(textBefore: String, langs: List<String>, onResult: (List<String>) -> Unit) {
        val gen = predictionGeneration.incrementAndGet()
        // An offline model after every word would cost a lot of battery for little gain.
        if (ProviderPreset.of(settings.aiProvider) == ProviderPreset.ON_DEVICE) return
        val key = "${langs.joinToString(",")}|${textBefore.takeLast(160)}"
        synchronized(predictionCache) { predictionCache[key] }?.let { onResult(it); return }
        executor.execute {
            if (gen != predictionGeneration.get()) return@execute
            val words = runCatching {
                val out = complete(Prompts.nextWords(languageName(langs), slangFor(langs)), textBefore.takeLast(400), 40, fast = true)
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
     * Words the user typed on purpose (known words, slang) are never turned into rules, and
     * neither are slips the keyboard already fixes by itself ([fixedAnyway]).
     */
    fun learnFromAcceptedFix(
        before: String, after: String, lexicon: UserLexicon,
        isKnownWord: (String) -> Boolean, fixedAnyway: (String, String) -> Boolean = { _, _ -> false },
    ) {
        for ((old, new) in TextDiff.wordFixes(before, after)) {
            val oldKey = TextUtils.key(old, lexicon.lang)
            val newKey = TextUtils.key(new, lexicon.lang)
            if (oldKey == newKey || isKnownWord(oldKey) || fixedAnyway(old, new)) continue
            lexicon.learnCorrection(oldKey, newKey)
        }
        SmartKeyboardApp.of(context).store.incrementStat("ai_fixes_applied")
    }

    fun testConnection(onResult: (Result<String>) -> Unit) = run({
        val out = complete("Reply with exactly: ok", "ping", 10, fast = true)
        "Connected ✓ (model replied: ${out.take(40)})"
    }, onResult)

    fun listModels(onResult: (Result<List<String>>) -> Unit) = run({ provider().listModels() }, onResult)

    private fun maxTokensFor(text: String) = (text.length / 2 + 64).coerceAtMost(4000)
}

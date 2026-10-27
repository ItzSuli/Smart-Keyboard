package com.itzsuli.smartkeyboard.ai

import android.content.Context
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.SamplerConfig
import com.google.ai.edge.litertlm.ThinkingConfig
import com.google.mlkit.genai.common.DownloadCallback
import com.google.mlkit.genai.common.FeatureStatus
import com.google.mlkit.genai.common.GenAiException
import com.google.mlkit.genai.prompt.Generation
import com.google.mlkit.genai.prompt.SystemInstruction
import com.google.mlkit.genai.prompt.TextPart
import com.google.mlkit.genai.prompt.generateContentRequest
import com.google.mlkit.genai.prompt.java.GenerativeModelFutures
import com.itzsuli.smartkeyboard.data.OfflineModel
import com.itzsuli.smartkeyboard.data.OfflineModelStore
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * AI that runs on the phone: no key, no internet, nothing leaves the device.
 * [engine] is "nano" for Gemini Nano (Android's built-in model on supported phones) or the
 * id of a downloaded [OfflineModel].
 */
class OnDeviceProvider(private val context: Context, private val engine: String) : AiProvider {
    override fun complete(system: String, user: String, maxTokens: Int, fast: Boolean): String {
        if (engine == NANO) return GeminiNano.complete(context, system, user, maxTokens)
        val model = OfflineModel.of(engine) ?: throw AiException("Pick an offline model in AI settings")
        if (!OfflineModelStore(context).isInstalled(model)) throw AiException("Download ${model.label} in AI settings → Offline AI")
        return LocalLlm.complete(context, model, system, user, maxTokens)
    }

    override fun listModels(): List<String> = listOf(engine)

    companion object {
        const val NANO = "nano"

        /** True when [engine] can answer right now (model downloaded / Gemini Nano ready). */
        fun isReady(context: Context, engine: String): Boolean = when (engine) {
            "" -> false
            NANO -> GeminiNano.cachedStatus == FeatureStatus.AVAILABLE
            else -> OfflineModel.of(engine)?.let { OfflineModelStore(context).isInstalled(it) } == true
        }
    }
}

/**
 * A downloaded model run by LiteRT-LM (Google's on-device LLM runtime): GPU first where the
 * model is built for it, the CPU otherwise. The model stays loaded while it's being used and is
 * let go after a while without use, so it doesn't hold on to memory.
 *
 * Loading takes seconds, so nothing the keyboard calls on its main thread ([warmUpAsync],
 * [isLoaded], [releaseAsync]) ever waits for it: typing must never stall behind the AI.
 */
object LocalLlm {
    /** One loaded model. Replaced in tests, where native code can't run. */
    interface Runner : AutoCloseable {
        fun generate(system: String, user: String, maxTokens: Int): String
    }

    var factory: (Context, OfflineModel) -> Runner = { ctx, model -> LiteRtRunner(ctx, model) }

    private var runner: Runner? = null
    @Volatile private var loaded: OfflineModel? = null
    @Volatile private var loading = false
    private val janitor = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "local-llm-release").apply { isDaemon = true } }
    /** Loads models at low priority so the keyboard's drawing and typing come first. */
    private val loader = Executors.newSingleThreadExecutor { r ->
        Thread({ android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND); r.run() }, "local-llm-load").apply { isDaemon = true }
    }
    private var release: ScheduledFuture<*>? = null

    @Synchronized
    fun complete(context: Context, model: OfflineModel, system: String, user: String, maxTokens: Int): String {
        val r = load(context, model)
        scheduleRelease()
        return stripThinking(r.generate(system, user, maxTokens))
    }

    /** Loads the model ahead of the first request (blocks until it's loaded). */
    @Synchronized
    fun warmUp(context: Context, model: OfflineModel) {
        if (!OfflineModelStore(context).isInstalled(model)) return
        runCatching { load(context, model) }
        scheduleRelease()
    }

    /** Starts loading the model in the background and returns at once. */
    fun warmUpAsync(context: Context, model: OfflineModel) {
        if (loaded == model || loading) return
        loading = true
        loader.execute {
            try {
                warmUp(context, model)
            } finally {
                loading = false
            }
        }
    }

    private fun load(context: Context, model: OfflineModel): Runner {
        if (loaded == model) runner?.let { return it }
        runner?.close()
        runner = null
        loaded = null
        return factory(context.applicationContext, model).also { runner = it; loaded = model }
    }

    @Synchronized
    fun release() {
        release?.cancel(false)
        runCatching { runner?.close() }
        runner = null
        loaded = null
    }

    /** Never waits, even while a model is loading. */
    val isLoaded: Boolean get() = loaded != null

    /** Lets the model go without waiting for a request or a load that's running. */
    fun releaseAsync() { janitor.execute { release() } }

    private fun scheduleRelease() {
        release?.cancel(false)
        release = janitor.schedule({ release() }, IDLE_MINUTES, TimeUnit.MINUTES)
    }

    /** Some models think out loud first; only the answer matters. */
    fun stripThinking(text: String): String =
        text.replace(Regex("(?s)<think>.*?</think>"), "").replace(Regex("(?s)^.*</think>"), "").trim()

    /** Kept loaded this long after its last use: reloading costs seconds every time. */
    private const val IDLE_MINUTES = 20L
}

private class LiteRtRunner(context: Context, private val model: OfflineModel) : LocalLlm.Runner {
    private val engine: Engine

    init {
        val path = OfflineModelStore(context).file(model).absolutePath
        val backends = if (model.preferGpu) listOf<() -> Backend>({ Backend.GPU() }, { Backend.CPU() })
        else listOf({ Backend.CPU() }, { Backend.GPU() })
        var started: Engine? = null
        var error: Throwable? = null
        for (backend in backends) {
            try {
                val e = Engine(EngineConfig(modelPath = path, backend = backend(), maxNumTokens = MAX_TOKENS, cacheDir = context.cacheDir.absolutePath))
                e.initialize()
                started = e
                break
            } catch (t: Throwable) {
                error = t
            }
        }
        engine = started ?: throw AiException("Couldn't start ${model.label} on this phone: ${error?.message ?: "unknown error"}")
    }

    override fun generate(system: String, user: String, maxTokens: Int): String {
        val config = ConversationConfig(
            systemInstruction = Contents.of(system),
            samplerConfig = SamplerConfig(topK = 20, topP = 0.9, temperature = 0.2),
            maxOutputToken = maxTokens.coerceIn(16, MAX_TOKENS / 2),
            thinkingConfig = ThinkingConfig(false),
        )
        engine.createConversation(config).use { conversation ->
            val reply = conversation.sendMessage(if (model.noThinkHint) "$user /no_think" else user)
            return reply.contents.contents.filterIsInstance<Content.Text>().joinToString("") { it.text }
        }
    }

    override fun close() = engine.close()

    companion object {
        private const val MAX_TOKENS = 2048
    }
}

/**
 * Gemini Nano through ML Kit's Prompt API: Android's own on-device model (AICore) on phones
 * that have it, such as recent Pixels and Galaxy flagships. Nothing to download from us; the
 * phone fetches the model itself the first time.
 */
object GeminiNano {
    @Volatile var cachedStatus: Int = -1
        private set

    private val client by lazy { GenerativeModelFutures.from(Generation.getClient()) }

    /** One of [FeatureStatus] (AVAILABLE, DOWNLOADABLE, DOWNLOADING, UNAVAILABLE). Blocking. */
    fun status(): Int = runCatching { client.checkStatus().get(15, TimeUnit.SECONDS) }
        .getOrDefault(FeatureStatus.UNAVAILABLE).also { cachedStatus = it }

    fun download(onProgress: (Long) -> Unit, onDone: (Throwable?) -> Unit) {
        runCatching {
            client.download(object : DownloadCallback {
                override fun onDownloadProgress(totalBytesDownloaded: Long) = onProgress(totalBytesDownloaded)
                override fun onDownloadCompleted() { cachedStatus = FeatureStatus.AVAILABLE; onDone(null) }
                override fun onDownloadFailed(e: GenAiException) = onDone(e)
            })
        }.onFailure(onDone)
    }

    fun complete(@Suppress("UNUSED_PARAMETER") context: Context, system: String, user: String, maxTokens: Int): String {
        if (cachedStatus != FeatureStatus.AVAILABLE && status() != FeatureStatus.AVAILABLE) {
            throw AiException("Gemini Nano isn't ready on this phone yet")
        }
        val withSystem = runCatching { client.isSystemPromptAvailable().get(5, TimeUnit.SECONDS) }.getOrDefault(false)
        val tokens = maxTokens.coerceIn(16, 256)
        val request = if (withSystem) {
            generateContentRequest(SystemInstruction(system), TextPart(user)) { temperature = 0.2f; topK = 16; maxOutputTokens = tokens }
        } else {
            generateContentRequest(TextPart("$system\n\n$user")) { temperature = 0.2f; topK = 16; maxOutputTokens = tokens }
        }
        val response = client.generateContent(request).get(60, TimeUnit.SECONDS)
        return response.candidates.firstOrNull()?.text?.trim() ?: throw AiException("Gemini Nano gave no answer")
    }
}

package com.itzsuli.smartkeyboard.ai

import com.anthropic.client.AnthropicClient
import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.anthropic.core.JsonValue
import com.anthropic.errors.AnthropicServiceException
import com.anthropic.models.beta.messages.BetaOutputConfig
import com.anthropic.models.beta.messages.MessageCreateParams
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.time.Duration

class AiException(message: String) : Exception(message)

/** One request/response round trip to a language model. */
interface AiProvider {
    /** @param fast true for latency-critical calls (live word suggestions). */
    fun complete(system: String, user: String, maxTokens: Int, fast: Boolean): String
    fun listModels(): List<String>
}

/** Presets shown in settings. Every model field stays editable. */
enum class ProviderPreset(val id: String, val label: String, val defaultModel: String, val baseUrl: String, val keyHint: String) {
    GEMINI("gemini", "Google Gemini (free tier available)", "gemini-2.5-flash", "https://generativelanguage.googleapis.com", "aistudio.google.com/apikey"),
    CLAUDE("claude", "Anthropic Claude", "claude-opus-5-5", "", "console.anthropic.com"),
    OPENAI("openai", "OpenAI", "gpt-4.1-mini", "https://api.openai.com/v1", "platform.openai.com/api-keys"),
    GROQ("groq", "Groq (very fast)", "llama-3.1-8b-instant", "https://api.groq.com/openai/v1", "console.groq.com/keys"),
    OPENROUTER("openrouter", "OpenRouter", "openai/gpt-4.1-mini", "https://openrouter.ai/api/v1", "openrouter.ai/keys"),
    CUSTOM("custom", "Custom (OpenAI-compatible, e.g. Ollama)", "", "http://localhost:11434/v1", "");

    companion object {
        fun of(id: String) = entries.firstOrNull { it.id == id } ?: GEMINI

        /** Model suggestions offered in settings for Claude. */
        val CLAUDE_MODELS = listOf(
            "claude-opus-5-5" to "Claude Opus 5.5 — best quality",
            "claude-haiku-5-5" to "Claude Haiku 5.5 — fastest & cheapest, great for live suggestions",
            "claude-sonnet-5-5" to "Claude Sonnet 5.5 — balanced",
        )
    }
}

object AiProviders {
    fun create(preset: ProviderPreset, apiKey: String, model: String, baseUrl: String): AiProvider {
        val m = model.ifBlank { preset.defaultModel }
        return when (preset) {
            ProviderPreset.CLAUDE -> ClaudeProvider(apiKey, m)
            ProviderPreset.GEMINI -> GeminiProvider(apiKey, m, baseUrl.ifBlank { preset.baseUrl })
            else -> OpenAiCompatibleProvider(apiKey, m, baseUrl.ifBlank { preset.baseUrl })
        }
    }
}

/** Claude through the official Anthropic Java SDK. */
class ClaudeProvider(apiKey: String, private val model: String) : AiProvider {
    private val client: AnthropicClient = AnthropicOkHttpClient.builder()
        .apiKey(apiKey)
        .timeout(Duration.ofSeconds(45))
        .maxRetries(1)
        .build()

    override fun complete(system: String, user: String, maxTokens: Int, fast: Boolean): String {
        val builder = MessageCreateParams.builder()
            .model(model)
            .maxTokens(MAX_TOKENS)
            .system(system)
            .addUserMessage(user)
            // Keyboard work is short and latency-sensitive: keep reasoning light.
            .outputConfig(BetaOutputConfig.builder().effort(BetaOutputConfig.Effort.LOW).build())
        if (supportsServerFallback(model)) {
            // Retry automatically on a fallback model if a safety classifier declines the request.
            builder.addBeta("server-side-fallback-2026-07-01")
            builder.putAdditionalBodyProperty("fallbacks", JsonValue.from("default"))
        }
        val response = try {
            client.beta().messages().create(builder.build())
        } catch (e: AnthropicServiceException) {
            throw AiException("Claude error ${e.statusCode()}: ${e.message}")
        } catch (e: Exception) {
            throw AiException(e.message ?: "Network error")
        }
        if (response.stopReason().map { it.toString() }.orElse("") == "refusal") {
            throw AiException("Claude declined this text")
        }
        return response.content().mapNotNull { block -> block.text().map { it.text() }.orElse(null) }.joinToString("").trim()
    }

    override fun listModels(): List<String> = try {
        client.models().list().data().map { it.id() }
    } catch (e: Exception) {
        throw AiException(e.message ?: "Could not list models")
    }

    private fun supportsServerFallback(model: String) =
        model.startsWith("claude-opus-5") || model.startsWith("claude-fable-5-1") || model.startsWith("claude-sonnet-5-5")

    companion object {
        private const val MAX_TOKENS = 16000L
    }
}

private fun httpJson(url: String, method: String, headers: Map<String, String>, body: JSONObject?): JSONObject {
    val conn = URL(url).openConnection() as HttpURLConnection
    try {
        conn.requestMethod = method
        conn.connectTimeout = 15_000
        conn.readTimeout = 45_000
        headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }
        if (body != null) {
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
        }
        val code = conn.responseCode
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
        if (code !in 200..299) {
            val msg = runCatching { JSONObject(text).optJSONObject("error")?.optString("message") }.getOrNull()
            throw AiException("HTTP $code${if (!msg.isNullOrBlank()) ": $msg" else ""}")
        }
        return JSONObject(text)
    } catch (e: IOException) {
        throw AiException(e.message ?: "Network error")
    } finally {
        conn.disconnect()
    }
}

/** Google Gemini (generateContent REST API). */
class GeminiProvider(private val apiKey: String, private val model: String, private val baseUrl: String) : AiProvider {
    override fun complete(system: String, user: String, maxTokens: Int, fast: Boolean): String {
        val body = JSONObject()
            .put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", system))))
            .put("contents", JSONArray().put(JSONObject().put("role", "user").put("parts", JSONArray().put(JSONObject().put("text", user)))))
            .put("generationConfig", JSONObject().put("temperature", 0.2).put("maxOutputTokens", maxTokens * 4))
        val res = httpJson("$baseUrl/v1beta/models/$model:generateContent", "POST", mapOf("x-goog-api-key" to apiKey), body)
        val parts = res.optJSONArray("candidates")?.optJSONObject(0)?.optJSONObject("content")?.optJSONArray("parts")
            ?: throw AiException("Gemini returned no answer")
        return (0 until parts.length()).joinToString("") { parts.getJSONObject(it).optString("text") }.trim()
    }

    override fun listModels(): List<String> {
        val res = httpJson("$baseUrl/v1beta/models?pageSize=200", "GET", mapOf("x-goog-api-key" to apiKey), null)
        val arr = res.optJSONArray("models") ?: return emptyList()
        return (0 until arr.length()).map { arr.getJSONObject(it) }
            .filter { m -> m.optJSONArray("supportedGenerationMethods")?.let { a -> (0 until a.length()).any { a.getString(it) == "generateContent" } } ?: true }
            .map { it.getString("name").removePrefix("models/") }
    }
}

/** Any OpenAI-compatible chat completions API (OpenAI, Groq, OpenRouter, Ollama, LM Studio...). */
class OpenAiCompatibleProvider(private val apiKey: String, private val model: String, private val baseUrl: String) : AiProvider {
    private fun headers() = if (apiKey.isBlank()) emptyMap() else mapOf("Authorization" to "Bearer $apiKey")

    override fun complete(system: String, user: String, maxTokens: Int, fast: Boolean): String {
        if (model.isBlank()) throw AiException("Pick a model in AI settings")
        val body = JSONObject()
            .put("model", model)
            .put("temperature", 0.2)
            .put("max_tokens", maxTokens * 4)
            .put("messages", JSONArray()
                .put(JSONObject().put("role", "system").put("content", system))
                .put(JSONObject().put("role", "user").put("content", user)))
        val res = httpJson("${baseUrl.trimEnd('/')}/chat/completions", "POST", headers(), body)
        return res.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message")?.optString("content")?.trim()
            ?: throw AiException("No answer from model")
    }

    override fun listModels(): List<String> {
        val res = httpJson("${baseUrl.trimEnd('/')}/models", "GET", headers(), null)
        val arr = res.optJSONArray("data") ?: return emptyList()
        return (0 until arr.length()).map { arr.getJSONObject(it).getString("id") }.sorted()
    }
}

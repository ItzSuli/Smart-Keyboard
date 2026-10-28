package com.itzsuli.smartkeyboard

import com.itzsuli.smartkeyboard.ai.ClaudeProvider
import com.itzsuli.smartkeyboard.ai.GeminiProvider
import com.itzsuli.smartkeyboard.ai.OpenAiCompatibleProvider
import com.itzsuli.smartkeyboard.ai.Prompts
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Each provider talks to a local fake server: checks the request we send and that we parse the answer. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AiProvidersTest {
    private lateinit var fake: FakeHttpServer
    private val lastPath get() = fake.lastPath
    private val lastBody get() = fake.lastBody
    private val lastHeaders get() = fake.lastHeaders
    private var reply: String
        get() = fake.reply
        set(v) { fake.reply = v }

    @Before fun start() { fake = FakeHttpServer() }

    @After fun stop() = fake.close()

    private val base get() = fake.baseUrl

    @Test fun claudeUsesSdkWithLowEffortAndFallbacks() {
        reply = """{"id":"msg_1","type":"message","role":"assistant","model":"claude-opus-5-5",
            "content":[{"type":"text","text":"i don't know lol"}],"stop_reason":"end_turn","stop_sequence":null,
            "usage":{"input_tokens":12,"output_tokens":6}}"""
        val out = ClaudeProvider("test-key", "claude-opus-5-5", base).complete(Prompts.proofread("English", listOf("lol")), Prompts.wrap("i dont no lol"), 64, false)
        assertEquals("i don't know lol", out)
        assertTrue(lastPath, lastPath.startsWith("/v1/messages"))
        val body = JSONObject(lastBody)
        assertEquals("claude-opus-5-5", body.getString("model"))
        assertEquals("low", body.getJSONObject("output_config").getString("effort"))
        assertEquals("default", body.getString("fallbacks"))
        assertTrue(lastHeaders["anthropic-beta"].orEmpty().contains("server-side-fallback-2026-07-01"))
        // Current API versions take the key as a bearer token (the SDK handles this).
        assertEquals("Bearer test-key", lastHeaders["authorization"])
    }

    @Test fun claudeHaikuSkipsFallbacks() {
        reply = """{"id":"msg_2","type":"message","role":"assistant","model":"claude-haiku-5-5",
            "content":[{"type":"text","text":"[\"you\",\"we\",\"it\"]"}],"stop_reason":"end_turn","stop_sequence":null,
            "usage":{"input_tokens":12,"output_tokens":6}}"""
        val out = ClaudeProvider("k", "claude-haiku-5-5", base).complete("sys", "how are", 40, true)
        assertEquals("[\"you\",\"we\",\"it\"]", out)
        assertTrue(!JSONObject(lastBody).has("fallbacks"))
    }

    @Test fun geminiRequestAndParse() {
        reply = """{"candidates":[{"content":{"parts":[{"text":"hello there"}]}}]}"""
        val out = GeminiProvider("gkey", "gemini-2.5-flash", base).complete("sys", "<text>helo there</text>", 50, false)
        assertEquals("hello there", out)
        assertEquals("/v1beta/models/gemini-2.5-flash:generateContent", lastPath)
        assertEquals("gkey", lastHeaders["x-goog-api-key"])
        assertEquals("sys", JSONObject(lastBody).getJSONObject("systemInstruction").getJSONArray("parts").getJSONObject(0).getString("text"))
    }

    @Test fun openAiCompatibleRequestAndParse() {
        reply = """{"choices":[{"message":{"role":"assistant","content":"  fixed  "}}]}"""
        val out = OpenAiCompatibleProvider("okey", "llama-3.1-8b-instant", "$base/openai/v1").complete("sys", "x", 50, false)
        assertEquals("fixed", out)
        assertEquals("/openai/v1/chat/completions", lastPath)
        assertEquals("Bearer okey", lastHeaders["authorization"])
    }

    @Test fun cleansModelOutput() {
        assertEquals("hi there", Prompts.clean("<text>hi there</text>"))
        assertEquals("hi there", Prompts.clean("```\nhi there\n```"))
        assertEquals("hi there", Prompts.clean("\"hi there\""))
    }
}

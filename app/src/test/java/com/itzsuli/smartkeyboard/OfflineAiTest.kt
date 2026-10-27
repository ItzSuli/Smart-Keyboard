package com.itzsuli.smartkeyboard

import android.os.Looper
import com.itzsuli.smartkeyboard.ai.LocalLlm
import com.itzsuli.smartkeyboard.data.OfflineModel
import com.itzsuli.smartkeyboard.data.OfflineModelStore
import com.itzsuli.smartkeyboard.data.Settings
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.RandomAccessFile
import java.net.ServerSocket

/**
 * The offline AI plumbing. The model itself is native Android code that can't run in a JVM
 * test, so a fake stands in for it; everything around it is real.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OfflineAiTest {
    private val app get() = org.robolectric.RuntimeEnvironment.getApplication() as SmartKeyboardApp
    private val model = OfflineModel.QWEN3_0_6B
    private val prompts = ArrayList<String>()

    @Before fun setUp() {
        // A sparse file of the exact size stands in for the downloaded model.
        RandomAccessFile(OfflineModelStore(app).file(model), "rw").use { it.setLength(model.sizeBytes) }
        LocalLlm.release()
        LocalLlm.factory = { _, m ->
            object : LocalLlm.Runner {
                override fun generate(system: String, user: String, maxTokens: Int): String {
                    prompts.add(system + "\n" + user + "\n" + m.id)
                    return "<think>they mean know and what</think>I don't know what you mean lol"
                }
                override fun close() {}
            }
        }
        app.settings.set(Settings.OFFLINE_ENGINE, model.id)
    }

    @After fun tearDown() {
        LocalLlm.release()
        OfflineModelStore(app).file(model).delete()
    }

    private fun <T> await(call: ((Result<T>) -> Unit) -> Unit): Result<T> {
        var out: Result<T>? = null
        call { out = it }
        val end = System.currentTimeMillis() + 10_000
        while (out == null && System.currentTimeMillis() < end) { shadowOf(Looper.getMainLooper()).idle(); Thread.sleep(10) }
        return out ?: error("timed out")
    }

    @Test fun proofreadsOnThePhone() {
        app.settings.set(Settings.AI_PROVIDER, "ondevice")
        assertTrue(app.settings.aiEnabled)
        assertTrue(OfflineModelStore(app).isInstalled(model))
        val fixed = await<String> { app.ai.proofread("i dont no wat u mean lol", listOf("en"), onResult = it) }.getOrThrow()
        // Thinking out loud is removed; only the answer is kept.
        assertEquals("I don't know what you mean lol", fixed)
        assertTrue(prompts.last(), prompts.last().contains("Keep it chill") && prompts.last().endsWith(model.id))
        assertTrue(LocalLlm.isLoaded)
        // Memory pressure lets the model go.
        app.onTrimMemory(android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW)
        val end = System.currentTimeMillis() + 5_000
        while (LocalLlm.isLoaded && System.currentTimeMillis() < end) Thread.sleep(10)
        assertFalse(LocalLlm.isLoaded)
    }

    @Test fun gibberishNeverReachesTheText() {
        // What a broken model build answered: other alphabets and emoji in a German sentence.
        LocalLlm.factory = { _, _ ->
            object : LocalLlm.Runner {
                override fun generate(system: String, user: String, maxTokens: Int) = "Ich bin 明天 leider не da 😢"
                override fun close() {}
            }
        }
        app.settings.set(Settings.AI_PROVIDER, "ondevice")
        val r = await<String> { app.ai.proofread("Ich bin morgen leider nicht da", listOf("en", "de"), onResult = it) }
        assertTrue(r.isFailure)
        assertTrue(r.exceptionOrNull()?.message, r.exceptionOrNull()?.message!!.contains("nothing was changed"))
    }

    @Test fun theKeyboardNeverWaitsForTheModelToLoad() {
        // A model that takes 1.5 s to load, like a big one on a phone.
        LocalLlm.factory = { _, _ ->
            Thread.sleep(1500)
            object : LocalLlm.Runner {
                override fun generate(system: String, user: String, maxTokens: Int) = "ok"
                override fun close() {}
            }
        }
        app.settings.set(Settings.AI_PROVIDER, "ondevice")
        app.settings.set(Settings.AI_AUTO_PROOF, com.itzsuli.smartkeyboard.data.AiAutoProof.SUGGEST)
        // The keyboard opens, closes and opens again while the model is still loading.
        val started = System.currentTimeMillis()
        repeat(3) {
            app.ai.warmUp()
            LocalLlm.isLoaded
        }
        app.onTrimMemory(android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW)
        val waited = System.currentTimeMillis() - started
        assertTrue("main thread waited $waited ms", waited < 200)
    }

    @Test fun takesOverWhenTheInternetIsGone() {
        // A cloud provider that can't be reached (nothing listens on this port).
        val port = ServerSocket(0).use { it.localPort }
        app.settings.set(Settings.AI_PROVIDER, "custom")
        app.settings.set(Settings.AI_BASE_URL, "http://127.0.0.1:$port/v1")
        app.settings.set("${Settings.AI_KEY}_custom", "k")
        app.settings.set(Settings.AI_MODEL, "some-model")
        val fixed = await<String> { app.ai.proofread("i dont no wat u mean lol", listOf("en"), onResult = it) }.getOrThrow()
        assertEquals("I don't know what you mean lol", fixed)
        // Turned off, the error comes through instead.
        app.settings.set(Settings.OFFLINE_FALLBACK, false)
        val failed = await<String> { app.ai.proofread("i dont no wat u mean lol", listOf("en"), onResult = it) }
        assertTrue(failed.isFailure)
        app.settings.set(Settings.OFFLINE_FALLBACK, null)
    }

    @Test fun catalogueLinksToTheOfficialFiles() {
        for (m in OfflineModel.entries) {
            assertTrue(m.url, m.url.startsWith("https://huggingface.co/litert-community/") && m.url.endsWith(".litertlm"))
            assertEquals(64, m.sha256.length)
        }
        assertEquals("345 MB", OfflineModel.QWEN3_0_6B.sizeLabel)
        assertEquals("2.6 GB", OfflineModel.GEMMA4_E2B.sizeLabel)
        // The broken browser build is gone; the old CPU choice carries over.
        assertTrue(OfflineModel.entries.none { it.fileName.contains("-gpu") || it.fileName.contains("web") })
        assertEquals(OfflineModel.GEMMA4_E2B, OfflineModel.of("gemma4-e2b-cpu"))
        assertEquals(null, OfflineModel.of("gemma4-e2b-gpu"))
        app.settings.set(Settings.OFFLINE_ENGINE, "gemma4-e2b-gpu")
        assertEquals("", app.settings.offlineEngine)
    }
}

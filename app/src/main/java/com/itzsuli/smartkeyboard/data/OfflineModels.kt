package com.itzsuli.smartkeyboard.data

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import java.io.File
import java.security.MessageDigest

/**
 * AI models that run entirely on the phone (LiteRT-LM format). They're too big to put in the
 * APK, so the app downloads the one the user picks, once, straight from the model's public
 * page (Hugging Face, litert-community), and checks it before use.
 */
enum class OfflineModel(
    val id: String,
    val label: String,
    val description: String,
    val repo: String,
    val fileName: String,
    val sizeBytes: Long,
    val sha256: String,
    /** Try the GPU first (otherwise the CPU first). */
    val preferGpu: Boolean,
    /** Qwen 3 thinks out loud unless asked not to. */
    val noThinkHint: Boolean = false,
) {
    GEMMA4_E2B_GPU(
        "gemma4-e2b-gpu", "Gemma 4 E2B · GPU",
        "Google's on-device model: the best quality in English, German, Arabic and 140+ languages. Fastest on flagships with a strong graphics chip, like the Galaxy S24 and newer.",
        "litert-community/gemma-4-E2B-it-litert-lm", "gemma-4-E2B-it-gpu.litertlm", 2_008_432_640L,
        "a53a59001894c58e6bdb5b9b227709f91a2e3e556baa7d85acf9c55402ba5cf5", preferGpu = true,
    ),
    GEMMA4_E2B_CPU(
        "gemma4-e2b-cpu", "Gemma 4 E2B · CPU",
        "The same model built for the processor: works on more phones, a bit slower.",
        "litert-community/gemma-4-E2B-it-litert-lm", "gemma-4-E2B-it.litertlm", 2_588_147_712L,
        "181938105e0eefd105961417e8da75903eacda102c4fce9ce90f50b97139a63c", preferGpu = false,
    ),
    QWEN3_1_7B(
        "qwen3-1.7b", "Qwen 3 1.7B",
        "Fast and multilingual (Arabic too), half the size of Gemma.",
        "litert-community/Qwen3-1.7B", "Qwen3-1.7B_dynamic_wi4b32_afp32.litertlm", 977_184_032L,
        "2a72330b504a6596d53a2c00923c57173b10f1b83e6a541213ffec08d1fc5de8", preferGpu = false, noThinkHint = true,
    ),
    QWEN3_0_6B(
        "qwen3-0.6b", "Qwen 3 0.6B",
        "The smallest and quickest. Fine for typos, weaker at rewriting.",
        "litert-community/Qwen3-0.6B", "Qwen3-0.6B_dynamic_wi4b32_afp32.litertlm", 344_671_744L,
        "03e7da1eb1108b50dffaa9bb52cc7bcbad2eb0c66ca990267f480c1e545d2856", preferGpu = false, noThinkHint = true,
    );

    val url: String get() = "https://huggingface.co/$repo/resolve/main/$fileName"
    val sizeLabel: String get() = if (sizeBytes >= 1_000_000_000L) "%.1f GB".format(sizeBytes / 1e9) else "${(sizeBytes + 500_000) / 1_000_000} MB"

    companion object {
        fun of(id: String?): OfflineModel? = entries.firstOrNull { it.id == id }
    }
}

/** Where models live, and downloading, importing, checking and deleting them. */
class OfflineModelStore(private val context: Context) {

    /** App-specific storage (removed with the app, no permission needed). */
    val dir: File get() = (context.getExternalFilesDir("models") ?: File(context.filesDir, "models")).apply { mkdirs() }

    fun file(model: OfflineModel) = File(dir, model.fileName)

    fun isInstalled(model: OfflineModel): Boolean = file(model).let { it.exists() && it.length() == model.sizeBytes }

    fun installed(): List<OfflineModel> = OfflineModel.entries.filter { isInstalled(it) }

    fun delete(model: OfflineModel) {
        cancelDownload(model)
        file(model).delete()
        prefs.edit().remove("verified_" + model.id).apply()
    }

    // ---- downloading (Android's download manager: resumes, shows progress, keeps going in the background)

    private val downloads get() = context.getSystemService(DownloadManager::class.java)
    private val prefs get() = context.getSharedPreferences("offline_models", Context.MODE_PRIVATE)

    fun startDownload(model: OfflineModel) {
        delete(model)
        val request = DownloadManager.Request(Uri.parse(model.url))
            .setTitle("Smart Keyboard · ${model.label}")
            .setDescription("Offline AI model (${model.sizeLabel})")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalFilesDir(context, "models", model.fileName)
            .setAllowedOverMetered(true)
        val id = downloads.enqueue(request)
        prefs.edit().putLong(model.id, id).apply()
    }

    fun cancelDownload(model: OfflineModel) {
        val id = prefs.getLong(model.id, -1L)
        if (id >= 0) runCatching { downloads.remove(id) }
        prefs.edit().remove(model.id).apply()
    }

    class Progress(val running: Boolean, val bytes: Long, val total: Long, val failed: Boolean, val reason: String?)

    /** Download state for [model], or null if it isn't being downloaded. */
    fun progress(model: OfflineModel): Progress? {
        val id = prefs.getLong(model.id, -1L)
        if (id < 0) return null
        return runCatching {
            downloads.query(DownloadManager.Query().setFilterById(id)).use { c ->
                if (!c.moveToFirst()) return@use null
                val status = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
                val bytes = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
                val total = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)).takeIf { it > 0 } ?: model.sizeBytes
                when (status) {
                    DownloadManager.STATUS_SUCCESSFUL -> { prefs.edit().remove(model.id).apply(); null }
                    DownloadManager.STATUS_FAILED -> Progress(false, bytes, total, true, "Download failed (${c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON))})")
                    DownloadManager.STATUS_PAUSED -> Progress(true, bytes, total, false, "Paused — waiting for a connection")
                    else -> Progress(true, bytes, total, false, null)
                }
            }
        }.getOrNull()
    }

    /** Copies a model file the user already has (e.g. from a computer) into place. */
    fun import(model: OfflineModel, source: Uri, onProgress: (Long) -> Unit) {
        val target = File(dir, model.fileName + ".part")
        context.contentResolver.openInputStream(source)?.use { input ->
            target.outputStream().use { out ->
                val buf = ByteArray(1 shl 20)
                var total = 0L
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    total += n
                    onProgress(total)
                }
            }
        } ?: error("Can't read that file")
        require(target.length() == model.sizeBytes) { "That's not the ${model.label} file (the size doesn't match)" }
        target.renameTo(file(model))
    }

    /** Checks the file against the published SHA-256 (takes a few seconds for big models). */
    fun verify(model: OfflineModel): Boolean {
        val f = file(model)
        if (!f.exists() || f.length() != model.sizeBytes) return false
        val md = MessageDigest.getInstance("SHA-256")
        f.inputStream().use { input ->
            val buf = ByteArray(1 shl 20)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        val ok = md.digest().joinToString("") { "%02x".format(it) } == model.sha256
        if (ok) prefs.edit().putBoolean("verified_" + model.id, true).apply()
        return ok
    }

    fun isVerified(model: OfflineModel): Boolean = prefs.getBoolean("verified_" + model.id, false) && isInstalled(model)
}

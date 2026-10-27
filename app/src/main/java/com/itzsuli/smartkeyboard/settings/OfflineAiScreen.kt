package com.itzsuli.smartkeyboard.settings

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.google.mlkit.genai.common.FeatureStatus
import com.itzsuli.smartkeyboard.SmartKeyboardApp
import com.itzsuli.smartkeyboard.ai.GeminiNano
import com.itzsuli.smartkeyboard.ai.OnDeviceProvider
import com.itzsuli.smartkeyboard.ai.Prompts
import com.itzsuli.smartkeyboard.data.AiTone
import com.itzsuli.smartkeyboard.data.OfflineModel
import com.itzsuli.smartkeyboard.data.OfflineModelStore
import com.itzsuli.smartkeyboard.data.Settings
import com.itzsuli.smartkeyboard.engine.AiOutputCheck
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun OfflineAiScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val app = SmartKeyboardApp.of(context)
    val s = app.settings
    val store = remember { OfflineModelStore(context) }
    val scope = rememberCoroutineScope()
    val engine by setting(Settings.OFFLINE_ENGINE) { it.offlineEngine }
    val provider by setting(Settings.AI_PROVIDER) { it.aiProvider }
    val active = provider == "ondevice"
    // Download progress comes from Android's download manager: refresh once a second.
    var tick by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) { while (true) { delay(1000); tick++ } }
    var nano by remember { mutableStateOf<Int?>(null) }
    var nanoProgress by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) { nano = withContext(Dispatchers.IO) { GeminiNano.status() } }
    val importing = remember { mutableStateMapOf<OfflineModel, String>() }
    val verified = remember { mutableStateMapOf<OfflineModel, Boolean>() }
    var importFor by remember { mutableStateOf<OfflineModel?>(null) }
    var test by remember { mutableStateOf<String?>(null) }

    fun use(id: String) {
        s.set(Settings.OFFLINE_ENGINE, id)
        s.set(Settings.AI_PROVIDER, "ondevice")
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        val model = importFor ?: return@rememberLauncherForActivityResult
        if (uri == null) return@rememberLauncherForActivityResult
        importing[model] = "Copying…"
        scope.launch {
            val r = withContext(Dispatchers.IO) {
                runCatching { store.import(model, uri) { n -> importing[model] = "Copying… ${n * 100 / model.sizeBytes} %" } }
            }
            importing.remove(model)
            r.onSuccess { if (engine.isBlank()) use(model.id) }.onFailure { test = "Import failed: ${it.message}" }
        }
    }

    SettingsPage("Offline AI", onBack) {
        item {
            Card(shape = RoundedCornerShape(22.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
                Column(Modifier.padding(18.dp)) {
                    Text("AI that never leaves your phone", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.size(6.dp))
                    Text("Chill proofreading, rewrites, translations and voice tidy-up without an API key or internet. Your text is processed on the phone itself.",
                        style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.size(8.dp))
                    Text(
                        if (active && engine.isNotBlank()) "In use: " + (if (engine == OnDeviceProvider.NANO) "Gemini Nano" else OfflineModel.of(engine)?.label ?: engine)
                        else "Not in use — pick one below.",
                        style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold,
                    )
                }
            }
        }
        item {
            Group("Gemini Nano · built into Android") {
                val status = nano
                val text = when (status) {
                    null -> "Checking this phone…"
                    FeatureStatus.AVAILABLE -> "Ready on this phone."
                    FeatureStatus.DOWNLOADABLE -> "This phone can download it (Android does it, once)."
                    FeatureStatus.DOWNLOADING -> "Android is downloading it…"
                    else -> "Not available on this phone (it needs Android's AICore with Gemini Nano, which only some recent phones have). The downloadable models below work on any recent phone."
                }
                SettingRow("Gemini Nano", nanoProgress ?: text)
                Row(Modifier.padding(horizontal = 12.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    when (status) {
                        FeatureStatus.AVAILABLE -> if (active && engine == OnDeviceProvider.NANO) FilledTonalButton(onClick = {}, enabled = false) { Text("In use ✓") }
                        else Button(onClick = { use(OnDeviceProvider.NANO) }) { Text("Use Gemini Nano") }
                        FeatureStatus.DOWNLOADABLE -> Button(onClick = {
                            nanoProgress = "Starting download…"
                            GeminiNano.download(
                                onProgress = { b -> nanoProgress = "Downloading… ${b / 1_000_000} MB" },
                                onDone = { e ->
                                    nanoProgress = if (e == null) null else "Download failed: ${e.message}"
                                    scope.launch { nano = withContext(Dispatchers.IO) { GeminiNano.status() } }
                                },
                            )
                        }) { Text("Download") }
                        else -> Unit
                    }
                }
            }
        }
        item {
            Group("Models you can download") {
                Hint("Downloaded once from Hugging Face (litert-community: the official on-device builds), checked, and kept in the app's storage. Gemma 4 is made by Google, Qwen 3 by Alibaba — both Apache 2.0.")
                OfflineModel.entries.forEach { model ->
                    tick // re-read progress every second
                    val installed = store.isInstalled(model)
                    val progress = if (installed) null else store.progress(model)
                    val copying = importing[model]
                    Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("${model.label} · ${model.sizeLabel}", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                        Text(model.description, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        when {
                            copying != null -> Text(copying, style = MaterialTheme.typography.bodySmall)
                            installed -> {
                                if (verified[model] == null) LaunchedEffect(model) {
                                    verified[model] = store.isVerified(model) || withContext(Dispatchers.IO) { store.verify(model) }
                                    if (engine.isBlank()) use(model.id)
                                }
                                Text(
                                    when (verified[model]) { null -> "Downloaded · checking the file…"; true -> "Downloaded · checked ✓"; false -> "The file is damaged — delete it and download again" },
                                    style = MaterialTheme.typography.bodySmall,
                                )
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    if (active && engine == model.id) FilledTonalButton(onClick = {}, enabled = false) { Text("In use ✓") }
                                    else Button(onClick = { use(model.id) }, enabled = verified[model] != false) { Text("Use") }
                                    TextButton(onClick = {
                                        store.delete(model)
                                        verified.remove(model)
                                        if (engine == model.id) s.set(Settings.OFFLINE_ENGINE, null)
                                        tick++
                                    }) { Text("Delete") }
                                }
                            }
                            progress != null && !progress.failed -> {
                                LinearProgressIndicator(progress = { (progress.bytes.toFloat() / progress.total).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                                Text(progress.reason ?: "${progress.bytes / 1_000_000} of ${progress.total / 1_000_000} MB — keeps going if you leave this screen",
                                    style = MaterialTheme.typography.bodySmall)
                                TextButton(onClick = { store.cancelDownload(model); tick++ }) { Text("Cancel") }
                            }
                            else -> {
                                progress?.reason?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Button(onClick = { store.startDownload(model); tick++ }) { Text("Download") }
                                    OutlinedButton(onClick = { importFor = model; picker.launch(arrayOf("*/*")) }) { Text("Import file…") }
                                }
                            }
                        }
                    }
                }
            }
        }
        item {
            Group("Try it") {
                Row(Modifier.padding(12.dp)) {
                    Button(onClick = {
                        test = "Thinking… (the first run loads the model, which takes a few seconds)"
                        val e = engine
                        scope.launch {
                            val started = System.currentTimeMillis()
                            val r = withContext(Dispatchers.IO) {
                                runCatching {
                                    OnDeviceProvider(context, e).complete(
                                        Prompts.proofread("English", emptyList(), AiTone.CHILL), Prompts.wrap(SAMPLE), 64, false,
                                    )
                                }
                            }
                            val secs = (System.currentTimeMillis() - started) / 1000.0
                            test = r.fold({
                                val answer = Prompts.clean(it)
                                val problem = AiOutputCheck.problem(SAMPLE, answer, AiOutputCheck.Kind.PROOFREAD, listOf("en"))
                                "“$answer” — %.1f s".format(secs) + (problem?.let { p -> "\n⚠ Answers like this are thrown away ($p)." } ?: "")
                            }, { "✗ ${it.message}" })
                        }
                    }, enabled = engine.isNotBlank()) { Text("Fix a test sentence") }
                }
                Hint(test ?: "Sends “$SAMPLE” to the offline model.")
            }
        }
        item {
            Group("When there's no internet") {
                SwitchSetting(Settings.OFFLINE_FALLBACK, "Use offline AI as a backup", "If your online AI can't be reached, the offline model answers instead") { it.offlineFallback }
            }
        }
    }
}

private const val SAMPLE = "i dont no wat u mean lol, teh movie was gud"

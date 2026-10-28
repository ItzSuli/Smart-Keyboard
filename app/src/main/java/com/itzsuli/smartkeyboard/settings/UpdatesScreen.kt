package com.itzsuli.smartkeyboard.settings

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.FileDownload
import androidx.compose.material.icons.rounded.FileUpload
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.itzsuli.smartkeyboard.BuildConfig
import com.itzsuli.smartkeyboard.SmartKeyboardApp
import com.itzsuli.smartkeyboard.data.SettingsBackup
import com.itzsuli.smartkeyboard.data.Updater
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Updating from inside the app, and backing everything up for when an update can't keep it. */
@Composable
fun UpdatesScreen(onBack: () -> Unit, open: (String) -> Unit) {
    val context = LocalContext.current
    val app = SmartKeyboardApp.of(context)
    val updater = remember { Updater(context) }
    val scope = rememberCoroutineScope()
    var status by remember { mutableStateOf<String?>(null) }
    var release by remember { mutableStateOf<Updater.Release?>(null) }
    var progress by remember { mutableStateOf<Float?>(null) }
    var otherKey by remember { mutableStateOf(false) }
    var downloaded by remember { mutableStateOf<File?>(null) }

    fun check() {
        status = "Looking for updates…"
        scope.launch {
            val r = withContext(Dispatchers.IO) { runCatching { updater.check() } }
            release = r.getOrNull()
            status = r.fold({ if (it == null) "You have the newest version." else null }, { "Couldn't check: ${it.message ?: "no connection"}" })
        }
    }

    fun install(r: Updater.Release) {
        progress = 0f
        status = null
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val apk = updater.download(r) { done, total -> if (total > 0) scope.launch { progress = done.toFloat() / total } }
                    apk to updater.signerOf(apk)
                }
            }
            progress = null
            result.onSuccess { (apk, signer) ->
                downloaded = apk
                if (signer == Updater.Signer.DIFFERENT) otherKey = true
                else updater.install(apk)
            }.onFailure { status = "Download failed: ${it.message}" }
        }
    }

    LaunchedEffect(Unit) { check() }

    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri: Uri? ->
        if (uri != null) scope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching { context.contentResolver.openOutputStream(uri)?.use { it.write(SettingsBackup.fullBackup(app).toByteArray()) } }.isSuccess
            }
            Toast.makeText(context, if (ok) "Backup saved" else "Couldn't save the backup", Toast.LENGTH_SHORT).show()
        }
    }
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) scope.launch {
            val n = withContext(Dispatchers.IO) {
                runCatching {
                    val json = context.contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) } ?: ""
                    SettingsBackup.restoreFull(app, json)
                }.getOrElse { -1 }
            }
            Toast.makeText(context, if (n >= 0) "Restored your settings and $n learned items" else "That file isn't a Smart Keyboard backup", Toast.LENGTH_SHORT).show()
        }
    }

    SettingsPage("Updates & backup", onBack) {
        item {
            Group("Updates") {
                SettingRow("Smart Keyboard ${BuildConfig.VERSION_NAME}", "Updates install over this version and keep your settings, words and offline AI.")
                val r = release
                if (r != null) Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Version ${r.version} is out", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    if (r.notes.isNotBlank()) Text(plainNotes(r.notes), style = MaterialTheme.typography.bodySmall)
                    progress?.let { LinearProgressIndicator(progress = { it }, modifier = Modifier.fillMaxWidth()) }
                    if (progress == null && !otherKey) Button(onClick = { install(r) }) { Icon(Icons.Rounded.FileDownload, null); Text(" Download & install") }
                }
                if (otherKey && r != null) Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("This version is signed with a different key, so Android can't install it over the one you have.",
                        color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                    Hint("1. Save a backup below.\n2. Download the new version in your browser.\n3. Uninstall Smart Keyboard, install the download.\n4. Updates & backup → Restore.\nThis only happens once: from then on updates install over the top.")
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { exporter.launch("smart-keyboard-backup.json") }) { Text("Save backup") }
                        OutlinedButton(onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(r.pageUrl))) }) { Text("Open in browser") }
                    }
                }
                if (release == null || status != null) Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                    Button(onClick = { check() }) { Text("Check for updates") }
                }
                status?.let { Hint(it) }
                if (downloaded != null && !otherKey) Hint("If Android asks, allow installing apps from Smart Keyboard, then tap Install.")
            }
        }
        item {
            Group("Backup") {
                Hint("Your settings, learned words, fixes and shortcuts in one file, for a new phone or a fresh install. API keys aren't included (sync carries them, encrypted).")
                Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { exporter.launch("smart-keyboard-backup.json") }) { Icon(Icons.Rounded.FileDownload, null); Text(" Save") }
                    OutlinedButton(onClick = { importer.launch(arrayOf("application/json", "text/plain", "*/*")) }) { Icon(Icons.Rounded.FileUpload, null); Text(" Restore") }
                }
                Divider()
                NavRow("Sync between phones", "Settings and learned words, end-to-end encrypted, through a file in Google Drive or any folder", Icons.Rounded.Sync) { open("sync") }
            }
        }
    }
}

/** The release notes without Markdown symbols, short enough for the screen. */
private fun plainNotes(markdown: String): String = markdown.lines()
    .filter { it.isNotBlank() && !it.startsWith(">") }
    .map { it.replace(Regex("^#+\\s*"), "").replace("**", "").replace("`", "").replace(Regex("^\\s*[-*]\\s+"), "• ") }
    .take(14).joinToString("\n")

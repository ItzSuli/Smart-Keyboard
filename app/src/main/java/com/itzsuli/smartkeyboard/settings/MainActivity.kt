package com.itzsuli.smartkeyboard.settings

import android.content.Intent
import android.os.Bundle
import android.provider.Settings as AndroidSettings
import android.view.inputmethod.InputMethodManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Keyboard
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.Psychology
import androidx.compose.material.icons.rounded.RadioButtonUnchecked
import androidx.compose.material.icons.rounded.Translate
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.itzsuli.smartkeyboard.SmartKeyboardApp
import com.itzsuli.smartkeyboard.data.Languages
import com.itzsuli.smartkeyboard.data.Settings

class MainActivity : ComponentActivity() {
    private val pendingSection = mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        pendingSection.value = intent?.getStringExtra(EXTRA_SECTION)
        setContent { SmartKeyboardTheme { SettingsApp(pendingSection.value) { pendingSection.value = null } } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        pendingSection.value = intent.getStringExtra(EXTRA_SECTION)
    }

    companion object {
        const val EXTRA_SECTION = "section"
    }
}

@Composable
fun SettingsApp(section: String?, onSectionHandled: () -> Unit) {
    val nav = rememberNavController()
    LaunchedEffect(section) {
        if (section != null) {
            nav.navigate(section) { launchSingleTop = true }
            onSectionHandled()
        }
    }
    val back: () -> Unit = { nav.popBackStack() }
    NavHost(nav, startDestination = "home") {
        composable("home") { HomeScreen { nav.navigate(it) } }
        composable("appearance") { AppearanceScreen(back) }
        composable("typing") { TypingScreen(back) }
        composable("languages") { LanguagesScreen(back) }
        composable("ai") { AiScreen(back) }
        composable("learning") { LearningScreen(back) }
        composable("about") { AboutScreen(back) }
    }
}

private data class SetupState(val enabled: Boolean, val selected: Boolean)

@Composable
private fun rememberSetupState(): SetupState {
    val context = LocalContext.current
    var state by remember { mutableStateOf(SetupState(false, false)) }
    fun refresh() {
        val imm = context.getSystemService(InputMethodManager::class.java)
        val enabled = imm.enabledInputMethodList.any { it.packageName == context.packageName }
        val current = AndroidSettings.Secure.getString(context.contentResolver, AndroidSettings.Secure.DEFAULT_INPUT_METHOD) ?: ""
        state = SetupState(enabled, current.startsWith(context.packageName + "/"))
    }
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val obs = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) refresh() }
        owner.lifecycle.addObserver(obs)
        onDispose { owner.lifecycle.removeObserver(obs) }
    }
    // The picker closes without resuming the activity; poll while it could be open.
    LaunchedEffect(Unit) {
        while (true) {
            refresh()
            kotlinx.coroutines.delay(1500)
        }
    }
    return state
}

@Composable
fun HomeScreen(open: (String) -> Unit) {
    val context = LocalContext.current
    val app = SmartKeyboardApp.of(context)
    val setup = rememberSetupState()
    val langs by setting(Settings.LANGUAGES) { it.languages }
    val aiOn = app.settings.aiEnabled
    val theme by setting(Settings.THEME) { it.themeId }
    SettingsPage("Smart Keyboard", onBack = null) {
        item { Hero() }
        if (!setup.enabled || !setup.selected) item {
            Card(shape = RoundedCornerShape(24.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Let's set it up", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                    Step(1, "Turn on Smart Keyboard", setup.enabled) {
                        context.startActivity(Intent(AndroidSettings.ACTION_INPUT_METHOD_SETTINGS))
                    }
                    Step(2, "Switch to Smart Keyboard", setup.selected, enabled = setup.enabled) {
                        context.getSystemService(InputMethodManager::class.java).showInputMethodPicker()
                    }
                    Text("Android shows a standard warning for every keyboard. Smart Keyboard keeps what you type on your phone; only the optional AI features send text, to the provider you choose.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onPrimaryContainer)
                }
            }
        }
        item {
            var text by remember { mutableStateOf("") }
            OutlinedTextField(text, { text = it }, Modifier.fillMaxWidth(), label = { Text("Try it here") },
                placeholder = { Text("Type or swipe something…") }, shape = RoundedCornerShape(18.dp), minLines = 2)
        }
        item {
            Group {
                NavRow("Appearance", "Themes, colours, key size, background — now: ${themeName(theme)}", Icons.Rounded.Palette) { open("appearance") }
                Divider()
                NavRow("Typing & swipe", "Chill autocorrect, adaptive keys, swipe, gestures", Icons.Rounded.Keyboard) { open("typing") }
                Divider()
                NavRow("Languages", langs.joinToString(" · ") { Languages.get(it).nativeName }, Icons.Rounded.Translate) { open("languages") }
                Divider()
                NavRow("AI assistant", if (aiOn) "Connected — proofreading & smart suggestions" else "Set up AI proofreading (free options)", Icons.Rounded.AutoAwesome) { open("ai") }
                Divider()
                NavRow("Your words & learning", "What the keyboard learned from you", Icons.Rounded.Psychology) { open("learning") }
                Divider()
                NavRow("About & privacy", null, Icons.Rounded.Info) { open("about") }
            }
        }
        item { Spacer(Modifier.height(8.dp)) }
    }
    LaunchedEffect(Unit) {
        val s = app.settings
        if (!s.onboardingDone) {
            // First start: pick languages from the phone's languages (plus English).
            val system = context.resources.configuration.locales.let { l -> (0 until l.size()).map { l[it].language } }
            val supported = (system + "en").map { if (it == "iw") "he" else it }.filter { code -> Languages.ALL.any { it.code == code } }.distinct()
            s.set(Settings.LANGUAGES, supported.joinToString(","))
            s.set(Settings.ONBOARDING_DONE, true)
        }
    }
}

private fun themeName(id: String) = when (id) {
    "auto" -> "Auto"
    "dynamic" -> "Material You"
    else -> com.itzsuli.smartkeyboard.ime.KeyboardTheme.PRESETS.firstOrNull { it.id == id }?.name ?: id
}

@Composable
private fun Hero() {
    Box(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(28.dp))
            .background(Brush.linearGradient(listOf(Color(0xFF4F46E5), Color(0xFF9333EA), Color(0xFFDB2777))))
            .padding(22.dp),
    ) {
        Column {
            Text("Types the way you do.", style = MaterialTheme.typography.headlineSmall, color = Color.White, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(6.dp))
            Text("Chill AI corrections, smart suggestions and swipe typing that learn your words, slang and style — on your phone.",
                style = MaterialTheme.typography.bodyMedium, color = Color.White.copy(alpha = 0.9f))
        }
    }
}

@Composable
private fun Step(n: Int, title: String, done: Boolean, enabled: Boolean = true, onClick: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(if (done) Icons.Rounded.CheckCircle else Icons.Rounded.RadioButtonUnchecked, null,
            tint = if (done) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.size(24.dp))
        Spacer(Modifier.width(12.dp))
        Text("$n. $title", Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        AnimatedVisibility(!done) {
            if (n == 1) Button(onClick = onClick, enabled = enabled) { Text("Open") }
            else FilledTonalButton(onClick = onClick, enabled = enabled) { Text("Choose") }
        }
    }
}

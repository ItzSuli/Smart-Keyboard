package com.itzsuli.smartkeyboard.settings

import android.content.SharedPreferences
import android.os.Build
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.itzsuli.smartkeyboard.SmartKeyboardApp
import com.itzsuli.smartkeyboard.data.Settings
import com.itzsuli.smartkeyboard.ime.KeyboardTheme

private val Brand = Color(0xFF5B4BE0)

@Composable
fun SmartKeyboardTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val ctx = LocalContext.current
    val scheme = when {
        Build.VERSION.SDK_INT >= 31 -> if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
        dark -> darkColorScheme(primary = Color(0xFFB4ABFF), secondary = Color(0xFFD0BCFF), tertiary = Color(0xFFFFB4AB))
        else -> lightColorScheme(primary = Brand, secondary = Color(0xFF7C4DFF), tertiary = Color(0xFFE5484D))
    }
    MaterialTheme(colorScheme = scheme, content = content)
}

/** Reads a setting and recomposes whenever it changes (from here or from the keyboard). */
@Composable
fun <T> setting(key: String, read: (Settings) -> T): State<T> {
    val settings = SmartKeyboardApp.of(LocalContext.current).settings
    val state = remember(key) { mutableStateOf(read(settings)) }
    DisposableEffect(key) {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, k -> if (k == key) state.value = read(settings) }
        settings.prefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose { settings.prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    return state
}

@Composable
fun settings(): Settings = SmartKeyboardApp.of(LocalContext.current).settings

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsPage(title: String, onBack: (() -> Unit)?, content: LazyListScope.() -> Unit) {
    val scroll = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    Scaffold(
        modifier = Modifier.nestedScroll(scroll.nestedScrollConnection),
        topBar = {
            LargeTopAppBar(
                title = { Text(title, fontWeight = FontWeight.SemiBold) },
                navigationIcon = {
                    if (onBack != null) IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") }
                },
                scrollBehavior = scroll,
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = padding.calculateTopPadding() + 4.dp, bottom = padding.calculateBottomPadding() + 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            content = content,
        )
    }
}

@Composable
fun Group(title: String? = null, content: @Composable ColumnScope.() -> Unit) {
    Column {
        if (title != null) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(start = 8.dp, bottom = 6.dp, top = 6.dp))
        }
        Card(
            shape = RoundedCornerShape(22.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        ) { Column(Modifier.padding(vertical = 4.dp), content = content) }
    }
}

@Composable
fun SettingRow(title: String, subtitle: String? = null, icon: ImageVector? = null, trailing: @Composable (() -> Unit)? = null, onClick: (() -> Unit)? = null) {
    Row(
        Modifier.fillMaxWidth().let { if (onClick != null) it.clickable(onClick = onClick) else it }.padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer, modifier = Modifier.size(38.dp)) {
                Box(contentAlignment = Alignment.Center) { Icon(icon, null, tint = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.size(20.dp)) }
            }
            Spacer(Modifier.width(14.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (trailing != null) {
            Spacer(Modifier.width(12.dp))
            trailing()
        }
    }
}

@Composable
fun NavRow(title: String, subtitle: String?, icon: ImageVector, onClick: () -> Unit) =
    SettingRow(title, subtitle, icon, trailing = { Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant) }, onClick = onClick)

@Composable
fun SwitchSetting(key: String, title: String, subtitle: String? = null, read: (Settings) -> Boolean) {
    val s = settings()
    val value by setting(key, read)
    SettingRow(title, subtitle, trailing = { Switch(checked = value, onCheckedChange = { s.set(key, it) }) }, onClick = { s.set(key, !value) })
}

@Composable
fun SliderSetting(key: String, title: String, range: IntRange, step: Int = 1, format: (Int) -> String = { "$it" }, read: (Settings) -> Int) {
    val s = settings()
    val value by setting(key, read)
    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            Text(format(value), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        }
        Slider(
            value = value.toFloat(),
            onValueChange = { v -> s.set(key, ((v / step).toInt() * step).coerceIn(range)) },
            valueRange = range.first.toFloat()..range.last.toFloat(),
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun <T> ChoiceSetting(title: String, subtitle: String? = null, options: List<Pair<T, String>>, selected: T, onSelect: (T) -> Unit) {
    Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
        Text(title, style = MaterialTheme.typography.bodyLarge)
        if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.size(8.dp))
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            options.forEachIndexed { i, (value, label) ->
                SegmentedButton(
                    selected = value == selected,
                    onClick = { onSelect(value) },
                    shape = SegmentedButtonDefaults.itemShape(i, options.size),
                ) { Text(label, maxLines = 1) }
            }
        }
    }
}

@Composable
fun Divider() = HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

/** A miniature keyboard drawn with a theme's colours. */
@Composable
fun KeyboardPreview(theme: KeyboardTheme, modifier: Modifier, radiusDp: Float = 6f, keyBackgrounds: Boolean = true, rows: Int = 4) {
    Canvas(modifier) {
        val bg = Color(theme.background)
        val end = theme.backgroundEnd
        if (end != null) drawRect(Brush.linearGradient(listOf(bg, Color(end)), Offset.Zero, Offset(size.width, size.height)))
        else drawRect(bg)
        val pad = size.width * 0.025f
        val rowH = (size.height - pad * 2) / rows
        val cols = 10
        val keyW = (size.width - pad * 2) / cols
        val gap = keyW * 0.12f
        // The preview stands for a ~411dp wide keyboard drawn at size.width pixels.
        val r = CornerRadius(radiusDp * size.width / 411f)
        for (row in 0 until rows) {
            val y = pad + row * rowH
            val last = row == rows - 1
            if (!last) {
                val n = if (row == 1) 9 else if (row == 2) 7 else 10
                val offset = (cols - n) * keyW / 2
                for (i in 0 until n) {
                    if (keyBackgrounds) drawRoundRect(Color(theme.key), Offset(pad + offset + i * keyW + gap / 2, y + gap / 2), Size(keyW - gap, rowH - gap), r)
                    drawCircle(Color(theme.keyText).copy(alpha = 0.85f), radius = rowH * 0.09f, center = Offset(pad + offset + (i + 0.5f) * keyW, y + rowH / 2))
                }
                if (row == 2) {
                    drawRoundRect(Color(theme.functionKey), Offset(pad + gap / 2, y + gap / 2), Size(keyW * 1.5f - gap, rowH - gap), r)
                    drawRoundRect(Color(theme.functionKey), Offset(pad + keyW * 8.5f + gap / 2, y + gap / 2), Size(keyW * 1.5f - gap, rowH - gap), r)
                }
            } else {
                drawRoundRect(Color(theme.functionKey), Offset(pad + gap / 2, y + gap / 2), Size(keyW * 1.5f - gap, rowH - gap), r)
                drawRoundRect(Color(theme.key), Offset(pad + keyW * 2.5f + gap / 2, y + gap / 2), Size(keyW * 5f - gap, rowH - gap), r)
                drawRoundRect(Color(theme.actionKey), Offset(pad + keyW * 8.5f + gap / 2, y + gap / 2), Size(keyW * 1.5f - gap, rowH - gap), r)
            }
        }
    }
}

@Composable
fun Hint(text: String) = Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))

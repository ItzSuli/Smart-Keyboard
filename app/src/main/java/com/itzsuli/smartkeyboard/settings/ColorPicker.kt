package com.itzsuli.smartkeyboard.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp

/** Quick picks that cover most tastes; the sliders below reach every other colour. */
val PALETTE: List<Int> = listOf(
    0xFF000000, 0xFF121212, 0xFF1B1C20, 0xFF2E3440, 0xFF334155, 0xFF64748B, 0xFF9CA3AF, 0xFFE5E7EB, 0xFFF5F1E8, 0xFFFFFFFF,
    0xFF7F1D1D, 0xFFE5484D, 0xFFFF5C8A, 0xFFDB2777, 0xFF9333EA, 0xFF7C3AED, 0xFF5B4BE0, 0xFF3B6FE0, 0xFF2563EB, 0xFF06B6D4,
    0xFF14B8A6, 0xFF10B981, 0xFF22C55E, 0xFF84CC16, 0xFFF5B800, 0xFFF97316, 0xFFB76E79, 0xFF8B5E3C, 0xFF0B2545, 0xFF14281D,
).map { it.toInt() }

/**
 * Pick any colour: a palette of quick picks plus hue, saturation and brightness sliders.
 * [allowNone] adds a "None" button (used for the optional gradient colour).
 */
@Composable
fun ColorPickerDialog(title: String, initial: Int, allowNone: Boolean = false, onDismiss: () -> Unit, onPick: (Int) -> Unit) {
    val start = remember { FloatArray(3).also { android.graphics.Color.colorToHSV(if (initial == 0) 0xFF5B4BE0.toInt() else initial, it) } }
    var hue by remember { mutableFloatStateOf(start[0]) }
    var sat by remember { mutableFloatStateOf(start[1]) }
    var value by remember { mutableFloatStateOf(start[2]) }
    val current = android.graphics.Color.HSVToColor(floatArrayOf(hue, sat, value))

    fun select(c: Int) {
        val hsv = FloatArray(3)
        android.graphics.Color.colorToHSV(c, hsv)
        hue = hsv[0]; sat = hsv[1]; value = hsv[2]
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(48.dp).clip(RoundedCornerShape(12.dp)).background(Color(current))
                        .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(12.dp)))
                    Text(
                        "#%06X".format(current and 0xFFFFFF), style = MaterialTheme.typography.titleMedium,
                        fontFamily = FontFamily.Monospace, modifier = Modifier.padding(start = 14.dp),
                    )
                }
                Spacer(Modifier.height(12.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    PALETTE.forEach { c ->
                        Box(
                            Modifier.size(26.dp).clip(CircleShape).background(Color(c))
                                .border(if (c == current) 2.dp else 1.dp, if (c == current) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant, CircleShape)
                                .clickable { select(c) },
                        )
                    }
                }
                Spacer(Modifier.height(12.dp))
                Text("Hue", style = MaterialTheme.typography.labelMedium)
                Box(Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)).background(
                    Brush.horizontalGradient((0..6).map { Color(android.graphics.Color.HSVToColor(floatArrayOf(it * 60f, 1f, 1f))) }),
                ))
                Slider(hue, { hue = it }, valueRange = 0f..360f, colors = SliderDefaults.colors(thumbColor = Color(android.graphics.Color.HSVToColor(floatArrayOf(hue, 1f, 1f)))))
                Text("Colour strength", style = MaterialTheme.typography.labelMedium)
                Slider(sat, { sat = it })
                Text("Brightness", style = MaterialTheme.typography.labelMedium)
                Slider(value, { value = it })
            }
        },
        confirmButton = { TextButton(onClick = { onPick(current) }) { Text("Use") } },
        dismissButton = {
            Row {
                if (allowNone) TextButton(onClick = { onPick(0) }) { Text("None") }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        },
    )
}

/** A settings row with a colour swatch that opens [ColorPickerDialog]. */
@Composable
fun ColorSetting(title: String, subtitle: String?, color: Int, allowNone: Boolean = false, onPick: (Int) -> Unit) {
    var open by remember { androidx.compose.runtime.mutableStateOf(false) }
    SettingRow(title, subtitle, trailing = {
        Box(
            Modifier.size(34.dp).clip(CircleShape)
                .background(if (color == 0) MaterialTheme.colorScheme.surfaceVariant else Color(color))
                .border(1.dp, MaterialTheme.colorScheme.outline, CircleShape),
            contentAlignment = Alignment.Center,
        ) { if (color == 0) Text("–") }
    }) { open = true }
    if (open) ColorPickerDialog(title, color, allowNone, onDismiss = { open = false }) { open = false; onPick(it) }
}

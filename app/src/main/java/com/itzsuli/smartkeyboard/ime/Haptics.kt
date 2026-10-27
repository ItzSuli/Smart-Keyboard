package com.itzsuli.smartkeyboard.ime

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.HapticFeedbackConstants
import android.view.View
import com.itzsuli.smartkeyboard.data.HapticStyle
import com.itzsuli.smartkeyboard.data.Settings

/**
 * Key-press vibration. Uses the phone's own tuned haptic effects where it has them (short,
 * crisp "clicks" and "ticks" on most current phones, the Galaxy S2x series included) and
 * scales them with the strength setting, falling back to short pulses on older phones.
 *
 * Repeats (held delete, cursor slide) use a lighter tick than a key press, so a fast run
 * of them reads as one smooth purr instead of a rattle.
 */
class Haptics(context: Context, private val settings: Settings) {

    enum class Kind {
        /** A key goes down. */
        PRESS,
        /** One step of a held delete. */
        REPEAT,
        /** The cursor moved a step, a word was added to a swipe-delete, a popup option changed. */
        TICK,
        /** A long press opened something. */
        LONG_PRESS,
    }

    private val vibrator: Vibrator? = if (Build.VERSION.SDK_INT >= 31) {
        context.getSystemService(VibratorManager::class.java)?.defaultVibrator
    } else {
        @Suppress("DEPRECATION") context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
    }

    private val hasVibrator = runCatching { vibrator?.hasVibrator() == true }.getOrDefault(false)
    private val amplitudeControl = runCatching { vibrator?.hasAmplitudeControl() == true }.getOrDefault(false)

    /** Click and tick primitives: the crispest effects, with fine strength control. */
    private val primitives: Boolean = Build.VERSION.SDK_INT >= 30 && runCatching {
        vibrator!!.areAllPrimitivesSupported(VibrationEffect.Composition.PRIMITIVE_CLICK, VibrationEffect.Composition.PRIMITIVE_TICK)
    }.getOrDefault(false)

    private val lowTick: Boolean = Build.VERSION.SDK_INT >= 31 && runCatching {
        vibrator!!.areAllPrimitivesSupported(VibrationEffect.Composition.PRIMITIVE_LOW_TICK)
    }.getOrDefault(false)

    /** What the current device will use for [HapticStyle.CRISP] (shown in settings). */
    val engineName: String get() = when {
        !hasVibrator -> "no vibration motor"
        primitives -> "precise haptics"
        Build.VERSION.SDK_INT >= 29 -> "phone's click effects"
        else -> "short pulses"
    }

    fun perform(kind: Kind, view: View? = null) {
        if (!settings.haptics) return
        // 0..1, a little curved so the low end of the slider stays usable.
        val strength = (settings.hapticStrength.coerceIn(1, 100) / 100f)
        runCatching {
            when (settings.hapticStyle) {
                HapticStyle.SYSTEM -> system(kind, view)
                HapticStyle.CLASSIC -> pulse(kind, strength)
                HapticStyle.CRISP -> effect(kind, strength, soft = false)
                HapticStyle.SOFT -> effect(kind, strength, soft = true)
            }
        }
    }

    private fun system(kind: Kind, view: View?) {
        val v = view ?: return
        val constant = when (kind) {
            Kind.PRESS -> HapticFeedbackConstants.KEYBOARD_TAP
            Kind.REPEAT, Kind.TICK -> if (Build.VERSION.SDK_INT >= 27) HapticFeedbackConstants.TEXT_HANDLE_MOVE else HapticFeedbackConstants.CLOCK_TICK
            Kind.LONG_PRESS -> HapticFeedbackConstants.LONG_PRESS
        }
        v.performHapticFeedback(constant)
    }

    private fun effect(kind: Kind, strength: Float, soft: Boolean) {
        val vib = vibrator ?: return
        if (!hasVibrator) return
        if (primitives && Build.VERSION.SDK_INT >= 30) {
            // Primitive scale is perceptual 0..1; keep a floor so the lowest setting is still felt.
            val scale = 0.18f + 0.82f * strength
            val (primitive, factor) = when (kind) {
                Kind.PRESS -> (if (soft) VibrationEffect.Composition.PRIMITIVE_TICK else VibrationEffect.Composition.PRIMITIVE_CLICK) to 1f
                Kind.REPEAT -> (if (soft && lowTick) VibrationEffect.Composition.PRIMITIVE_LOW_TICK else VibrationEffect.Composition.PRIMITIVE_TICK) to 0.8f
                Kind.TICK -> (if (lowTick) VibrationEffect.Composition.PRIMITIVE_LOW_TICK else VibrationEffect.Composition.PRIMITIVE_TICK) to 0.75f
                Kind.LONG_PRESS -> VibrationEffect.Composition.PRIMITIVE_CLICK to 1.25f
            }
            vib.vibrate(VibrationEffect.startComposition().addPrimitive(primitive, (scale * factor).coerceIn(0.05f, 1f)).compose())
            return
        }
        if (Build.VERSION.SDK_INT >= 29 && !amplitudeControl) {
            // No strength control on this motor: pick the effect that matches the slider.
            val id = when (kind) {
                Kind.PRESS -> when {
                    soft || strength < 0.34f -> VibrationEffect.EFFECT_TICK
                    strength < 0.8f -> VibrationEffect.EFFECT_CLICK
                    else -> VibrationEffect.EFFECT_HEAVY_CLICK
                }
                Kind.REPEAT, Kind.TICK -> VibrationEffect.EFFECT_TICK
                Kind.LONG_PRESS -> VibrationEffect.EFFECT_HEAVY_CLICK
            }
            vib.vibrate(VibrationEffect.createPredefined(id))
            return
        }
        // Amplitude-controlled motors: a very short, sharp pulse feels like a click.
        val base = if (soft) 0.7f else 1f
        val (ms, amp) = when (kind) {
            Kind.PRESS -> 9L to strength * base
            Kind.REPEAT -> 6L to strength * base * 0.75f
            Kind.TICK -> 5L to strength * base * 0.6f
            Kind.LONG_PRESS -> 14L to (strength * 1.2f).coerceAtMost(1f)
        }
        vib.vibrate(VibrationEffect.createOneShot(ms, amplitude(amp)))
    }

    /** The original style: plain pulses whose length and power follow the slider. */
    private fun pulse(kind: Kind, strength: Float) {
        val vib = vibrator ?: return
        if (!hasVibrator) return
        val ms = (6 + strength * 16).toLong()
        val (len, amp) = when (kind) {
            Kind.PRESS -> ms to strength
            Kind.REPEAT, Kind.TICK -> (ms * 0.55f).toLong().coerceAtLeast(4) to strength * 0.7f
            Kind.LONG_PRESS -> (ms * 1.6f).toLong() to (strength * 1.2f).coerceAtMost(1f)
        }
        vib.vibrate(VibrationEffect.createOneShot(len, amplitude(amp)))
    }

    private fun amplitude(fraction: Float): Int =
        if (amplitudeControl) (20 + fraction * 235).toInt().coerceIn(1, 255) else VibrationEffect.DEFAULT_AMPLITUDE
}

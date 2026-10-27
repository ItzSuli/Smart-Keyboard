package com.itzsuli.smartkeyboard.ime

import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.os.Build
import androidx.annotation.ColorInt
import com.itzsuli.smartkeyboard.data.Settings

/** Every colour the keyboard draws with. */
data class KeyboardTheme(
    val id: String,
    val name: String,
    @ColorInt val background: Int,
    /** Optional second colour: the background becomes a vertical gradient. */
    @ColorInt val backgroundEnd: Int? = null,
    @ColorInt val key: Int,
    @ColorInt val keyPressed: Int,
    @ColorInt val functionKey: Int,
    @ColorInt val actionKey: Int,
    @ColorInt val keyText: Int,
    @ColorInt val functionText: Int,
    @ColorInt val actionText: Int,
    @ColorInt val hintText: Int,
    @ColorInt val accent: Int,
    @ColorInt val stripText: Int,
    @ColorInt val keyBorder: Int = Color.TRANSPARENT,
    @ColorInt val keyShadow: Int = Color.TRANSPARENT,
    val isDark: Boolean,
) {
    fun withAccent(@ColorInt accent: Int) = copy(accent = accent, actionKey = accent, actionText = if (isLight(accent)) Color.BLACK else Color.WHITE)

    companion object {
        fun isLight(@ColorInt c: Int): Boolean =
            (Color.red(c) * 299 + Color.green(c) * 587 + Color.blue(c) * 114) / 1000 > 150

        private fun c(hex: String) = Color.parseColor(hex)

        val LIGHT = KeyboardTheme(
            "light", "Light", c("#E8EAEE"), null, c("#FFFFFF"), c("#D5D9E0"), c("#C9CED6"), c("#3B6FE0"),
            c("#1B1D21"), c("#2A2D33"), Color.WHITE, c("#7A808A"), c("#3B6FE0"), c("#1B1D21"),
            keyShadow = c("#33000000"), isDark = false,
        )
        val DARK = KeyboardTheme(
            "dark", "Dark", c("#1B1C20"), null, c("#3A3B41"), c("#56575F"), c("#2A2B30"), c("#7FA7FF"),
            c("#F2F3F5"), c("#E1E2E6"), c("#0E1A33"), c("#9A9CA5"), c("#7FA7FF"), c("#F2F3F5"),
            keyShadow = c("#66000000"), isDark = true,
        )
        val AMOLED = KeyboardTheme(
            "amoled", "AMOLED Black", c("#000000"), null, c("#141414"), c("#2C2C2C"), c("#0A0A0A"), c("#FFFFFF"),
            c("#F5F5F5"), c("#D8D8D8"), c("#000000"), c("#7C7C7C"), c("#FFFFFF"), c("#F5F5F5"),
            keyBorder = c("#262626"), isDark = true,
        )
        val OCEAN = KeyboardTheme(
            "ocean", "Ocean", c("#0B2545"), c("#13315C"), c("#1D4E89"), c("#2B67AD"), c("#16406F"), c("#4CC9F0"),
            c("#EAF6FF"), c("#CFE8FF"), c("#062033"), c("#8DB7DA"), c("#4CC9F0"), c("#EAF6FF"),
            keyShadow = c("#55000000"), isDark = true,
        )
        val SUNSET = KeyboardTheme(
            "sunset", "Sunset", c("#3A1C71"), c("#D76D77"), c("#5B2A86"), c("#7B3FA8"), c("#4A2275"), c("#FFAF7B"),
            c("#FFF4EC"), c("#FFE3D1"), c("#3A1C71"), c("#E9C4D0"), c("#FFAF7B"), c("#FFF4EC"),
            keyBorder = c("#33FFFFFF"), isDark = true,
        )
        val FOREST = KeyboardTheme(
            "forest", "Forest", c("#14281D"), c("#1F3B2C"), c("#2C4A3A"), c("#3F6650"), c("#22392C"), c("#9BD770"),
            c("#EEF6E9"), c("#D6E8CC"), c("#10210F"), c("#93AE95"), c("#9BD770"), c("#EEF6E9"),
            keyShadow = c("#55000000"), isDark = true,
        )
        val NEON = KeyboardTheme(
            "neon", "Neon", c("#07070F"), c("#120A24"), c("#0F0F1E"), c("#26264A"), c("#0B0B17"), c("#FF2E88"),
            c("#00F5FF"), c("#FF2E88"), c("#07070F"), c("#6F6FA0"), c("#FF2E88"), c("#00F5FF"),
            keyBorder = c("#5500F5FF"), isDark = true,
        )
        val PASTEL = KeyboardTheme(
            "pastel", "Pastel", c("#F6E7F2"), c("#E5E1FA"), c("#FFFFFF"), c("#F0DDF0"), c("#E8D7F1"), c("#B388EB"),
            c("#4A3B5C"), c("#5D4B70"), Color.WHITE, c("#A795B8"), c("#B388EB"), c("#4A3B5C"),
            keyShadow = c("#22806090"), isDark = false,
        )
        val MOCHA = KeyboardTheme(
            "mocha", "Mocha", c("#2B211C"), null, c("#4A3A31"), c("#665245"), c("#3A2D26"), c("#E0B07E"),
            c("#F7EDE2"), c("#EADBC8"), c("#2B211C"), c("#B49E8A"), c("#E0B07E"), c("#F7EDE2"),
            keyShadow = c("#55000000"), isDark = true,
        )
        val GLASS = KeyboardTheme(
            "glass", "Glass", c("#243B55"), c("#141E30"), c("#33FFFFFF"), c("#55FFFFFF"), c("#22FFFFFF"), c("#CCFFFFFF"),
            Color.WHITE, c("#E6FFFFFF"), c("#141E30"), c("#B3FFFFFF"), c("#9AD0FF"), Color.WHITE,
            keyBorder = c("#33FFFFFF"), isDark = true,
        )
        val SAKURA = KeyboardTheme(
            "sakura", "Sakura", c("#FFE4EC"), c("#FFF5F8"), c("#FFFFFF"), c("#FFD1DF"), c("#FFD9E5"), c("#FF6F91"),
            c("#5A2A3A"), c("#7A3A50"), Color.WHITE, c("#C28497"), c("#FF6F91"), c("#5A2A3A"),
            keyShadow = c("#22FF6F91"), isDark = false,
        )

        val PRESETS = listOf(LIGHT, DARK, AMOLED, OCEAN, SUNSET, FOREST, NEON, PASTEL, MOCHA, GLASS, SAKURA)

        /** Material You: built from the wallpaper palette on Android 12+. */
        fun dynamic(context: Context, dark: Boolean): KeyboardTheme {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return if (dark) DARK else LIGHT
            fun sys(name: String): Int {
                val id = context.resources.getIdentifier(name, "color", "android")
                return if (id != 0) context.getColor(id) else Color.GRAY
            }
            return if (dark) KeyboardTheme(
                "dynamic", "Material You", sys("system_neutral1_900"), null, sys("system_neutral2_700"), sys("system_neutral2_600"),
                sys("system_neutral2_800"), sys("system_accent1_200"), sys("system_neutral1_50"), sys("system_neutral1_100"),
                sys("system_accent1_800"), sys("system_neutral2_300"), sys("system_accent1_200"), sys("system_neutral1_50"),
                keyShadow = Color.parseColor("#55000000"), isDark = true,
            ) else KeyboardTheme(
                "dynamic", "Material You", sys("system_neutral1_50"), null, sys("system_neutral1_10"), sys("system_accent2_100"),
                sys("system_accent2_100"), sys("system_accent1_600"), sys("system_neutral1_900"), sys("system_neutral1_800"),
                Color.WHITE, sys("system_neutral2_500"), sys("system_accent1_600"), sys("system_neutral1_900"),
                keyShadow = Color.parseColor("#22000000"), isDark = false,
            )
        }

        fun systemIsDark(context: Context): Boolean =
            (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES

        /** Resolves the theme chosen in settings (including "auto" and custom accent colours). */
        fun resolve(context: Context, settings: Settings): KeyboardTheme {
            val dark = systemIsDark(context)
            val base = when (val id = settings.themeId) {
                "auto" -> if (dark) DARK else LIGHT
                "dynamic" -> dynamic(context, dark)
                else -> PRESETS.firstOrNull { it.id == id } ?: if (dark) DARK else LIGHT
            }
            val accent = settings.customAccent
            return if (accent != 0) base.withAccent(accent) else base
        }
    }
}

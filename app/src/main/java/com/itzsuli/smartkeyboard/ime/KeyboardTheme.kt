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

        val MIDNIGHT = KeyboardTheme(
            "midnight", "Midnight", c("#0F172A"), c("#1E1B4B"), c("#1E293B"), c("#334155"), c("#172033"), c("#818CF8"),
            c("#E2E8F0"), c("#CBD5E1"), c("#0F172A"), c("#64748B"), c("#818CF8"), c("#E2E8F0"),
            keyShadow = c("#66000000"), isDark = true,
        )
        val NORD = KeyboardTheme(
            "nord", "Nord", c("#2E3440"), null, c("#3B4252"), c("#4C566A"), c("#353C4A"), c("#88C0D0"),
            c("#ECEFF4"), c("#D8DEE9"), c("#2E3440"), c("#81A1C1"), c("#88C0D0"), c("#ECEFF4"),
            keyShadow = c("#44000000"), isDark = true,
        )
        val DRACULA = KeyboardTheme(
            "dracula", "Dracula", c("#282A36"), null, c("#44475A"), c("#6272A4"), c("#343746"), c("#BD93F9"),
            c("#F8F8F2"), c("#F8F8F2"), c("#282A36"), c("#8B93C4"), c("#FF79C6"), c("#F8F8F2"),
            keyShadow = c("#55000000"), isDark = true,
        )
        val SOLARIZED = KeyboardTheme(
            "solarized", "Solarized", c("#EEE8D5"), null, c("#FDF6E3"), c("#E4DCC4"), c("#E0D9C3"), c("#268BD2"),
            c("#586E75"), c("#657B83"), Color.WHITE, c("#93A1A1"), c("#268BD2"), c("#586E75"),
            keyShadow = c("#22000000"), isDark = false,
        )
        val MINT = KeyboardTheme(
            "mint", "Mint", c("#E3F6EF"), c("#F2FBF7"), c("#FFFFFF"), c("#CDEDE1"), c("#D3EFE4"), c("#10B981"),
            c("#134E4A"), c("#115E59"), Color.WHITE, c("#6BA595"), c("#10B981"), c("#134E4A"),
            keyShadow = c("#2210B981"), isDark = false,
        )
        val LAVENDER = KeyboardTheme(
            "lavender", "Lavender", c("#EDE9FE"), c("#F5F3FF"), c("#FFFFFF"), c("#DDD6FE"), c("#E4DEFC"), c("#7C3AED"),
            c("#3B0764"), c("#4C1D95"), Color.WHITE, c("#A78BFA"), c("#7C3AED"), c("#3B0764"),
            keyShadow = c("#227C3AED"), isDark = false,
        )
        val AURORA = KeyboardTheme(
            "aurora", "Aurora", c("#0B3D3A"), c("#3B1E5A"), c("#2AFFFFFF"), c("#50FFFFFF"), c("#1AFFFFFF"), c("#5EEAD4"),
            Color.WHITE, c("#E6FFFFFF"), c("#0B3D3A"), c("#A0FFFFFF"), c("#5EEAD4"), Color.WHITE,
            keyBorder = c("#22FFFFFF"), isDark = true,
        )
        val ROSE_GOLD = KeyboardTheme(
            "rose_gold", "Rose Gold", c("#F4E1DA"), c("#EBC8BD"), c("#FFF8F5"), c("#F1D3C8"), c("#EBCFC4"), c("#B76E79"),
            c("#5A3A3A"), c("#6E4545"), Color.WHITE, c("#B08A80"), c("#B76E79"), c("#5A3A3A"),
            keyShadow = c("#22B76E79"), isDark = false,
        )
        val CYBERPUNK = KeyboardTheme(
            "cyberpunk", "Cyberpunk", c("#0D0D0D"), c("#1A1A05"), c("#1A1A1A"), c("#333300"), c("#121212"), c("#FCEE0A"),
            c("#FCEE0A"), c("#00F0FF"), c("#0D0D0D"), c("#8A8A3A"), c("#FCEE0A"), c("#FCEE0A"),
            keyBorder = c("#55FCEE0A"), isDark = true,
        )
        val PAPER = KeyboardTheme(
            "paper", "Paper", c("#F5F1E8"), null, c("#FFFDF8"), c("#E9E2D3"), c("#E8E1D2"), c("#2F2F2F"),
            c("#2B2B2B"), c("#3A3A3A"), c("#F5F1E8"), c("#9C958A"), c("#D97706"), c("#2B2B2B"),
            keyShadow = c("#22000000"), isDark = false,
        )
        val SLATE = KeyboardTheme(
            "slate", "Slate", c("#334155"), null, c("#475569"), c("#64748B"), c("#3B4A5F"), c("#38BDF8"),
            c("#F1F5F9"), c("#E2E8F0"), c("#0C2233"), c("#94A3B8"), c("#38BDF8"), c("#F1F5F9"),
            keyShadow = c("#44000000"), isDark = true,
        )
        val BERRY = KeyboardTheme(
            "berry", "Berry", c("#4A0E2E"), c("#2D0A3E"), c("#6B1D45"), c("#8C2B5C"), c("#5A1538"), c("#FF5C8A"),
            c("#FFE4EE"), c("#FFD1E1"), c("#3A0A22"), c("#D58BA8"), c("#FF5C8A"), c("#FFE4EE"),
            keyBorder = c("#33FF5C8A"), isDark = true,
        )
        val CARBON = KeyboardTheme(
            "carbon", "Carbon", c("#121212"), null, c("#1F1F1F"), c("#333333"), c("#181818"), c("#FF6B00"),
            c("#EDEDED"), c("#CFCFCF"), c("#121212"), c("#777777"), c("#FF6B00"), c("#EDEDED"),
            keyShadow = c("#88000000"), isDark = true,
        )
        val SKY = KeyboardTheme(
            "sky", "Sky", c("#DBEAFE"), c("#EFF6FF"), c("#FFFFFF"), c("#BFDBFE"), c("#C7DCF7"), c("#2563EB"),
            c("#1E3A8A"), c("#1E40AF"), Color.WHITE, c("#7DA3D9"), c("#2563EB"), c("#1E3A8A"),
            keyShadow = c("#222563EB"), isDark = false,
        )

        val PRESETS = listOf(
            LIGHT, DARK, AMOLED, OCEAN, SUNSET, FOREST, NEON, PASTEL, MOCHA, GLASS, SAKURA,
            MIDNIGHT, NORD, DRACULA, SOLARIZED, MINT, LAVENDER, AURORA, ROSE_GOLD, CYBERPUNK, PAPER, SLATE, BERRY, CARBON, SKY,
        )

        /** Blends [a] towards [b] by [t] (0..1). */
        fun mix(@ColorInt a: Int, @ColorInt b: Int, t: Float): Int = Color.argb(
            (Color.alpha(a) + (Color.alpha(b) - Color.alpha(a)) * t).toInt(),
            (Color.red(a) + (Color.red(b) - Color.red(a)) * t).toInt(),
            (Color.green(a) + (Color.green(b) - Color.green(a)) * t).toInt(),
            (Color.blue(a) + (Color.blue(b) - Color.blue(a)) * t).toInt(),
        )

        /**
         * The user's own theme from four colours (background, optional second background for
         * a gradient, keys, letters); everything else is derived so it always looks coherent.
         */
        fun custom(settings: Settings): KeyboardTheme {
            val bg = settings.customBackground or 0xFF000000.toInt()
            val key = settings.customKey
            val text = settings.customText or 0xFF000000.toInt()
            val dark = !isLight(bg)
            val accent = if (isLight(bg)) c("#3B6FE0") else c("#7FA7FF")
            return KeyboardTheme(
                "custom", "Custom", bg, settings.customBackground2.takeIf { it != 0 }, key, mix(key, text, 0.22f), mix(key, bg, 0.55f),
                accent, text, text, if (isLight(accent)) Color.BLACK else Color.WHITE, mix(text, bg, 0.45f), accent, text,
                keyShadow = if (dark) c("#55000000") else c("#22000000"), isDark = dark,
            )
        }

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
            var base = when (val id = settings.themeId) {
                "auto" -> if (dark) DARK else LIGHT
                "dynamic" -> dynamic(context, dark)
                "custom" -> custom(settings)
                else -> PRESETS.firstOrNull { it.id == id } ?: if (dark) DARK else LIGHT
            }
            if (!settings.keyShadows) base = base.copy(keyShadow = Color.TRANSPARENT)
            val accent = settings.customAccent
            return if (accent != 0) base.withAccent(accent) else base
        }
    }
}

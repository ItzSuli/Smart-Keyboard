package com.itzsuli.smartkeyboard.data

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.itzsuli.smartkeyboard.engine.BinaryDictionary
import com.itzsuli.smartkeyboard.engine.LanguageModel
import com.itzsuli.smartkeyboard.engine.UserLexicon
import java.util.concurrent.Executors

data class Language(
    val code: String,
    val name: String,
    val nativeName: String,
    val rtl: Boolean = false,
    val currency: String = "$",
    /** Name the AI uses ("German", "Arabic"). */
    val englishName: String = name,
)

object Languages {
    val ALL = listOf(
        Language("en", "English", "English"),
        Language("de", "German", "Deutsch", currency = "€"),
        Language("ar", "Arabic", "العربية", rtl = true, currency = "$"),
        Language("fr", "French", "Français", currency = "€"),
        Language("es", "Spanish", "Español", currency = "€"),
        Language("it", "Italian", "Italiano", currency = "€"),
        Language("pt", "Portuguese", "Português", currency = "€"),
        Language("tr", "Turkish", "Türkçe", currency = "₺"),
        Language("nl", "Dutch", "Nederlands", currency = "€"),
        Language("ru", "Russian", "Русский", currency = "₽"),
        Language("pl", "Polish", "Polski", currency = "zł"),
    )

    fun get(code: String): Language = ALL.firstOrNull { it.code == code } ?: ALL[0]
}

/**
 * Loads language packs and the user's learned data for each language, off the main thread.
 * Keeps the two most recently used languages in memory so switching back is instant.
 */
class LanguageManager(private val context: Context, private val store: LearningStore) {
    private val executor = Executors.newSingleThreadExecutor { r -> Thread(r, "language-loader").apply { priority = Thread.NORM_PRIORITY - 1 } }
    private val main = Handler(Looper.getMainLooper())
    private val models = LinkedHashMap<String, LanguageModel>(4, 0.75f, true)
    private val lexicons = HashMap<String, UserLexicon>()

    @Synchronized fun loaded(lang: String): LanguageModel? = models[lang]

    /** The user's lexicon for [lang], loaded on demand (also used by the settings screens). */
    fun lexicon(lang: String): UserLexicon = synchronized(lexicons) {
        lexicons.getOrPut(lang) {
            UserLexicon(lang).also {
                store.load(it)
                it.listener = store
            }
        }
    }

    fun load(lang: String, onReady: (LanguageModel) -> Unit) {
        loaded(lang)?.let { onReady(it); return }
        executor.execute {
            val model = loadBlocking(lang)
            main.post { onReady(model) }
        }
    }

    fun loadBlocking(lang: String): LanguageModel {
        loaded(lang)?.let { return it }
        val dict = context.assets.open("dict/$lang.dict").use { BinaryDictionary.load(it) }
        dict.trie // build the prefix tree now rather than on the first keystroke
        val model = LanguageModel(dict, lexicon(lang))
        synchronized(this) {
            models[lang] = model
            while (models.size > MAX_LOADED) models.remove(models.keys.first())
        }
        return model
    }

    fun runInBackground(task: () -> Unit) = executor.execute(task)

    /** Re-reads learned data (after a backup import or reset) into the live lexicons. */
    fun reloadUserData() {
        val all = synchronized(lexicons) { lexicons.values.toList() }
        for (lex in all) {
            lex.listener = null
            lex.resetMemory()
            store.load(lex)
            lex.listener = store
        }
    }

    companion object {
        private const val MAX_LOADED = 2
    }
}

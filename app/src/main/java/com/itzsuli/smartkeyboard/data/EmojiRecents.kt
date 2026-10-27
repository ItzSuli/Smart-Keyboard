package com.itzsuli.smartkeyboard.data

/**
 * The emoji you used last, newest first: from the emoji panel and from emoji suggestions.
 * Until you've used any, a handful of popular ones stand in.
 */
class EmojiRecents(private val settings: Settings) {
    private val list: MutableList<String> by lazy {
        settings.prefs.getString(Settings.EMOJI_RECENTS, "")!!.split(SEP).filter { it.isNotEmpty() }.toMutableList()
    }

    /** What the "Recently used" section shows. */
    @Synchronized fun shown(): List<String> = list.toList().ifEmpty { STARTERS }

    @Synchronized fun used(): List<String> = list.toList()

    @Synchronized fun add(emoji: String) {
        if (emoji.isBlank()) return
        list.remove(emoji)
        list.add(0, emoji)
        while (list.size > MAX) list.removeAt(list.size - 1)
        settings.prefs.edit().putString(Settings.EMOJI_RECENTS, list.joinToString(SEP.toString())).apply()
    }

    companion object {
        private const val SEP = '\u0001'
        private const val MAX = 32
        val STARTERS = listOf("😂", "❤️", "😭", "🙏", "🔥", "😍", "🥺", "👍", "😊", "💀", "✨", "🥰", "😅", "👀", "🤣", "💯")
    }
}

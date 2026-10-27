package com.itzsuli.smartkeyboard.data

import android.content.Context
import android.graphics.Paint

class Emoji(val value: String, val name: String, val variants: List<String>)
class EmojiGroup(val name: String, val icon: String, val emoji: List<Emoji>)

/** Emoji catalogue (Unicode emoji-test.txt), filtered to what this phone can draw. */
class EmojiData private constructor(val groups: List<EmojiGroup>, private val byWord: Map<String, String>) {

    /** An emoji for a typed word ("pizza" → 🍕, "liebe" → ❤️), if there is an obvious one. */
    fun forWord(word: String): String? = byWord[word.lowercase()]

    companion object {
        @Volatile private var instance: EmojiData? = null

        fun get(context: Context): EmojiData = instance ?: synchronized(this) {
            instance ?: load(context).also { instance = it }
        }

        private val GROUP_ICONS = mapOf(
            "Smileys & Emotion" to "😀", "People & Body" to "👋", "Animals & Nature" to "🐶", "Food & Drink" to "🍔",
            "Travel & Places" to "✈️", "Activities" to "⚽", "Objects" to "💡", "Symbols" to "❤️", "Flags" to "🏳️",
        )

        /** Hand-picked chat words in the supported languages. */
        private val KEYWORDS = mapOf(
            "love" to "❤️", "heart" to "❤️", "lol" to "😂", "lmao" to "😂", "haha" to "😂", "hahaha" to "😂", "funny" to "😂",
            "happy" to "😊", "smile" to "😊", "sad" to "😢", "cry" to "😭", "crying" to "😭", "fire" to "🔥", "lit" to "🔥",
            "ok" to "👌", "okay" to "👌", "yes" to "👍", "thanks" to "🙏", "thank" to "🙏", "please" to "🙏", "cool" to "😎",
            "party" to "🎉", "congrats" to "🎉", "birthday" to "🎂", "pizza" to "🍕", "coffee" to "☕", "beer" to "🍺",
            "sleep" to "😴", "tired" to "😴", "angry" to "😠", "mad" to "😡", "kiss" to "😘", "wow" to "😮", "omg" to "😱",
            "dead" to "💀", "skull" to "💀", "think" to "🤔", "hmm" to "🤔", "money" to "💰", "music" to "🎵", "sun" to "☀️",
            "rain" to "🌧️", "snow" to "❄️", "car" to "🚗", "home" to "🏠", "phone" to "📱", "food" to "🍔", "hungry" to "🤤",
            "sick" to "🤒", "strong" to "💪", "gym" to "💪", "eyes" to "👀", "clap" to "👏", "hi" to "👋", "hello" to "👋",
            "bye" to "👋", "football" to "⚽", "soccer" to "⚽", "game" to "🎮", "gaming" to "🎮", "star" to "⭐", "cat" to "🐱",
            "dog" to "🐶", "rocket" to "🚀", "100" to "💯", "perfect" to "💯", "sorry" to "😔", "shy" to "😳", "cute" to "🥰",
            "crazy" to "🤪", "sus" to "🤨", "cap" to "🧢", "goat" to "🐐", "shit" to "💩", "poop" to "💩",
            // German
            "liebe" to "❤️", "herz" to "❤️", "danke" to "🙏", "bitte" to "🙏", "glücklich" to "😊", "traurig" to "😢",
            "geil" to "🔥", "krass" to "🤯", "geburtstag" to "🎂", "kaffee" to "☕", "bier" to "🍺", "müde" to "😴",
            "hunger" to "🤤", "hallo" to "👋", "tschüss" to "👋", "fußball" to "⚽", "hund" to "🐶", "katze" to "🐱",
            "lach" to "😂", "lachen" to "😂", "sonne" to "☀️", "auto" to "🚗", "geld" to "💰", "kuss" to "😘",
            // Arabic
            "حب" to "❤️", "احبك" to "❤️", "قلب" to "❤️", "شكرا" to "🙏", "هههه" to "😂", "ههههه" to "😂", "ههه" to "😂",
            "سعيد" to "😊", "حزين" to "😢", "نار" to "🔥", "قهوة" to "☕", "مرحبا" to "👋", "هلا" to "👋", "يلا" to "🏃",
            "والله" to "🤞", "ماشاءالله" to "🤩", "الحمدلله" to "🤲", "انشالله" to "🤲",
            // French / Spanish / Italian / Turkish
            "amour" to "❤️", "merci" to "🙏", "mdr" to "😂", "amor" to "❤️", "gracias" to "🙏", "jaja" to "😂", "jajaja" to "😂",
            "grazie" to "🙏", "amore" to "❤️", "teşekkürler" to "🙏", "aşk" to "❤️",
        )

        private fun load(context: Context): EmojiData {
            val paint = Paint()
            val groups = ArrayList<EmojiGroup>()
            val byWord = HashMap<String, String>(KEYWORDS)
            var name: String? = null
            var current = ArrayList<Emoji>()
            fun flush() {
                val n = name ?: return
                if (current.isNotEmpty()) groups.add(EmojiGroup(n, GROUP_ICONS[n] ?: current.first().value, current))
                current = ArrayList()
            }
            context.assets.open("emoji/emoji.txt").bufferedReader().useLines { lines ->
                for (line in lines) {
                    if (line.startsWith("#")) {
                        flush()
                        name = line.substring(1)
                        continue
                    }
                    val f = line.split('\t')
                    if (f.size < 4) continue
                    val value = f[0]
                    if (!paint.hasGlyph(value)) continue
                    val variants = if (f[2].isEmpty()) emptyList() else f[2].split(',').filter { paint.hasGlyph(it) }
                    current.add(Emoji(value, f[3], variants))
                    // Single-word names make good suggestions ("pizza", "rocket", "dog face" → dog).
                    val words = f[3].removeSuffix(" face").split(' ')
                    if (words.size == 1 && words[0].length > 2) byWord.putIfAbsent(words[0], value)
                }
            }
            flush()
            return EmojiData(groups, byWord)
        }
    }
}

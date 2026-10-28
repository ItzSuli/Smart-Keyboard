package com.itzsuli.smartkeyboard.data

import android.content.Context
import android.graphics.Paint

class Emoji(val value: String, val name: String, val variants: List<String>)
class EmojiGroup(val name: String, val icon: String, val emoji: List<Emoji>)

/** Emoji catalogue (Unicode emoji-test.txt), filtered to what this phone can draw. */
class EmojiData private constructor(val groups: List<EmojiGroup>, private val byWord: Map<String, String>) {

    /**
     * An emoji for a typed word ("pizza" → 🍕, "sob" → 😭, "liebe" → ❤️), if there is an obvious
     * one. Laughter in any spelling ("hahaha", "looool", "xD", "هههه") and stretched words
     * ("sooob", "loooove") count too.
     */
    fun forWord(word: String): String? {
        val w = word.lowercase().trim('\'', '’')
        if (w.isEmpty()) return null
        byWord[w]?.let { return it }
        for ((pattern, emoji) in PATTERNS) if (pattern.matches(w)) return emoji
        if (w.length < 4) return null
        val once = STRETCH.replace(w, "$1")
        val twice = STRETCH.replace(w, "$1$1")
        return byWord[once] ?: byWord[twice]
    }

    companion object {
        @Volatile private var instance: EmojiData? = null

        fun get(context: Context): EmojiData = instance ?: synchronized(this) {
            instance ?: load(context).also { instance = it }
        }

        private val GROUP_ICONS = mapOf(
            "Smileys & Emotion" to "😀", "People & Body" to "👋", "Animals & Nature" to "🐶", "Food & Drink" to "🍔",
            "Travel & Places" to "✈️", "Activities" to "⚽", "Objects" to "💡", "Symbols" to "❤️", "Flags" to "🏳️",
        )

        private val STRETCH = Regex("(.)\\1{2,}")

        /** Laughter and other words people spell a hundred ways. */
        private val PATTERNS = listOf(
            Regex("(ha){2,}h?|a?(ha)+ha|(he){2,}h?|(hi){2,}|lo+l+|lo+o+l|lmf?a+o+|ro+fl|x+d+|(ja){2,}j?|(je){2,}|(ah){2,}|kek(w)?|ه{3,}|خخ+") to "😂",
            Regex("(sob)+|(wa)+h+|w+a+h+|bu+hu+|(boo)+hoo") to "😭",
            Regex("(<3)+|l+o+v+e+|i+ ?l+u+v+ ?u+") to "❤️",
            Regex("(ugh)+h*|u+g+h+") to "😩",
            Regex("y+a+y+") to "🥳",
            Regex("o+mg+|o+m+g+") to "😱",
            Regex("(z){3,}") to "😴",
        )

        /** Hand-picked chat words in the supported languages. */
        private val KEYWORDS = mapOf(
            // Crying, laughing and other feelings people text all the time.
            "sob" to "😭", "sobbing" to "😭", "bawling" to "😭", "tears" to "😭", "weep" to "😭", "weeping" to "😭", "dying" to "💀",
            "ded" to "💀", "rofl" to "🤣", "lmfao" to "🤣", "hehe" to "😁", "xd" to "😆", "smh" to "🤦", "facepalm" to "🤦",
            "shrug" to "🤷", "idk" to "🤷", "ugh" to "😩", "bruh" to "😐", "meh" to "😑", "yay" to "🥳", "woohoo" to "🥳",
            "hype" to "🔥", "slay" to "💅", "queen" to "👑", "king" to "👑", "pray" to "🙏", "praying" to "🙏", "blessed" to "😇",
            "angel" to "😇", "devil" to "😈", "evil" to "😈", "scared" to "😨", "afraid" to "😨", "shocked" to "😱",
            "surprised" to "😮", "nervous" to "😬", "awkward" to "😬", "cringe" to "😬", "oops" to "😅", "relieved" to "😌",
            "yum" to "😋", "yummy" to "😋", "delicious" to "😋", "sleepy" to "😴", "bored" to "🥱", "ill" to "🤒", "puke" to "🤮",
            "vomit" to "🤮", "gross" to "🤢", "nasty" to "🤢", "hot" to "🥵", "cold" to "🥶", "freezing" to "🥶", "nerd" to "🤓",
            "genius" to "🧠", "brain" to "🧠", "mindblown" to "🤯", "lovely" to "🥰", "adorable" to "🥰", "hug" to "🤗",
            "hugs" to "🤗", "wave" to "👋", "muscle" to "💪", "win" to "🏆", "winner" to "🏆", "done" to "✅", "nope" to "🙅",
            "stop" to "✋", "wait" to "⏳", "late" to "⏰", "night" to "🌙", "goodnight" to "🌙", "gn" to "🌙", "morning" to "☀️",
            "gm" to "☀️", "rainbow" to "🌈", "flower" to "🌸", "flowers" to "💐", "rose" to "🌹", "gift" to "🎁", "present" to "🎁",
            "cake" to "🎂", "cheers" to "🥂", "wine" to "🍷", "drunk" to "🥴", "burger" to "🍔", "fries" to "🍟", "taco" to "🌮",
            "sushi" to "🍣", "chocolate" to "🍫", "cookie" to "🍪", "popcorn" to "🍿", "movie" to "🎬", "film" to "🎬",
            "book" to "📚", "study" to "📚", "school" to "🏫", "work" to "💼", "laptop" to "💻", "camera" to "📷", "photo" to "📸",
            "selfie" to "🤳", "plane" to "✈️", "travel" to "✈️", "beach" to "🏖️", "holiday" to "🏖️", "vacation" to "🏖️",
            "run" to "🏃", "running" to "🏃", "dance" to "💃", "dancing" to "💃", "sing" to "🎤", "basketball" to "🏀",
            "boom" to "💥", "sparkle" to "✨", "magic" to "✨", "ghost" to "👻", "alien" to "👽", "clown" to "🤡", "chill" to "😎",
            "smirk" to "😏", "wink" to "😉", "silly" to "😜", "shh" to "🤫", "secret" to "🤫", "liar" to "🤥", "nice" to "👌",
            "great" to "👍", "good" to "👍", "lucky" to "🍀", "baby" to "👶", "pls" to "🥺", "plz" to "🥺", "beg" to "🥺",
            "upset" to "😢", "hurt" to "💔", "heartbroken" to "💔", "broken" to "💔", "wtf" to "🤨",
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
            "weinen" to "😭", "heul" to "😭", "heulen" to "😭", "wütend" to "😠", "sauer" to "😠", "umarmung" to "🤗",
            "feiern" to "🎉", "nacht" to "🌙", "schlafen" to "😴", "geschenk" to "🎁", "blumen" to "💐", "urlaub" to "🏖️",
            "strand" to "🏖️", "schule" to "🏫", "arbeit" to "💼", "super" to "👍", "toll" to "👍", "lecker" to "😋",
            "geil" to "🔥", "krass" to "🤯", "geburtstag" to "🎂", "kaffee" to "☕", "bier" to "🍺", "müde" to "😴",
            "hunger" to "🤤", "hallo" to "👋", "tschüss" to "👋", "fußball" to "⚽", "hund" to "🐶", "katze" to "🐱",
            "lach" to "😂", "lachen" to "😂", "sonne" to "☀️", "auto" to "🚗", "geld" to "💰", "kuss" to "😘",
            // Arabic
            "حب" to "❤️", "احبك" to "❤️", "قلب" to "❤️", "شكرا" to "🙏", "هههه" to "😂", "ههههه" to "😂", "ههه" to "😂",
            "سعيد" to "😊", "حزين" to "😢", "نار" to "🔥", "قهوة" to "☕", "مرحبا" to "👋", "هلا" to "👋", "يلا" to "🏃",
            "والله" to "🤞", "ماشاءالله" to "🤩", "الحمدلله" to "🤲", "انشالله" to "🤲",
            "حبيبي" to "❤️", "حبيبتي" to "❤️", "قلبي" to "❤️", "روحي" to "❤️", "زعلان" to "😢", "زعلانة" to "😢", "بكي" to "😭",
            "تعبان" to "😩", "تعبانة" to "😩", "جوعان" to "🤤", "نايم" to "😴", "عيد" to "🎉", "مبروك" to "🎉", "صباح" to "☀️",
            "ورد" to "🌹", "تمام" to "👌", "حلو" to "😍", "حلوة" to "😍",
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

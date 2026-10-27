package com.itzsuli.smartkeyboard.engine

/**
 * Everything the keyboard has learned about how one person types in one language: their own
 * words and slang, the word sequences they use, which corrections they accept and which ones
 * they undo. Lives in memory; [listener] mirrors every change to persistent storage.
 */
class UserLexicon(val lang: String) {

    class Word(
        val key: String,
        var display: String,
        var count: Int,
        var lastUsed: Long,
        var flags: Int,
    ) {
        val isBlocked get() = flags and BLOCKED != 0
        /** Confirmed words are suggested and never corrected away. */
        val isConfirmed get() = flags and (CONFIRMED or MANUAL) != 0 || count >= CONFIRM_COUNT
        val isSlang get() = flags and SLANG != 0
        val isManual get() = flags and MANUAL != 0
        /** A dictionary word the user writes their own way ("LOL", "haus"), out of habit. */
        val hasOwnCase get() = flags and OWN_CASE != 0
    }

    interface Listener {
        fun onWord(lang: String, word: Word) {}
        fun onWordRemoved(lang: String, key: String) {}
        fun onNgram(lang: String, ngram: String, count: Int) {}
        fun onCorrection(lang: String, typed: String, target: String, count: Int) {}
        fun onRejection(lang: String, typed: String, target: String, count: Int) {}
        fun onCleared(lang: String) {}
    }

    var listener: Listener? = null

    private val words = HashMap<String, Word>()
    private val bigrams = HashMap<String, Int>()
    private val trigrams = HashMap<String, Int>()
    private val followers = HashMap<String, HashMap<String, Int>>()
    private val headCounts = HashMap<String, Int>()
    private val pairFollowers = HashMap<String, HashMap<String, Int>>()
    private val corrections = HashMap<String, HashMap<String, Int>>()
    private val rejections = HashMap<String, Int>()
    /** A new way of writing a word, seen once; it takes over if the next use is the same. */
    private val pendingCase = HashMap<String, String>()

    /** Words committed while learning was on (drives how much the user model is trusted). */
    var totalTokens: Long = 0
        private set

    @Synchronized fun word(key: String): Word? = words[key]

    @Synchronized fun allWords(): List<Word> = words.values.toList()

    @Synchronized fun wordCount(): Int = words.size

    @Synchronized fun isKnown(key: String): Boolean = words[key]?.let { it.isConfirmed && !it.isBlocked } ?: false

    @Synchronized fun isBlocked(key: String): Boolean = words[key]?.isBlocked ?: false

    @Synchronized fun count(key: String): Int = words[key]?.count ?: 0

    @Synchronized fun bigramCount(a: String, b: String): Int = bigrams[a + SEP + b] ?: 0

    @Synchronized fun trigramCount(a: String, b: String, c: String): Int = trigrams["$a$SEP$b$SEP$c"] ?: 0

    /** Total number of bigrams starting with [prev]. */
    @Synchronized fun headCount(prev: String): Int = headCounts[prev] ?: 0

    @Synchronized fun followersOf(prev: String): Map<String, Int> = followers[prev]?.let { HashMap(it) } ?: emptyMap()

    @Synchronized fun followersOf(prev2: String, prev1: String): Map<String, Int> =
        pairFollowers[prev2 + SEP + prev1]?.let { HashMap(it) } ?: emptyMap()

    /** How often the user kept [typed] corrected to each target. */
    @Synchronized fun correctionsFor(typed: String): Map<String, Int> = corrections[typed]?.let { HashMap(it) } ?: emptyMap()

    @Synchronized fun allCorrections(): List<Triple<String, String, Int>> =
        corrections.flatMap { (typed, targets) -> targets.map { Triple(typed, it.key, it.value) } }

    @Synchronized fun rejectionCount(typed: String, target: String): Int = rejections[typed + SEP + target] ?: 0

    /** Confirmed, non-blocked words; used for completions, corrections and swipe. */
    @Synchronized fun activeWords(): List<Word> = words.values.filter { it.isConfirmed && !it.isBlocked }

    // ---- learning ------------------------------------------------------------------------

    /**
     * Records that the user committed [display]. [baseDisplay] is how the language pack writes
     * the word, or null when it doesn't know it (such words become slang once confirmed).
     * [caseNeutral] marks capitals the situation explains (a sentence start, caps lock): they say
     * nothing about how the user writes the word, so they're never learned.
     */
    @Synchronized fun learnWord(display: String, baseDisplay: String?, now: Long, confirmed: Boolean = false, caseNeutral: Boolean = false): Word {
        val key = TextUtils.key(display, lang)
        val written = if (caseNeutral) baseDisplay ?: TextUtils.lowercase(display, lang) else display
        val w = words.getOrPut(key) { Word(key, baseDisplay ?: written, 0, now, 0) }
        w.count++
        w.lastUsed = now
        // Another way of writing a word only takes over once it's a habit: the same form twice in
        // a row. One "What" mid-sentence never turns every "what" into "What".
        if (!caseNeutral && !w.isManual) {
            when {
                written == w.display -> pendingCase.remove(key)
                pendingCase[key] == written -> { applyDisplay(w, written, baseDisplay); pendingCase.remove(key) }
                else -> pendingCase[key] = written
            }
        }
        if (baseDisplay == null) w.flags = w.flags or SLANG
        if (confirmed) w.flags = w.flags or CONFIRMED
        totalTokens++
        listener?.onWord(lang, w)
        return w
    }

    /** Sets how [key] is written; [baseDisplay] is the dictionary's form (null if it has none). */
    @Synchronized fun setDisplay(key: String, display: String, baseDisplay: String?) {
        val w = words[key] ?: return
        applyDisplay(w, display, baseDisplay)
        pendingCase.remove(key)
        listener?.onWord(lang, w)
    }

    private fun applyDisplay(w: Word, display: String, baseDisplay: String?) {
        w.display = display
        w.flags = if (baseDisplay != null && display != baseDisplay) w.flags or OWN_CASE else w.flags and OWN_CASE.inv()
    }

    @Synchronized fun addManualWord(display: String, now: Long) {
        val key = TextUtils.key(display, lang)
        val w = words.getOrPut(key) { Word(key, display, 0, now, 0) }
        w.display = display
        w.flags = (w.flags or MANUAL or CONFIRMED) and BLOCKED.inv()
        w.lastUsed = now
        listener?.onWord(lang, w)
    }

    @Synchronized fun confirm(key: String) {
        val w = words[key] ?: return
        w.flags = w.flags or CONFIRMED
        listener?.onWord(lang, w)
    }

    @Synchronized fun block(display: String, now: Long) {
        val key = TextUtils.key(display, lang)
        val w = words.getOrPut(key) { Word(key, display, 0, now, 0) }
        w.flags = (w.flags or BLOCKED) and (CONFIRMED or MANUAL).inv()
        listener?.onWord(lang, w)
    }

    @Synchronized fun unblock(key: String) {
        val w = words[key] ?: return
        w.flags = w.flags and BLOCKED.inv()
        listener?.onWord(lang, w)
    }

    @Synchronized fun remove(key: String) {
        if (words.remove(key) != null) listener?.onWordRemoved(lang, key)
        corrections.remove(key)
    }

    @Synchronized fun learnBigram(a: String, b: String) {
        val k = a + SEP + b
        val c = (bigrams[k] ?: 0) + 1
        bigrams[k] = c
        followers.getOrPut(a) { HashMap() }[b] = c
        headCounts[a] = (headCounts[a] ?: 0) + 1
        listener?.onNgram(lang, k, c)
    }

    @Synchronized fun learnTrigram(a: String, b: String, c: String) {
        val k = "$a$SEP$b$SEP$c"
        val n = (trigrams[k] ?: 0) + 1
        trigrams[k] = n
        pairFollowers.getOrPut(a + SEP + b) { HashMap() }[c] = n
        listener?.onNgram(lang, k, n)
    }

    /** The user accepted (or applied through AI proofreading) [typed] → [target]. */
    @Synchronized fun learnCorrection(typed: String, target: String) {
        if (typed == target) return
        val m = corrections.getOrPut(typed) { HashMap() }
        val c = (m[target] ?: 0) + 1
        m[target] = c
        listener?.onCorrection(lang, typed, target, c)
    }

    /** Forgets a learned fix without blocking it: the keyboard may still make it by itself. */
    @Synchronized fun forgetCorrection(typed: String, target: String) {
        val m = corrections[typed] ?: return
        if (m.remove(target) == null) return
        if (m.isEmpty()) corrections.remove(typed)
        listener?.onCorrection(lang, typed, target, 0)
    }

    /** The user undid an autocorrection of [typed] into [target]. */
    @Synchronized fun learnRejection(typed: String, target: String) {
        val k = typed + SEP + target
        val c = (rejections[k] ?: 0) + 1
        rejections[k] = c
        corrections[typed]?.remove(target)
        listener?.onRejection(lang, typed, target, c)
    }

    @Synchronized fun clear() {
        words.clear(); bigrams.clear(); trigrams.clear(); followers.clear(); headCounts.clear(); pairFollowers.clear()
        corrections.clear(); rejections.clear(); pendingCase.clear(); totalTokens = 0
        listener?.onCleared(lang)
    }

    // ---- bulk loading from storage (does not notify the listener) --------------------------

    /** Empties the in-memory copy only (used before reloading from storage). */
    @Synchronized fun resetMemory() {
        words.clear(); bigrams.clear(); trigrams.clear(); followers.clear(); headCounts.clear(); pairFollowers.clear()
        corrections.clear(); rejections.clear(); pendingCase.clear(); totalTokens = 0
    }

    @Synchronized fun restoreWord(key: String, display: String, count: Int, lastUsed: Long, flags: Int) {
        words[key] = Word(key, display, count, lastUsed, flags)
        totalTokens += count
    }

    @Synchronized fun restoreNgram(ngram: String, count: Int) {
        val parts = ngram.split(SEP)
        when (parts.size) {
            2 -> {
                bigrams[ngram] = count
                followers.getOrPut(parts[0]) { HashMap() }[parts[1]] = count
                headCounts[parts[0]] = (headCounts[parts[0]] ?: 0) + count
            }
            3 -> {
                trigrams[ngram] = count
                pairFollowers.getOrPut(parts[0] + SEP + parts[1]) { HashMap() }[parts[2]] = count
            }
        }
    }

    @Synchronized fun restoreCorrection(typed: String, target: String, count: Int) {
        corrections.getOrPut(typed) { HashMap() }[target] = count
    }

    @Synchronized fun restoreRejection(typed: String, target: String, count: Int) {
        rejections[typed + SEP + target] = count
    }

    companion object {
        const val SEP = '\u0001'
        const val CONFIRMED = 1
        const val SLANG = 2
        const val BLOCKED = 4
        const val MANUAL = 8
        const val OWN_CASE = 16
        /** Typing a word this many times teaches it, no questions asked. */
        const val CONFIRM_COUNT = 2
    }
}

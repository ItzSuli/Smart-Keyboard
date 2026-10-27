package com.itzsuli.smartkeyboard.ime

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.InputType
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import com.itzsuli.smartkeyboard.SmartKeyboardApp
import com.itzsuli.smartkeyboard.data.AiAutoProof
import com.itzsuli.smartkeyboard.data.EmojiData
import com.itzsuli.smartkeyboard.engine.Arabizi
import com.itzsuli.smartkeyboard.engine.AutoCorrectMode
import com.itzsuli.smartkeyboard.engine.ContextWords
import com.itzsuli.smartkeyboard.engine.KeyGeometry
import com.itzsuli.smartkeyboard.engine.KeyInfo
import com.itzsuli.smartkeyboard.engine.KeyPredictor
import com.itzsuli.smartkeyboard.engine.LanguageMix
import com.itzsuli.smartkeyboard.engine.LanguageModel
import com.itzsuli.smartkeyboard.engine.MixedSuggester
import com.itzsuli.smartkeyboard.engine.recentWords
import com.itzsuli.smartkeyboard.engine.SpatialModel
import com.itzsuli.smartkeyboard.engine.Suggester
import com.itzsuli.smartkeyboard.engine.Suggestion
import com.itzsuli.smartkeyboard.engine.SuggestionResult
import com.itzsuli.smartkeyboard.engine.SwipeDecoder
import com.itzsuli.smartkeyboard.engine.TextUtils
import com.itzsuli.smartkeyboard.engine.TouchModel
import com.itzsuli.smartkeyboard.engine.TypedChar
import java.text.BreakIterator
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * Everything that turns key presses into text: the word being typed (composing text),
 * autocorrect and its undo, suggestions, swipe words, learning and AI proofreading.
 * The service owns the views; this class owns the text.
 */
class InputLogic(private val service: SmartKeyboardService) {

    private val app = SmartKeyboardApp.of(service)
    private val settings = app.settings
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "typing-engine").apply { priority = Thread.NORM_PRIORITY + 1 } }
    private val generation = AtomicInteger()
    private val swipeGeneration = AtomicInteger()

    private val ic: InputConnection? get() = service.currentInputConnection

    // ---- current field ----
    private var editorInfo: EditorInfo? = null
    var lang = "en"; private set
    private var allowSuggestions = true
    private var allowLearning = true
    private var allowAutocorrect = true
    private var allowAi = true
    var isPassword = false; private set
    /** The app being typed in, and the AI tone that goes with it (chill in chats, polite in email...). */
    private var appPackage: String? = null
    val tone: com.itzsuli.smartkeyboard.data.AiTone get() = settings.toneFor(appPackage)

    // ---- engine ----
    /**
     * Everything needed to type in the keyboard's language plus any languages mixed into it
     * (e.g. German on the English keyboard). Index 0 is the keyboard's own language.
     */
    private class Engine(
        val models: List<LanguageModel>,
        val mixed: MixedSuggester,
        val predictors: List<KeyPredictor>,
        val decoders: List<SwipeDecoder>?,
    ) {
        val mix: LanguageMix get() = mixed.mix
        val primary: LanguageModel get() = models[0]
        fun isKnownAnywhere(key: String) = models.any { it.isKnown(key) }
    }

    @Volatile private var engine: Engine? = null
    @Volatile var keyProbabilities: Map<Char, Float>? = null; private set
    private var spatial: SpatialModel? = null
    private var geometry: KeyGeometry? = null
    private var geometryKey: String = ""
    /** Languages typed on the current keyboard: its own first, then the mixed-in ones. */
    var activeLanguages: List<String> = listOf("en"); private set
    val touchModel = TouchModel()
    private var touchUpdates = 0

    // ---- composing state ----
    private val composing = StringBuilder()
    private val touches = ArrayList<TypedChar>()
    private var composingFromSwipe = false
    private var swipeAlternatives: List<String> = emptyList()
    /** Our best knowledge of the cursor position after our own edits (-1 = unknown). */
    private var cursor = -1
    private var composingStart = -1
    /** Cursor positions our recent edits will produce; selection updates arrive late. */
    private val pendingCursors = ArrayDeque<Int>()
    private var result: SuggestionResult = SuggestionResult.EMPTY
    /** Which language produced [result]'s autocorrection. */
    private var resultAutoLanguage = -1
    private var stripList: List<Suggestion> = emptyList()

    // ---- Arabizi ----
    @Volatile private var arabizi: Arabizi? = null
    @Volatile private var arabiziModel: LanguageModel? = null

    // ---- adaptive keys learning ----
    /** Taps where the adaptive keys typed a neighbour of the key under the finger: index → (under finger, typed). */
    private val tapOverrides = HashMap<Int, Pair<Char, Char>>()
    private var pendingOverride: Pair<Char, Char>? = null
    val hitboxLearning = HitboxLearning(app.store)

    /** Called by the key resolver just before the tap is delivered. */
    fun onTapResolved(underFinger: Char, typed: Char) {
        pendingOverride = if (underFinger != typed) underFinger to typed else null
    }

    /** Last autocorrection, so backspace can undo it. */
    private class LastCorrection(val typed: String, val committed: String, var separator: String, val language: Int)
    private var lastCorrection: LastCorrection? = null
    /** A word brought back by undoing a correction: the next space keeps it exactly as typed. */
    private var keepAsTyped: String? = null
    private var autoSpaced = false
    private var lastSpaceAt = 0L
    private var deleteSelectionAnchor = -1

    // ---- AI ----
    private var aiChipFix: Triple<String, String, String>? = null // (original, fixed, trailing separator)
    private var aiUndo: Triple<String, String, String>? = null
    private var proofInFlight = false

    init {
        app.store.getValue(TOUCH_KEY)?.let { touchModel.restore(it) }
    }

    // ------------------------------------------------------------------ lifecycle

    fun startInput(info: EditorInfo, restarting: Boolean) {
        editorInfo = info
        resetComposing(finish = false)
        lastCorrection = null
        autoSpaced = false
        aiChipFix = null
        aiUndo = null
        val cls = info.inputType and InputType.TYPE_MASK_CLASS
        val variation = info.inputType and InputType.TYPE_MASK_VARIATION
        val flags = info.inputType and InputType.TYPE_MASK_FLAGS
        isPassword = cls == InputType.TYPE_CLASS_TEXT && variation in setOf(
            InputType.TYPE_TEXT_VARIATION_PASSWORD, InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD, InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD,
        ) || cls == InputType.TYPE_CLASS_NUMBER && variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD
        val webOrEmail = cls == InputType.TYPE_CLASS_TEXT && variation in setOf(
            InputType.TYPE_TEXT_VARIATION_URI, InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS, InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS,
        )
        val noPersonalLearning = info.imeOptions and EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING != 0
        allowSuggestions = cls == InputType.TYPE_CLASS_TEXT && !isPassword && settings.showSuggestions &&
            (flags and InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS == 0 || variation == InputType.TYPE_TEXT_VARIATION_FILTER)
        allowLearning = allowSuggestions && settings.learning && !settings.incognito && !noPersonalLearning
        allowAutocorrect = allowSuggestions && !webOrEmail && settings.autoCorrectMode != AutoCorrectMode.OFF
        allowAi = allowSuggestions && !webOrEmail && !settings.incognito && !noPersonalLearning
        appPackage = info.packageName
        info.packageName?.let { pkg ->
            if (pkg != service.packageName && !isPassword && allowLearning) settings.noteApp(pkg)
        }
        cursor = info.initialSelStart
        pendingCursors.clear()
        setLanguage(settings.currentLanguage)
        if (!restarting) refreshAfterCursorChange()
    }

    fun finishInput() {
        ic?.finishComposingText()
        resetComposing(finish = false)
        app.ai.cancelPredictions()
        saveTouchModel()
        hitboxLearning.save()
        app.store.flushAsync()
    }

    fun setLanguage(code: String) {
        lang = code
        val wanted = listOf(code) + settings.mixedLanguagesFor(code)
        activeLanguages = wanted
        setUpArabizi(code)
        val loaded = wanted.mapNotNull { app.languages.loaded(it) }
        if (loaded.size == wanted.size) {
            attachModels(loaded)
            return
        }
        engine = null
        val models = arrayOfNulls<LanguageModel>(wanted.size)
        wanted.forEachIndexed { i, l ->
            app.languages.load(l) { m ->
                models[i] = m
                if (activeLanguages == wanted && models.all { it != null }) {
                    attachModels(models.filterNotNull())
                    refreshSuggestions()
                }
            }
        }
    }

    /** Arabizi works on Latin keyboards when Arabic is one of the languages. */
    private fun setUpArabizi(code: String) {
        if (!(settings.arabizi && "ar" in settings.languages && code in com.itzsuli.smartkeyboard.data.Settings.LATIN_SCRIPT)) {
            arabizi = null
            arabiziModel = null
            return
        }
        if (arabizi != null) return
        app.languages.load("ar") { m ->
            if (lang == code && settings.arabizi && arabizi == null) {
                arabiziModel = m
                arabizi = Arabizi(m, app.languages.arabicChatWords)
            }
        }
    }

    private fun attachModels(models: List<LanguageModel>) {
        val suggesters = models.map { m -> Suggester(m, spatial).also { it.fixCapitalization = settings.fixCapitalization } }
        val decoders = geometry?.let { g -> models.map { SwipeDecoder(g) } }
        engine = Engine(models, MixedSuggester(LanguageMix(models), suggesters), models.map { KeyPredictor(it) }, decoders)
        if (decoders != null) worker.execute { decoders.forEachIndexed { i, d -> d.prepare(models[i].dict) } }
    }

    /** Called whenever the letter keys move (new layout, rotation, resize). */
    fun onKeyboardLaidOut(layout: KeyboardLayout) {
        if (layout.mode != KeyboardLayout.Mode.ALPHABET) return
        val letters = layout.keys.filter { it.isLetter }
        if (letters.isEmpty() || letters[0].bounds.width() <= 0f) return
        val key = layout.lang + letters.joinToString("") { "${it.label}${it.bounds.left.toInt()},${it.bounds.top.toInt()}" }
        if (key == geometryKey) return
        geometryKey = key
        val geometry = KeyGeometry(letters.map { k ->
            KeyInfo(TextUtils.lowercase(k.label, layout.lang)[0], k.bounds.centerX(), k.bounds.centerY(), k.bounds.width(), k.bounds.height())
        })
        this.geometry = geometry
        spatial = SpatialModel(geometry, touchModel)
        engine?.let { attachModels(it.models) }
    }

    // ------------------------------------------------------------------ helpers

    private val separatorsThatCorrect = " \n.,!?;:)]}\"»”…،؟؛"

    private fun textBefore(n: Int): CharSequence = ic?.getTextBeforeCursor(n, 0) ?: ""

    /** Text before the word being typed (the editor reports the composing word too; strip it). */
    private fun textBeforeWord(n: Int): String {
        val before = textBefore(n).toString()
        return if (composing.isNotEmpty() && before.endsWith(composing)) before.dropLast(composing.length) else before
    }

    private fun contextWords(): ContextWords = TextUtils.contextWords(textBeforeWord(80), lang)

    /** The last few words, which tell mixed languages apart. */
    private fun recentWords(): List<String> = TextUtils.recentWords(textBeforeWord(160), lang)

    private fun resetComposing(finish: Boolean) {
        if (finish && composing.isNotEmpty()) ic?.finishComposingText()
        composing.setLength(0)
        touches.clear()
        tapOverrides.clear()
        keepAsTyped = null
        composingFromSwipe = false
        swipeAlternatives = emptyList()
        result = SuggestionResult.EMPTY
        composingStart = -1
    }

    private fun applyShift(text: String): String = when (service.shiftState) {
        KeyboardView.ShiftState.OFF -> text
        KeyboardView.ShiftState.ON -> TextUtils.capitalize(text, lang)
        KeyboardView.ShiftState.LOCKED -> TextUtils.uppercase(text, lang)
    }

    private fun expect(pos: Int) {
        cursor = pos
        if (pos < 0) { pendingCursors.clear(); return }
        pendingCursors.addLast(pos)
        while (pendingCursors.size > 16) pendingCursors.removeFirst()
    }

    /** Commits [text], replacing the composing word if there is one. */
    private fun commit(text: String) {
        val conn = ic ?: return
        val base = if (composing.isNotEmpty()) composingStart else cursor
        conn.commitText(text, 1)
        expect(if (base >= 0) base + text.length else -1)
    }

    private fun setComposing(text: CharSequence) {
        val conn = ic ?: return
        if (composingStart < 0 && cursor >= 0) composingStart = cursor
        conn.setComposingText(text, 1)
        expect(if (composingStart >= 0) composingStart + text.length else -1)
    }

    private fun deleteBefore(n: Int) {
        val conn = ic ?: return
        conn.deleteSurroundingText(n, 0)
        expect(if (cursor >= 0) cursor - n else -1)
    }

    // ------------------------------------------------------------------ cursor tracking

    fun onUpdateSelection(newSelStart: Int, newSelEnd: Int, candidatesStart: Int, candidatesEnd: Int) {
        if (newSelStart == newSelEnd && pendingCursors.contains(newSelStart)) {
            // One of our own edits (possibly a late report of an earlier one).
            while (pendingCursors.isNotEmpty() && pendingCursors.removeFirst() != newSelStart) { /* drop older */ }
            if (cursor < 0) cursor = newSelStart
            return
        }
        if (composing.isNotEmpty() && candidatesStart >= 0 && newSelStart == newSelEnd && candidatesEnd == newSelEnd &&
            candidatesEnd - candidatesStart == composing.length) {
            // The editor agrees with our composing word: nothing changed behind our back.
            cursor = newSelStart
            composingStart = candidatesStart
            return
        }
        if (deleteSelectionAnchor >= 0) return
        // The user moved the cursor or the app changed the text: start over at the new spot.
        pendingCursors.clear()
        cursor = newSelStart
        if (composing.isNotEmpty()) {
            ic?.finishComposingText()
            resetComposing(finish = false)
        }
        lastCorrection = null
        autoSpaced = false
        aiChipFix?.let { aiChipFix = null; service.showAiChip(null) }
        if (newSelStart == newSelEnd) refreshAfterCursorChange()
    }

    private fun refreshAfterCursorChange() {
        service.updateShiftFromEditor()
        refreshSuggestions()
    }

    // ------------------------------------------------------------------ typing

    fun onCharacter(raw: String, x: Float = Float.NaN, y: Float = Float.NaN) {
        aiUndo = null
        val override = pendingOverride
        pendingOverride = null
        val text = applyShift(raw)
        // With Arabizi, 2 3 5 6 7 8 9 are letters ("7abibi"), unless they continue a number.
        val arabiziDigit = arabizi != null && text.length == 1 && text[0] in Arabizi.DIGITS &&
            (composing.isNotEmpty() || textBefore(1).let { it.isEmpty() || !it[0].isDigit() })
        val isWordPart = (text.all { TextUtils.isWordChar(it) } || arabiziDigit) &&
            !(text == "'" && composing.isEmpty()) // a lone apostrophe starts a quote, not a word
        if (isWordPart && allowSuggestions) {
            // A letter tapped right after a swiped word starts a new word ("hello" + "I" → "hello I").
            if (composingFromSwipe && composing.isNotEmpty() && text.any { it.isLetter() }) {
                ic?.let { conn ->
                    conn.beginBatchEdit()
                    commitComposing(autocorrect = false, separator = " ")
                    conn.endBatchEdit()
                }
            }
            if (composing.isEmpty()) composingStart = cursor
            composing.append(text)
            if (override != null && text.length == 1) tapOverrides[composing.length - 1] = override
            if (text.length == 1) touches.add(TypedChar(text[0], x, y)) else text.forEach { touches.add(TypedChar(it)) }
            setComposing(composing)
            autoSpaced = false
            lastCorrection = null
            service.afterCharacterTyped()
            refreshSuggestions()
            return
        }
        if (isWordPart) {
            // No suggestions in this field: plain typing.
            commit(text)
            service.afterCharacterTyped()
            return
        }
        onSeparatorOrSymbol(text)
    }

    /** Inserts text exactly as given (clipboard paste): no shift, no autocorrect, no learning. */
    fun onPaste(text: String) {
        val conn = ic ?: return
        conn.beginBatchEdit()
        if (composing.isNotEmpty()) commitComposing(autocorrect = false, separator = "")
        commit(text)
        conn.endBatchEdit()
        autoSpaced = false
        lastCorrection = null
        service.updateShiftFromEditor()
        refreshSuggestions()
    }

    private fun onSeparatorOrSymbol(text: String) {
        val c = text[0]
        val conn = ic ?: return
        conn.beginBatchEdit()
        try {
            val corrects = text.length == 1 && c in separatorsThatCorrect
            val corrected = if (composing.isNotEmpty()) commitComposing(autocorrect = corrects, separator = "") else null
            val beforeSeparator = cursor
            val now = SystemClock.uptimeMillis()
            when {
                // "word ␣␣" → "word. "
                c == ' ' && settings.doubleSpacePeriod && now - lastSpaceAt < DOUBLE_SPACE_MS && canDoubleSpacePeriod() -> {
                    deleteBefore(1)
                    commit(". ")
                    lastSpaceAt = 0
                    autoSpaced = false
                    sentenceEnded()
                }
                // "word␣" + "." → "word. " (the automatic space moves behind the punctuation)
                autoSpaced && text.length == 1 && c in ".,!?;:)…،؟" && textBefore(1) == " " -> {
                    deleteBefore(1)
                    commit(if (c == ')') ") " else "$c ")
                    autoSpaced = true
                    if (c in ".!?…؟") sentenceEnded()
                }
                else -> {
                    commit(text)
                    autoSpaced = false
                    if (c == ' ') lastSpaceAt = now
                    if (c in ".!?…؟") lastSpaceAt = 0
                }
            }
            // Backspace must undo the correction together with whatever was typed after it.
            if (corrected != null && beforeSeparator >= 0 && cursor >= beforeSeparator) {
                corrected.separator = textBefore(cursor - beforeSeparator).toString()
                lastCorrection = corrected
            }
            if (c == ' ' || c == '\n') maybeProofreadLastSentence()
        } finally {
            conn.endBatchEdit()
        }
        service.afterSeparator()
        refreshSuggestions()
    }

    private fun canDoubleSpacePeriod(): Boolean {
        val before = textBefore(3)
        if (before.length < 2 || before[before.length - 1] != ' ') return false
        val prev = before[before.length - 2]
        return Character.isLetterOrDigit(prev) || prev in "\"')]”»"
    }

    private fun sentenceEnded() = maybeProofreadLastSentence()

    /**
     * Finishes the current word. With [autocorrect], a clear typo is replaced by the best
     * correction, which backspace can undo. Every committed word feeds the learning.
     */
    private fun commitComposing(autocorrect: Boolean, separator: String): LastCorrection? {
        if (composing.isEmpty()) return null
        val typed = composing.toString()
        val ctx = contextWords()
        var final = typed
        var autoLanguage = -1
        if (autocorrect && allowAutocorrect && !composingFromSwipe && typed != keepAsTyped) {
            if (result.typed == typed) {
                result.autoCorrect?.let { final = it.text; autoLanguage = resultAutoLanguage }
            } else engine?.let { e ->
                // Typed faster than suggestions came back: decide right here.
                val d = decide(e, typed, touches.toList(), ctx, recentWords(), settings.autoCorrectMode, wordSurroundings())
                d.result.autoCorrect?.let { final = it.text; autoLanguage = d.autoLanguage }
            }
        }
        val typedTouches = touches.toList()
        val fromSwipe = composingFromSwipe
        val recent = recentWords()
        // Letters the adaptive keys picked that survived into the word were good calls.
        for ((i, pair) in tapOverrides) if (final.getOrNull(i)?.lowercaseChar() == pair.second) hitboxLearning.kept(pair.first, pair.second)
        commit(final + separator)
        resetComposing(finish = false)
        if (autoLanguage != SHORTCUT) learn(final, ctx, recent, typedTouches, fromSwipe)
        if (final == typed) return null
        app.store.incrementStat("autocorrections")
        return LastCorrection(typed, final, separator, autoLanguage).also { lastCorrection = it }
    }

    /** Learns a committed word into the language it belongs to (or the one being written). */
    private fun learn(word: String, ctx: ContextWords, recent: List<String>, typedTouches: List<TypedChar>, fromSwipe: Boolean) {
        if (!allowLearning) return
        val e = engine ?: return
        val now = System.currentTimeMillis()
        if (word.any { it.isWhitespace() }) return
        val shouting = isShouting(word)
        if (word.any { it in '\u0600'..'\u06FF' }) {
            // Arabic written on this Latin keyboard (Arabizi): it belongs to the Arabic model.
            val m = arabiziModel ?: return
            worker.execute { m.learn(word, ctx, now) }
            return
        }
        worker.execute {
            val target = e.mix.learnTarget(TextUtils.key(word, lang), e.mix.weights(recent))
            e.models[target].learn(word, ctx, now, shouting = shouting)
            app.store.incrementStat(if (fromSwipe) "words_swiped" else "words_typed")
            // Where does this person really hit each key? Align taps with the final letters.
            val sp = spatial ?: return@execute
            if (fromSwipe || typedTouches.size != word.length) return@execute
            for ((i, t) in typedTouches.withIndex()) {
                if (!t.hasPoint) continue
                val intended = TextUtils.lowercase(word[i].toString(), lang)[0]
                val key = sp.geometry.keyFor(intended) ?: continue
                if (sp.keyDistance(TextUtils.lowercase(t.char.toString(), lang)[0], intended) > 1.6f) continue
                touchModel.update(key.char, (t.x - key.cx) / key.width, (t.y - key.cy) / key.height)
                touchUpdates++
            }
            if (touchUpdates > 60) saveTouchModel()
        }
    }

    /**
     * True when [word], just written, is in capitals like the word before it: a shouted message
     * (caps lock), which says nothing about how the user normally writes the word.
     */
    private fun isShouting(word: String): Boolean {
        if (TextUtils.caseOf(word) != TextUtils.Case.UPPER) return false
        val before = textBefore(80).toString().trimEnd { !TextUtils.isWordChar(it) }
        if (!before.endsWith(word)) return false
        val previous = before.dropLast(word.length).trimEnd { !TextUtils.isWordChar(it) }.takeLastWhile { TextUtils.isWordChar(it) }
        return TextUtils.caseOf(previous) == TextUtils.Case.UPPER
    }

    private fun saveTouchModel() {
        touchUpdates = 0
        app.store.putValue(TOUCH_KEY, touchModel.serialize())
    }

    /**
     * Enter does what the app asks for, exactly like the stock Android keyboard: Send in a chat
     * (Snapchat, WhatsApp...), Search, Go, Next or Done, even in fields that can hold several
     * lines. Only fields that ask for plain new lines get one. Long-press enter always types a
     * new line ([onNewLine]).
     */
    fun onEnter() {
        val action = enterAction(editorInfo)
        if (action == null) { onNewLine(); return }
        val conn = ic ?: return
        conn.beginBatchEdit()
        if (composing.isNotEmpty()) commitComposing(autocorrect = true, separator = "")
        conn.endBatchEdit()
        autoSpaced = false
        lastCorrection = null
        conn.performEditorAction(action)
        service.afterSeparator()
        refreshSuggestions()
    }

    fun onNewLine() {
        val conn = ic ?: return
        conn.beginBatchEdit()
        val corrected = if (composing.isNotEmpty()) commitComposing(autocorrect = true, separator = "") else null
        commit("\n")
        conn.endBatchEdit()
        autoSpaced = false
        corrected?.let { it.separator = "\n"; lastCorrection = it }
        maybeProofreadLastSentence()
        service.afterSeparator()
        refreshSuggestions()
    }

    // ------------------------------------------------------------------ deleting

    /** Returns false when there was nothing to delete (so a held delete stops vibrating). */
    fun onDelete(): Boolean {
        val conn = ic ?: return false
        aiUndo = null
        // Backspace right after an autocorrection brings back what was typed — and remembers it.
        val lc = lastCorrection
        if (lc != null && composing.isEmpty()) {
            val expect = lc.committed + lc.separator
            if (textBefore(expect.length).toString() == expect) {
                lastCorrection = null
                conn.beginBatchEdit()
                deleteBefore(expect.length)
                composingStart = cursor
                composing.append(lc.typed)
                lc.typed.forEach { touches.add(TypedChar(it)) }
                setComposing(composing)
                conn.endBatchEdit()
                keepAsTyped = lc.typed
                // Remembered right away (cheap, in memory), so the very next space respects it.
                val e = engine
                if (e != null && allowLearning && lc.language != SHORTCUT) {
                    // Remembered by the language that made the correction.
                    val m = e.models.getOrNull(lc.language) ?: e.primary
                    m.user.learnRejection(TextUtils.key(lc.typed, lang), TextUtils.key(lc.committed, lang))
                    app.store.incrementStat("corrections_undone")
                }
                service.showStripMessage("Got it — I won't change “${lc.typed}” again")
                refreshSuggestions()
                return true
            }
        }
        lastCorrection = null
        autoSpaced = false
        if (composing.isNotEmpty() && composingFromSwipe && settings.deleteSwipedWord) {
            // One backspace (or the first step of a held one) takes back the whole swiped word.
            commit("")
            resetComposing(finish = false)
            refreshSuggestions()
            service.updateShiftFromEditor()
            return true
        }
        if (composing.isNotEmpty()) {
            tapOverrides.remove(composing.length - 1)?.let { (from, to) -> hitboxLearning.undone(from, to) }
            composing.setLength(composing.length - 1)
            if (touches.isNotEmpty()) touches.removeAt(touches.size - 1)
            if (composing.isEmpty()) {
                commit("")
                resetComposing(finish = false)
            } else {
                composingFromSwipe = false
                setComposing(composing)
            }
            refreshSuggestions()
            service.updateShiftFromEditor()
            return true
        }
        var deleted = true
        val selected = conn.getSelectedText(0)
        if (!selected.isNullOrEmpty()) {
            conn.commitText("", 1)
            expect(-1)
        } else {
            val before = textBefore(24).toString()
            if (before.isEmpty()) {
                // Nothing we can see: let the app handle it (e.g. delete in an empty chat field).
                conn.sendKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_DEL))
                conn.sendKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_UP, android.view.KeyEvent.KEYCODE_DEL))
                expect(-1)
                deleted = false
            } else {
                // Delete one whole character as the user sees it (emoji with skin tone, flags...).
                val it = BreakIterator.getCharacterInstance()
                it.setText(before)
                val end = before.length
                val start = it.preceding(end).coerceAtLeast(0)
                val n = (end - start).coerceAtLeast(1)
                deleteBefore(n)
                resumeComposingIfInWord()
            }
        }
        service.updateShiftFromEditor()
        refreshSuggestions()
        return deleted
    }

    /** After deleting back into a word, make it editable again so its suggestions show up. */
    private fun resumeComposingIfInWord() {
        if (!allowSuggestions || cursor < 0) return
        val conn = ic ?: return
        val after = conn.getTextAfterCursor(1, 0) ?: ""
        if (after.isNotEmpty() && TextUtils.isWordChar(after[0])) return
        val before = textBefore(48).toString()
        var i = before.length
        val az = arabizi != null
        while (i > 0 && (TextUtils.isWordCharAt(before, i - 1) || az && before[i - 1] in Arabizi.DIGITS)) i--
        val word = before.substring(i)
        if (word.isEmpty() || word.length > 40 || (if (az) word.none { it.isLetter() } else word.any { it.isDigit() })) return
        conn.setComposingRegion(cursor - word.length, cursor)
        composing.append(word)
        word.forEach { touches.add(TypedChar(it)) }
        composingStart = cursor - word.length
    }

    /** Which editor action enter performs here, or null for a plain new line (LatinIME rules). */
    private fun enterAction(info: EditorInfo?): Int? {
        if (info == null) return null
        if (info.imeOptions and EditorInfo.IME_FLAG_NO_ENTER_ACTION != 0) return null
        // An app-specific label ("Send", "Post"...) goes with the app's own action id.
        if (info.actionLabel != null) return info.actionId
        val action = info.imeOptions and EditorInfo.IME_MASK_ACTION
        return if (action == EditorInfo.IME_ACTION_NONE) null else action
    }

    fun onDeleteWordsPreview(words: Int) {
        val conn = ic ?: return
        if (composing.isNotEmpty()) { conn.finishComposingText(); resetComposing(false) }
        if (cursor < 0) return
        if (deleteSelectionAnchor < 0) deleteSelectionAnchor = cursor
        val before = textBefore(400).toString()
        var i = before.length
        repeat(words) {
            while (i > 0 && !TextUtils.isWordCharAt(before, i - 1)) i--
            while (i > 0 && TextUtils.isWordCharAt(before, i - 1)) i--
        }
        val start = deleteSelectionAnchor - (before.length - i)
        conn.setSelection(start.coerceAtLeast(0), deleteSelectionAnchor)
    }

    fun onDeleteWordsCommit(words: Int) {
        val conn = ic ?: return
        val anchor = deleteSelectionAnchor
        deleteSelectionAnchor = -1
        if (anchor < 0) return
        if (words > 0) conn.commitText("", 1) else conn.setSelection(anchor, anchor)
        expect(-1)
        service.updateShiftFromEditor()
        refreshSuggestions()
    }

    /** Space-bar cursor control. Returns false when the cursor is already at the start/end. */
    fun moveCursor(steps: Int): Boolean {
        val conn = ic ?: return false
        if (composing.isNotEmpty()) { conn.finishComposingText(); resetComposing(false) }
        if (cursor < 0) return false
        val target = if (steps < 0) {
            val before = textBefore(-steps).length
            cursor - before
        } else {
            val after = (conn.getTextAfterCursor(steps, 0) ?: "").length
            cursor + after
        }
        if (target == cursor) return false
        conn.setSelection(target, target)
        // Tracked as our own edit, so fast steps build on each other; suggestions catch up
        // once the finger rests.
        expect(target)
        lastCorrection = null
        autoSpaced = false
        main.removeCallbacks(cursorSettled)
        main.postDelayed(cursorSettled, 150)
        return true
    }

    private val cursorSettled = Runnable { refreshAfterCursorChange() }

    // ------------------------------------------------------------------ suggestions

    fun onSuggestionPicked(s: Suggestion) {
        val conn = ic ?: return
        aiUndo = null
        conn.beginBatchEdit()
        val ctx = contextWords()
        when (s.kind) {
            Suggestion.Kind.EMOJI -> {
                // The emoji takes the word's place: "sob" → "😭".
                if (composing.isNotEmpty()) {
                    commit(s.text + " ")
                    resetComposing(finish = false)
                } else {
                    if (!textBefore(1).endsWith(" ") && textBefore(1).isNotEmpty()) commit(" ")
                    commit(s.text + " ")
                }
                app.emojiRecents.add(s.text)
                autoSpaced = true
            }
            else -> {
                if (composing.isNotEmpty()) {
                    val typed = composing.toString()
                    val fromSwipe = composingFromSwipe
                    val recent = recentWords()
                    val rejected = result.autoCorrect?.text
                    val rejectedBy = resultAutoLanguage
                    // The keyboard's own first idea for a typo: picking it teaches nothing new.
                    val obviousFix = result.typed == typed &&
                        (rejected == s.text || result.candidates.firstOrNull { it.kind == Suggestion.Kind.CORRECTION }?.text == s.text)
                    commit(s.text + " ")
                    resetComposing(finish = false)
                    val shouting = isShouting(s.text)
                    val e = engine
                    if (e != null && allowLearning) worker.execute {
                        val typedKey = TextUtils.key(typed, lang)
                        val pickedKey = TextUtils.key(s.text, lang)
                        val weights = e.mix.weights(recent)
                        when {
                            // "7abibi" → the Arabic word they picked: remember their spelling.
                            s.kind == Suggestion.Kind.ARABIZI -> arabizi?.learn(typed, s.text)
                            // The user insisted on exactly what they typed: learn it as their word.
                            s.kind == Suggestion.Kind.TYPED -> {
                                if (rejected != null) (e.models.getOrNull(rejectedBy) ?: e.primary).user
                                    .learnRejection(typedKey, TextUtils.key(rejected, lang))
                                e.models[e.mix.learnTarget(typedKey, weights)].learn(s.text, ctx, System.currentTimeMillis(), confirmed = true, shouting = shouting)
                                return@execute
                            }
                            // Picking a fix the keyboard didn't think of first teaches it this typo.
                            // Ordinary slips it already fixes ("wat" → "what") aren't worth keeping.
                            !fromSwipe && !obviousFix && s.kind == Suggestion.Kind.CORRECTION && !e.isKnownAnywhere(typedKey) && typedKey != pickedKey ->
                                e.models[e.mix.learnTarget(pickedKey, weights)].user.learnCorrection(typedKey, pickedKey)
                        }
                    }
                    if (s.kind != Suggestion.Kind.SHORTCUT) learn(s.text, ctx, recent, emptyList(), fromSwipe)
                } else {
                    val before = textBefore(1)
                    if (before.isNotEmpty() && !before[0].isWhitespace() && before[0] !in "([{\"'“«") commit(" ")
                    val recent = recentWords()
                    commit(s.text + " ")
                    learn(s.text, ctx, recent, emptyList(), false)
                    if (s.kind == Suggestion.Kind.AI) app.store.incrementStat("ai_words_used")
                }
                autoSpaced = true
            }
        }
        conn.endBatchEdit()
        lastCorrection = null
        service.afterSeparator()
        refreshSuggestions()
    }

    fun onSuggestionLongPressed(s: Suggestion) {
        val e = engine ?: return
        // Blocked in every mixed language, so it can't sneak back in through the other one.
        worker.execute { e.models.forEach { it.user.block(s.text, System.currentTimeMillis()) } }
        service.showStripMessage("Removed “${s.text}” from suggestions")
        main.postDelayed({ refreshSuggestions() }, 50)
    }

    fun refreshSuggestions() {
        val gen = generation.incrementAndGet()
        if (!allowSuggestions) {
            service.showSuggestions(emptyList(), -1)
            keyProbabilities = null
            return
        }
        val e = engine ?: run { service.showSuggestions(emptyList(), -1); return }
        val typed = composing.toString()
        val typedTouches = touches.toList()
        val ctx = contextWords()
        val recent = recentWords()
        val keyboard = spatial?.geometry
        val shiftOn = service.shiftState != KeyboardView.ShiftState.OFF
        val swipeAlts = swipeAlternatives
        val mode = if (allowAutocorrect) settings.autoCorrectMode else AutoCorrectMode.OFF
        val wantEmoji = settings.emojiSuggestions
        val textForAi = if (typed.isEmpty()) textBefore(400).toString() else ""
        val around = if (typed.isEmpty()) null else wordSurroundings()
        worker.execute {
            if (gen != generation.get()) return@execute
            val list = ArrayList<Suggestion>(4)
            var autoIndex = -1
            var r = SuggestionResult.EMPTY
            var autoLanguage = -1
            if (typed.isEmpty()) {
                e.mixed.predict(ctx, recent, 3).forEach { p -> list.add(if (shiftOn) p.copy(text = TextUtils.capitalize(p.text, lang)) else p) }
            } else if (swipeAlts.isNotEmpty()) {
                swipeAlts.take(3).forEach { list.add(Suggestion(it, Suggestion.Kind.EXACT)) }
                // Swiped words get their emoji too.
                (if (wantEmoji) EmojiData.get(service) else null)?.forWord(swipeAlts[0])?.let { em ->
                    while (list.size > 2) list.removeAt(list.size - 1)
                    list.add(Suggestion(em, Suggestion.Kind.EMOJI))
                }
            } else {
                val d = decide(e, typed, typedTouches, ctx, recent, mode, around!!)
                r = d.result
                autoLanguage = d.autoLanguage
                if (r.autoCorrect != null) {
                    list.add(r.autoCorrect!!)
                    autoIndex = 0
                    list.add(Suggestion(typed, Suggestion.Kind.TYPED))
                    r.candidates.firstOrNull { it.text != r.autoCorrect!!.text && it.text != typed }?.let { list.add(it) }
                } else {
                    list.addAll(r.candidates.take(3))
                    if (!r.typedIsValid && list.none { it.text == typed }) {
                        list.add(minOf(1, list.size), Suggestion(typed, Suggestion.Kind.TYPED))
                    }
                }
                // Loaded lazily on this thread the first time (reads the emoji catalogue once).
                (if (wantEmoji) EmojiData.get(service) else null)?.forWord(typed)?.let { e ->
                    while (list.size > 2) list.removeAt(list.size - 1)
                    list.add(Suggestion(e, Suggestion.Kind.EMOJI))
                }
            }
            val probs = runCatching { e.mixed.nextChars(e.predictors, typed, ctx, recent) }.getOrNull()?.let { foldToKeys(it, keyboard) }
            main.post {
                if (gen != generation.get()) return@post
                result = r
                resultAutoLanguage = autoLanguage
                keyProbabilities = probs
                stripList = list.take(3)
                service.showSuggestions(stripList, autoIndex)
            }
        }
        if (!(typed.isEmpty() && allowAi && settings.aiSuggestions && app.ai.isConfigured && textForAi.isNotBlank())) {
            app.ai.cancelPredictions()
            return
        }
        app.ai.predictNext(textForAi, activeLanguages) { words ->
            if (gen != generation.get() || composing.isNotEmpty()) return@predictNext
            val merged = ArrayList(stripList)
            val fresh = words.filter { w -> merged.none { it.text.equals(w, ignoreCase = true) } }
            if (fresh.isEmpty()) return@predictNext
            // AI ideas take the side slots; the local favourite stays in the middle.
            val ai = fresh.map { Suggestion(if (shiftOn) TextUtils.capitalize(it, lang) else it, Suggestion.Kind.AI) }
            val out = ArrayList<Suggestion>()
            merged.firstOrNull()?.let { out.add(it) }
            out.addAll(ai.take(if (out.isEmpty()) 3 else 2))
            for (m in merged.drop(1)) if (out.size < 3) out.add(m)
            stripList = out
            service.showSuggestions(out, -1)
        }
    }

    /** What surrounds the word being typed, for Arabizi: Arabic context words and whether Arabic came just before. */
    private class Surroundings(val arabicCtx: ContextWords, val afterArabic: Boolean)

    private fun wordSurroundings(): Surroundings {
        val before = textBeforeWord(80)
        val lastLetter = before.lastOrNull { it.isLetter() }
        return Surroundings(TextUtils.contextWords(before, "ar"), lastLetter != null && lastLetter in '\u0600'..'\u06FF')
    }

    private class Decision(val result: SuggestionResult, val autoLanguage: Int)

    /**
     * Suggestions and the space-bar choice for the word being typed: the user's own shortcut,
     * Arabic for Arabizi, or the normal (mixed-language) suggestions.
     */
    private fun decide(
        e: Engine, typed: String, typedTouches: List<TypedChar>, ctx: ContextWords, recent: List<String>,
        mode: AutoCorrectMode, around: Surroundings,
    ): Decision {
        val shortcut = app.shortcuts.find(typed)
        val az = arabizi
        val clearlyArabizi = az != null && Arabizi.hasArabiziDigits(typed) && typed.lowercase() !in Arabizi.ENGLISH_LOOKALIKES
        val normal = if (clearlyArabizi) null else e.mixed.suggest(typed, typedTouches, ctx, recent, mode)
        var r = normal?.result ?: SuggestionResult(typed, emptyList(), null, false, LanguageModel.OOV)
        var autoLanguage = normal?.autoLanguage ?: -1

        // Arabizi: Arabic spellings, converted on space when it's clearly Arabizi.
        if (az != null && typed.all { Arabizi.isArabiziChar(it) } && typed.any { it.isLetter() } && typed.lowercase() !in Arabizi.ENGLISH_LOOKALIKES) {
            val latinKey = TextUtils.key(typed, lang)
            val knownLatin = e.isKnownAnywhere(latinKey)
            // Plain English/German words only get Arabic ideas while writing Arabic.
            val arabic = if (knownLatin && !clearlyArabizi && !around.afterArabic) emptyList()
            else az.convert(typed, around.arabicCtx, 3).map { Suggestion(it.arabic, Suggestion.Kind.ARABIZI) }
            if (arabic.isNotEmpty()) {
                val undone = e.primary.user.rejectionCount(latinKey, TextUtils.key(arabic[0].text, "ar")) > 0
                val auto = mode != AutoCorrectMode.OFF && settings.arabiziAuto && !undone && !knownLatin &&
                    (clearlyArabizi || around.afterArabic)
                r = when {
                    auto -> SuggestionResult(typed, arabic + r.candidates, arabic[0], r.typedIsValid, r.typedScore)
                        .also { autoLanguage = ARABIZI }
                    clearlyArabizi -> SuggestionResult(typed, arabic, null, false, r.typedScore)
                    // A Latin word that could be Arabizi: offer the Arabic next to the normal ideas.
                    else -> SuggestionResult(typed, insertAt(r.candidates, if (knownLatin) 2 else 1, arabic[0]), r.autoCorrect, r.typedIsValid, r.typedScore)
                }
            }
        }

        // The user's own shortcut comes first.
        if (shortcut != null) {
            val s = Suggestion(shortcut.text, Suggestion.Kind.SHORTCUT)
            val others = r.candidates.filter { it.text != s.text }
            r = if (settings.shortcutsAuto && mode != AutoCorrectMode.OFF) {
                autoLanguage = SHORTCUT
                SuggestionResult(typed, listOf(s) + others, s, r.typedIsValid, r.typedScore)
            } else {
                // Offered first; space keeps exactly what was typed (it's their word).
                autoLanguage = -1
                SuggestionResult(typed, listOf(s) + others, null, true, r.typedScore)
            }
        }
        return Decision(r, autoLanguage)
    }

    private fun insertAt(list: List<Suggestion>, index: Int, s: Suggestion): List<Suggestion> {
        val out = ArrayList(list.filter { it.text != s.text })
        out.add(minOf(index, out.size), s)
        return out
    }

    // ------------------------------------------------------------------ swipe typing

    val swipeAllowed: Boolean get() = allowSuggestions && settings.swipeTyping

    /** What the suggestion bar shows right now (used by tests). */
    val shownSuggestions: List<Suggestion> get() = stripList

    /** True once Arabizi can convert (used by tests). */
    val arabiziReady: Boolean get() = arabizi != null

    /** True once the language pack and swipe index are ready (used by tests). */
    val isReady: Boolean get() = engine?.decoders != null

    fun canSwipe(): Boolean = swipeAllowed && engine?.decoders != null

    fun onSwipeMove(xs: FloatArray, ys: FloatArray, count: Int) {
        if (!settings.swipePreview) return
        val e = engine ?: return
        val decoders = e.decoders ?: return
        val gen = swipeGeneration.incrementAndGet()
        val cx = xs.copyOf(count)
        val cy = ys.copyOf(count)
        val ctx = swipeContext()
        val recent = swipeRecent()
        worker.execute {
            if (gen != swipeGeneration.get()) return@execute
            val best = e.mixed.decodeSwipe(decoders, cx, cy, count, ctx, recent, limit = 1).firstOrNull() ?: return@execute
            val word = applySwipeCase(best.first)
            main.post { if (gen == swipeGeneration.get()) service.showSwipePreview(word) }
        }
    }

    private fun swipeRecent(): List<String> = TextUtils.recentWords(textBefore(160), lang)

    private fun swipeContext(): ContextWords {
        if (composing.isNotEmpty()) {
            val before = textBefore(80).toString()
            return TextUtils.contextWords(before + " ", lang)
        }
        return contextWords()
    }

    private fun applySwipeCase(word: String): String = when (service.shiftState) {
        KeyboardView.ShiftState.OFF -> word
        KeyboardView.ShiftState.ON -> TextUtils.capitalize(word, lang)
        KeyboardView.ShiftState.LOCKED -> TextUtils.uppercase(word, lang)
    }

    fun onSwipeEnd(xs: FloatArray, ys: FloatArray, count: Int) {
        swipeGeneration.incrementAndGet()
        val e = engine ?: return
        val decoders = e.decoders ?: return
        val ctx = swipeContext()
        val recent = swipeRecent()
        val gen = swipeGeneration.get()
        worker.execute {
            val candidates = e.mixed.decodeSwipe(decoders, xs, ys, count, ctx, recent, limit = 6)
            main.post {
                if (gen != swipeGeneration.get()) return@post
                if (candidates.isEmpty()) {
                    service.showStripMessage("Didn't catch that — try again")
                    return@post
                }
                val words = candidates.map { applySwipeCase(it.first) }.distinct()
                insertSwipedWord(words)
            }
        }
    }

    private fun insertSwipedWord(words: List<String>) {
        val conn = ic ?: return
        aiUndo = null
        conn.beginBatchEdit()
        // The previous word is done: commit it and separate the new one with a space.
        if (composing.isNotEmpty()) commitComposing(autocorrect = !composingFromSwipe, separator = " ")
        else {
            val before = textBefore(1)
            if (before.isNotEmpty() && !before[0].isWhitespace() && before[0] !in "([{\"“«¿¡/@#-") commit(" ")
        }
        composingStart = cursor
        composing.append(words[0])
        composingFromSwipe = true
        swipeAlternatives = words
        setComposing(composing)
        conn.endBatchEdit()
        autoSpaced = false
        lastCorrection = null
        service.afterCharacterTyped()
        refreshSuggestions()
    }

    // ------------------------------------------------------------------ voice

    /**
     * Dictated text: inserted with the right spacing and capitals, then proofread by the AI in
     * this app's tone (chill in chats, polite in email) when one is set up. The AI's fix can be
     * undone from the chip, like an automatic proofread.
     */
    fun onVoiceText(spoken: String) {
        val conn = ic ?: return
        var text = spoken.trim()
        if (text.isEmpty()) return
        aiUndo = null
        conn.beginBatchEdit()
        if (composing.isNotEmpty()) commitComposing(autocorrect = false, separator = "")
        val before = textBefore(1).toString()
        val needsSpace = before.isNotEmpty() && !before[0].isWhitespace() && before[0] !in "([{\"'“«"
        if (service.shiftState != KeyboardView.ShiftState.OFF) text = TextUtils.capitalize(text, lang)
        commit((if (needsSpace) " " else "") + text + " ")
        conn.endBatchEdit()
        autoSpaced = true
        lastCorrection = null
        service.afterSeparator()
        refreshSuggestions()
        if (!(settings.voiceAiFix && allowAi && app.ai.isConfigured) || text.count { it == ' ' } < 1) return
        app.ai.proofread(text, activeLanguages, tone) { res ->
            val fixed = res.getOrNull()?.trim()
            if (fixed.isNullOrBlank() || fixed == text) return@proofread
            // Only if nothing was typed since.
            if (textBefore(text.length + 1).toString() != "$text ") return@proofread
            replaceBeforeCursor("$text ", "$fixed ")
            aiUndo = Triple(fixed, text, " ")
            learnFromAi(text, fixed)
            service.showAiChip("Fixed by AI · tap to undo")
        }
    }

    // ------------------------------------------------------------------ AI

    /** Text the AI panel works on: the selection, or the whole field if it is short enough. */
    fun textForAi(): String? {
        val conn = ic ?: return null
        if (composing.isNotEmpty()) { conn.finishComposingText(); resetComposing(false) }
        conn.getSelectedText(0)?.toString()?.takeIf { it.isNotBlank() }?.let { return it }
        val before = textBefore(LIMIT).toString()
        val after = (conn.getTextAfterCursor(LIMIT, 0) ?: "").toString()
        if (before.length >= LIMIT || after.length >= LIMIT) return null
        return (before + after).takeIf { it.isNotBlank() }
    }

    fun applyAiResult(original: String, replacement: String) {
        val conn = ic ?: return
        conn.beginBatchEdit()
        val selected = conn.getSelectedText(0)?.toString()
        if (selected != null && selected == original) {
            conn.commitText(replacement, 1)
        } else {
            val before = textBefore(LIMIT).toString()
            val after = (conn.getTextAfterCursor(LIMIT, 0) ?: "").toString()
            if (before + after == original) {
                conn.setSelection(0, original.length)
                conn.commitText(replacement, 1)
            }
        }
        conn.endBatchEdit()
        expect(-1)
        learnFromAi(original, replacement)
    }

    private fun learnFromAi(original: String, fixed: String) {
        val e = engine ?: return
        if (!allowLearning) return
        worker.execute {
            // Typo fixes go to the language the text is written in.
            val target = e.mix.dominant(e.mix.weights(TextUtils.recentWords(fixed, lang)))
            app.ai.learnFromAcceptedFix(original, fixed, e.models[target].user, isKnownWord = { k -> e.isKnownAnywhere(k) }) { old, new ->
                // A typo the keyboard already fixes by itself is an ordinary slip, not worth keeping.
                val r = e.mixed.suggest(old, old.map { TypedChar(it) }, ContextWords(null, null, false), emptyList(), AutoCorrectMode.NORMAL).result
                TextUtils.key(r.autoCorrect?.text ?: return@learnFromAcceptedFix false, lang) == TextUtils.key(new, lang)
            }
        }
    }

    /** After a sentence ends, quietly proofread it (suggest or auto-apply, per settings). */
    private fun maybeProofreadLastSentence() {
        if (!allowAi || proofInFlight || !app.ai.isConfigured) return
        val mode = settings.aiAutoProof
        if (mode == AiAutoProof.OFF) return
        val before = textBefore(600).toString()
        val tailMatch = Regex("[ \\n]*$").find(before)
        val tail = tailMatch?.value ?: ""
        val body = before.dropLast(tail.length)
        // Only once a sentence is finished (". ", "! ", "? " or a new line).
        if (body.isEmpty() || !(body.last() in ".!?؟…" || tail.contains('\n'))) return
        // The sentence = everything after the previous sentence end.
        var start = body.length
        while (start > 0) {
            val c = body[start - 1]
            if ((c == '\n') || ((c == '.' || c == '!' || c == '?' || c == '؟') && start < body.length - 1 && body[start] == ' ')) break
            start--
        }
        val sentence = body.substring(start).trimStart()
        if (sentence.split(' ').count { it.isNotBlank() } < 3 || sentence.length > 400) return
        proofInFlight = true
        app.ai.proofread(sentence, activeLanguages, tone) { res ->
            proofInFlight = false
            val fixed = res.getOrNull() ?: return@proofread
            if (fixed == sentence || fixed.isBlank()) return@proofread
            // Only offer it if the text is still exactly as it was.
            if (!textBefore(sentence.length + tail.length).toString().equals(sentence + tail)) return@proofread
            if (mode == AiAutoProof.AUTO) {
                replaceBeforeCursor(sentence + tail, fixed + tail)
                aiUndo = Triple(fixed, sentence, tail)
                learnFromAi(sentence, fixed)
                service.showAiChip("Fixed by AI · tap to undo")
            } else {
                aiChipFix = Triple(sentence, fixed, tail)
                service.showAiChip("✨ $fixed")
            }
        }
    }

    private fun replaceBeforeCursor(old: String, new: String) {
        val conn = ic ?: return
        conn.beginBatchEdit()
        conn.deleteSurroundingText(old.length, 0)
        conn.commitText(new, 1)
        conn.endBatchEdit()
        expect(if (cursor >= 0) cursor - old.length + new.length else -1)
    }

    fun onAiChipTapped() {
        aiChipFix?.let { (original, fixed, tail) ->
            aiChipFix = null
            if (textBefore(original.length + tail.length).toString() == original + tail) {
                replaceBeforeCursor(original + tail, fixed + tail)
                learnFromAi(original, fixed)
                service.showStripMessage("Fixed ✓")
            }
            service.showAiChip(null)
            return
        }
        aiUndo?.let { (fixed, original, tail) ->
            aiUndo = null
            if (textBefore(fixed.length + tail.length).toString() == fixed + tail) replaceBeforeCursor(fixed + tail, original + tail)
            service.showAiChip(null)
        }
    }

    fun onAiChipDismissed() {
        aiChipFix = null
        aiUndo = null
        service.showAiChip(null)
    }

    /**
     * Letters a mixed-in language has but this keyboard doesn't (ä, ö, ü, ß on QWERTY) count
     * towards the key that types them (a, o, u, s).
     */
    private fun foldToKeys(probs: Map<Char, Float>, keyboard: KeyGeometry?): Map<Char, Float> {
        if (keyboard == null || activeLanguages.size == 1) return probs
        val out = HashMap<Char, Float>(probs.size)
        for ((c, p) in probs) {
            val k = if (c == ' ' || keyboard.hasKey(c)) c else TextUtils.fold(c)
            out[k] = (out[k] ?: 0f) + p
        }
        return out
    }

    fun shutdown() {
        saveTouchModel()
        hitboxLearning.save()
        worker.shutdown()
    }

    companion object {
        private const val DOUBLE_SPACE_MS = 600L
        /** "Language" of a correction made by Arabizi (undo is remembered by the keyboard's own language). */
        private const val ARABIZI = -2
        /** "Language" of an expanded shortcut (undo is not held against it). */
        private const val SHORTCUT = -3
        private const val TOUCH_KEY = "touch.model"
        private const val LIMIT = 3000
    }
}

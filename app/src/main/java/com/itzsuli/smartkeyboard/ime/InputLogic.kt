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
import com.itzsuli.smartkeyboard.engine.AutoCorrectMode
import com.itzsuli.smartkeyboard.engine.ContextWords
import com.itzsuli.smartkeyboard.engine.KeyGeometry
import com.itzsuli.smartkeyboard.engine.KeyInfo
import com.itzsuli.smartkeyboard.engine.KeyPredictor
import com.itzsuli.smartkeyboard.engine.LanguageModel
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

    // ---- engine ----
    @Volatile private var model: LanguageModel? = null
    @Volatile private var suggester: Suggester? = null
    @Volatile private var keyPredictor: KeyPredictor? = null
    @Volatile var keyProbabilities: Map<Char, Float>? = null; private set
    private var spatial: SpatialModel? = null
    private var swipeDecoder: SwipeDecoder? = null
    private var geometryKey: String = ""
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
    private var stripList: List<Suggestion> = emptyList()

    /** Last autocorrection, so backspace can undo it. */
    private class LastCorrection(val typed: String, val committed: String, var separator: String)
    private var lastCorrection: LastCorrection? = null
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
        app.store.flushAsync()
    }

    fun setLanguage(code: String) {
        lang = code
        val loaded = app.languages.loaded(code)
        if (loaded != null) attachModel(loaded) else {
            model = null; suggester = null; keyPredictor = null
            app.languages.load(code) { m -> if (lang == m.lang) { attachModel(m); refreshSuggestions() } }
        }
    }

    private fun attachModel(m: LanguageModel) {
        model = m
        suggester = Suggester(m, spatial).also { it.fixCapitalization = settings.fixCapitalization }
        keyPredictor = KeyPredictor(m)
        swipeDecoder?.let { d -> worker.execute { d.prepare(m.dict) } }
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
        spatial = SpatialModel(geometry, touchModel)
        val decoder = SwipeDecoder(geometry)
        swipeDecoder = decoder
        model?.let { m ->
            suggester = Suggester(m, spatial).also { it.fixCapitalization = settings.fixCapitalization }
            worker.execute { decoder.prepare(m.dict) }
        }
    }

    // ------------------------------------------------------------------ helpers

    private val separatorsThatCorrect = " \n.,!?;:)]}\"»”…،؟؛"

    private fun textBefore(n: Int): CharSequence = ic?.getTextBeforeCursor(n, 0) ?: ""

    private fun contextWords(): ContextWords {
        // Composing text is part of what the editor reports; strip it.
        val before = textBefore(80).toString()
        val trimmed = if (composing.isNotEmpty() && before.endsWith(composing)) before.dropLast(composing.length) else before
        return TextUtils.contextWords(trimmed, lang)
    }

    private fun resetComposing(finish: Boolean) {
        if (finish && composing.isNotEmpty()) ic?.finishComposingText()
        composing.setLength(0)
        touches.clear()
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
        val text = applyShift(raw)
        val isWordPart = text.all { TextUtils.isWordChar(it) } &&
            !(text == "'" && composing.isEmpty()) // a lone apostrophe starts a quote, not a word
        if (isWordPart && allowSuggestions) {
            if (composing.isEmpty()) composingStart = cursor
            composing.append(text)
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
        if (autocorrect && allowAutocorrect && !composingFromSwipe) {
            val s = suggester
            val r = if (result.typed == typed) result else s?.suggest(typed, touches.toList(), ctx, settings.autoCorrectMode) ?: SuggestionResult.EMPTY
            r.autoCorrect?.let { final = it.text }
        }
        val typedTouches = touches.toList()
        val fromSwipe = composingFromSwipe
        commit(final + separator)
        resetComposing(finish = false)
        learn(final, ctx, typed, typedTouches, fromSwipe)
        if (final == typed) return null
        app.store.incrementStat("autocorrections")
        return LastCorrection(typed, final, separator).also { lastCorrection = it }
    }

    private fun learn(word: String, ctx: ContextWords, typed: String, typedTouches: List<TypedChar>, fromSwipe: Boolean) {
        if (!allowLearning) return
        val m = model ?: return
        val now = System.currentTimeMillis()
        worker.execute {
            m.learn(word, ctx, now)
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

    private fun saveTouchModel() {
        touchUpdates = 0
        app.store.putValue(TOUCH_KEY, touchModel.serialize())
    }

    fun onEnter() {
        val conn = ic ?: return
        conn.beginBatchEdit()
        val corrected = if (composing.isNotEmpty()) commitComposing(autocorrect = true, separator = "") else null
        conn.endBatchEdit()
        autoSpaced = false
        val info = editorInfo
        val action = (info?.imeOptions ?: 0) and EditorInfo.IME_MASK_ACTION
        val multiLine = info != null && info.inputType and InputType.TYPE_TEXT_FLAG_MULTI_LINE != 0
        val noEnterAction = info != null && info.imeOptions and EditorInfo.IME_FLAG_NO_ENTER_ACTION != 0
        if (!multiLine && !noEnterAction && action != EditorInfo.IME_ACTION_NONE && action != EditorInfo.IME_ACTION_UNSPECIFIED) {
            conn.performEditorAction(action)
        } else {
            commit("\n")
            corrected?.let { it.separator = "\n"; lastCorrection = it }
            maybeProofreadLastSentence()
        }
        service.afterSeparator()
        refreshSuggestions()
    }

    // ------------------------------------------------------------------ deleting

    fun onDelete() {
        val conn = ic ?: return
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
                // Remembered right away (cheap, in memory), so the very next space respects it.
                val m = model
                if (m != null && allowLearning) {
                    m.user.learnRejection(TextUtils.key(lc.typed, lang), TextUtils.key(lc.committed, lang))
                    app.store.incrementStat("corrections_undone")
                }
                service.showStripMessage("Got it — I won't change “${lc.typed}” again")
                refreshSuggestions()
                return
            }
        }
        lastCorrection = null
        autoSpaced = false
        if (composing.isNotEmpty()) {
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
            return
        }
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
    }

    /** After deleting back into a word, make it editable again so its suggestions show up. */
    private fun resumeComposingIfInWord() {
        if (!allowSuggestions || cursor < 0) return
        val conn = ic ?: return
        val after = conn.getTextAfterCursor(1, 0) ?: ""
        if (after.isNotEmpty() && TextUtils.isWordChar(after[0])) return
        val before = textBefore(48).toString()
        var i = before.length
        while (i > 0 && TextUtils.isWordCharAt(before, i - 1)) i--
        val word = before.substring(i)
        if (word.isEmpty() || word.length > 40 || word.any { it.isDigit() }) return
        conn.setComposingRegion(cursor - word.length, cursor)
        composing.append(word)
        word.forEach { touches.add(TypedChar(it)) }
        composingStart = cursor - word.length
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

    fun moveCursor(steps: Int) {
        val conn = ic ?: return
        if (composing.isNotEmpty()) { conn.finishComposingText(); resetComposing(false) }
        if (cursor < 0) return
        val target = if (steps < 0) {
            val before = textBefore(-steps).length
            cursor - before
        } else {
            val after = (conn.getTextAfterCursor(steps, 0) ?: "").length
            cursor + after
        }
        conn.setSelection(target, target)
    }

    // ------------------------------------------------------------------ suggestions

    fun onSuggestionPicked(s: Suggestion) {
        val conn = ic ?: return
        aiUndo = null
        conn.beginBatchEdit()
        val ctx = contextWords()
        when (s.kind) {
            Suggestion.Kind.EMOJI -> {
                if (composing.isNotEmpty()) commitComposing(autocorrect = false, separator = " ")
                else if (!textBefore(1).endsWith(" ") && textBefore(1).isNotEmpty()) commit(" ")
                commit(s.text + " ")
            }
            else -> {
                if (composing.isNotEmpty()) {
                    val typed = composing.toString()
                    val fromSwipe = composingFromSwipe
                    commit(s.text + " ")
                    resetComposing(finish = false)
                    val m = model
                    if (m != null && allowLearning) worker.execute {
                        val typedKey = TextUtils.key(typed, lang)
                        val pickedKey = TextUtils.key(s.text, lang)
                        when {
                            // The user insisted on exactly what they typed: learn it as their word.
                            s.kind == Suggestion.Kind.TYPED -> {
                                result.autoCorrect?.let { m.user.learnRejection(typedKey, TextUtils.key(it.text, lang)) }
                                m.learn(s.text, ctx, System.currentTimeMillis(), confirmed = true)
                                return@execute
                            }
                            // Picking a fix for a non-word teaches the corrector this typo.
                            !fromSwipe && s.kind == Suggestion.Kind.CORRECTION && !m.isKnown(typedKey) && typedKey != pickedKey ->
                                m.user.learnCorrection(typedKey, pickedKey)
                        }
                    }
                    learn(s.text, ctx, typed, emptyList(), fromSwipe)
                } else {
                    val before = textBefore(1)
                    if (before.isNotEmpty() && !before[0].isWhitespace() && before[0] !in "([{\"'“«") commit(" ")
                    commit(s.text + " ")
                    learn(s.text, ctx, s.text, emptyList(), false)
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
        val m = model ?: return
        val key = TextUtils.key(s.text, lang)
        worker.execute { m.user.block(s.text, System.currentTimeMillis()) }
        service.showStripMessage("Removed “${s.text}” from suggestions")
        main.postDelayed({ refreshSuggestions() }, 50)
        if (key.isEmpty()) return
    }

    fun refreshSuggestions() {
        val gen = generation.incrementAndGet()
        if (!allowSuggestions) {
            service.showSuggestions(emptyList(), -1)
            keyProbabilities = null
            return
        }
        val s = suggester ?: run { service.showSuggestions(emptyList(), -1); return }
        val kp = keyPredictor
        val typed = composing.toString()
        val typedTouches = touches.toList()
        val ctx = contextWords()
        val shiftOn = service.shiftState != KeyboardView.ShiftState.OFF
        val swipeAlts = swipeAlternatives
        val mode = if (allowAutocorrect) settings.autoCorrectMode else AutoCorrectMode.OFF
        val wantEmoji = settings.emojiSuggestions
        val textForAi = if (typed.isEmpty()) textBefore(400).toString() else ""
        worker.execute {
            if (gen != generation.get()) return@execute
            val list = ArrayList<Suggestion>(4)
            var autoIndex = -1
            var r = SuggestionResult.EMPTY
            if (typed.isEmpty()) {
                s.predict(ctx, 3).forEach { p -> list.add(if (shiftOn) p.copy(text = TextUtils.capitalize(p.text, lang)) else p) }
            } else if (swipeAlts.isNotEmpty()) {
                swipeAlts.take(3).forEach { list.add(Suggestion(it, Suggestion.Kind.EXACT)) }
            } else {
                r = s.suggest(typed, typedTouches, ctx, mode)
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
            val probs = if (kp != null) runCatching { kp.nextChars(typed, ctx) }.getOrNull() else null
            main.post {
                if (gen != generation.get()) return@post
                result = r
                keyProbabilities = probs
                stripList = list.take(3)
                service.showSuggestions(stripList, autoIndex)
            }
        }
        if (!(typed.isEmpty() && allowAi && settings.aiSuggestions && app.ai.isConfigured && textForAi.isNotBlank())) {
            app.ai.cancelPredictions()
            return
        }
        app.ai.predictNext(textForAi, lang) { words ->
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

    // ------------------------------------------------------------------ swipe typing

    val swipeAllowed: Boolean get() = allowSuggestions && settings.swipeTyping

    /** True once the language pack and swipe index are ready (used by tests). */
    val isReady: Boolean get() = model != null && swipeDecoder != null

    fun canSwipe(): Boolean = swipeAllowed && swipeDecoder != null && model != null

    fun onSwipeMove(xs: FloatArray, ys: FloatArray, count: Int) {
        if (!settings.swipePreview) return
        val d = swipeDecoder ?: return
        val m = model ?: return
        val gen = swipeGeneration.incrementAndGet()
        val cx = xs.copyOf(count)
        val cy = ys.copyOf(count)
        val ctx = swipeContext()
        worker.execute {
            if (gen != swipeGeneration.get()) return@execute
            val best = d.decode(cx, cy, count, m, ctx, limit = 1).firstOrNull() ?: return@execute
            val word = applySwipeCase(m.display(best.key))
            main.post { if (gen == swipeGeneration.get()) service.showSwipePreview(word) }
        }
    }

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
        val d = swipeDecoder ?: return
        val m = model ?: return
        val ctx = swipeContext()
        val gen = swipeGeneration.get()
        worker.execute {
            val candidates = d.decode(xs, ys, count, m, ctx, limit = 6)
            main.post {
                if (gen != swipeGeneration.get()) return@post
                if (candidates.isEmpty()) {
                    service.showStripMessage("Didn't catch that — try again")
                    return@post
                }
                val words = candidates.map { applySwipeCase(m.display(it.key)) }.distinct()
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
        val m = model ?: return
        if (!allowLearning) return
        worker.execute { app.ai.learnFromAcceptedFix(original, fixed, m.user) { k -> m.isKnown(k) } }
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
        app.ai.proofread(sentence, lang) { res ->
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

    fun shutdown() {
        saveTouchModel()
        worker.shutdown()
    }

    companion object {
        private const val DOUBLE_SPACE_MS = 600L
        private const val TOUCH_KEY = "touch.model"
        private const val LIMIT = 3000
    }
}

package com.itzsuli.smartkeyboard.ime

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.inputmethodservice.InputMethodService
import android.media.AudioManager
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.itzsuli.smartkeyboard.SmartKeyboardApp
import com.itzsuli.smartkeyboard.ai.Prompts
import com.itzsuli.smartkeyboard.data.EmojiData
import com.itzsuli.smartkeyboard.data.Languages
import com.itzsuli.smartkeyboard.data.OneHanded
import com.itzsuli.smartkeyboard.data.Settings
import com.itzsuli.smartkeyboard.engine.KeyPredictor
import com.itzsuli.smartkeyboard.engine.Suggestion
import com.itzsuli.smartkeyboard.engine.TextUtils
import com.itzsuli.smartkeyboard.settings.MainActivity
import java.io.File

/** The keyboard itself. Builds the views and routes key events to [InputLogic]. */
open class SmartKeyboardService : InputMethodService(), KeyboardView.Listener, SuggestionStripView.Listener,
    SharedPreferences.OnSharedPreferenceChangeListener, AiPanel.Listener {

    private lateinit var app: SmartKeyboardApp
    private val settings: Settings get() = app.settings
    lateinit var logic: InputLogic
        private set

    private var root: FrameLayout? = null
    private var column: LinearLayout? = null
    private var strip: SuggestionStripView? = null
    private var keyboardView: KeyboardView? = null
    private var overlay: KeyboardOverlay? = null
    private var panelHost: FrameLayout? = null
    private var theme = KeyboardTheme.DARK
    private var backgroundBitmap: Bitmap? = null
    private var mode = KeyboardLayout.Mode.ALPHABET
    private var panel: View? = null
    private val emojiRecents = ArrayList<String>()

    var shiftState = KeyboardView.ShiftState.OFF
        private set
    private var vibrator: Vibrator? = null
    private var audio: AudioManager? = null

    override fun onCreate() {
        super.onCreate()
        app = SmartKeyboardApp.of(this)
        logic = InputLogic(this)
        settings.prefs.registerOnSharedPreferenceChangeListener(this)
        vibrator = if (Build.VERSION.SDK_INT >= 31) getSystemService(VibratorManager::class.java)?.defaultVibrator
        else @Suppress("DEPRECATION") getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        audio = getSystemService(AudioManager::class.java)
        emojiRecents.addAll(settings.prefs.getString("emoji_recents", "")!!.split('\u0001').filter { it.isNotEmpty() })
        app.clipboard.enabled = settings.clipboardHistory
        app.clipboard.startListening()
        // Warm up the current language so the first keystrokes already get suggestions.
        app.languages.load(settings.currentLanguage) {}
    }

    override fun onDestroy() {
        settings.prefs.unregisterOnSharedPreferenceChangeListener(this)
        app.clipboard.stopListening()
        logic.shutdown()
        super.onDestroy()
    }

    override fun onEvaluateFullscreenMode(): Boolean = false

    /** Height of the transparent band above the keyboard (for previews and popups). */
    val previewSpacePx: Int get() = (72 * resources.displayMetrics.density).toInt()

    override fun onComputeInsets(outInsets: InputMethodService.Insets) {
        super.onComputeInsets(outInsets)
        val frame = root ?: return
        val col = column ?: return
        if (frame.height == 0) return
        val loc = IntArray(2)
        frame.getLocationInWindow(loc)
        val visibleTop = loc[1] + col.top
        outInsets.contentTopInsets = visibleTop
        outInsets.visibleTopInsets = visibleTop
        outInsets.touchableInsets = InputMethodService.Insets.TOUCHABLE_INSETS_REGION
        outInsets.touchableRegion.set(0, visibleTop, frame.width, loc[1] + frame.height)
    }

    // ------------------------------------------------------------------ views

    override fun onCreateInputView(): View {
        val ctx = this
        val overlayView = KeyboardOverlay(ctx)
        val kv = KeyboardView(ctx, overlayView)
        kv.listener = this
        kv.keyResolver = { x, y, candidates -> resolveKey(x, y, candidates) }
        val stripView = SuggestionStripView(ctx).also { it.listener = this }
        val host = FrameLayout(ctx)
        val col = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        col.addView(stripView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        col.addView(host, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, keyboardHeight()))
        val frame = FrameLayout(ctx)
        // A transparent band above the keyboard gives key previews and accent popups room to draw;
        // onComputeInsets keeps it from covering the app or taking its touches.
        frame.addView(col, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM).apply {
            topMargin = previewSpacePx
        })
        frame.addView(overlayView, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        root = frame; column = col; strip = stripView; keyboardView = kv; overlay = overlayView; panelHost = host
        applyTheme()
        showKeyboard()
        return frame
    }

    private fun keyboardHeight(): Int {
        val density = resources.displayMetrics.density
        val landscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        val rowDp = (if (landscape) 40f else 54f) * settings.keyHeightPercent / 100f
        val rows = 4f + if (settings.numberRow && mode == KeyboardLayout.Mode.ALPHABET) 0.8f else 0f
        return ((rows * rowDp + 10) * density).toInt()
    }

    private fun applyTheme() {
        theme = KeyboardTheme.resolve(this, settings)
        backgroundBitmap = if (settings.backgroundImage) loadBackground() else null
        keyboardView?.let { it.theme = theme; it.settings = settings; it.backgroundBitmap = backgroundBitmap }
        overlay?.theme = theme
        strip?.theme = theme
        strip?.setBackgroundColor(if (backgroundBitmap != null) darken(theme.background) else theme.background)
        column?.setBackgroundColor(theme.background)
        window?.window?.navigationBarColor = theme.background
    }

    private fun darken(c: Int) = android.graphics.Color.rgb(
        (android.graphics.Color.red(c) * 0.8f).toInt(), (android.graphics.Color.green(c) * 0.8f).toInt(), (android.graphics.Color.blue(c) * 0.8f).toInt(),
    )

    private fun loadBackground(): Bitmap? {
        val f = File(filesDir, BACKGROUND_FILE)
        if (!f.exists()) return null
        return runCatching {
            val opts = BitmapFactory.Options().apply { inSampleSize = 2 }
            BitmapFactory.decodeFile(f.absolutePath, opts)
        }.getOrNull()
    }

    /** Puts the key grid back into the keyboard area (after emoji/clipboard/AI panels). */
    private fun showKeyboard() {
        val host = panelHost ?: return
        val kv = keyboardView ?: return
        panel = null
        host.removeAllViews()
        val oneHanded = settings.oneHanded
        if (oneHanded == OneHanded.OFF || resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) {
            host.addView(kv, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        } else {
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            val side = oneHandedSide(oneHanded)
            val kbWidth = (resources.displayMetrics.widthPixels * 0.82f).toInt()
            if (oneHanded == OneHanded.RIGHT) row.addView(side, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
            row.addView(kv, LinearLayout.LayoutParams(kbWidth, ViewGroup.LayoutParams.MATCH_PARENT))
            if (oneHanded == OneHanded.LEFT) row.addView(side, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
            host.addView(row, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        }
        host.layoutParams = host.layoutParams.apply { height = keyboardHeight() }
        kv.post { kv.layout?.let { logic.onKeyboardLaidOut(it) } }
    }

    private fun oneHandedSide(current: OneHanded): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        setBackgroundColor(theme.background)
        fun button(label: String, action: () -> Unit) = TextView(context).apply {
            text = label
            textSize = 22f
            gravity = Gravity.CENTER
            setTextColor(theme.functionText)
            setPadding(0, 24, 0, 24)
            setOnClickListener { action() }
        }
        addView(button(if (current == OneHanded.RIGHT) "⇤" else "⇥") {
            settings.set(Settings.ONE_HANDED, if (current == OneHanded.RIGHT) OneHanded.LEFT else OneHanded.RIGHT)
        })
        addView(button("⤢") { settings.set(Settings.ONE_HANDED, OneHanded.OFF) })
    }

    private fun showPanel(view: View) {
        val host = panelHost ?: return
        keyboardView?.cancelAll()
        (keyboardView?.parent as? ViewGroup)?.removeView(keyboardView)
        host.removeAllViews()
        host.addView(view, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        panel = view
    }

    // ------------------------------------------------------------------ input lifecycle

    override fun onStartInput(attribute: EditorInfo, restarting: Boolean) {
        super.onStartInput(attribute, restarting)
        logic.startInput(attribute, restarting)
    }

    override fun onStartInputView(info: EditorInfo, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        app.clipboard.enabled = settings.clipboardHistory
        applyTheme()
        if (panel != null) showKeyboard()
        mode = when (info.inputType and InputType.TYPE_MASK_CLASS) {
            InputType.TYPE_CLASS_NUMBER, InputType.TYPE_CLASS_DATETIME -> KeyboardLayout.Mode.NUMBER
            InputType.TYPE_CLASS_PHONE -> KeyboardLayout.Mode.PHONE
            else -> KeyboardLayout.Mode.ALPHABET
        }
        shiftState = KeyboardView.ShiftState.OFF
        rebuildLayout()
        updateShiftFromEditor()
        strip?.incognito = settings.incognito
        strip?.aiAvailable = app.ai.isConfigured
        strip?.showAiChip(null)
        logic.refreshSuggestions()
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        super.onFinishInputView(finishingInput)
        keyboardView?.cancelAll()
        if (panel != null) showKeyboard()
    }

    override fun onFinishInput() {
        super.onFinishInput()
        logic.finishInput()
    }

    override fun onUpdateSelection(oldSelStart: Int, oldSelEnd: Int, newSelStart: Int, newSelEnd: Int, candidatesStart: Int, candidatesEnd: Int) {
        super.onUpdateSelection(oldSelStart, oldSelEnd, newSelStart, newSelEnd, candidatesStart, candidatesEnd)
        logic.onUpdateSelection(newSelStart, newSelEnd, candidatesStart, candidatesEnd)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        applyTheme()
        showKeyboard()
    }

    override fun onSharedPreferenceChanged(prefs: SharedPreferences?, key: String?) {
        if (keyboardView == null) return
        when (key) {
            Settings.CURRENT_LANGUAGE, Settings.LANGUAGES -> { logic.setLanguage(settings.currentLanguage); rebuildLayout() }
            Settings.INCOGNITO -> { strip?.incognito = settings.incognito; currentInputEditorInfo?.let { logic.startInput(it, true) } }
            Settings.ONE_HANDED, Settings.KEY_HEIGHT, Settings.NUMBER_ROW -> { rebuildLayout(); showKeyboard() }
            Settings.EMOJI_KEY, Settings.SHOW_HINTS, Settings.ARABIC_DIGITS -> rebuildLayout()
            else -> if (key?.startsWith("ai_") == true) strip?.aiAvailable = app.ai.isConfigured else applyTheme()
        }
    }

    // ------------------------------------------------------------------ layouts

    private fun enterIconFor(info: EditorInfo?): KeyIcons.Icon {
        if (info == null) return KeyIcons.Icon.ENTER
        if (info.inputType and InputType.TYPE_TEXT_FLAG_MULTI_LINE != 0 || info.imeOptions and EditorInfo.IME_FLAG_NO_ENTER_ACTION != 0) return KeyIcons.Icon.ENTER
        return when (info.imeOptions and EditorInfo.IME_MASK_ACTION) {
            EditorInfo.IME_ACTION_SEARCH -> KeyIcons.Icon.SEARCH
            EditorInfo.IME_ACTION_SEND -> KeyIcons.Icon.SEND
            EditorInfo.IME_ACTION_DONE -> KeyIcons.Icon.DONE
            EditorInfo.IME_ACTION_GO, EditorInfo.IME_ACTION_NEXT -> KeyIcons.Icon.NEXT
            else -> KeyIcons.Icon.ENTER
        }
    }

    private fun rebuildLayout() {
        val kv = keyboardView ?: return
        val lang = logic.lang
        val opts = Layouts.Options(
            numberRow = settings.numberRow,
            emojiKey = settings.emojiKey,
            languageKey = settings.languages.size > 1,
            arabicDigits = settings.arabicDigits,
            showHints = settings.showHints,
        )
        kv.layout = when (mode) {
            KeyboardLayout.Mode.ALPHABET -> Layouts.letters(lang, opts)
            KeyboardLayout.Mode.SYMBOLS -> Layouts.symbols(lang, opts, more = false)
            KeyboardLayout.Mode.SYMBOLS_MORE -> Layouts.symbols(lang, opts, more = true)
            KeyboardLayout.Mode.NUMBER -> Layouts.numbers(lang, opts, phone = false)
            KeyboardLayout.Mode.PHONE -> Layouts.numbers(lang, opts, phone = true)
        }
        kv.enterIcon = enterIconFor(currentInputEditorInfo)
        kv.languageLabel = Languages.get(lang).nativeName
        kv.swipeEnabled = logic.swipeAllowed
        panelHost?.let { h -> h.layoutParams = h.layoutParams.apply { height = keyboardHeight() } }
        kv.post { kv.layout?.let { logic.onKeyboardLaidOut(it) } }
    }

    fun updateShiftFromEditor() {
        if (shiftState == KeyboardView.ShiftState.LOCKED) return
        val info = currentInputEditorInfo
        val conn = currentInputConnection
        val caps = settings.autoCapitalize && info != null && conn != null && mode == KeyboardLayout.Mode.ALPHABET &&
            info.inputType != InputType.TYPE_NULL && conn.getCursorCapsMode(info.inputType) != 0 && !logic.isPassword
        setShift(if (caps) KeyboardView.ShiftState.ON else KeyboardView.ShiftState.OFF)
    }

    private fun setShift(state: KeyboardView.ShiftState) {
        shiftState = state
        keyboardView?.shiftState = state
    }

    fun afterCharacterTyped() {
        if (shiftState == KeyboardView.ShiftState.ON) setShift(KeyboardView.ShiftState.OFF)
    }

    fun afterSeparator() {
        if (mode == KeyboardLayout.Mode.SYMBOLS || mode == KeyboardLayout.Mode.SYMBOLS_MORE) {
            // Like on most phones: after "?123 + punctuation + space", go back to letters.
            val before = currentInputConnection?.getTextBeforeCursor(1, 0)
            if (before == " ") { mode = KeyboardLayout.Mode.ALPHABET; rebuildLayout() }
        }
        updateShiftFromEditor()
    }

    // ------------------------------------------------------------------ strip

    fun showSuggestions(list: List<Suggestion>, autoIndex: Int) {
        val s = strip ?: return
        if (list.isEmpty()) s.clearSuggestions() else s.setSuggestions(list, autoIndex)
    }

    fun showSwipePreview(word: String) {
        strip?.setSuggestions(listOf(Suggestion(word, Suggestion.Kind.EXACT)), -1)
    }

    fun showStripMessage(text: String) = strip?.showMessage(text)

    fun showAiChip(text: String?) = strip?.showAiChip(text)

    // ------------------------------------------------------------------ adaptive hitboxes

    private var resolverKeys: List<Key>? = null
    private var resolverInfos: List<com.itzsuli.smartkeyboard.engine.KeyInfo> = emptyList()
    private var resolverSpatial: com.itzsuli.smartkeyboard.engine.SpatialModel? = null

    private fun resolveKey(x: Float, y: Float, candidates: List<Key>): Key? {
        val strength = when (settings.adaptiveHitbox) { 0 -> 0f; 1 -> 0.5f; 2 -> 0.85f; else -> 1.25f }
        if (strength <= 0f) return null
        val probs = logic.keyProbabilities ?: return null
        if (resolverKeys !== candidates || resolverInfos.firstOrNull()?.cx != candidates.firstOrNull()?.bounds?.centerX()) {
            resolverKeys = candidates
            val lang = logic.lang
            resolverInfos = candidates.map { k ->
                val c = if (k.code == KeyCode.SPACE) ' ' else TextUtils.lowercase(k.label, lang)[0]
                com.itzsuli.smartkeyboard.engine.KeyInfo(c, k.bounds.centerX(), k.bounds.centerY(), k.bounds.width(), k.bounds.height())
            }
            resolverSpatial = com.itzsuli.smartkeyboard.engine.SpatialModel(
                com.itzsuli.smartkeyboard.engine.KeyGeometry(resolverInfos.filter { it.char != ' ' }), logic.touchModel,
            )
        }
        val sp = resolverSpatial ?: return null
        val chosen = KeyPredictor.resolve(x, y, resolverInfos, probs, sp, strength) ?: return null
        return candidates[resolverInfos.indexOf(chosen)]
    }

    // ------------------------------------------------------------------ KeyboardView.Listener

    override fun onKeyTap(key: Key, x: Float, y: Float) {
        when (key.code) {
            KeyCode.TEXT -> logic.onCharacter(key.text, x, y)
            KeyCode.SPACE -> logic.onCharacter(" ")
            KeyCode.ENTER -> logic.onEnter()
            KeyCode.SYMBOLS -> { mode = KeyboardLayout.Mode.SYMBOLS; rebuildLayout() }
            KeyCode.SYMBOLS_MORE -> { mode = KeyboardLayout.Mode.SYMBOLS_MORE; rebuildLayout() }
            KeyCode.ALPHABET -> { mode = KeyboardLayout.Mode.ALPHABET; rebuildLayout(); updateShiftFromEditor() }
            KeyCode.EMOJI -> openEmoji()
            KeyCode.LANGUAGE -> nextLanguage()
        }
    }

    override fun onPopupText(text: String) = logic.onCharacter(text)

    override fun onDelete() = logic.onDelete()

    override fun onDeleteWordsPreview(words: Int) = logic.onDeleteWordsPreview(words)

    override fun onDeleteWordsCommit(words: Int) = logic.onDeleteWordsCommit(words)

    override fun onCursorMove(steps: Int) = logic.moveCursor(steps)

    override fun onSwipeStart() {
        if (!logic.canSwipe()) return
    }

    override fun onSwipeMove(xs: FloatArray, ys: FloatArray, count: Int) {
        if (logic.canSwipe()) logic.onSwipeMove(xs, ys, count)
    }

    override fun onSwipeEnd(xs: FloatArray, ys: FloatArray, count: Int) {
        if (logic.canSwipe()) logic.onSwipeEnd(xs, ys, count)
        else showStripMessage("Swipe typing is getting ready…")
    }

    override fun onLongPress(key: Key): Boolean = when (key.code) {
        KeyCode.SPACE -> {
            getSystemService(InputMethodManager::class.java)?.showInputMethodPicker()
            true
        }
        KeyCode.LANGUAGE -> { openSettings("languages"); true }
        KeyCode.SYMBOLS, KeyCode.ALPHABET -> { openEmoji(); true }
        KeyCode.EMOJI -> { openSettings(null); true }
        else -> false
    }

    override fun onFeedback(key: Key) {
        if (settings.haptics) {
            val strength = settings.hapticStrength
            val ms = (6 + strength / 6).toLong()
            val amp = (30 + strength * 2.2f).toInt().coerceIn(1, 255)
            runCatching {
                val v = vibrator
                if (v != null && v.hasVibrator()) {
                    v.vibrate(VibrationEffect.createOneShot(ms, if (v.hasAmplitudeControl()) amp else VibrationEffect.DEFAULT_AMPLITUDE))
                }
            }
        }
        if (settings.sound) {
            val fx = when (key.code) {
                KeyCode.DELETE -> AudioManager.FX_KEYPRESS_DELETE
                KeyCode.SPACE -> AudioManager.FX_KEYPRESS_SPACEBAR
                KeyCode.ENTER -> AudioManager.FX_KEYPRESS_RETURN
                else -> AudioManager.FX_KEYPRESS_STANDARD
            }
            audio?.playSoundEffect(fx, settings.soundVolume / 100f)
        }
    }

    override fun onShiftTap() {
        setShift(if (shiftState == KeyboardView.ShiftState.OFF) KeyboardView.ShiftState.ON else KeyboardView.ShiftState.OFF)
    }

    override fun onShiftLock() = setShift(KeyboardView.ShiftState.LOCKED)

    private fun nextLanguage() {
        val langs = settings.languages
        if (langs.size < 2) return
        val next = langs[(langs.indexOf(logic.lang) + 1) % langs.size]
        settings.set(Settings.CURRENT_LANGUAGE, next)
        showStripMessage(Languages.get(next).nativeName)
    }

    // ------------------------------------------------------------------ strip listener

    override fun onSuggestionPicked(index: Int, suggestion: Suggestion) = logic.onSuggestionPicked(suggestion)

    override fun onSuggestionLongPressed(suggestion: Suggestion) {
        if (suggestion.kind == Suggestion.Kind.EMOJI || suggestion.kind == Suggestion.Kind.TYPED) return
        logic.onSuggestionLongPressed(suggestion)
    }

    override fun onToolbarAction(action: SuggestionStripView.ToolbarAction) {
        when (action) {
            SuggestionStripView.ToolbarAction.AI -> if (panel is AiPanel) showKeyboard() else openAi()
            SuggestionStripView.ToolbarAction.CLIPBOARD -> openClipboard()
            SuggestionStripView.ToolbarAction.EMOJI -> openEmoji()
            SuggestionStripView.ToolbarAction.ONE_HANDED ->
                settings.set(Settings.ONE_HANDED, if (settings.oneHanded == OneHanded.OFF) OneHanded.RIGHT else OneHanded.OFF)
            SuggestionStripView.ToolbarAction.INCOGNITO -> {
                val on = !settings.incognito
                settings.set(Settings.INCOGNITO, on)
                showStripMessage(if (on) "Incognito: nothing is learned or sent to AI" else "Incognito off")
            }
            SuggestionStripView.ToolbarAction.SETTINGS -> openSettings(null)
            SuggestionStripView.ToolbarAction.HIDE -> requestHideSelf(0)
        }
    }

    override fun onAiChipTapped() = logic.onAiChipTapped()

    override fun onAiChipDismissed() = logic.onAiChipDismissed()

    // ------------------------------------------------------------------ panels

    private fun openEmoji() {
        val data = EmojiData.get(this)
        val p = EmojiPanel(this, data, emojiRecents, onEmoji = { e ->
            logic.onCharacter(e)
            settings.prefs.edit().putString("emoji_recents", emojiRecents.joinToString("\u0001")).apply()
        }, onBack = { showKeyboard() }, onDelete = { logic.onDelete() })
        p.applyTheme(theme)
        showPanel(p)
    }

    private fun openClipboard() {
        showPanel(ClipboardPanel(this, app.clipboard, theme, onPaste = { logic.onPaste(it) }, onClose = { showKeyboard() }))
    }

    private var aiOriginal: String? = null

    private fun openAi() {
        val p = AiPanel(this, theme, settings.languages, this)
        p.showActions(app.ai.isConfigured && !logic.isPassword)
        showPanel(p)
    }

    private fun runAi(label: String, call: (String, (Result<String>) -> Unit) -> Unit) {
        val p = panel as? AiPanel ?: return
        val text = logic.textForAi()
        if (text.isNullOrBlank()) {
            p.showError("type or select some text first")
            return
        }
        aiOriginal = text
        p.showLoading(label)
        strip?.aiBusy = true
        call(text) { res ->
            strip?.aiBusy = false
            val current = panel as? AiPanel ?: return@call
            res.fold(onSuccess = { current.showResult(text, it) }, onFailure = { current.showError(it.message ?: "unknown error") })
        }
    }

    override fun onAiProofread() = runAi("Fixing") { t, cb -> app.ai.proofread(t, logic.lang, cb) }

    override fun onAiRewrite(mode: Prompts.Rewrite) = runAi("Rewriting") { t, cb -> app.ai.rewrite(t, mode, logic.lang, cb) }

    override fun onAiTranslate(lang: String) = runAi("Translating") { t, cb -> app.ai.translate(t, lang, cb) }

    override fun onAiApply(result: String) {
        aiOriginal?.let { logic.applyAiResult(it, result) }
        aiOriginal = null
        showKeyboard()
        showStripMessage("Done ✓")
    }

    override fun onAiOpenSettings() = openSettings("ai")

    override fun onAiClose() = showKeyboard()

    private fun openSettings(section: String?) {
        val intent = Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        if (section != null) intent.putExtra(MainActivity.EXTRA_SECTION, section)
        startActivity(intent)
        requestHideSelf(0)
    }

    companion object {
        const val BACKGROUND_FILE = "keyboard_background.jpg"
    }
}

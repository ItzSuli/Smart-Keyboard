package com.itzsuli.smartkeyboard

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.text.InputType
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.widget.EditText
import android.widget.FrameLayout
import com.itzsuli.smartkeyboard.data.Settings
import com.itzsuli.smartkeyboard.ime.Key
import com.itzsuli.smartkeyboard.ime.KeyCode
import com.itzsuli.smartkeyboard.ime.KeyboardView
import com.itzsuli.smartkeyboard.ime.SmartKeyboardService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import android.os.Looper
import java.io.File

/** Drives the real keyboard service against a real EditText, the way Android would. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class KeyboardServiceTest {

    class TestService : SmartKeyboardService() {
        var testIc: InputConnection? = null
        var testInfo: EditorInfo? = null
        override fun getCurrentInputConnection(): InputConnection? = testIc
        override fun getCurrentInputEditorInfo(): EditorInfo? = testInfo
    }

    private lateinit var service: TestService
    private lateinit var edit: EditText
    private lateinit var keyboard: KeyboardView
    private lateinit var root: View
    private lateinit var activity: Activity

    @Before
    fun setUp() {
        val app = org.robolectric.RuntimeEnvironment.getApplication() as SmartKeyboardApp
        app.settings.set(Settings.LANGUAGES, "en,de,ar")
        app.settings.set(Settings.CURRENT_LANGUAGE, "en")
        app.settings.set(Settings.ONBOARDING_DONE, true)
        activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        edit = EditText(activity)
        val container = FrameLayout(activity)
        container.addView(edit, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 200))
        service = Robolectric.buildService(TestService::class.java).create().get()
        root = service.onCreateInputView()
        container.addView(root, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, android.view.Gravity.BOTTOM))
        activity.setContentView(container)
        keyboard = find(root)!!
        startInput(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES)
    }

    private fun find(v: View): KeyboardView? {
        if (v is KeyboardView) return v
        if (v is ViewGroup) for (i in 0 until v.childCount) find(v.getChildAt(i))?.let { return it }
        return null
    }

    private fun startInput(type: Int) {
        edit.inputType = type
        val info = EditorInfo().apply { inputType = type; imeOptions = EditorInfo.IME_ACTION_SEND; initialSelStart = edit.selectionStart; initialSelEnd = edit.selectionEnd }
        val ic = edit.onCreateInputConnection(info)
        service.testIc = ic
        service.testInfo = info
        service.onStartInput(info, false)
        service.onStartInputView(info, false)
        layout()
        waitUntil { service.logic.isReady }
    }

    private fun layout() {
        root.measure(View.MeasureSpec.makeMeasureSpec(1233, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        root.layout(0, 0, root.measuredWidth, root.measuredHeight)
        idle()
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun waitUntil(timeoutMs: Long = 30_000, cond: () -> Boolean) {
        val end = System.currentTimeMillis() + timeoutMs
        while (!cond()) {
            idle()
            if (System.currentTimeMillis() > end) error("timed out")
            Thread.sleep(20)
        }
        idle()
    }

    /** Tells the service about the editor's selection, as the framework does after edits. */
    private fun sync() {
        val text = edit.text
        service.onUpdateSelection(0, 0, edit.selectionStart, edit.selectionEnd,
            BaseInputConnection.getComposingSpanStart(text), BaseInputConnection.getComposingSpanEnd(text))
        Thread.sleep(30)
        idle()
    }

    private fun key(label: String): Key = keyboard.layout!!.keys.first { it.label == label || it.text == label }

    private fun tap(k: Key) {
        when (k.code) {
            KeyCode.DELETE -> service.onDelete()
            else -> service.onKeyTap(k, k.bounds.centerX(), k.bounds.centerY())
        }
        sync()
    }

    private fun type(text: String) {
        for (c in text) {
            if (c == ' ') tap(key(" ")) else tap(key(c.lowercaseChar().toString()))
            Thread.sleep(5)
            idle()
        }
    }

    private fun backspace() = tap(keyboard.layout!!.keys.first { it.code == KeyCode.DELETE })

    private val text get() = edit.text.toString()

    @Test fun typesAndAutocorrects() {
        type("i think teh ")
        assertEquals("I think the ", text)
    }

    @Test fun backspaceUndoesAutocorrectAndRemembers() {
        type("teh ")
        assertEquals("The ", text)
        backspace()
        assertEquals("Teh", text)
        type(" ")
        assertEquals("Teh ", text)
        type("teh ")
        assertEquals("Teh teh ", text) // remembered: not corrected again
    }

    @Test fun keepsSlangAndSwears() {
        type("lol idk wtf bruh thats so fucking funny ")
        assertEquals("Lol idk wtf bruh that's so fucking funny ", text)
    }

    @Test fun doubleSpaceAddsPeriodAndCapitalises() {
        type("hey  ")
        assertEquals("Hey. ", text)
        assertEquals(KeyboardView.ShiftState.ON, service.shiftState)
    }

    @Test fun punctuationReplacesAutoSpaceAfterSuggestion() {
        type("hel")
        waitUntil { true }
        service.logic.onSuggestionPicked(com.itzsuli.smartkeyboard.engine.Suggestion("Hello", com.itzsuli.smartkeyboard.engine.Suggestion.Kind.COMPLETION))
        sync()
        assertEquals("Hello ", text)
        tap(key("."))
        assertEquals("Hello. ", text)
    }

    @Test fun swipeTypesWords() {
        fun swipe(word: String) {
            val xs = ArrayList<Float>()
            val ys = ArrayList<Float>()
            var px = Float.NaN
            var py = Float.NaN
            for (c in word) {
                val k = key(c.toString())
                val x = k.bounds.centerX()
                val y = k.bounds.centerY()
                if (!px.isNaN()) for (s in 1..12) { xs.add(px + (x - px) * s / 12f); ys.add(py + (y - py) * s / 12f) } else { xs.add(x); ys.add(y) }
                px = x; py = y
            }
            service.onSwipeEnd(xs.toFloatArray(), ys.toFloatArray(), xs.size)
            waitUntil { edit.text.toString().lowercase().contains(word.takeLast(2)) }
            sync()
        }
        swipe("hello")
        swipe("world")
        type(" ")
        assertEquals("Hello world ", text)
    }

    @Test fun learnsNewWordsAfterTwoUses() {
        // Whatever happens the first time, one undo is all it takes; after that the word is yours.
        type("ok skrrt ")
        if (!text.endsWith("skrrt ")) { backspace(); type(" ") }
        type("skrrt ")
        assertTrue(text, text.endsWith("skrrt skrrt "))
        Thread.sleep(300); idle()
        type("skrrt ")
        assertTrue(text, text.endsWith("skrrt skrrt skrrt "))
    }

    @Test fun rendersKeyboardScreenshots() {
        val out = File("build/screenshots").apply { mkdirs() }
        fun shot(name: String) {
            layout()
            val bmp = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
            root.draw(Canvas(bmp))
            File(out, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
        type("how are ")
        Thread.sleep(200); idle()
        shot("english_light")
        val s = (org.robolectric.RuntimeEnvironment.getApplication() as SmartKeyboardApp).settings
        for (t in listOf("dark", "sunset", "neon", "glass")) {
            s.set(Settings.THEME, t)
            idle()
            shot("theme_$t")
        }
        s.set(Settings.CURRENT_LANGUAGE, "de"); idle(); waitUntil { service.logic.isReady }; shot("german")
        s.set(Settings.CURRENT_LANGUAGE, "ar"); idle(); waitUntil { service.logic.isReady }; shot("arabic")
        tap(keyboard.layout!!.keys.first { it.code == KeyCode.SYMBOLS }); shot("symbols")
    }
}

package com.itzsuli.smartkeyboard

import android.app.Application
import android.content.Context
import com.itzsuli.smartkeyboard.ai.AiAssistant
import com.itzsuli.smartkeyboard.data.ClipboardHistory
import com.itzsuli.smartkeyboard.data.LanguageManager
import com.itzsuli.smartkeyboard.data.LearningStore
import com.itzsuli.smartkeyboard.data.Settings

/** Process-wide singletons shared by the keyboard service and the settings app. */
class SmartKeyboardApp : Application() {
    val settings by lazy { Settings(this) }
    val store by lazy { LearningStore(this) }
    val languages by lazy { LanguageManager(this, store) }
    val ai by lazy { AiAssistant(this, settings) }
    val clipboard by lazy { ClipboardHistory(this) }

    companion object {
        fun of(context: Context): SmartKeyboardApp = context.applicationContext as SmartKeyboardApp
    }
}

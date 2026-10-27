package com.itzsuli.smartkeyboard

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.view.View
import com.itzsuli.smartkeyboard.settings.MainActivity
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** Renders every settings screen to build/screenshots for a visual check. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SettingsScreenshotTest {
    @Test fun rendersSettingsScreens() {
        val out = File("build/screenshots").apply { mkdirs() }
        for (section in listOf("home", "appearance", "typing", "languages", "ai", "learning", "about", "voice", "shortcuts", "tones", "sync", "offline_ai")) {
            val intent = Intent(org.robolectric.RuntimeEnvironment.getApplication(), MainActivity::class.java)
            if (section != "home") intent.putExtra(MainActivity.EXTRA_SECTION, section)
            val controller = Robolectric.buildActivity(MainActivity::class.java, intent).setup()
            repeat(10) { shadowOf(Looper.getMainLooper()).idle(); Thread.sleep(30) }
            val root: View = controller.get().window.decorView
            // Phone-sized for the docs, plus a very tall one that shows every option at once.
            for ((suffix, height) in listOf("" to 2673, "_full" to 9000)) {
                if (suffix.isNotEmpty() && section !in setOf("appearance", "typing", "ai", "languages")) continue
                root.measure(View.MeasureSpec.makeMeasureSpec(1233, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
                root.layout(0, 0, 1233, height)
                repeat(5) { shadowOf(Looper.getMainLooper()).idle(); Thread.sleep(30) }
                val bmp = Bitmap.createBitmap(1233, height, Bitmap.Config.ARGB_8888)
                root.draw(Canvas(bmp))
                File(out, "settings_$section$suffix.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
            }
            controller.pause().stop().destroy()
        }
    }
}

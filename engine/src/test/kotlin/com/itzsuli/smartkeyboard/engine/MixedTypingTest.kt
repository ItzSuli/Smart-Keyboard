package com.itzsuli.smartkeyboard.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

/** English keyboard (QWERTY) that also writes German: mixed typing without switching. */
class MixedTypingTest {
    private val geo = TestSupport.qwerty
    private val en = TestSupport.model("en")
    private val de = TestSupport.model("de")
    private val mix = LanguageMix(listOf(en, de))
    private val spatial = SpatialModel(geo, null)
    private val mixed = MixedSuggester(mix, listOf(Suggester(en, spatial), Suggester(de, spatial)))

    private fun auto(before: String, word: String): String? {
        val ctx = TextUtils.contextWords(before, "en")
        val recent = TextUtils.recentWords(before, "en")
        return mixed.suggest(word, TestSupport.typed(word, geo), ctx, recent, AutoCorrectMode.CHILL).result.autoCorrect?.text
    }

    private fun weights(text: String) = mix.weights(TextUtils.recentWords(text, "en"))

    @Test fun detectsWhichLanguageIsBeingWritten() {
        assertTrue(weights("ich bin gerade ")[1] > 0.9f)
        assertTrue(weights("what are you doing ")[0] > 0.9f)
        // Nothing typed yet: English (the keyboard's language) leads.
        assertTrue(weights("")[0] > 0.5f)
        // A new sentence switches quickly.
        assertTrue(weights("Danke für alles. And ")[0] > 0.8f)
        assertTrue(weights("See you later. Ich ")[1] > 0.65f)
        assertTrue(weights("See you later. Ich bin ")[1] > 0.9f)
    }

    @Test fun germanWordsWithoutUmlautKeys() {
        assertEquals("müde", auto("ich bin so ", "mude"))
        assertEquals("für", auto("danke ", "fur"))
        // "schon" is a real word itself, so it is left alone.
        assertNull(auto("das ist ", "schon"))
        assertEquals("für", auto("danke ", "fuer"))
        assertEquals("Straße", auto("ich wohne in der ", "strasse"))
    }

    @Test fun neverPushesOneLanguageOntoTheOther() {
        // Real English words stay English in English sentences ...
        assertNull(auto("the cat has soft ", "fur"))
        assertNull(auto("my ", "hand"))
        // ... and German words stay German, even inside an English sentence.
        assertNull(auto("i said ", "und"))
        assertNull(auto("ich bin ", "im"))
        assertNotEquals("Haus", auto("my ", "haus"))
    }

    @Test fun stillFixesTyposInBothLanguages() {
        assertEquals("the", auto("i saw ", "teh"))
        assertEquals("because", auto("i think ", "becuase"))
        assertEquals("I'm", auto("ok. ", "im"))
        assertEquals("nicht", auto("ich weiß ", "nihct"))
        assertEquals("Haus", auto("das ", "haus"))
    }

    @Test fun predictsInTheCurrentLanguage() {
        fun predict(text: String) = mixed.predict(TextUtils.contextWords(text, "en"), TextUtils.recentWords(text, "en")).map { it.text }
        assertEquals("you", predict("how are ").first())
        val german = predict("wie geht ")
        assertTrue(german.toString(), "es" in german || "dir" in german)
    }

    @Test fun swipesGermanOnQwerty() {
        val rnd = Random(5)
        fun swipe(word: String, before: String): String {
            val (xs, ys) = TestSupport.gesture(word, geo, rnd, noise = 0.08f)
            val decs = listOf(SwipeDecoder(geo), SwipeDecoder(geo))
            return mixed.decodeSwipe(decs, xs, ys, xs.size, TextUtils.contextWords(before, "en"), TextUtils.recentWords(before, "en")).first().first
        }
        assertEquals("danke", swipe("danke", "ok "))
        assertEquals("hallo", swipe("hallo", "ich sage ").lowercase())
        assertEquals("hello", swipe("hello", "and then i said "))
        assertEquals("müde", swipe("mude", "ich bin so "))
    }

    @Test fun englishSwipeStaysAccurateWithGermanMixedIn() {
        val words = (0 until 1200).map { en.dict.keys[it] }.filter { it.length >= 2 && it.all { c -> c in 'a'..'z' } }.take(400)
        val decs = listOf(SwipeDecoder(geo), SwipeDecoder(geo))
        val rnd = Random(42)
        var top1 = 0
        val misses = ArrayList<String>()
        for (w in words) {
            val (xs, ys) = TestSupport.gesture(w, geo, rnd)
            val r = mixed.decodeSwipe(decs, xs, ys, xs.size, ContextWords(null, null, false), emptyList()).map { it.first.lowercase() }
            if (r.firstOrNull() == w) top1++ else if (misses.size < 25) misses.add("$w→${r.take(2)}")
        }
        val acc = top1 / words.size.toFloat()
        println("mixed swipe (no context): top1=%.3f misses=%s".format(acc, misses))
        assertTrue("top1 $acc", acc >= 0.88f)
    }

    @Test fun learnsWordsIntoTheRightLanguage() {
        val w = weights("ich bin ")
        assertEquals(1, mix.learnTarget("müde", w))
        assertEquals(0, mix.learnTarget("because", weights("i did it ")))
        // Unknown slang goes to whichever language is being written.
        assertEquals(1, mix.learnTarget("krassomat", w))
        // Words that exist in both go where they really belong.
        assertEquals(0, mix.learnTarget("and", w))
        assertEquals(1, mix.learnTarget("ich", weights("and then i ")))
    }

    @Test fun undoneCorrectionsStayUndoneInEveryLanguage() {
        assertEquals("the", auto("i saw ", "teh"))
        en.user.learnRejection("teh", "the")
        assertNull(auto("i saw ", "teh"))
    }

    @Test fun personalSlangIsSafeInBothLanguages() {
        repeat(3) { en.learn("skrrt", ContextWords(null, "so", false), it.toLong()) }
        assertNull(auto("ich bin ", "skrrt"))
        assertNull(auto("i was like ", "skrrt"))
    }
}

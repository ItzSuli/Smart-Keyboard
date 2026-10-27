package com.itzsuli.smartkeyboard.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MiscEngineTest {

    @Test fun nextLetterFavoursRealWords() {
        val lm = TestSupport.model()
        val p = KeyPredictor(lm).nextChars("th", ContextWords(null, null, false))
        assertNotNull(p)
        val best = p!!.maxByOrNull { it.value }!!.key
        assertTrue("best after th = $best", best == 'e' || best == 'a' || best == 'i')
        assertTrue((p['e'] ?: 0f) > (p['r'] ?: 0f) * 2)
    }

    @Test fun adaptiveHitboxStealsOnlyTheBorder() {
        val geo = TestSupport.qwerty
        val spatial = SpatialModel(geo, null)
        val lm = TestSupport.model()
        val probs = KeyPredictor(lm).nextChars("th", ContextWords(null, null, false))
        val e = geo.keyFor('e')!!
        val r = geo.keyFor('r')!!
        // Touch slightly inside "r", close to the e/r border.
        val x = (e.cx + r.cx) / 2 + 8f
        assertEquals('r', KeyPredictor.resolve(x, e.cy, geo.keys, null, spatial, 1f)!!.char)
        assertEquals('e', KeyPredictor.resolve(x, e.cy, geo.keys, probs, spatial, 1f)!!.char)
        // A touch in the middle of "r" is always "r".
        assertEquals('r', KeyPredictor.resolve(r.cx, r.cy, geo.keys, probs, spatial, 1f)!!.char)
    }

    @Test fun diffFindsWordFixes() {
        val fixes = TextDiff.wordFixes("i dont no wat u mean lol", "I don't know what you mean lol")
        assertTrue(fixes.toString(), ("dont" to "don't") in fixes)
        assertTrue(fixes.toString(), ("wat" to "what") in fixes)
        assertEquals(0, TextDiff.changeCount("same text.", "same text."))
    }

    @Test fun contextWordsStopAtSentenceEnd() {
        assertEquals(ContextWords("how", "are", false), TextUtils.contextWords("Hey. How are ", "en"))
        assertEquals(ContextWords(null, "i", true), TextUtils.contextWords("Ok! I ", "en"))
        assertEquals(ContextWords(null, null, true), TextUtils.contextWords("", "en"))
    }

    @Test fun arabicFolding() {
        assertEquals('ا', TextUtils.fold('أ'))
        assertEquals("انا", TextUtils.foldWord("أنا"))
        val lm = TestSupport.model("ar")
        assertTrue(lm.isKnown("والله"))
    }

    @Test fun touchModelLearnsOffsets() {
        val t = TouchModel()
        repeat(100) { t.update('a', 0.3f, -0.2f) }
        val (dx, dy) = t.offset('a')
        assertEquals(0.3f, dx, 0.02f)
        assertEquals(-0.2f, dy, 0.02f)
        val copy = TouchModel().also { it.restore(t.serialize()) }
        assertEquals(dx, copy.offset('a').first, 0.001f)
    }

    @Test fun allLanguagePacksLoad() {
        for (lang in listOf("en", "de", "ar", "fr", "es", "it", "pt", "tr", "nl", "ru", "pl")) {
            val d = TestSupport.dict(lang)
            assertTrue("$lang ${d.size}", d.size > 30_000)
            assertEquals(lang, d.lang)
            d.trie
        }
    }
}

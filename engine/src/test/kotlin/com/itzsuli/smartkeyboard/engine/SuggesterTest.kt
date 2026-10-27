package com.itzsuli.smartkeyboard.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SuggesterTest {
    private val geo = TestSupport.qwerty

    private fun suggester(lm: LanguageModel = TestSupport.model()) = Suggester(lm, SpatialModel(geo, null))

    private fun auto(s: Suggester, typed: String, ctx: ContextWords = ContextWords(null, "the", false), mode: AutoCorrectMode = AutoCorrectMode.CHILL): String? =
        s.suggest(typed, TestSupport.typed(typed, geo), ctx, mode).autoCorrect?.text

    @Test fun dictionaryLoads() {
        val d = TestSupport.dict("en")
        assertTrue(d.size > 50_000)
        assertTrue(d.idOf("the") in 0..5)
        assertEquals("I'm", d.words[d.idOf("i'm")])
    }

    @Test fun fixesObviousTypos() {
        val s = suggester()
        val mid = ContextWords("i", "think", false)
        assertEquals("the", auto(s, "teh", ContextWords("i", "saw", false)))
        assertEquals("because", auto(s, "becuase", mid))
        assertEquals("receive", auto(s, "recieve", ContextWords(null, "to", false)))
        assertEquals("hello", auto(s, "helo", ContextWords.START)?.lowercase())
        assertEquals("definitely", auto(s, "definately", mid))
        assertEquals("tomorrow", auto(s, "tomorow", ContextWords("see", "you", false)))
    }

    @Test fun fixesMissingApostrophes() {
        val s = suggester()
        assertEquals("don't", auto(s, "dont", ContextWords(null, "i", false)))
        assertEquals("I'm", auto(s, "im", ContextWords.START.copy(sentenceStart = true)))
        assertEquals("can't", auto(s, "cant", ContextWords(null, "i", false)))
        assertEquals("I", auto(s, "i", ContextWords.START))
    }

    @Test fun leavesRealWordsSlangAndSwearsAlone() {
        val s = suggester()
        for (w in listOf("form", "lol", "idk", "bruh", "tbh", "ngl", "fuck", "shit", "gonna", "were", "its", "well")) {
            assertNull("$w should not be corrected", auto(s, w))
        }
        // Acronyms in caps stay too.
        assertNull(auto(s, "NASAX"))
    }

    @Test fun learnsSlangAfterTypingItTwice() {
        val lm = TestSupport.model()
        val s = suggester(lm)
        val ctx = ContextWords(null, "that", false)
        assertTrue(auto(s, "skrrt", ctx) != null || true) // may or may not be "corrected" at first
        lm.learn("skrrt", ctx, 1)
        lm.learn("skrrt", ctx, 2)
        assertNull(auto(s, "skrrt", ctx))
        val completion = s.suggest("skr", TestSupport.typed("skr", geo), ctx, AutoCorrectMode.CHILL)
        assertTrue(completion.candidates.map { it.text }.toString(), completion.candidates.any { it.text == "skrrt" })
    }

    @Test fun remembersUndoneCorrections() {
        val lm = TestSupport.model()
        val s = suggester(lm)
        val ctx = ContextWords(null, "the", false)
        val first = auto(s, "teh", ctx)
        assertEquals("the", first)
        lm.user.learnRejection("teh", "the")
        assertNull(auto(s, "teh", ctx))
    }

    @Test fun usesCorrectionsLearnedFromAi() {
        val lm = TestSupport.model()
        val s = suggester(lm)
        // "alot" is not in the dictionary as a single word; the AI taught "a lot" style fix "alot" -> "allot" is wrong,
        // so use a personal mapping that the plain corrector would never pick.
        lm.user.learnCorrection("wanna", "want")
        // Confirmed slang stays untouched unless a correction was learned for it.
        lm.learn("wanna", ContextWords.START, 1)
        lm.learn("wanna", ContextWords.START, 2)
        assertEquals("want", auto(s, "wanna", ContextWords(null, "i", false)))
    }

    @Test fun completesWords() {
        val s = suggester()
        val r = s.suggest("becau", TestSupport.typed("becau", geo), ContextWords("i", "did", false), AutoCorrectMode.CHILL)
        assertTrue(r.candidates.toString(), r.candidates.take(3).any { it.text == "because" })
        assertNull("completions are never auto-applied", r.autoCorrect)
    }

    @Test fun predictsFromContext() {
        val s = suggester()
        assertEquals("you", s.predict(ContextWords("how", "are", false)).first().text)
        val wantTo = s.predict(ContextWords("want", "to", false)).map { it.text }
        assertTrue(wantTo.toString(), "go" in wantTo || "do" in wantTo || "be" in wantTo)
    }

    @Test fun personalHabitsShapePredictions() {
        val lm = TestSupport.model()
        val s = suggester(lm)
        val ctx = ContextWords("see", "you", false)
        repeat(6) { lm.learn("tmrw", ctx, it.toLong()) }
        assertEquals("tmrw", s.predict(ctx).first().text)
    }

    @Test fun keepsCaseTheUserTyped() {
        val s = suggester()
        assertEquals("The", auto(s, "Teh", ContextWords.START))
        // All-caps words are treated like acronyms and left alone.
        assertNull(auto(s, "TEH", ContextWords.START))
    }

    @Test fun germanUmlautsAndNouns() {
        val lm = TestSupport.model("de")
        val s = Suggester(lm, SpatialModel(TestSupport.qwertz, null))
        val r = s.suggest("fur", TestSupport.typed("fur", TestSupport.qwertz), ContextWords(null, "danke", false), AutoCorrectMode.CHILL)
        assertEquals("für", r.autoCorrect?.text)
        val haus = s.suggest("haus", TestSupport.typed("haus", TestSupport.qwertz), ContextWords(null, "das", false), AutoCorrectMode.CHILL)
        assertEquals("Haus", haus.autoCorrect?.text)
    }
}

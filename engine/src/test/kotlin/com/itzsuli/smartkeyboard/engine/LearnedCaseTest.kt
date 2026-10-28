package com.itzsuli.smartkeyboard.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Capitals are learned only when they're the user's habit, never from where a word stands. */
class LearnedCaseTest {
    private val geo = TestSupport.qwerty
    private val mid = ContextWords("i", "know", false)

    private fun auto(lm: LanguageModel, typed: String, ctx: ContextWords = mid): String? =
        Suggester(lm, SpatialModel(geo, null)).suggest(typed, TestSupport.typed(typed, geo), ctx, AutoCorrectMode.CHILL).autoCorrect?.text

    @Test fun aSentenceStartNeverTeachesACapital() {
        val lm = TestSupport.model()
        // "What" at the start of many sentences (shift was on by itself).
        repeat(5) { lm.learn("What", ContextWords.START, it.toLong()) }
        assertEquals("what", lm.display("what"))
        // So "what" in the middle of a sentence stays lowercase.
        assertNull(auto(lm, "what"))
        // The same goes for the user's own slang typed first at a sentence start.
        repeat(2) { lm.learn("Wallah", ContextWords.START, it.toLong()) }
        assertEquals("wallah", lm.display("wallah"))
    }

    @Test fun oneCapitalMidSentenceIsNotAHabit() {
        val lm = TestSupport.model()
        repeat(3) { lm.learn("what", mid, it.toLong()) }
        lm.learn("What", mid, 10)
        assertEquals("what", lm.display("what"))
        lm.learn("what", mid, 11)
        lm.learn("What", mid, 12)
        assertEquals("what", lm.display("what"))
        assertNull(auto(lm, "what"))
    }

    @Test fun allCapsShortcutsKeepTheirCapitals() {
        val lm = TestSupport.model()
        // Their own abbreviations, always in capitals.
        repeat(3) { lm.learn("LMAO", mid, it.toLong()) }
        assertEquals("LMAO", lm.display("lmao"))
        repeat(2) { lm.learn("LMAO", ContextWords.START, it.toLong()) }
        assertEquals("LMAO", lm.display("lmao"))
        // A dictionary word they always write in capitals becomes theirs after two uses in a row.
        lm.learn("lol", mid, 1)
        lm.learn("LOL", mid, 2)
        lm.learn("LOL", mid, 3)
        assertEquals("LOL", lm.display("lol"))
        assertTrue(lm.user.word("lol")!!.hasOwnCase)
        // Shouting in caps lock doesn't count.
        repeat(3) { lm.learn("WHAT", mid, it.toLong(), shouting = true) }
        assertEquals("what", lm.display("what"))
    }

    @Test fun namesTheUserWritesStayCapitalised() {
        val lm = TestSupport.model()
        lm.learn("Suli", mid, 1)
        lm.learn("Suli", mid, 2)
        assertEquals("Suli", lm.display("suli"))
        assertEquals("Suli", auto(lm, "suli"))
    }

    @Test fun repairsCapitalsLearnedByOlderVersions() {
        val lm = TestSupport.model()
        // What 1.3 left behind: "What" stored for "what", plus a real all-caps habit.
        lm.user.restoreWord("what", "What", 12, 1, 0)
        lm.user.restoreWord("lol", "LOL", 5, 1, 0)
        lm.user.restoreWord("suli", "Suli", 4, 1, UserLexicon.SLANG)
        assertEquals("What", lm.display("what"))
        // Even before the repair, a learned capital is no longer forced on a dictionary word.
        assertNull(auto(lm, "what"))
        assertEquals(1, lm.repairLearnedCase())
        assertEquals("what", lm.display("what"))
        assertNull(auto(lm, "what"))
        assertEquals("LOL", lm.display("lol"))
        assertEquals("Suli", lm.display("suli"))
        assertFalse(lm.user.word("what")!!.hasOwnCase)
    }

    @Test fun dictionaryCapitalsStillApply() {
        val lm = TestSupport.model()
        // Names and "I" from the dictionary are still capitalised.
        assertEquals("I", auto(lm, "i", ContextWords(null, "and", false)))
        repeat(3) { lm.learn("Monday", ContextWords.START, it.toLong()) }
        assertEquals("Monday", lm.display("monday"))
    }
}

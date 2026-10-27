package com.itzsuli.smartkeyboard.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

/** Gibberish from a broken model must never be applied; real fixes must be. */
class AiOutputCheckTest {
    private val de = listOf("en", "de")
    private val proof = AiOutputCheck.Kind.PROOFREAD

    @Test fun acceptsRealFixes() {
        assertEquals(null, AiOutputCheck.problem("ich hab das nich gewust", "Ich hab das nicht gewusst", proof, de))
        assertEquals(null, AiOutputCheck.problem("i dont no wat u mean lol", "I don't know what you mean lol", proof, de))
        assertEquals(null, AiOutputCheck.problem("hey 😂 wie gehts", "hey 😂 wie geht's", proof, de))
        // Arabic text on a German/English keyboard stays allowed when the text has it.
        assertEquals(null, AiOutputCheck.problem("habibi حبيبي wo bist du", "habibi حبيبي, wo bist du?", proof, de))
        assertEquals(null, AiOutputCheck.problem("ok", "OK", proof, de))
    }

    @Test fun rejectsGibberish() {
        // The kind of output a broken on-device model produced: other alphabets mixed in.
        assertNotNull(AiOutputCheck.problem("Ich bin morgen leider nicht da", "Ich bin 明天 leider не da", proof, de))
        assertNotNull(AiOutputCheck.problem("Ich bin morgen leider nicht da", "Ich bin morgen 😢 leider nicht da 🙏", proof, de))
        assertNotNull(AiOutputCheck.problem("Kannst du mir bitte das Ladekabel mitbringen", "Der Himmel ist blau und die Katze schläft heute im Garten", proof, de))
        assertNotNull(AiOutputCheck.problem("Kannst du mir bitte das Ladekabel mitbringen", "", proof, de))
    }

    @Test fun rewritesAndTranslations() {
        val rewrite = AiOutputCheck.Kind.REWRITE
        assertEquals(null, AiOutputCheck.problem("kannst du kommen", "Hättest du Zeit vorbeizukommen?", rewrite, de))
        assertNotNull(AiOutputCheck.problem("kannst du kommen", "你能来吗", rewrite, de))
        assertEquals(null, AiOutputCheck.problem("kannst du kommen", "kannst du kommen? 🥺", AiOutputCheck.Kind.REWRITE_EMOJI, de))
        assertEquals(null, AiOutputCheck.problem("kannst du kommen", "هل يمكنك المجيء؟", AiOutputCheck.Kind.TRANSLATE, de, "ar"))
        assertNotNull(AiOutputCheck.problem("kannst du kommen", "你能来吗", AiOutputCheck.Kind.TRANSLATE, de, "ar"))
    }
}

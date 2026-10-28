package com.itzsuli.smartkeyboard.engine

import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

/**
 * Simulated thumb typing on everyday sentences: taps land around the intended key with
 * human-like scatter. Adaptive hit areas must fix more slips than plain geometry, and must
 * almost never turn a tap that landed on the right key into a wrong letter.
 */
class AdaptiveKeysBenchmarkTest {
    private val geo = TestSupport.qwerty
    private val spatial = SpatialModel(geo, null)
    private val lm = TestSupport.model()
    private val predictor = KeyPredictor(lm)

    private val sentences = listOf(
        "are you kidding me", "just kidding lol", "i was kidding", "no way you did that", "what are you doing tonight",
        "i think we should go home", "can you send me the address", "thanks for the help today", "see you tomorrow at school",
        "that was so funny", "i dont know what to say", "let me know when you get there", "how was your day",
        "we need to talk about it", "i love this song", "she said she would call me later", "where did you put my keys",
        "i am going to the gym", "did you see the game last night", "my phone is almost dead", "the kids are sleeping",
        "happy birthday bro", "it is raining again", "can we meet at six", "i forgot my wallet at home",
        "this is the best pizza ever", "good morning have a nice day", "he is driving me crazy", "do you want to come with us",
        "the movie starts at nine", "i need some coffee", "send me a picture", "we should hang out soon",
        "my mom made dinner", "the weather is really nice", "call me when you are free", "i just woke up",
        "this is taking forever", "what time is it", "i will be there in ten minutes", "kidding aside it was good",
    )

    data class Stats(val taps: Int, val geoErrors: Int, val errors: Int, val steals: Int, val saves: Int) {
        override fun toString() = "taps=$taps geoErr=${pct(geoErrors)} adaptiveErr=${pct(errors)} steals=${pct(steals)} saves=${pct(saves)}"
        private fun pct(n: Int) = "%.2f%%".format(100.0 * n / taps)
    }

    private fun run(strength: Float, noiseX: Float, noiseY: Float, seed: Long = 7, damp: ((Char, Char) -> Float)? = null): Stats {
        val rnd = Random(seed)
        var taps = 0; var geoErr = 0; var err = 0; var steals = 0; var saves = 0
        val keys = geo.keys
        repeat(4) {
            for (s in sentences) {
                var text = ""
                for (word in s.split(' ')) {
                    val ctx = TextUtils.contextWords(text, "en")
                    for (i in word.indices) {
                        val target = geo.keyFor(word[i]) ?: continue
                        val probs = predictor.nextChars(word.substring(0, i), ctx)
                        val x = target.cx + rnd.nextGaussian().toFloat() * noiseX * target.width
                        val y = target.cy + rnd.nextGaussian().toFloat() * noiseY * target.height
                        val plain = KeyPredictor.resolve(x, y, keys, null, spatial, 0f)
                        val adaptive = if (damp != null) KeyPredictor.resolve(x, y, keys, probs, spatial, strength, damp = damp)
                        else KeyPredictor.resolve(x, y, keys, probs, spatial, strength)
                        taps++
                        val plainOk = plain?.char == target.char
                        val adaptiveOk = adaptive?.char == target.char
                        if (!plainOk) geoErr++
                        if (!adaptiveOk) err++
                        if (plainOk && !adaptiveOk) steals++
                        if (!plainOk && adaptiveOk) saves++
                    }
                    text += "$word "
                }
            }
        }
        return Stats(taps, geoErr, err, steals, saves)
    }

    @Test fun adaptiveKeysHelpWithoutStealing() {
        for ((name, nx, ny) in listOf(Triple("careful", 0.22f, 0.2f), Triple("sloppy", 0.32f, 0.26f))) {
            for ((level, strength) in listOf("subtle" to 0.5f, "balanced" to 1f, "strong" to 1.5f)) {
                val s = run(strength, nx, ny)
                println("adaptive keys [$name, $level]: $s")
                // Must help overall...
                assertTrue("$name/$level: $s", s.errors < s.geoErrors)
                // ...and taps on the right key must stay right (border taps only).
                val maxSteals = when (level) { "subtle" -> 0.3; "balanced" -> 0.6; else -> 0.9 }
                assertTrue("$name/$level: $s", s.steals * 100.0 / s.taps < maxSteals)
            }
        }
    }

    @Test fun kiddingIsNotTurnedIntoKids() {
        // A tap clearly on "d" (but off-centre towards "s") after "kid" stays "d".
        val d = geo.keyFor('d')!!
        val probs = predictor.nextChars("kid", ContextWords("are", "you", false))
        for (rx in listOf(-0.3f, -0.5f, -0.65f)) {
            val k = KeyPredictor.resolve(d.cx + rx * d.width / 2, d.cy, geo.keys, probs, spatial, 1f)
            assertTrue("rx=$rx gave ${k?.char}", k?.char == 'd')
        }
    }

    @Test fun undoneStealsCalmThatKeyPairDown() {
        // After the user undid "d → s" a few times, border taps on d stay d.
        val d = geo.keyFor('d')!!
        val probs = mapOf('s' to 0.9f, 'd' to 0.02f, ' ' to 0.08f)
        val x = d.cx - d.width * 0.45f
        assertTrue(KeyPredictor.resolve(x, d.cy, geo.keys, probs, spatial, 1f)!!.char == 's')
        val learned = KeyPredictor.resolve(x, d.cy, geo.keys, probs, spatial, 1f, damp = { from, to -> if (from == 'd' && to == 's') 0.1f else 1f })
        assertTrue(learned!!.char == 'd')
    }
}

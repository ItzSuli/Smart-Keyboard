package com.itzsuli.smartkeyboard.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

class SwipeDecoderTest {
    private val geo = TestSupport.qwerty
    private val decoder = SwipeDecoder(geo)

    private fun decode(word: String, lm: LanguageModel, ctx: ContextWords, rnd: Random, noise: Float = 0.22f): List<String> {
        val (xs, ys) = TestSupport.gesture(word, geo, rnd, noise)
        return decoder.decode(xs, ys, xs.size, lm, ctx).map { it.key }
    }

    @Test fun decodesCleanGestures() {
        val lm = TestSupport.model()
        val rnd = Random(1)
        for (w in listOf("hello", "world", "keyboard", "because", "thanks", "tomorrow", "people", "something")) {
            assertEquals(w, decode(w, lm, ContextWords(null, null, false), rnd, noise = 0.05f).firstOrNull())
        }
    }

    @Test fun accuracyOnCommonWords() {
        val lm = TestSupport.model()
        val dict = lm.dict
        val words = (0 until 1200).map { dict.keys[it] }
            .filter { it.length >= 2 && it.all { c -> c in 'a'..'z' } }
            .take(400)
        val rnd = Random(42)
        var top1 = 0
        var top3 = 0
        val misses = ArrayList<String>()
        val t0 = System.nanoTime()
        for (w in words) {
            val r = decode(w, lm, ContextWords(null, null, false), rnd)
            if (r.firstOrNull() == w) top1++ else if (misses.size < 40) misses.add("$w→${r.take(3)}")
            if (w in r.take(3)) top3++
        }
        val ms = (System.nanoTime() - t0) / 1e6 / words.size
        val acc1 = top1.toFloat() / words.size
        val acc3 = top3.toFloat() / words.size
        println("swipe: top1=%.3f top3=%.3f (%.1f ms/word) misses=%s".format(acc1, acc3, ms, misses))
        assertTrue("top1 $acc1", acc1 >= 0.80f)
        assertTrue("top3 $acc3", acc3 >= 0.93f)
    }

    @Test fun contextResolvesAmbiguousShapes() {
        val lm = TestSupport.model()
        val rnd = Random(7)
        // "to" and "too" share one gesture; context decides.
        assertEquals("to", decode("to", lm, ContextWords("i", "want", false), rnd, 0.05f).first())
    }

    @Test fun learnedSlangIsSwipeable() {
        val lm = TestSupport.model()
        repeat(3) { lm.learn("yeet", ContextWords.START, it.toLong()) }
        val r = decode("yeet", lm, ContextWords(null, null, false), Random(3), 0.05f)
        assertTrue(r.toString(), "yeet" in r.take(3))
    }
}

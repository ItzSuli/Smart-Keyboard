package com.itzsuli.smartkeyboard.engine

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Real-world English typos (common misspellings and fat-finger slips) must be fixed, while
 * real words, slang and swearing must be left exactly as typed.
 */
class AutocorrectBenchmarkTest {
    private val geo = TestSupport.qwerty

    private val typos = mapOf(
        "acheive" to "achieve", "accomodate" to "accommodate", "adress" to "address", "beleive" to "believe",
        "begining" to "beginning", "calender" to "calendar", "comming" to "coming", "definately" to "definitely",
        "embarass" to "embarrass", "enviroment" to "environment", "existance" to "existence", "freind" to "friend",
        "goverment" to "government", "happend" to "happened", "immediatly" to "immediately",
        "independant" to "independent", "knowlege" to "knowledge", "neccessary" to "necessary", "occured" to "occurred",
        "peice" to "piece", "realy" to "really", "recieve" to "receive", "seperate" to "separate",
        "succesful" to "successful", "tommorow" to "tomorrow", "truely" to "truly", "untill" to "until",
        "wierd" to "weird", "wich" to "which", "becuase" to "because", "thier" to "their", "teh" to "the",
        "adn" to "and", "taht" to "that", "waht" to "what", "hte" to "the", "jsut" to "just", "konw" to "know",
        "tihs" to "this", "yuo" to "you", "dont" to "don't", "doesnt" to "doesn't", "isnt" to "isn't",
        "wasnt" to "wasn't", "couldnt" to "couldn't", "wouldnt" to "wouldn't", "shouldnt" to "shouldn't",
        "youre" to "you're", "theyre" to "they're", "thats" to "that's", "whats" to "what's", "didnt" to "didn't",
        "hrllo" to "hello", "tgis" to "this", "wjat" to "what", "goinf" to "going", "thamks" to "thanks",
        "pleasr" to "please", "somethinf" to "something", "anyrhing" to "anything", "abd" to "and", "rhe" to "the",
        "yiu" to "you", "thnks" to "thanks", "helo" to "hello", "probaly" to "probably", "basicly" to "basically",
        "finaly" to "finally", "beutiful" to "beautiful", "diffrent" to "different", "intresting" to "interesting",
        "remeber" to "remember", "togehter" to "together", "minuts" to "minutes",
        "everyting" to "everything", "becasue" to "because", "freinds" to "friends", "excercise" to "exercise",
    )

    private val keep = listOf(
        "form", "from", "lol", "idk", "tbh", "gonna", "wanna", "were", "well", "shit", "fuck", "fucking",
        "cuz", "tho", "bc", "rn", "ngl", "wyd", "hbu", "lmao", "bruh", "yall", "dunno", "kinda", "ok", "okay",
        "yeah", "nah", "yep", "u", "ur", "omg", "wtf", "btw", "pls", "thx", "bro", "dude", "lowkey", "sus",
        "cringe", "vibe", "lit", "goin", "nothin", "ain't", "y'all", "asshole", "bitch", "damn", "hella", "wat",
        "tonite", "bout",
    )

    @Test fun fixesTyposAndKeepsSlang() {
        val s = Suggester(TestSupport.model(), SpatialModel(geo, null))
        fun auto(w: String) = s.suggest(w, TestSupport.typed(w, geo), ContextWords("i", "think", false), AutoCorrectMode.CHILL).autoCorrect?.text
        val wrong = typos.filter { (t, fix) -> auto(t) != fix }.map { (t, fix) -> "$t→${auto(t)} (want $fix)" }
        val touched = keep.filter { auto(it) != null }.map { "$it→${auto(it)}" }
        val rate = 1f - wrong.size.toFloat() / typos.size
        println("autocorrect: fixed %.3f of typos; wrong=%s; touched=%s".format(rate, wrong, touched))
        assertTrue("fix rate $rate: $wrong", rate >= 0.9f)
        assertTrue("must not touch: $touched", touched.isEmpty())
    }
}

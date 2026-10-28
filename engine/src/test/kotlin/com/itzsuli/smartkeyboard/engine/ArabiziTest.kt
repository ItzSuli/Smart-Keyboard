package com.itzsuli.smartkeyboard.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Everyday Arabizi (Levantine, Gulf, Egyptian chat spelling) must become the right Arabic words. */
class ArabiziTest {
    private val arabizi = Arabizi(TestSupport.model("ar"), TestSupport.arabicChat)
    private val none = ContextWords(null, null, true)

    // Several Arabic spellings are fine where people really write both (انا / أنا).
    private val cases = listOf(
        "7abibi" to listOf("حبيبي"), "habibi" to listOf("حبيبي"), "3arabi" to listOf("عربي"), "ana" to listOf("أنا", "انا"),
        "sho" to listOf("شو"), "kifak" to listOf("كيفك"), "keefak" to listOf("كيفك"), "shukran" to listOf("شكرا"),
        "marhaba" to listOf("مرحبا"), "2albi" to listOf("قلبي"), "5alas" to listOf("خلاص"), "mni7" to listOf("منيح"),
        "3anjad" to listOf("عنجد"), "ta3al" to listOf("تعال"), "b7ebak" to listOf("بحبك"), "enta" to listOf("انت", "إنت", "أنت"),
        "inta" to listOf("انت", "إنت", "أنت"), "ma3" to listOf("مع"), "kteer" to listOf("كتير"), "ya3ni" to listOf("يعني"),
        "tayeb" to listOf("طيب"), "6ayeb" to listOf("طيب"), "habibti" to listOf("حبيبتي"), "salam" to listOf("سلام"),
        "3ala" to listOf("على"), "ahlan" to listOf("أهلا", "اهلا"), "sabah" to listOf("صباح"), "el5eir" to listOf("الخير"),
        "hayk" to listOf("هيك"), "mesh" to listOf("مش"), "msh" to listOf("مش"), "3ndi" to listOf("عندي"), "bdi" to listOf("بدي"),
        "leh" to listOf("ليه"), "lesh" to listOf("ليش"), "wen" to listOf("وين"), "ween" to listOf("وين"), "tamam" to listOf("تمام"),
        "7elwe" to listOf("حلوة", "حلوه"), "7ilu" to listOf("حلو"), "ba3d" to listOf("بعد"), "kaman" to listOf("كمان"),
        "zalameh" to listOf("زلمة", "زلمه"), "3amel" to listOf("عامل"), "eh" to listOf("ايه", "إيه"), "ahla" to listOf("أهلا", "اهلا"),
        "hala" to listOf("هلا"), "allah" to listOf("الله"), "w" to listOf("و"), "ya" to listOf("يا"), "la2" to listOf("لأ", "لا"),
        "2ana" to listOf("أنا"), "ma3lesh" to listOf("معلش"), "3aib" to listOf("عيب"), "sa7" to listOf("صح"), "a5i" to listOf("أخي", "اخي"),
        "5ayye" to listOf("خيي"), "lissa" to listOf("لسا"), "halla2" to listOf("هلق", "هلأ"), "inshallah" to listOf("إن شاء الله"),
    )

    @Test fun convertsEverydayArabizi() {
        var top1 = 0
        var top3 = 0
        val misses = ArrayList<String>()
        for ((latin, want) in cases) {
            val got = arabizi.convert(latin, none, 3).map { it.arabic }
            if (got.firstOrNull() in want) top1++ else misses.add("$latin→$got")
            if (got.any { it in want }) top3++
        }
        val n = cases.size.toDouble()
        println("arabizi: top1=%.3f top3=%.3f misses=$misses".format(top1 / n, top3 / n))
        assertTrue("top1 ${top1 / n}: $misses", top1 / n >= 0.9)
        assertTrue("top3 ${top3 / n}: $misses", top3 / n >= 0.98)
    }

    @Test fun recognisesArabiziAndEnglishLookalikes() {
        assertTrue(Arabizi.hasArabiziDigits("7abibi"))
        assertTrue(Arabizi.hasArabiziDigits("mni7"))
        assertTrue(!Arabizi.hasArabiziDigits("hello"))
        assertTrue(!Arabizi.hasArabiziDigits("2024"))
        assertTrue("gr8" in Arabizi.ENGLISH_LOOKALIKES)
        // Numbers alone, or anything with other symbols, are never converted.
        assertTrue(arabizi.convert("2024", none).isEmpty())
        assertTrue(arabizi.convert("a.b", none).isEmpty())
    }

    @Test fun remembersWhatThePersonPicked() {
        val own = Arabizi(TestSupport.model("ar"), TestSupport.arabicChat)
        assertEquals("لسه", own.convert("lesh", none, 1).first().arabic)
        own.learn("lesh", "ليش")
        assertEquals("ليش", own.convert("lesh", none, 1).first().arabic)
    }

    @Test fun contextHelpsPickTheWord() {
        val got = arabizi.convert("el5eir", ContextWords(null, "صباح", false), 1)
        assertEquals("الخير", got.firstOrNull()?.arabic)
    }
}

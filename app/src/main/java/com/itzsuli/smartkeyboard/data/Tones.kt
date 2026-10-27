package com.itzsuli.smartkeyboard.data

/** How the AI proofreads (and how voice typing is cleaned up). */
enum class AiTone(val label: String, val description: String) {
    CHILL("Chill", "Fixes typos only. Keeps your slang, swearing, lowercase and style."),
    CLEAN("Clean", "Also fixes capitals and punctuation. Keeps your words and slang."),
    POLITE("Polite", "Fixes everything and writes out chat shortcuts (u → you). Friendly and natural, never stiff."),
}

/**
 * The tone each kind of app gets automatically: chill in chats, clean in work tools, polite
 * in email. The user can change any app (Settings → AI → Tone per app).
 */
object AppTones {
    val CHAT = setOf(
        "com.whatsapp", "com.whatsapp.w4b", "com.snapchat.android", "org.telegram.messenger", "org.thunderdog.challegram",
        "com.instagram.android", "com.instagram.barcelona", "com.facebook.orca", "com.facebook.katana", "com.facebook.mlite",
        "org.thoughtcrime.securesms", "com.discord", "com.google.android.apps.messaging", "com.samsung.android.messaging",
        "com.twitter.android", "com.zhiliaoapp.musically", "com.ss.android.ugc.trill", "com.reddit.frontpage",
        "jp.naver.line.android", "com.viber.voip", "com.kakao.talk", "com.tencent.mm", "com.imo.android.imoim",
        "com.skype.raider", "com.bereal.ft", "com.google.android.apps.dynamite", "im.vector.app", "com.beeper.android",
    )
    val EMAIL = setOf(
        "com.google.android.gm", "com.google.android.gm.lite", "com.microsoft.office.outlook", "com.samsung.android.email.provider",
        "com.yahoo.mobile.client.android.mail", "ch.protonmail.android", "me.proton.android.mail", "com.readdle.spark",
        "com.fsck.k9", "com.android.email", "me.bluemail.mail", "com.easilydo.mail", "de.gmx.mobile.android.mail",
        "de.web.mobile.android.mail", "com.tutanota",
    )
    val WORK = setOf(
        "com.Slack", "com.microsoft.teams", "com.linkedin.android", "com.google.android.apps.docs.editors.docs",
        "com.microsoft.office.word", "com.microsoft.office.officehubrow", "notion.id", "com.google.android.keep",
        "com.samsung.android.app.notes", "com.atlassian.android.jira.core", "com.asana.app", "com.trello", "com.zoom.videomeetings",
    )

    /** The automatic tone for [pkg], or null when the app isn't known (the default tone applies). */
    fun automatic(pkg: String?): AiTone? = when (pkg) {
        null -> null
        in EMAIL -> AiTone.POLITE
        in WORK -> AiTone.CLEAN
        in CHAT -> AiTone.CHILL
        else -> null
    }

    /** Well-known apps shown in the per-app list even before the user has typed in them. */
    val KNOWN: List<String> get() = (CHAT + EMAIL + WORK).toList()
}

package com.itzsuli.smartkeyboard.data

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.FileProvider
import com.itzsuli.smartkeyboard.BuildConfig
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Updates from inside the app: finds the newest release on GitHub, downloads its APK and hands
 * it to Android's installer. An update signed with the same key installs over this version and
 * keeps everything (settings, learned words, offline models); Android refuses one signed with
 * another key, which [signerOf] tells before trying.
 */
class Updater(private val context: Context) {

    class Release(val version: String, val notes: String, val apkUrl: String, val apkSize: Long, val pageUrl: String)

    enum class Signer { SAME, DIFFERENT, UNKNOWN }

    /** The newest release if it's newer than this version, else null. Blocking. */
    fun check(): Release? {
        val conn = (URL("$apiBase/releases/latest").openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 20_000
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("User-Agent", "SmartKeyboard/${BuildConfig.VERSION_NAME}")
        }
        try {
            if (conn.responseCode == 404) return null
            if (conn.responseCode !in 200..299) throw IOException("GitHub answered ${conn.responseCode}")
            val json = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
            val version = json.optString("tag_name").removePrefix("v")
            if (!isNewer(version, BuildConfig.VERSION_NAME.removeSuffix("-debug"))) return null
            val assets = json.optJSONArray("assets") ?: return null
            for (i in 0 until assets.length()) {
                val a = assets.getJSONObject(i)
                if (!a.optString("name").endsWith(".apk")) continue
                return Release(version, json.optString("body"), a.optString("browser_download_url"), a.optLong("size"), json.optString("html_url"))
            }
            return null
        } finally {
            conn.disconnect()
        }
    }

    /** Downloads the APK of [release] into the app's cache. Blocking. */
    fun download(release: Release, onProgress: (Long, Long) -> Unit): File {
        val dir = File(context.cacheDir, "updates").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val target = File(dir, "SmartKeyboard-v${release.version}.apk")
        val conn = (URL(release.apkUrl).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 30_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "SmartKeyboard/${BuildConfig.VERSION_NAME}")
        }
        try {
            if (conn.responseCode !in 200..299) throw IOException("Download failed (${conn.responseCode})")
            val total = conn.contentLengthLong.takeIf { it > 0 } ?: release.apkSize
            conn.inputStream.use { input ->
                target.outputStream().use { out ->
                    val buf = ByteArray(1 shl 16)
                    var done = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        onProgress(done, total)
                    }
                }
            }
        } finally {
            conn.disconnect()
        }
        if (release.apkSize > 0 && target.length() != release.apkSize) throw IOException("The download was incomplete, try again")
        return target
    }

    /** Whether [apk] is signed with the same key as this app (only then can it update it). */
    fun signerOf(apk: File): Signer {
        val pm = context.packageManager
        val theirs = runCatching { certificates(archiveInfo(pm, apk.absolutePath)) }.getOrNull()
        val ours = runCatching { certificates(installedInfo(pm)) }.getOrNull()
        if (theirs.isNullOrEmpty() || ours.isNullOrEmpty()) return Signer.UNKNOWN
        return if (theirs == ours) Signer.SAME else Signer.DIFFERENT
    }

    /** Opens Android's installer for [apk] (it asks to allow installs from this app the first time). */
    fun install(apk: File) {
        val uri = FileProvider.getUriForFile(context, context.packageName + ".files", apk)
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }

    private val prefs get() = context.getSharedPreferences("updater", Context.MODE_PRIVATE)

    /**
     * The newer version the last check found, if any. Checks again when the last check is older
     * than [maxAgeMs]; never throws (no internet just means no news). Blocking.
     */
    fun availableVersion(maxAgeMs: Long = 6 * 3_600_000L): String? {
        val now = System.currentTimeMillis()
        if (now - prefs.getLong("checked", 0L) > maxAgeMs) {
            runCatching { check() }.onSuccess { prefs.edit().putLong("checked", now).putString("version", it?.version).apply() }
        }
        return prefs.getString("version", null)?.takeIf { isNewer(it, BuildConfig.VERSION_NAME.removeSuffix("-debug")) }
    }

    /** SHA-256 fingerprints of this app's signing certificates (shown in About). */
    fun ownFingerprints(): List<String> = runCatching { certificates(installedInfo(context.packageManager)).toList() }.getOrDefault(emptyList())

    @Suppress("DEPRECATION")
    private fun archiveInfo(pm: PackageManager, path: String): PackageInfo? =
        if (Build.VERSION.SDK_INT >= 28) pm.getPackageArchiveInfo(path, PackageManager.GET_SIGNING_CERTIFICATES)
        else pm.getPackageArchiveInfo(path, PackageManager.GET_SIGNATURES)

    @Suppress("DEPRECATION")
    private fun installedInfo(pm: PackageManager): PackageInfo =
        if (Build.VERSION.SDK_INT >= 28) pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
        else pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNATURES)

    @Suppress("DEPRECATION")
    private fun certificates(info: PackageInfo?): Set<String> {
        info ?: return emptySet()
        val sigs = if (Build.VERSION.SDK_INT >= 28) info.signingInfo?.apkContentsSigners else info.signatures
        return sigs.orEmpty().map { sig ->
            MessageDigest.getInstance("SHA-256").digest(sig.toByteArray()).joinToString(":") { "%02X".format(it) }
        }.toSet()
    }

    companion object {
        /** Replaced in tests. */
        var apiBase = "https://api.github.com/repos/ItzSuli/Smart-Keyboard"
        /** Look for updates when the app opens (off in tests). */
        var autoCheck = true

        /** True when version [a] ("1.4.0") is newer than [b]. */
        fun isNewer(a: String, b: String): Boolean {
            val x = a.split('.', '-').map { it.toIntOrNull() ?: 0 }
            val y = b.split('.', '-').map { it.toIntOrNull() ?: 0 }
            for (i in 0 until maxOf(x.size, y.size)) {
                val d = x.getOrElse(i) { 0 } - y.getOrElse(i) { 0 }
                if (d != 0) return d > 0
            }
            return false
        }
    }
}

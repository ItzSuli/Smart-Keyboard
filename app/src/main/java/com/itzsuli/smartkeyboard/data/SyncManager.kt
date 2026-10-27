package com.itzsuli.smartkeyboard.data

import android.content.Context
import android.net.Uri
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.security.KeyStore
import java.security.SecureRandom
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * End-to-end encrypted file format for what the keyboard learned: AES-256-GCM with a key
 * derived from the user's passphrase (PBKDF2-HMAC-SHA256, 210 000 rounds, random salt).
 * Whoever stores the file (Google Drive, a USB stick...) only ever sees random bytes.
 */
object SyncCrypto {
    private val MAGIC = "SKSYNC".toByteArray()
    private const val VERSION: Byte = 1
    private const val ITERATIONS = 210_000
    private const val SALT_BYTES = 16
    private const val IV_BYTES = 12

    class WrongPassphrase : Exception("Wrong passphrase for this sync file")

    fun encrypt(plain: String, passphrase: String): ByteArray {
        val random = SecureRandom()
        val salt = ByteArray(SALT_BYTES).also { random.nextBytes(it) }
        val iv = ByteArray(IV_BYTES).also { random.nextBytes(it) }
        val zipped = ByteArrayOutputStream().also { out -> GZIPOutputStream(out).use { it.write(plain.toByteArray()) } }.toByteArray()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key(passphrase, salt, ITERATIONS), GCMParameterSpec(128, iv))
        val header = ByteBuffer.allocate(MAGIC.size + 1 + 4).put(MAGIC).put(VERSION).putInt(ITERATIONS).array()
        cipher.updateAAD(header)
        val body = cipher.doFinal(zipped)
        return header + salt + iv + body
    }

    fun decrypt(data: ByteArray, passphrase: String): String {
        val headerSize = MAGIC.size + 1 + 4
        require(data.size > headerSize + SALT_BYTES + IV_BYTES && data.copyOfRange(0, MAGIC.size).contentEquals(MAGIC)) { "Not a Smart Keyboard sync file" }
        val buf = ByteBuffer.wrap(data)
        buf.position(MAGIC.size)
        require(buf.get() == VERSION) { "This sync file was made by a newer version of Smart Keyboard" }
        val iterations = buf.int
        require(iterations in 10_000..5_000_000) { "Damaged sync file" }
        val salt = data.copyOfRange(headerSize, headerSize + SALT_BYTES)
        val iv = data.copyOfRange(headerSize + SALT_BYTES, headerSize + SALT_BYTES + IV_BYTES)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(passphrase, salt, iterations), GCMParameterSpec(128, iv))
        cipher.updateAAD(data.copyOfRange(0, headerSize))
        val zipped = try {
            cipher.doFinal(data, headerSize + SALT_BYTES + IV_BYTES, data.size - headerSize - SALT_BYTES - IV_BYTES)
        } catch (e: AEADBadTagException) {
            throw WrongPassphrase()
        }
        return GZIPInputStream(ByteArrayInputStream(zipped)).use { String(it.readBytes()) }
    }

    private fun key(passphrase: String, salt: ByteArray, iterations: Int): SecretKey {
        val spec = PBEKeySpec(passphrase.toCharArray(), salt, iterations, 256)
        val bytes = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        spec.clearPassword()
        return SecretKeySpec(bytes, "AES")
    }
}

/**
 * Keeps the sync passphrase on this phone, encrypted with a key that lives in the Android
 * Keystore (it can't be read out of the phone). Falls back to app-private storage where the
 * Keystore isn't available.
 */
object PassphraseStore {
    private const val ALIAS = "smart_keyboard_sync"

    fun seal(plain: String): String = runCatching {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, keystoreKey())
        "ks:" + Base64.encodeToString(cipher.iv + cipher.doFinal(plain.toByteArray()), Base64.NO_WRAP)
    }.getOrElse { "pl:" + Base64.encodeToString(plain.toByteArray(), Base64.NO_WRAP) }

    fun open(sealed: String): String? = runCatching {
        when {
            sealed.startsWith("ks:") -> {
                val data = Base64.decode(sealed.substring(3), Base64.NO_WRAP)
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(Cipher.DECRYPT_MODE, keystoreKey(), GCMParameterSpec(128, data, 0, 12))
                String(cipher.doFinal(data, 12, data.size - 12))
            }
            sealed.startsWith("pl:") -> String(Base64.decode(sealed.substring(3), Base64.NO_WRAP))
            else -> null
        }
    }.getOrNull()

    private fun keystoreKey(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .build())
        return gen.generateKey()
    }
}

/**
 * Syncs what the keyboard learned between phones through one encrypted file the user picks
 * (for example in Google Drive, through Android's file picker). Each sync reads the file,
 * merges it into this phone (nothing is ever lost: see [LearningStore.importJson]) and writes
 * the combined result back, so two phones pointed at the same file end up knowing the same.
 */
class SyncManager(private val context: Context, private val settings: Settings) {
    private val app get() = com.itzsuli.smartkeyboard.SmartKeyboardApp.of(context)

    class Report(val merged: Int, val createdFile: Boolean)

    val fileUri: Uri? get() = settings.prefs.getString(Settings.SYNC_URI, null)?.let { Uri.parse(it) }

    val hasPassphrase: Boolean get() = settings.prefs.getString(Settings.SYNC_SECRET, null) != null

    val isReady: Boolean get() = fileUri != null && hasPassphrase

    fun setPassphrase(passphrase: String) {
        require(passphrase.length >= 8) { "Use at least 8 characters" }
        settings.set(Settings.SYNC_SECRET, PassphraseStore.seal(passphrase))
    }

    fun setFile(uri: Uri?) = settings.set(Settings.SYNC_URI, uri?.toString())

    /** Runs a sync now (call from a background thread). */
    @Synchronized
    fun syncNow(): Result<Report> = runCatching {
        val uri = fileUri ?: error("Choose a sync file first")
        val passphrase = settings.prefs.getString(Settings.SYNC_SECRET, null)?.let { PassphraseStore.open(it) } ?: error("Set a passphrase first")
        val resolver = context.contentResolver
        val remote = runCatching { resolver.openInputStream(uri)?.use { it.readBytes() } }.getOrNull()
        var merged = 0
        val created = remote == null || remote.isEmpty()
        if (!created) {
            merged = app.store.importJson(SyncCrypto.decrypt(remote!!, passphrase))
            app.languages.reloadUserData()
            app.shortcuts.reload()
        }
        val out = SyncCrypto.encrypt(app.store.exportJson(forSync = true), passphrase)
        (resolver.openOutputStream(uri, "wt") ?: error("Can't write the sync file")).use { it.write(out) }
        settings.set(Settings.SYNC_LAST, System.currentTimeMillis())
        Report(merged, created)
    }.onFailure { settings.set(Settings.SYNC_ERROR, it.message ?: it.javaClass.simpleName) }
        .onSuccess { settings.set(Settings.SYNC_ERROR, null) }

    /** Syncs in the background if automatic sync is on and the last one is a while ago. */
    fun maybeSyncInBackground(minIntervalMs: Long = 30 * 60_000L) {
        if (!settings.syncAuto || !isReady) return
        if (System.currentTimeMillis() - settings.syncLast < minIntervalMs) return
        Thread({ syncNow() }, "sync").apply { isDaemon = true }.start()
    }
}

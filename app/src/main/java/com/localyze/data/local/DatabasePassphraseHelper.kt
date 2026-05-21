package com.localyze.data.local

import android.content.Context
import android.provider.Settings
import androidx.core.content.edit
import androidx.core.util.component1
import androidx.core.util.component2
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory
import java.nio.charset.Charset
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Generates and caches a stable SQLCipher passphrase for this app installation.
 *
 * The passphrase is derived from the device's ANDROID_ID and the app's
 * signing certificate fingerprint, then encrypted with AES-GCM and stored
 * in a dedicated SharedPreferences file. This provides encryption-at-rest
 * for the Room database without requiring user interaction or cloud storage.
 */
object DatabasePassphraseHelper {

    private const val TAG = "DBPassphrase"
    private const val PREFS_NAME = "localyze_db_key"
    private const val KEY_ENCRYPTED_PASSPHRASE = "encrypted_passphrase"
    private const val KEY_IV = "passphrase_iv"
    private const val AES_KEY_SIZE = 32
    private const val GCM_TAG_LENGTH = 128
    private const val GCM_IV_LENGTH = 12

    fun getOrCreatePassphrase(context: Context): ByteArray {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val encrypted = prefs.getString(KEY_ENCRYPTED_PASSPHRASE, null)
        // IV is bundled into the encrypted blob (combined = iv || ciphertext), not stored separately.
        return if (encrypted != null) {
            try {
                decryptPassphrase(encrypted, "", context)
            } catch (e: Exception) {
                // The wrapping key changed between app versions (e.g. ANDROID_ID fallback
                // migrated from a static string to a per-install UUID in version 4).
                // The stored passphrase can no longer be decrypted — the database is
                // inaccessible regardless.  Wipe and regenerate so the app can start
                // instead of crashing in a loop during Hilt initialization.
                android.util.Log.w(TAG, "Passphrase decrypt failed; wiping database and regenerating: ${e.message}")
                wipeAndReset(context, prefs)
            }
        } else {
            generateAndStorePassphrase(context)
        }
    }

    private fun wipeAndReset(context: Context, prefs: android.content.SharedPreferences): ByteArray {
        prefs.edit()
            .remove(KEY_ENCRYPTED_PASSPHRASE)
            .remove(KEY_IV)
            .apply()
        for (suffix in listOf("", "-shm", "-wal", "-journal")) {
            context.getDatabasePath("local_assistant_db$suffix").delete()
        }
        return generateAndStorePassphrase(context)
    }

    private fun generateAndStorePassphrase(context: Context): ByteArray {
        val randomBytes = ByteArray(AES_KEY_SIZE)
        java.security.SecureRandom().nextBytes(randomBytes)

        val wrappingKey = deriveWrappingKey(context)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, wrappingKey)
        val iv = cipher.iv
        val encrypted = cipher.doFinal(randomBytes)

        val combined = iv + encrypted
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit {
            putString(KEY_ENCRYPTED_PASSPHRASE, android.util.Base64.encodeToString(combined, android.util.Base64.NO_WRAP))
        }
        return randomBytes
    }

    private fun decryptPassphrase(encryptedBase64: String, ivBase64: String, context: Context): ByteArray {
        val combined = android.util.Base64.decode(encryptedBase64, android.util.Base64.NO_WRAP)
        val iv = combined.copyOfRange(0, GCM_IV_LENGTH)
        val ciphertext = combined.copyOfRange(GCM_IV_LENGTH, combined.size)

        val wrappingKey = deriveWrappingKey(context)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, wrappingKey, GCMParameterSpec(GCM_TAG_LENGTH, iv))
        return cipher.doFinal(ciphertext)
    }

    private fun getOrCreateFallbackDeviceId(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val existing = prefs.getString("fallback_device_id", null)
        if (existing != null) return existing
        val newId = java.util.UUID.randomUUID().toString()
        prefs.edit().putString("fallback_device_id", newId).apply()
        return newId
    }

    private fun deriveWrappingKey(context: Context): SecretKeySpec {
        val androidId = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
            ?.takeIf { it.isNotBlank() && it != "9774d56d682e549c" }
            ?: getOrCreateFallbackDeviceId(context)
        val packageName = context.packageName
        val signatureHash = kotlin.runCatching {
            val info = context.packageManager.getPackageInfo(packageName, android.content.pm.PackageManager.GET_SIGNING_CERTIFICATES)
            info.signingInfo?.apkContentsSigners?.firstOrNull()?.toByteArray()
                ?.let { MessageDigest.getInstance("SHA-256").digest(it) }
                ?.joinToString("") { "%02x".format(it) }
        }.getOrNull() ?: "fallback-signature"

        val rawKey = MessageDigest.getInstance("SHA-256").run {
            update("$packageName:$androidId:$signatureHash".toByteArray(Charset.forName("UTF-8")))
            digest()
        }
        return SecretKeySpec(rawKey, "AES")
    }
}

fun createEncryptedDatabaseFactory(context: Context): SupportOpenHelperFactory {
    // sqlcipher-android 4.6+ loads its native library via System.loadLibrary
    // on the first SupportOpenHelperFactory call; explicit init not needed.
    System.loadLibrary("sqlcipher")
    val passphrase = DatabasePassphraseHelper.getOrCreatePassphrase(context)
    return SupportOpenHelperFactory(passphrase)
}

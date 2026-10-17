package ch.vitosstudios.tippli.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import ch.vitosstudios.tippli.core.settings.SettingKeys
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * API keys (`apiKey.<providerId>`, SPEC §8), encrypted with an AES-256-GCM key
 * that never leaves the Android Keystore. The ciphertext (IV + data, Base64)
 * lives in the private SharedPreferences file `tippli_secrets`.
 */
class SecretStore(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val cache = HashMap<String, Pair<String, String>>() // key → (ciphertext, plaintext)

    /** The stored API key of a provider, or null. */
    @Synchronized
    fun apiKey(providerId: String): String? {
        val name = SettingKeys.apiKey(providerId)
        val encrypted = prefs.getString(name, null) ?: return null
        cache[name]?.let { (cipherText, plain) -> if (cipherText == encrypted) return plain }
        val plain = try {
            decrypt(encrypted)
        } catch (e: Exception) {
            Log.w(TAG, "Cannot decrypt the API key of $providerId", e)
            null
        } ?: return null
        cache[name] = encrypted to plain
        return plain
    }

    fun hasApiKey(providerId: String): Boolean = !apiKey(providerId).isNullOrBlank()

    /** Stores (or with a blank value removes) the API key of a provider. Returns false on failure. */
    @Synchronized
    fun setApiKey(providerId: String, value: String): Boolean {
        val name = SettingKeys.apiKey(providerId)
        val trimmed = value.trim()
        if (trimmed.isEmpty()) {
            prefs.edit().remove(name).apply()
            cache.remove(name)
            return true
        }
        return try {
            val encrypted = encrypt(trimmed)
            prefs.edit().putString(name, encrypted).apply()
            cache[name] = encrypted to trimmed
            true
        } catch (e: Exception) {
            Log.e(TAG, "Cannot encrypt the API key", e)
            false
        }
    }

    private fun secretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }

    private fun encrypt(plain: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val iv = cipher.iv
        val data = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(byteArrayOf(iv.size.toByte()) + iv + data, Base64.NO_WRAP)
    }

    private fun decrypt(encoded: String): String {
        val bytes = Base64.decode(encoded, Base64.NO_WRAP)
        val ivLength = bytes[0].toInt()
        require(ivLength in 12..16 && bytes.size > 1 + ivLength) { "corrupt secret" }
        val iv = bytes.copyOfRange(1, 1 + ivLength)
        val data = bytes.copyOfRange(1 + ivLength, bytes.size)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, iv))
        return cipher.doFinal(data).toString(Charsets.UTF_8)
    }

    companion object {
        private const val TAG = "TippliSecrets"
        private const val PREFS_NAME = "tippli_secrets"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val KEY_ALIAS = "tippli_api_keys"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
    }
}

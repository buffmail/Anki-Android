/*
 *  Copyright (c) 2026 Jaehwa Chang <buffmail@gmail.com>
 *
 *  This program is free software; you can redistribute it and/or modify it under
 *  the terms of the GNU General Public License as published by the Free Software
 *  Foundation; either version 3 of the License, or (at your option) any later
 *  version.
 *
 *  This program is distributed in the hope that it will be useful, but WITHOUT ANY
 *  WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A
 *  PARTICULAR PURPOSE. See the GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License along with
 *  this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package com.ichi2.anki.wear

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.core.content.edit
import timber.log.Timber
import java.security.GeneralSecurityException
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * The user's Gemini API key, used to look up words added from the watch.
 *
 * It is typed into AnkiDroid's settings, never into the source code or the APK, and it never
 * leaves the phone except in the request to anki-new-card.
 *
 * It is stored encrypted with an AES key kept in the Android Keystore. AnkiDroid allows backups,
 * so the stored value may be copied to a Google Drive backup; without the Keystore key, which
 * never leaves this device, that copy can't be decrypted. After a restore the key reads as
 * missing, and the watch asks for it to be set again.
 */
object GeminiApiKey {
    private const val PREFS_NAME = "watch_word_lookup"
    private const val PREF_ENCRYPTED_KEY = "gemini_api_key"

    private const val KEYSTORE = "AndroidKeyStore"
    private const val KEYSTORE_ALIAS = "ankidroid_gemini_api_key"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val IV_BYTES = 12
    private const val TAG_BITS = 128

    /** Whether a key was saved. Doesn't touch the Keystore, so it's cheap and safe in tests. */
    fun isSet(context: Context): Boolean = prefs(context).contains(PREF_ENCRYPTED_KEY)

    /** The saved key, or `null` if none was saved or it can't be decrypted on this device. */
    fun get(context: Context): String? {
        val stored = prefs(context).getString(PREF_ENCRYPTED_KEY, null) ?: return null
        return try {
            decrypt(stored)
        } catch (e: GeneralSecurityException) {
            Timber.w(e, "Saved Gemini API key can't be decrypted (restored from a backup?): forgetting it")
            clear(context)
            null
        } catch (e: IllegalArgumentException) {
            Timber.w(e, "Saved Gemini API key is corrupt: forgetting it")
            clear(context)
            null
        }
    }

    /** Saves [apiKey], or forgets the saved key if [apiKey] is blank. */
    fun set(
        context: Context,
        apiKey: String,
    ) {
        val trimmed = apiKey.trim()
        if (trimmed.isEmpty()) {
            clear(context)
            return
        }
        prefs(context).edit { putString(PREF_ENCRYPTED_KEY, encrypt(trimmed)) }
        Timber.i("Gemini API key saved")
    }

    fun clear(context: Context) {
        prefs(context).edit { remove(PREF_ENCRYPTED_KEY) }
    }

    private fun prefs(context: Context): SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** Returns the IV followed by the ciphertext, in Base64. */
    private fun encrypt(plainText: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val encrypted = cipher.iv + cipher.doFinal(plainText.encodeToByteArray())
        return Base64.encodeToString(encrypted, Base64.NO_WRAP)
    }

    private fun decrypt(stored: String): String {
        val bytes = Base64.decode(stored, Base64.NO_WRAP)
        require(bytes.size > IV_BYTES) { "too short" }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(TAG_BITS, bytes, 0, IV_BYTES))
        return cipher.doFinal(bytes, IV_BYTES, bytes.size - IV_BYTES).decodeToString()
    }

    private fun secretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (keyStore.getKey(KEYSTORE_ALIAS, null) as? SecretKey)?.let { return it }
        val spec =
            KeyGenParameterSpec
                .Builder(KEYSTORE_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        return KeyGenerator
            .getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
            .apply { init(spec) }
            .generateKey()
    }
}

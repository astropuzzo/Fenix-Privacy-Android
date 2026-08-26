/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.privacyhistory

import android.content.Context
import android.util.Base64
import androidx.preference.PreferenceManager
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec
import org.json.JSONObject

/** Password-encrypted, cross-platform rule bundle. It never contains counters or browsing data. */
object PrivateHistoryBackup {
    private const val HEADER = "FENIX-PRIVACY-2\n"
    private const val ITERATIONS = 210_000
    private const val KEY_BITS = 256
    private const val SALT_BYTES = 16
    private const val IV_BYTES = 12
    private const val TAG_BITS = 128

    fun exportEncrypted(context: Context, passphrase: CharArray): String {
        require(passphrase.size >= 8) { "Passphrase must contain at least 8 characters" }
        return try {
            val prefs = PreferenceManager.getDefaultSharedPreferences(context.applicationContext)
            val payload = JSONObject().apply {
                put("format", "fenix-privacy-rules")
                put("version", 2)
                put("createdAt", System.currentTimeMillis())
                put("visualRules", prefs.getString(PrivateHistoryRules.KEY_VISUAL_RULES, "[]"))
                put("activeProfiles", prefs.getString(PrivateHistoryRules.KEY_ACTIVE_PROFILES, "Default"))
                put("domains", prefs.getString(PrivateHistoryRules.KEY_DOMAINS, ""))
                put("keywords", prefs.getString(PrivateHistoryRules.KEY_KEYWORDS, ""))
                put("regex", prefs.getString(PrivateHistoryRules.KEY_REGEX, ""))
                put("matchUrl", prefs.getBoolean(PrivateHistoryRules.KEY_MATCH_URL, true))
                put("matchTitle", prefs.getBoolean(PrivateHistoryRules.KEY_MATCH_TITLE, true))
                put("decodeUrl", prefs.getBoolean(PrivateHistoryRules.KEY_DECODE_URL, true))
                put("caseSensitive", prefs.getBoolean(PrivateHistoryRules.KEY_CASE_SENSITIVE, false))
                put("wholeWords", prefs.getBoolean(PrivateHistoryRules.KEY_WHOLE_WORDS, false))
            }.toString().toByteArray(Charsets.UTF_8)

            val random = SecureRandom()
            val salt = ByteArray(SALT_BYTES).also(random::nextBytes)
            val iv = ByteArray(IV_BYTES).also(random::nextBytes)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, deriveKey(passphrase, salt), GCMParameterSpec(TAG_BITS, iv))
            cipher.updateAAD(HEADER.toByteArray(Charsets.UTF_8))
            val encrypted = cipher.doFinal(payload)
            HEADER + Base64.encodeToString(salt + iv + encrypted, Base64.NO_WRAP)
        } finally {
            passphrase.fill('\u0000')
        }
    }

    fun importEncrypted(context: Context, bundle: String, passphrase: CharArray): Int {
        return try {
            require(bundle.startsWith(HEADER)) { "Unsupported or unencrypted bundle" }
            val bytes = Base64.decode(bundle.removePrefix(HEADER).trim(), Base64.DEFAULT)
            require(bytes.size > SALT_BYTES + IV_BYTES) { "Corrupt bundle" }
            val salt = bytes.copyOfRange(0, SALT_BYTES)
            val iv = bytes.copyOfRange(SALT_BYTES, SALT_BYTES + IV_BYTES)
            val encrypted = bytes.copyOfRange(SALT_BYTES + IV_BYTES, bytes.size)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, deriveKey(passphrase, salt), GCMParameterSpec(TAG_BITS, iv))
            cipher.updateAAD(HEADER.toByteArray(Charsets.UTF_8))
            val json = JSONObject(String(cipher.doFinal(encrypted), Charsets.UTF_8))
            require(json.optString("format") == "fenix-privacy-rules") { "Wrong bundle type" }

            val visualRules = json.optString("visualRules", "[]")
            val parsedRules = PrivateHistoryRule.decode(visualRules)
            val prefs = PreferenceManager.getDefaultSharedPreferences(context.applicationContext)
            prefs.edit()
                .putString(PrivateHistoryRules.KEY_VISUAL_RULES, PrivateHistoryRule.encode(parsedRules))
                .putString(PrivateHistoryRules.KEY_ACTIVE_PROFILES, json.optString("activeProfiles", "Default"))
                .putString(PrivateHistoryRules.KEY_DOMAINS, json.optString("domains"))
                .putString(PrivateHistoryRules.KEY_KEYWORDS, json.optString("keywords"))
                .putString(PrivateHistoryRules.KEY_REGEX, json.optString("regex"))
                .putBoolean(PrivateHistoryRules.KEY_MATCH_URL, json.optBoolean("matchUrl", true))
                .putBoolean(PrivateHistoryRules.KEY_MATCH_TITLE, json.optBoolean("matchTitle", true))
                .putBoolean(PrivateHistoryRules.KEY_DECODE_URL, json.optBoolean("decodeUrl", true))
                .putBoolean(PrivateHistoryRules.KEY_CASE_SENSITIVE, json.optBoolean("caseSensitive", false))
                .putBoolean(PrivateHistoryRules.KEY_WHOLE_WORDS, json.optBoolean("wholeWords", false))
                .apply()
            parsedRules.size
        } finally {
            passphrase.fill('\u0000')
        }
    }

    private fun deriveKey(passphrase: CharArray, salt: ByteArray): SecretKeySpec {
        val spec = PBEKeySpec(passphrase, salt, ITERATIONS, KEY_BITS)
        return try {
            val bytes = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
            SecretKeySpec(bytes, "AES")
        } finally {
            spec.clearPassword()
        }
    }
}

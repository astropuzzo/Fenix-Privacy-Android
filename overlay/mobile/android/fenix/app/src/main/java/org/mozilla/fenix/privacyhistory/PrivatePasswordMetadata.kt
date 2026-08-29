/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.privacyhistory

import android.util.Base64
import java.net.IDN
import java.net.URI
import java.util.Locale
import mozilla.components.concept.storage.Login
import mozilla.components.concept.storage.LoginEntry

/**
 * Stores the private-tier bit inside the synced login record instead of in a separate site list.
 *
 * Firefox Sync encrypts the complete password payload. usernameField is not displayed by the
 * password manager and Application Services documents it as non-restrictive for login use. The
 * original field name is preserved inside the marker and restored before a login reaches Gecko.
 */
object PrivatePasswordMetadata {
    private const val PREFIX = "__fenix_privacy_private_v1__:"

    fun isProtected(login: Login): Boolean = login.usernameField.startsWith(PREFIX)

    fun canProtect(login: Login): Boolean = login.formActionOrigin != null

    /**
     * Matches the exact web origin when Gecko supplies one, and never widens a host to sibling
     * subdomains. Older Gecko callbacks supply only a host; in that case host equality is the
     * strongest origin binding available.
     */
    fun matchesOrigin(login: Login, requestedOrigin: String): Boolean {
        val requested = originKey(requestedOrigin) ?: return false
        val stored = originKey(login.origin) ?: return false
        if (requested.host != stored.host) return false
        if (requested.scheme != null && requested.scheme != stored.scheme) return false
        return requested.port == null || requested.port == stored.port
    }

    fun protect(login: Login): LoginEntry {
        require(canProtect(login)) { "HTTP-auth logins cannot carry private-tier metadata" }
        return login.toEntry().copy(usernameField = encode(originalUsernameField(login.usernameField)))
    }

    fun unprotect(login: Login): LoginEntry =
        login.toEntry().copy(usernameField = originalUsernameField(login.usernameField))

    /** Preserve a synced private marker when Gecko updates the username or password on a page. */
    fun preserveProtection(existing: Login?, incoming: LoginEntry): LoginEntry {
        if (existing == null || !isProtected(existing)) return incoming
        val original = originalUsernameField(incoming.usernameField)
            .ifBlank { originalUsernameField(existing.usernameField) }
        return incoming.copy(usernameField = encode(original))
    }

    /** Remove internal metadata before handing a credential to Gecko or Android Autofill. */
    fun forUse(login: Login): Login =
        if (isProtected(login)) login.copy(usernameField = originalUsernameField(login.usernameField)) else login

    internal fun originalUsernameField(value: String): String = when {
        !value.startsWith(PREFIX) -> value
        else -> decode(value).orEmpty()
    }

    private fun encode(value: String): String {
        val payload = Base64.encodeToString(
            value.toByteArray(Charsets.UTF_8),
            Base64.NO_WRAP or Base64.NO_PADDING or Base64.URL_SAFE,
        )
        return PREFIX + payload
    }

    private fun decode(value: String): String? {
        if (!value.startsWith(PREFIX)) return null
        return runCatching {
            val payload = value.removePrefix(PREFIX)
            String(
                Base64.decode(payload, Base64.NO_WRAP or Base64.NO_PADDING or Base64.URL_SAFE),
                Charsets.UTF_8,
            )
        }.getOrNull()
    }

    private fun originKey(value: String): OriginKey? = runCatching {
        val trimmed = value.trim()
        val hasScheme = trimmed.contains("://")
        val parsed = URI(if (hasScheme) trimmed else "https://$trimmed")
        val scheme = parsed.scheme?.lowercase(Locale.ROOT)
        val host = IDN.toASCII(parsed.host.orEmpty().trim('.'), IDN.USE_STD3_ASCII_RULES)
            .lowercase(Locale.ROOT)
            .takeIf(String::isNotBlank)
            ?: return@runCatching null
        val normalizedPort = when {
            parsed.port >= 0 -> parsed.port
            hasScheme && scheme == "https" -> 443
            hasScheme && scheme == "http" -> 80
            else -> null
        }
        OriginKey(
            scheme = scheme.takeIf { hasScheme },
            host = host,
            port = normalizedPort,
        )
    }.getOrNull()

    private data class OriginKey(
        val scheme: String?,
        val host: String,
        val port: Int?,
    )
}

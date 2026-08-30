/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.privacyhistory

import java.net.IDN
import java.net.URI
import java.util.Locale
import mozilla.components.concept.storage.Login

/**
 * Creates the transient login action offered to Gecko before a fresh authentication.
 *
 * The fixed username and password only make the action eligible for Gecko's username/password
 * autocomplete filters. They are replaced with neutral UI text by Fenix and are never persisted
 * or synchronized. Candidate origins come from the page already open in Firefox, never from saved
 * logins, so no saved origin, username, password or count is read before authentication.
 */
object PrivatePasswordAccess {
    private const val LOCKED_GUID_PREFIX = "fenix-privacy-login-gate:"
    private const val NEUTRAL_USERNAME = "Passwords"
    private const val NEUTRAL_PASSWORD = "locked"

    fun lockedLogins(domain: String, pageUrls: List<String>): List<Login> {
        val domainHost = normalizeWebOrigin("https://$domain")
            ?.let { URI(it).host }
            ?: return emptyList()
        val openOrigins = pageUrls.mapNotNull(::normalizeWebOrigin).filter { origin ->
            val host = URI(origin).host
            host == domainHost || host.endsWith(".$domainHost")
        }
        val candidateOrigins = (
            openOrigins +
                listOfNotNull(
                    normalizeWebOrigin("https://$domainHost"),
                    normalizeWebOrigin("http://$domainHost"),
                )
        ).distinct()

        return candidateOrigins.map { origin ->
            Login(
                guid = LOCKED_GUID_PREFIX + domainHost,
                username = NEUTRAL_USERNAME,
                password = NEUTRAL_PASSWORD,
                origin = origin,
                formActionOrigin = null,
            )
        }
    }

    fun normalizeWebOrigin(value: String): String? = runCatching {
        val uri = URI(value.trim())
        val scheme = uri.scheme?.lowercase(Locale.ROOT)
            ?.takeIf { it == "https" || it == "http" }
            ?: return@runCatching null
        val rawHost = uri.host?.trimEnd('.')?.takeIf(String::isNotBlank)
            ?: return@runCatching null
        val host = if (rawHost.contains(':')) {
            "[${rawHost.lowercase(Locale.ROOT)}]"
        } else {
            IDN.toASCII(rawHost, IDN.USE_STD3_ASCII_RULES).lowercase(Locale.ROOT)
        }
        val defaultPort = if (scheme == "https") 443 else 80
        val port = uri.port.takeIf { explicit -> explicit >= 0 && explicit != defaultPort }
        "$scheme://$host${port?.let { ":$it" }.orEmpty()}"
    }.getOrNull()

    fun isLockedLogin(login: Login): Boolean = login.guid.startsWith(LOCKED_GUID_PREFIX)

    fun lockedLookupDomain(login: Login): String? = login.guid
        .takeIf { isLockedLogin(login) }
        ?.removePrefix(LOCKED_GUID_PREFIX)
        ?.takeIf(String::isNotBlank)

    fun isProtected(login: Login, legacyRules: PrivateHistoryRules): Boolean =
        PrivatePasswordMetadata.isProtected(login) || legacyRules.shouldProtectLogin(login.origin)
}

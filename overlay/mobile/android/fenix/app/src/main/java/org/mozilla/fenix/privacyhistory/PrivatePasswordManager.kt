/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.privacyhistory

import mozilla.components.concept.storage.Login
import mozilla.components.concept.storage.LoginEntry
import mozilla.components.concept.storage.LoginsStorage

/** Origin-safe operations for the biometric password manager. */
class PrivatePasswordManager(
    private val storage: LoginsStorage,
    private val legacyRules: PrivateHistoryRules,
) {
    suspend fun listForManagement(): List<Login> {
        migrateLegacyRules()
        return storage.list().sortedWith(
            compareBy(String.CASE_INSENSITIVE_ORDER) { login: Login -> login.origin },
        )
    }

    suspend fun setProtected(login: Login, protected: Boolean): Login = storage.update(
        login.guid,
        if (protected) PrivatePasswordMetadata.protect(login) else PrivatePasswordMetadata.unprotect(login),
    )

    suspend fun update(
        login: Login,
        origin: String,
        username: String,
        password: String,
    ): Login {
        val normalizedOrigin = origin.trim()
        require(normalizedOrigin.startsWith("https://") || normalizedOrigin.startsWith("http://")) {
            "A web origin is required"
        }
        require(password.isNotEmpty()) { "A password is required" }

        val formActionOrigin = login.formActionOrigin?.let { current ->
            if (current == login.origin) normalizedOrigin else current
        }
        val entry = LoginEntry(
            origin = normalizedOrigin,
            formActionOrigin = formActionOrigin,
            httpRealm = login.httpRealm,
            usernameField = PrivatePasswordMetadata.originalUsernameField(login.usernameField),
            passwordField = login.passwordField,
            username = username,
            password = password,
        )
        return storage.update(
            login.guid,
            if (PrivatePasswordMetadata.isProtected(login)) {
                entry.copy(usernameField = PrivatePasswordMetadata.protect(login).usernameField)
            } else {
                entry
            },
        )
    }

    suspend fun delete(login: Login): Boolean = storage.delete(login.guid)

    suspend fun forOrigin(origin: String, protected: Boolean): List<Login> =
        storage.getByBaseDomain(origin)
            .filter { PrivatePasswordMetadata.matchesOrigin(it, origin) }
            .filter { PrivatePasswordMetadata.isProtected(it) == protected }
            .map(PrivatePasswordMetadata::forUse)

    /** Converts the preview release's history-rule flags into per-login synced metadata once. */
    private suspend fun migrateLegacyRules() {
        val legacy = legacyRules.visualRules().filter { it.protectLogin }
        if (legacy.isEmpty()) return

        val matching = storage.list().filter { legacyRules.shouldProtectLogin(it.origin) }
        if (matching.isEmpty()) return

        var unsupported = false
        matching.filterNot(PrivatePasswordMetadata::isProtected).forEach { login ->
            if (!PrivatePasswordMetadata.canProtect(login)) {
                unsupported = true
            } else {
                storage.update(login.guid, PrivatePasswordMetadata.protect(login))
            }
        }
        if (!unsupported) {
            legacyRules.saveVisualRules(
                legacyRules.visualRules().map { rule ->
                    if (rule.protectLogin) rule.copy(protectLogin = false) else rule
                },
            )
        }
    }
}

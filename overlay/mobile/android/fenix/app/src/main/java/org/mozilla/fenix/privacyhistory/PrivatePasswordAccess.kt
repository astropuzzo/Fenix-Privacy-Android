/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.privacyhistory

import mozilla.components.concept.storage.Login

/**
 * Creates the transient, metadata-free login offered to Gecko before a fresh authentication.
 *
 * The real origin is kept only in the in-memory form-action field so the authenticated callback
 * can perform an origin-bound lookup. No username, password, saved-login count or real origin is
 * rendered by the prompt, persisted, logged or synchronized through this object.
 */
object PrivatePasswordAccess {
    private const val LOCKED_GUID = "fenix-privacy-login-gate"
    private const val NEUTRAL_ORIGIN = "https://passwords.invalid"

    fun lockedLogin(domain: String): Login = Login(
        guid = LOCKED_GUID,
        username = "",
        password = "",
        origin = NEUTRAL_ORIGIN,
        formActionOrigin = domain,
    )

    fun isLockedLogin(login: Login): Boolean = login.guid == LOCKED_GUID
}

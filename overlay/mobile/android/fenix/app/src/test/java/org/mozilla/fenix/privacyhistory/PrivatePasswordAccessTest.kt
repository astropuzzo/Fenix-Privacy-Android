/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.privacyhistory

import mozilla.components.concept.storage.Login
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PrivatePasswordAccessTest {
    @Test
    fun `locked action exposes no credential metadata`() {
        val locked = PrivatePasswordAccess.lockedLogins(
            "accounts.example",
            listOf(
                "https://unrelated.example/path",
                "https://login.accounts.example:8443/path?ignored=true",
            ),
        )

        assertEquals(
            listOf(
                "https://login.accounts.example:8443",
                "https://accounts.example",
                "http://accounts.example",
            ),
            locked.map(Login::origin),
        )
        assertTrue(locked.all { it.username == "Passwords" })
        assertTrue(locked.all { it.password == "locked" })
        assertTrue(locked.all { it.formActionOrigin == null })
        assertTrue(locked.all(PrivatePasswordAccess::isLockedLogin))
        assertTrue(locked.all { PrivatePasswordAccess.lockedLookupDomain(it) == "accounts.example" })
    }

    @Test
    fun `web origin normalization strips paths and default ports`() {
        assertEquals(
            "https://accounts.example",
            PrivatePasswordAccess.normalizeWebOrigin("HTTPS://Accounts.Example:443/login"),
        )
        assertEquals(null, PrivatePasswordAccess.normalizeWebOrigin("file:///tmp/login"))
    }

    @Test
    fun `ordinary login is never mistaken for the locked action`() {
        val login = Login(
            guid = "ordinary-guid",
            username = "person",
            password = "secret",
            origin = "https://accounts.example",
        )

        assertFalse(PrivatePasswordAccess.isLockedLogin(login))
    }
}

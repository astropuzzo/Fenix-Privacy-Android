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
        val locked = PrivatePasswordAccess.lockedLogin("accounts.example")

        assertEquals("https://passwords.invalid", locked.origin)
        assertEquals("", locked.username)
        assertEquals("", locked.password)
        assertEquals("accounts.example", locked.formActionOrigin)
        assertTrue(PrivatePasswordAccess.isLockedLogin(locked))
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

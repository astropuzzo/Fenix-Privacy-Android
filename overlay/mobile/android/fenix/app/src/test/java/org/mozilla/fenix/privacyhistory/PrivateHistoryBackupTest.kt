/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.privacyhistory

import androidx.preference.PreferenceManager
import mozilla.components.support.test.robolectric.testContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class PrivateHistoryBackupTest {
    private val prefs get() = PreferenceManager.getDefaultSharedPreferences(testContext)

    @Before
    fun setUp() {
        prefs.edit().clear().commit()
    }

    @After
    fun tearDown() {
        prefs.edit().clear().commit()
    }

    @Test
    fun `encrypted bundle round trips rules without exposing them`() {
        val rule = PrivateHistoryRule(
            name = "Secret rule",
            matcher = PrivateHistoryRule.Matcher.DOMAIN,
            value = "secret.example",
        )
        prefs.edit()
            .putString(PrivateHistoryRules.KEY_VISUAL_RULES, PrivateHistoryRule.encode(listOf(rule)))
            .putString(PrivateHistoryRules.KEY_ACTIVE_PROFILES, "Personal")
            .commit()

        val bundle = PrivateHistoryBackup.exportEncrypted(testContext, "correct horse".toCharArray())
        assertTrue(bundle.startsWith("FENIX-PRIVACY-2\n"))
        assertFalse(bundle.contains("secret.example"))
        prefs.edit().clear().commit()

        val count = PrivateHistoryBackup.importEncrypted(testContext, bundle, "correct horse".toCharArray())

        assertEquals(1, count)
        assertEquals("secret.example", PrivateHistoryRules(testContext).visualRules().single().value)
        assertEquals(setOf("Personal"), PrivateHistoryRules(testContext).activeProfiles())
    }

    @Test(expected = Exception::class)
    fun `wrong passphrase cannot import`() {
        val bundle = PrivateHistoryBackup.exportEncrypted(testContext, "correct horse".toCharArray())
        PrivateHistoryBackup.importEncrypted(testContext, bundle, "wrong password".toCharArray())
    }
}

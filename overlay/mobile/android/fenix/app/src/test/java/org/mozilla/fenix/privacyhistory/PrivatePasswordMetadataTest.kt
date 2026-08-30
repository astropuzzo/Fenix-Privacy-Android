/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.privacyhistory

import androidx.preference.PreferenceManager
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import mozilla.components.concept.storage.Login
import mozilla.components.concept.storage.LoginsStorage
import mozilla.components.support.test.robolectric.testContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class PrivatePasswordMetadataTest {
    @Before
    fun setUp() {
        PreferenceManager.getDefaultSharedPreferences(testContext).edit().clear().commit()
    }

    @After
    fun tearDown() {
        PreferenceManager.getDefaultSharedPreferences(testContext).edit().clear().commit()
    }

    @Test
    fun `private marker round trips without changing credential data`() {
        val login = login(usernameField = "account-name")

        val markedEntry = PrivatePasswordMetadata.protect(login)
        val marked = login.copy(usernameField = markedEntry.usernameField)

        assertNotEquals("account-name", marked.usernameField)
        assertTrue(PrivatePasswordMetadata.isProtected(marked))
        assertEquals(login.origin, marked.origin)
        assertEquals(login.username, marked.username)
        assertEquals(login.password, marked.password)
        assertEquals("account-name", PrivatePasswordMetadata.forUse(marked).usernameField)
        assertFalse(PrivatePasswordMetadata.isProtected(PrivatePasswordMetadata.forUse(marked)))
    }

    @Test
    fun `malformed private metadata fails closed`() {
        val malformed = login(usernameField = "__fenix_privacy_private_v1__:%%%")

        assertTrue(PrivatePasswordMetadata.isProtected(malformed))
        assertEquals("", PrivatePasswordMetadata.forUse(malformed).usernameField)
    }

    @Test
    fun `page password update preserves a synced private marker`() {
        val marked = login().let {
            it.copy(usernameField = PrivatePasswordMetadata.protect(it).usernameField)
        }
        val incoming = marked.toEntry().copy(usernameField = "new-field", password = "new-secret")

        val preserved = PrivatePasswordMetadata.preserveProtection(marked, incoming)
        val updated = marked.copy(usernameField = preserved.usernameField, password = preserved.password)

        assertTrue(PrivatePasswordMetadata.isProtected(updated))
        assertEquals("new-field", PrivatePasswordMetadata.forUse(updated).usernameField)
        assertEquals("new-secret", updated.password)
    }

    @Test
    fun `origin matching never widens to sibling subdomains or another scheme`() {
        val stored = login()

        assertTrue(PrivatePasswordMetadata.matchesOrigin(stored, "example.com"))
        assertTrue(PrivatePasswordMetadata.matchesOrigin(stored, "https://example.com"))
        assertFalse(PrivatePasswordMetadata.matchesOrigin(stored, "https://sub.example.com"))
        assertFalse(PrivatePasswordMetadata.matchesOrigin(stored, "http://example.com"))
    }

    @Test
    fun `origin lookup returns only the requested tier and strips metadata`() = runTest {
        val standard = login(guid = "standard")
        val privateBase = login(guid = "private")
        val private = privateBase.copy(
            usernameField = PrivatePasswordMetadata.protect(privateBase).usernameField,
        )
        val storage = mockk<LoginsStorage>()
        val sibling = login(guid = "sibling").copy(origin = "https://sub.example.com")
        coEvery { storage.getByBaseDomain(any()) } returns listOf(standard, sibling, private)
        val manager = PrivatePasswordManager(storage, PrivateHistoryRules(testContext))

        assertEquals(listOf(standard), manager.forOrigin("example.com", protected = false))
        val privateResults = manager.forOrigin("example.com", protected = true)
        assertEquals(listOf("private"), privateResults.map(Login::guid))
        assertFalse(PrivatePasswordMetadata.isProtected(privateResults.single()))
    }

    @Test
    fun `legacy rule migrates into the password record and clears only after success`() = runTest {
        val rules = PrivateHistoryRules(testContext)
        rules.saveVisualRules(
            listOf(
                PrivateHistoryRule(
                    id = "legacy",
                    name = "Legacy",
                    matcher = PrivateHistoryRule.Matcher.DOMAIN,
                    value = "example.com",
                    protectLogin = true,
                ),
            ),
        )
        val source = login()
        val migrated = source.copy(
            usernameField = PrivatePasswordMetadata.protect(source).usernameField,
        )
        val storage = mockk<LoginsStorage>()
        coEvery { storage.list() } returnsMany listOf(listOf(source), listOf(migrated))
        coEvery { storage.update(source.guid, any()) } returns migrated

        val result = PrivatePasswordManager(storage, rules).listForManagement()

        assertTrue(PrivatePasswordMetadata.isProtected(result.single()))
        assertFalse(rules.visualRules().single().protectLogin)
        coVerify(exactly = 1) { storage.update(source.guid, any()) }
    }

    private fun login(
        guid: String = "guid",
        usernameField: String = "username-field",
    ) = Login(
        guid = guid,
        username = "person",
        password = "secret",
        origin = "https://example.com",
        formActionOrigin = "https://example.com",
        usernameField = usernameField,
        passwordField = "password-field",
    )
}

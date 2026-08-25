/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.privacyhistory

import androidx.preference.PreferenceManager
import mozilla.components.support.test.robolectric.testContext
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class PrivateHistoryRulesTest {
    private val prefs
        get() = PreferenceManager.getDefaultSharedPreferences(testContext)

    @Before
    fun setUp() {
        prefs.edit().clear().commit()
    }

    @After
    fun tearDown() {
        prefs.edit().clear().commit()
    }

    @Test
    fun `domain rules accept domains wildcard domains and pasted URLs`() {
        putString(
            PrivateHistoryRules.KEY_DOMAINS,
            """
            https://*.Example.COM/private/path
            .example.net
            # ignored comment
            """.trimIndent(),
        )
        val rules = PrivateHistoryRules(testContext)

        assertTrue(rules.shouldBlockUri("https://example.com/page"))
        assertTrue(rules.shouldBlockUri("https://deep.sub.example.com/page"))
        assertTrue(rules.shouldBlockUri("https://sub.example.net/page"))
        assertFalse(rules.shouldBlockUri("https://notexample.com/page"))
    }

    @Test
    fun `keywords match plus encoded and repeatedly encoded search queries`() {
        putString(PrivateHistoryRules.KEY_KEYWORDS, "very secret")
        val rules = PrivateHistoryRules(testContext)

        assertTrue(rules.shouldBlockUri("https://search.test/?q=very+secret"))
        assertTrue(rules.shouldBlockUri("https://search.test/?q=very%20secret"))
        assertTrue(rules.shouldBlockUri("https://search.test/?q=very%2520secret"))
        assertFalse(rules.shouldBlockUri("https://search.test/?q=public"))
    }

    @Test
    fun `search terms are protected even when URL matching is disabled`() {
        putString(PrivateHistoryRules.KEY_KEYWORDS, "blocked phrase")
        putBoolean(PrivateHistoryRules.KEY_MATCH_URL, false)
        val rules = PrivateHistoryRules(testContext)

        assertFalse(rules.shouldBlockUri("https://search.test/?q=blocked+phrase"))
        assertTrue(rules.shouldBlockSearchTerm("blocked phrase"))
    }

    @Test
    fun `whole word and case-sensitive switches are enforced`() {
        putString(PrivateHistoryRules.KEY_KEYWORDS, "Secret")
        putBoolean(PrivateHistoryRules.KEY_WHOLE_WORDS, true)
        putBoolean(PrivateHistoryRules.KEY_CASE_SENSITIVE, true)
        val rules = PrivateHistoryRules(testContext)

        assertTrue(rules.shouldBlockTitle("A Secret page"))
        assertFalse(rules.shouldBlockTitle("A secret page"))
        assertFalse(rules.shouldBlockTitle("Secretariat"))
    }

    @Test
    fun `regex lines preserve quantifier commas and semicolons while invalid rules are ignored`() {
        putString(
            PrivateHistoryRules.KEY_REGEX,
            """
            foo{1,3}
            alpha;beta
            (
            """.trimIndent(),
        )
        val rules = PrivateHistoryRules(testContext)

        assertTrue(rules.shouldBlockUri("https://test.invalid/fooo"))
        assertTrue(rules.shouldBlockTitle("alpha;beta"))
        assertFalse(rules.shouldBlockUri("https://test.invalid/clean"))
    }

    @Test
    fun `disabled protection never blocks`() {
        putString(PrivateHistoryRules.KEY_DOMAINS, "example.com")
        putString(PrivateHistoryRules.KEY_KEYWORDS, "secret")
        putBoolean(PrivateHistoryRules.KEY_ENABLED, false)
        val rules = PrivateHistoryRules(testContext)

        assertFalse(rules.shouldBlockVisit("https://example.com/secret", "secret", "secret"))
    }

    private fun putString(key: String, value: String) {
        prefs.edit().putString(key, value).commit()
    }

    private fun putBoolean(key: String, value: Boolean) {
        prefs.edit().putBoolean(key, value).commit()
    }
}

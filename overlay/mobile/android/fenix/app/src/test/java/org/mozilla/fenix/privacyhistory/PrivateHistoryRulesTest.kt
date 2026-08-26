/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.privacyhistory

import androidx.preference.PreferenceManager
import mozilla.components.support.test.robolectric.testContext
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
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
        PrivateHistoryRules(testContext).clearTemporaryMode()
    }

    @After
    fun tearDown() {
        PrivateHistoryRules(testContext).clearTemporaryMode()
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
    fun `whole word mode also enforces phrase boundaries`() {
        putString(PrivateHistoryRules.KEY_KEYWORDS, "very secret")
        putBoolean(PrivateHistoryRules.KEY_WHOLE_WORDS, true)
        val rules = PrivateHistoryRules(testContext)

        assertTrue(rules.shouldBlockTitle("A very secret page"))
        assertFalse(rules.shouldBlockTitle("A very secretary page"))
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

    @Test
    fun `allowlist exact page wins over a blocked legacy domain`() {
        putString(PrivateHistoryRules.KEY_DOMAINS, "example.com")
        putVisualRules(
            PrivateHistoryRule(
                name = "Allowed homepage",
                matcher = PrivateHistoryRule.Matcher.EXACT_URL,
                value = "https://www.example.com/",
                action = PrivateHistoryRule.Action.ALLOW,
            ),
        )
        val rules = PrivateHistoryRules(testContext)

        assertFalse(rules.shouldBlockUri("https://www.example.com/"))
        assertTrue(rules.shouldBlockUri("https://www.example.com/private"))
    }

    @Test
    fun `domain except root preserves only a clean homepage`() {
        putVisualRules(
            PrivateHistoryRule(
                name = "Keep root",
                matcher = PrivateHistoryRule.Matcher.DOMAIN_EXCEPT_ROOT,
                value = "sitoacaso.it",
                action = PrivateHistoryRule.Action.BLOCK,
            ),
        )
        val rules = PrivateHistoryRules(testContext)

        assertFalse(rules.shouldBlockUri("https://www.sitoacaso.it/"))
        assertTrue(rules.shouldBlockUri("https://www.sitoacaso.it/threads"))
        assertTrue(rules.shouldBlockUri("https://www.sitoacaso.it/?q=private"))
    }

    @Test
    fun `collapse rule returns canonical site root`() {
        putVisualRules(
            PrivateHistoryRule(
                name = "Collapse",
                matcher = PrivateHistoryRule.Matcher.DOMAIN_EXCEPT_ROOT,
                value = "example.com",
                action = PrivateHistoryRule.Action.COLLAPSE_TO_ROOT,
            ),
        )

        val decision = PrivateHistoryRules(testContext).decide("https://sub.example.com/path?q=private")

        assertEquals(PrivateHistoryRule.Action.COLLAPSE_TO_ROOT, decision.action)
        assertEquals("https://sub.example.com/", decision.collapsedUri)
    }

    @Test
    fun `inactive profiles and expired rules do not match`() {
        putString(PrivateHistoryRules.KEY_ACTIVE_PROFILES, "Work")
        putVisualRules(
            PrivateHistoryRule(
                name = "Personal",
                profile = "Personal",
                matcher = PrivateHistoryRule.Matcher.DOMAIN,
                value = "personal.example",
            ),
            PrivateHistoryRule(
                name = "Expired",
                profile = "Work",
                matcher = PrivateHistoryRule.Matcher.DOMAIN,
                value = "expired.example",
                expiresAtEpochMillis = 999L,
            ),
        )
        val rules = PrivateHistoryRules(testContext) { 1_000L }

        assertFalse(rules.shouldBlockUri("https://personal.example/page"))
        assertFalse(rules.shouldBlockUri("https://expired.example/page"))
    }

    @Test
    fun `query parameter rules inspect decoded values`() {
        putVisualRules(
            PrivateHistoryRule(
                name = "Private searches",
                matcher = PrivateHistoryRule.Matcher.QUERY_PARAMETER,
                queryParameter = "q",
                value = "very secret",
            ),
        )
        val rules = PrivateHistoryRules(testContext)

        assertTrue(rules.shouldBlockUri("https://search.example/?q=very%20secret"))
        assertFalse(rules.shouldBlockUri("https://search.example/?page=very%20secret"))
    }

    @Test
    fun `temporary mode blocks globally without persisting a URL`() {
        val rules = PrivateHistoryRules(testContext) { 10_000L }
        rules.setTemporaryMode(60_000L)

        assertTrue(rules.shouldBlockUri("https://mozilla.org/"))
        assertEquals(70_000L, rules.temporaryProtectionUntil())
        rules.clearTemporaryMode()
        assertFalse(rules.shouldBlockUri("https://mozilla.org/"))
    }

    private fun putString(key: String, value: String) {
        prefs.edit().putString(key, value).commit()
    }

    private fun putBoolean(key: String, value: Boolean) {
        prefs.edit().putBoolean(key, value).commit()
    }

    private fun putVisualRules(vararg rules: PrivateHistoryRule) {
        putString(PrivateHistoryRules.KEY_VISUAL_RULES, PrivateHistoryRule.encode(rules.toList()))
    }
}

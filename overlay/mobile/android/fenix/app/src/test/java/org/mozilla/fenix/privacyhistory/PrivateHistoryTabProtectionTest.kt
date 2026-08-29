/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.privacyhistory

import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class PrivateHistoryTabProtectionTest {
    @Before
    fun setUp() = PrivateHistoryTabProtection.clearForTests()

    @After
    fun tearDown() = PrivateHistoryTabProtection.clearForTests()

    @Test
    fun `protected tabs pass protection to child tabs only when inheritance is enabled`() {
        PrivateHistoryTabProtection.onNavigation("parent", null, "https://example.com/parent")
        assertTrue(PrivateHistoryTabProtection.toggle("parent", inherit = true))
        PrivateHistoryTabProtection.onNavigation("child", "parent", "https://example.com/child")

        assertTrue(PrivateHistoryTabProtection.isTabProtected("parent"))
        assertTrue(PrivateHistoryTabProtection.isTabProtected("child"))
        assertTrue(PrivateHistoryTabProtection.isProtectedUri("https://example.com/child"))

        assertFalse(PrivateHistoryTabProtection.toggle("parent"))
        PrivateHistoryTabProtection.onNavigation("other", "parent", "https://example.com/other")
        assertFalse(PrivateHistoryTabProtection.isTabProtected("other"))
    }

    @Test
    fun `one-shot protection covers exactly the next distinct navigation`() {
        val current = "https://example.com/start"
        val next = "https://example.com/private"
        PrivateHistoryTabProtection.onNavigation("tab", null, current)
        PrivateHistoryTabProtection.armNext("tab", current)
        PrivateHistoryTabProtection.onNavigation("tab", null, current)

        assertTrue(PrivateHistoryTabProtection.isNextArmed("tab"))
        assertFalse(PrivateHistoryTabProtection.isProtectedUri(current))

        PrivateHistoryTabProtection.onNavigation("tab", null, next)
        assertTrue(PrivateHistoryTabProtection.isProtectedUri(next))

        PrivateHistoryTabProtection.onNavigation("tab", null, "https://example.com/after")
        assertFalse(PrivateHistoryTabProtection.isProtectedUri(next))
        assertFalse(PrivateHistoryTabProtection.isNextArmed("tab"))
    }

    @Test
    fun `closing tabs prunes all process-only state`() {
        PrivateHistoryTabProtection.onNavigation("tab", null, "https://example.com/private")
        PrivateHistoryTabProtection.toggle("tab")
        PrivateHistoryTabProtection.prune(emptySet())

        assertFalse(PrivateHistoryTabProtection.isTabProtected("tab"))
        assertFalse(PrivateHistoryTabProtection.isProtectedUri("https://example.com/private"))
    }
}

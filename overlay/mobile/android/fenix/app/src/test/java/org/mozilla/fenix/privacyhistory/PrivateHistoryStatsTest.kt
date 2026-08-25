/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.privacyhistory

import android.content.Context
import mozilla.components.support.test.robolectric.testContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class PrivateHistoryStatsTest {
    private var now = 1_000L
    private lateinit var stats: PrivateHistoryStats

    @Before
    fun setUp() {
        stats = PrivateHistoryStats(testContext) { now }
        stats.reset()
    }

    @After
    fun tearDown() {
        stats.reset()
        testContext.getSharedPreferences("site_session_test", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test
    fun `counter aggregates protections without persisting browsing details`() {
        val privateUrl = "https://secret.example/search?q=blocked+phrase"

        stats.recordPreventedBeforeWrite(privateUrl)
        stats.recordRemovedAfterMatch(privateUrl)

        // Overlapping callbacks for the same navigation count as one protection.
        assertEquals(1L, stats.snapshot().preventedBeforeWrite)
        assertEquals(0L, stats.snapshot().removedAfterMatch)

        now += 2_001L
        stats.recordRemovedAfterMatch(privateUrl)
        stats.recordRemovedDuringCleanup(3)

        val snapshot = stats.snapshot()
        assertEquals(1L, snapshot.preventedBeforeWrite)
        assertEquals(1L, snapshot.removedAfterMatch)
        assertEquals(3L, snapshot.removedDuringCleanup)
        assertEquals(5L, snapshot.total)

        val storedValues = testContext.getSharedPreferences(
            PrivateHistoryStats.PREFERENCES_NAME,
            Context.MODE_PRIVATE,
        ).all.values
        assertTrue(storedValues.all { it is Long })
    }

    @Test
    fun `reset clears only the aggregate counters`() {
        val unrelated = testContext.getSharedPreferences("site_session_test", Context.MODE_PRIVATE)
        unrelated.edit().putString("session", "keep-me").commit()
        stats.recordPreventedBeforeWrite("https://example.invalid/private")

        stats.reset()

        assertEquals(0L, stats.snapshot().total)
        assertEquals("keep-me", unrelated.getString("session", null))
    }
}

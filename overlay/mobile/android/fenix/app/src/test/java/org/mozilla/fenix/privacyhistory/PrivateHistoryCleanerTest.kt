/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.privacyhistory

import androidx.preference.PreferenceManager
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import mozilla.components.browser.storage.sync.PlacesHistoryStorage
import mozilla.components.concept.storage.DocumentType
import mozilla.components.concept.storage.HistoryMetadata
import mozilla.components.concept.storage.HistoryMetadataKey
import mozilla.components.concept.storage.VisitInfo
import mozilla.components.concept.storage.VisitType
import mozilla.components.support.test.robolectric.testContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class PrivateHistoryCleanerTest {
    private val prefs
        get() = PreferenceManager.getDefaultSharedPreferences(testContext)

    @Before
    fun setUp() {
        prefs.edit().clear().commit()
        PrivateHistoryRules(testContext).clearTemporaryMode()
        PrivateHistoryStats(testContext).reset()
        prefs.edit().putString(PrivateHistoryRules.KEY_KEYWORDS, "blocked phrase").commit()
    }

    @After
    fun tearDown() {
        PrivateHistoryRules(testContext).clearTemporaryMode()
        prefs.edit().clear().commit()
        PrivateHistoryStats(testContext).reset()
    }

    @Test
    fun `matching synced search metadata also purges its Places visit`() = runTest {
        val storage = mockk<PlacesHistoryStorage>(relaxed = true)
        val url = "https://search.example/results"
        coEvery { storage.getDetailedVisits(any(), any(), any()) } returns listOf(
            visit(url = url, title = "Clean title", isRemote = true),
        )
        coEvery { storage.getHistoryMetadataSince(0L) } returns listOf(
            metadata(url = url, searchTerm = "blocked phrase"),
        )
        val stats = PrivateHistoryStats(testContext)

        val removed = PrivateHistoryCleaner(storage, PrivateHistoryRules(testContext), stats)
            .purgeMatchingHistory()

        assertEquals(1, removed)
        assertEquals(1L, stats.snapshot().removedDuringCleanup)
        coVerify(exactly = 1) { storage.deleteVisitsFor(url) }
        coVerify(exactly = 1) { storage.deleteHistoryMetadataForUrl(url) }
    }

    @Test
    fun `title-only visit match purges visits and all metadata for the URL`() = runTest {
        val storage = mockk<PlacesHistoryStorage>(relaxed = true)
        val url = "https://clean.example/article"
        coEvery { storage.getDetailedVisits(any(), any(), any()) } returns listOf(
            visit(url = url, title = "A blocked phrase appears", isRemote = false),
        )
        coEvery { storage.getHistoryMetadataSince(0L) } returns emptyList()
        val stats = PrivateHistoryStats(testContext)

        val removed = PrivateHistoryCleaner(storage, PrivateHistoryRules(testContext), stats)
            .purgeMatchingHistory()

        assertEquals(1, removed)
        assertEquals(1L, stats.snapshot().removedDuringCleanup)
        coVerify(exactly = 1) { storage.deleteVisitsFor(url) }
        coVerify(exactly = 1) { storage.deleteHistoryMetadataForUrl(url) }
    }

    @Test
    fun `forget-after removes only visits older than the rule retention`() = runTest {
        val storage = mockk<PlacesHistoryStorage>(relaxed = true)
        val oldUrl = "https://temporary.example/old"
        val recentUrl = "https://temporary.example/recent"
        coEvery { storage.getDetailedVisits(any(), any(), any()) } returns listOf(
            visit(url = oldUrl, title = "Old", isRemote = false, visitTime = 100_000L),
            visit(url = recentUrl, title = "Recent", isRemote = false, visitTime = 190_000L),
        )
        coEvery { storage.getHistoryMetadataSince(0L) } returns emptyList()
        prefs.edit().putString(
            PrivateHistoryRules.KEY_VISUAL_RULES,
            PrivateHistoryRule.encode(
                listOf(
                    PrivateHistoryRule(
                        name = "Temporary",
                        matcher = PrivateHistoryRule.Matcher.DOMAIN,
                        value = "temporary.example",
                        action = PrivateHistoryRule.Action.FORGET_AFTER,
                        retentionMillis = 60_000L,
                    ),
                ),
            ),
        ).commit()

        val removed = PrivateHistoryCleaner(
            storage,
            PrivateHistoryRules(testContext) { 200_000L },
            PrivateHistoryStats(testContext),
        ).purgeMatchingHistory()

        assertEquals(1, removed)
        coVerify(exactly = 1) { storage.deleteVisitsFor(oldUrl) }
        coVerify(exactly = 0) { storage.deleteVisitsFor(recentUrl) }
    }

    @Test
    fun `restart-only rules are skipped periodically and included at startup`() = runTest {
        val storage = mockk<PlacesHistoryStorage>(relaxed = true)
        val url = "https://restart.example/page"
        coEvery { storage.getDetailedVisits(any(), any(), any()) } returns listOf(
            visit(url = url, title = "Page", isRemote = false),
        )
        coEvery { storage.getHistoryMetadataSince(0L) } returns emptyList()
        prefs.edit().putString(
            PrivateHistoryRules.KEY_VISUAL_RULES,
            PrivateHistoryRule.encode(
                listOf(
                    PrivateHistoryRule(
                        name = "Restart",
                        matcher = PrivateHistoryRule.Matcher.DOMAIN,
                        value = "restart.example",
                        action = PrivateHistoryRule.Action.FORGET_ON_RESTART,
                    ),
                ),
            ),
        ).commit()
        val cleaner = PrivateHistoryCleaner(
            storage,
            PrivateHistoryRules(testContext),
            PrivateHistoryStats(testContext),
        )

        assertEquals(0, cleaner.purgeMatchingHistory(includeRestartRules = false))
        assertEquals(1, cleaner.purgeMatchingHistory(includeRestartRules = true))
        coVerify(exactly = 1) { storage.deleteVisitsFor(url) }
    }

    @Test
    fun `a clean metadata record cannot hide a matching visit for the same URL`() = runTest {
        val storage = mockk<PlacesHistoryStorage>(relaxed = true)
        val url = "https://clean.example/article"
        coEvery { storage.getDetailedVisits(any(), any(), any()) } returns listOf(
            visit(url = url, title = "blocked phrase", isRemote = false),
        )
        coEvery { storage.getHistoryMetadataSince(0L) } returns listOf(
            metadata(url = url, searchTerm = "clean"),
        )

        val removed = PrivateHistoryCleaner(
            storage,
            PrivateHistoryRules(testContext),
            PrivateHistoryStats(testContext),
        ).purgeMatchingHistory()

        assertEquals(1, removed)
        coVerify(exactly = 1) { storage.deleteVisitsFor(url) }
    }

    @Test
    fun `temporary live shield never turns cleanup into full history deletion`() = runTest {
        val storage = mockk<PlacesHistoryStorage>(relaxed = true)
        val url = "https://safe.example/article"
        coEvery { storage.getDetailedVisits(any(), any(), any()) } returns listOf(
            visit(url = url, title = "Safe", isRemote = false),
        )
        coEvery { storage.getHistoryMetadataSince(0L) } returns emptyList()
        val rules = PrivateHistoryRules(testContext)
        rules.setSessionMode(true)

        val removed = PrivateHistoryCleaner(
            storage,
            rules,
            PrivateHistoryStats(testContext),
        ).purgeMatchingHistory(includeRestartRules = true)

        assertEquals(0, removed)
        coVerify(exactly = 0) { storage.deleteVisitsFor(url) }
    }

    private fun visit(url: String, title: String, isRemote: Boolean, visitTime: Long = 1L) = VisitInfo(
        url = url,
        title = title,
        visitTime = visitTime,
        visitType = VisitType.LINK,
        previewImageUrl = null,
        isRemote = isRemote,
    )

    private fun metadata(url: String, searchTerm: String) = HistoryMetadata(
        key = HistoryMetadataKey(url = url, searchTerm = searchTerm),
        title = "Clean title",
        createdAt = 1L,
        updatedAt = 1L,
        totalViewTime = 0,
        documentType = DocumentType.Regular,
        previewImageUrl = null,
    )
}

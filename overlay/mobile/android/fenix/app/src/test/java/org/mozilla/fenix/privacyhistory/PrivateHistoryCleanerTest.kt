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
        PrivateHistoryStats(testContext).reset()
        prefs.edit().putString(PrivateHistoryRules.KEY_KEYWORDS, "blocked phrase").commit()
    }

    @After
    fun tearDown() {
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

    private fun visit(url: String, title: String, isRemote: Boolean) = VisitInfo(
        url = url,
        title = title,
        visitTime = 1L,
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

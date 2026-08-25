/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.privacyhistory

import androidx.preference.PreferenceManager
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import mozilla.components.browser.storage.sync.PlacesHistoryStorage
import mozilla.components.concept.storage.PageObservation
import mozilla.components.concept.storage.PageVisit
import mozilla.components.concept.storage.VisitType
import mozilla.components.support.test.robolectric.testContext
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class PrivateHistoryDelegateTest {
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
    fun `blocked URL is rejected synchronously then existing history is purged`() = runTest {
        prefs.edit().putString(PrivateHistoryRules.KEY_DOMAINS, "example.com").commit()
        val storage = mockk<PlacesHistoryStorage>(relaxed = true)
        var initialized = false
        val lazyStorage = lazy {
            initialized = true
            storage
        }
        val purger = PrivateHistoryPurger(lazyStorage, this)
        val delegate = PrivateHistoryDelegate(
            lazyStorage,
            PrivateHistoryRules(testContext),
            purger,
        )

        assertFalse(delegate.shouldStoreUri("https://example.com/private"))
        assertFalse(initialized)
        testScheduler.advanceUntilIdle()
        assertTrue(initialized)
        coVerify(exactly = 1) { storage.deleteVisitsFor("https://example.com/private") }
        coVerify(exactly = 1) { storage.deleteHistoryMetadataForUrl("https://example.com/private") }

        every { storage.canAddUri(any()) } returns true
        assertTrue(delegate.shouldStoreUri("https://mozilla.org"))
        assertTrue(initialized)
    }

    @Test
    fun `allowed visit is recorded`() = runTest {
        val storage = mockk<PlacesHistoryStorage>(relaxed = true)
        every { storage.canAddUri(any()) } returns true
        val lazyStorage = lazy { storage }
        val delegate = PrivateHistoryDelegate(
            lazyStorage,
            PrivateHistoryRules(testContext),
            PrivateHistoryPurger(lazyStorage, this),
        )
        val visit = PageVisit(VisitType.LINK)

        delegate.onVisited("https://mozilla.org", visit)

        coVerify(exactly = 1) { storage.recordVisit("https://mozilla.org", visit) }
        coVerify(exactly = 0) { storage.deleteVisitsFor(any()) }
    }

    @Test
    fun `title-only match deletes visit and metadata instead of recording the title`() = runTest {
        prefs.edit().putString(PrivateHistoryRules.KEY_KEYWORDS, "blocked title").commit()
        val storage = mockk<PlacesHistoryStorage>(relaxed = true)
        every { storage.canAddUri(any()) } returns true
        val lazyStorage = lazy { storage }
        val delegate = PrivateHistoryDelegate(
            lazyStorage,
            PrivateHistoryRules(testContext),
            PrivateHistoryPurger(lazyStorage, this),
        )
        val url = "https://clean.example/article"

        delegate.onTitleChanged(url, "A blocked title appears")

        coVerify(exactly = 1) { storage.deleteVisitsFor(url) }
        coVerify(exactly = 1) { storage.deleteHistoryMetadataForUrl(url) }
        coVerify(exactly = 0) { storage.recordObservation(url, any<PageObservation>()) }
    }

    @Test
    fun `purger deduplicates concurrent requests for the same URL`() = runTest {
        val storage = mockk<PlacesHistoryStorage>(relaxed = true)
        val lazyStorage = lazy { storage }
        val purger = PrivateHistoryPurger(lazyStorage, this)
        val url = "https://example.com/private"

        purger.purgeAsync(url)
        purger.purgeAsync(url)
        testScheduler.advanceUntilIdle()

        coVerify(exactly = 1) { storage.deleteVisitsFor(url) }
        coVerify(exactly = 1) { storage.deleteHistoryMetadataForUrl(url) }
    }
}

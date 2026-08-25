/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.privacyhistory

import mozilla.components.browser.storage.sync.PlacesHistoryStorage

/** Scrubs old/local/synced history plus search-term metadata that matches current rules. */
class PrivateHistoryCleaner(
    private val historyStorage: PlacesHistoryStorage,
    private val rules: PrivateHistoryRules,
) {
    suspend fun purgeMatchingHistory(): Int {
        if (!rules.enabled) return 0

        val visitUrls = historyStorage
            .getDetailedVisits(start = 0L, end = Long.MAX_VALUE)
            .asSequence()
            .filter { rules.shouldBlockVisit(it.url, it.title) }
            .map { it.url }
            .distinct()
            .toList()

        val metadata = historyStorage
            .getHistoryMetadataSince(0L)
            .filter { record ->
                rules.shouldBlockVisit(
                    uri = record.key.url,
                    title = record.title,
                    searchTerm = record.key.searchTerm,
                )
            }

        // A search term may be present only in metadata. Its URL still has a Places visit,
        // so always purge both stores for every matching URL, including entries from Sync.
        val urls = (visitUrls + metadata.map { it.key.url }).distinct()
        urls.forEach { url ->
            historyStorage.deleteVisitsFor(url)
            historyStorage.deleteHistoryMetadataForUrl(url)
        }

        return urls.size
    }
}

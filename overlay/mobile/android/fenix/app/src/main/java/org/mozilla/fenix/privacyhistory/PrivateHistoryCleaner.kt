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

        val urls = historyStorage
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

        urls.forEach { historyStorage.deleteVisitsFor(it) }
        metadata.forEach { historyStorage.deleteHistoryMetadata(it.key) }

        return urls.size + metadata.size
    }
}

/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.privacyhistory

import mozilla.components.browser.storage.sync.PlacesHistoryStorage
import mozilla.components.concept.engine.history.HistoryTrackingDelegate
import mozilla.components.concept.storage.PageObservation
import mozilla.components.concept.storage.PageVisit

/** Filters visits before Places receives them. */
class PrivateHistoryDelegate(
    private val historyStorage: Lazy<PlacesHistoryStorage>,
    private val rules: PrivateHistoryRules,
    private val purger: PrivateHistoryPurger,
    private val stats: PrivateHistoryStats,
) : HistoryTrackingDelegate {
    override suspend fun onVisited(uri: String, visit: PageVisit) {
        if (!shouldStoreUri(uri)) {
            purgeUri(uri)
            return
        }
        historyStorage.value.recordVisit(uri, visit)
    }

    override suspend fun onTitleChanged(uri: String, title: String) {
        if (rules.shouldBlockVisit(uri, title)) {
            stats.recordRemovedAfterMatch(uri)
            purgeUri(uri)
            return
        }
        historyStorage.value.recordObservation(uri, PageObservation(title = title))
    }

    override suspend fun onPreviewImageChange(uri: String, previewImageUrl: String) {
        if (rules.shouldBlockUri(uri)) return
        historyStorage.value.recordObservation(uri, PageObservation(previewImageUrl = previewImageUrl))
    }

    override suspend fun getVisited(uris: List<String>): List<Boolean> {
        val stored = historyStorage.value.getVisited(uris)
        return uris.mapIndexed { index, uri ->
            if (rules.shouldBlockUri(uri)) false else stored.getOrElse(index) { false }
        }
    }

    override suspend fun getVisited(): List<String> =
        historyStorage.value.getVisited().filterNot(rules::shouldBlockUri)

    override fun shouldStoreUri(uri: String): Boolean {
        if (rules.shouldBlockUri(uri)) {
            stats.recordPreventedBeforeWrite(uri)
            // Gecko calls this before onVisited, so schedule deletion here as well. This removes
            // any older or synced visits for the URL even though the new visit is never recorded.
            purger.purgeAsync(uri)
            return false
        }
        return historyStorage.value.canAddUri(uri)
    }

    private suspend fun purgeUri(uri: String) = purger.purge(uri)
}

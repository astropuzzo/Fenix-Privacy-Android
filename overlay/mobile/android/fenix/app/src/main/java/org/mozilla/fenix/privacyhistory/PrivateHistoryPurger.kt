/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.privacyhistory

import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import mozilla.components.browser.storage.sync.PlacesHistoryStorage

/**
 * Deletes both Places visits and Firefox history metadata.
 *
 * The application-scoped coroutine is intentionally independent from a tab/session. This ensures
 * a title or search-term match still gets scrubbed if the originating tab closes immediately.
 */
class PrivateHistoryPurger(
    private val historyStorage: Lazy<PlacesHistoryStorage>,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) {
    private val pendingUris = ConcurrentHashMap.newKeySet<String>()

    fun purgeAsync(uri: String) {
        if (uri.isBlank() || !pendingUris.add(uri)) return

        scope.launch {
            try {
                purge(uri)
            } finally {
                pendingUris.remove(uri)
            }
        }
    }

    suspend fun purge(uri: String) {
        if (uri.isBlank()) return
        historyStorage.value.deleteVisitsFor(uri)
        historyStorage.value.deleteHistoryMetadataForUrl(uri)
    }
}

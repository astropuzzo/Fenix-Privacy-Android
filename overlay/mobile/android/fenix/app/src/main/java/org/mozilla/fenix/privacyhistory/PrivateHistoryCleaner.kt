/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.privacyhistory

import mozilla.components.browser.storage.sync.PlacesHistoryStorage
import mozilla.components.concept.storage.PageVisit
import mozilla.components.concept.storage.VisitType

/** Scrubs old/local/synced history plus search-term metadata that matches current rules. */
class PrivateHistoryCleaner(
    private val historyStorage: PlacesHistoryStorage,
    private val rules: PrivateHistoryRules,
    private val stats: PrivateHistoryStats,
) {
    data class Preview(
        val scanned: Int,
        val matching: Int,
        val collapsedToRoot: Int,
    )

    private data class PlannedRemoval(
        val url: String,
        val collapsedUri: String?,
    )

    suspend fun previewMatchingHistory(): Preview {
        if (!rules.enabled) return Preview(scanned = 0, matching = 0, collapsedToRoot = 0)
        val (scanned, plan) = buildPlan()
        return Preview(
            scanned = scanned,
            matching = plan.size,
            collapsedToRoot = plan.count { !it.collapsedUri.isNullOrBlank() },
        )
    }

    suspend fun purgeMatchingHistory(): Int {
        if (!rules.enabled) return 0
        val (_, plan) = buildPlan()
        val roots = linkedSetOf<String>()

        plan.forEach { item ->
            historyStorage.deleteVisitsFor(item.url)
            historyStorage.deleteHistoryMetadataForUrl(item.url)
            item.collapsedUri?.takeIf { it != item.url }?.let(roots::add)
        }
        roots.forEach { root ->
            if (historyStorage.canAddUri(root)) {
                historyStorage.recordVisit(root, PageVisit(VisitType.LINK))
            }
        }

        stats.recordRemovedDuringCleanup(plan.size)
        if (roots.isNotEmpty()) stats.recordCollapsedDuringCleanup(roots.size)
        return plan.size
    }

    private suspend fun buildPlan(): Pair<Int, List<PlannedRemoval>> {
        val visits = historyStorage.getDetailedVisits(start = 0L, end = Long.MAX_VALUE)
        val metadata = historyStorage.getHistoryMetadataSince(0L)
        val decisions = linkedMapOf<String, PrivateHistoryDecision>()

        visits.forEach { visit ->
            val decision = rules.decide(visit.url, visit.title)
            if (decision.suppressesOriginal) decisions[visit.url] = decision
        }
        metadata.forEach { record ->
            val decision = rules.decide(
                uri = record.key.url,
                title = record.title,
                searchTerm = record.key.searchTerm,
            )
            if (decision.suppressesOriginal) decisions[record.key.url] = decision
        }

        val plan = decisions.map { (url, decision) ->
            PlannedRemoval(
                url = url,
                collapsedUri = decision.collapsedUri
                    .takeIf { decision.action == PrivateHistoryRule.Action.COLLAPSE_TO_ROOT },
            )
        }
        return (visits.size + metadata.size) to plan
    }
}

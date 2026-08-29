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

    private data class Candidate(
        val decision: PrivateHistoryDecision,
        val lastVisitAt: Long,
    )

    suspend fun previewMatchingHistory(includeRestartRules: Boolean = true): Preview {
        if (!rules.enabled) return Preview(scanned = 0, matching = 0, collapsedToRoot = 0)
        val (scanned, plan) = buildPlan(includeRestartRules)
        return Preview(
            scanned = scanned,
            matching = plan.size,
            collapsedToRoot = plan.count { !it.collapsedUri.isNullOrBlank() },
        )
    }

    suspend fun purgeMatchingHistory(includeRestartRules: Boolean = false): Int {
        if (!rules.enabled) return 0
        val (_, plan) = buildPlan(includeRestartRules)
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

    private suspend fun buildPlan(includeRestartRules: Boolean): Pair<Int, List<PlannedRemoval>> {
        val visits = historyStorage.getDetailedVisits(start = 0L, end = Long.MAX_VALUE)
        val metadata = historyStorage.getHistoryMetadataSince(0L)
        val candidates = linkedMapOf<String, Candidate>()

        visits.forEach { visit ->
            addCandidate(
                candidates,
                visit.url,
                rules.decide(visit.url, visit.title, includeTransientProtection = false),
                visit.visitTime,
            )
        }
        metadata.forEach { record ->
            addCandidate(
                candidates = candidates,
                url = record.key.url,
                decision = rules.decide(
                    uri = record.key.url,
                    title = record.title,
                    searchTerm = record.key.searchTerm,
                    includeTransientProtection = false,
                ),
                visitedAt = record.updatedAt,
            )
        }

        val plan = candidates.mapNotNull { (url, candidate) ->
            val (decision, lastVisitAt) = candidate
            if (!rules.shouldRemoveStoredVisit(decision, lastVisitAt, includeRestartRules)) return@mapNotNull null
            PlannedRemoval(
                url = url,
                collapsedUri = decision.collapsedUri
                    .takeIf { decision.action == PrivateHistoryRule.Action.COLLAPSE_TO_ROOT },
            )
        }
        return (visits.size + metadata.size) to plan
    }

    private fun addCandidate(
        candidates: MutableMap<String, Candidate>,
        url: String,
        decision: PrivateHistoryDecision,
        visitedAt: Long,
    ) {
        val previous = candidates[url]
        candidates[url] = Candidate(
            decision = listOfNotNull(previous?.decision, decision).maxBy(::decisionPriority),
            lastVisitAt = maxOf(visitedAt, previous?.lastVisitAt ?: 0L),
        )
    }

    private fun decisionPriority(decision: PrivateHistoryDecision): Int = when {
        decision.action == PrivateHistoryRule.Action.ALLOW && decision.matchedRule != null -> 100
        decision.action == PrivateHistoryRule.Action.BLOCK -> 80
        decision.action == PrivateHistoryRule.Action.COLLAPSE_TO_ROOT -> 70
        decision.action == PrivateHistoryRule.Action.FORGET_ON_RESTART -> 60
        decision.action == PrivateHistoryRule.Action.FORGET_AFTER -> 50
        else -> 0
    }
}

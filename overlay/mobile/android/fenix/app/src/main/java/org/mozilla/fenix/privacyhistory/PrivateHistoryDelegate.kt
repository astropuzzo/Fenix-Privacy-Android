/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.privacyhistory

import mozilla.components.browser.storage.sync.PlacesHistoryStorage
import mozilla.components.concept.engine.history.HistoryTrackingDelegate
import mozilla.components.concept.storage.PageObservation
import mozilla.components.concept.storage.PageVisit
import mozilla.components.concept.storage.VisitType

/** Filters visits before Places receives them. */
class PrivateHistoryDelegate(
    private val historyStorage: Lazy<PlacesHistoryStorage>,
    private val rules: PrivateHistoryRules,
    private val purger: PrivateHistoryPurger,
    private val stats: PrivateHistoryStats,
    private val actionExecutor: PrivateHistoryActionExecutor? = null,
) : HistoryTrackingDelegate {
    override suspend fun onVisited(uri: String, visit: PageVisit) {
        val decision = rules.decide(uri)
        when (decision.action) {
            PrivateHistoryRule.Action.ALLOW -> historyStorage.value.recordVisit(uri, visit)
            PrivateHistoryRule.Action.BLOCK -> purgeUri(uri)
            PrivateHistoryRule.Action.COLLAPSE_TO_ROOT -> {
                val target = decision.collapsedUri
                if (!target.isNullOrBlank() && target != uri && historyStorage.value.canAddUri(target)) {
                    historyStorage.value.recordVisit(target, visit)
                    stats.recordCollapsedToRoot(uri)
                } else {
                    stats.recordPreventedBeforeWrite(uri)
                }
                purgeUri(uri)
            }
        }
        if (decision.suppressesOriginal) actionExecutor?.execute(uri, decision.matchedRule)
    }

    override suspend fun onTitleChanged(uri: String, title: String) {
        val decision = rules.decide(uri, title)
        when (decision.action) {
            PrivateHistoryRule.Action.ALLOW ->
                historyStorage.value.recordObservation(uri, PageObservation(title = title))
            PrivateHistoryRule.Action.BLOCK -> {
                stats.recordRemovedAfterMatch(uri)
                purgeUri(uri)
                actionExecutor?.execute(uri, decision.matchedRule)
            }
            PrivateHistoryRule.Action.COLLAPSE_TO_ROOT -> {
                decision.collapsedUri
                    ?.takeIf { it != uri && historyStorage.value.canAddUri(it) }
                    ?.let { historyStorage.value.recordVisit(it, PageVisit(VisitType.LINK)) }
                stats.recordCollapsedToRoot(uri)
                purgeUri(uri)
                actionExecutor?.execute(uri, decision.matchedRule)
            }
        }
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
        val decision = rules.decide(uri)
        return when (decision.action) {
            PrivateHistoryRule.Action.ALLOW -> historyStorage.value.canAddUri(uri)
            PrivateHistoryRule.Action.BLOCK -> {
                stats.recordPreventedBeforeWrite(uri)
                // Gecko calls this before onVisited. Remove older/synced visits too.
                purger.purgeAsync(uri)
                actionExecutor?.execute(uri, decision.matchedRule)
                false
            }
            PrivateHistoryRule.Action.COLLAPSE_TO_ROOT -> {
                // Returning true lets onVisited replace the specific URL with the site root.
                decision.collapsedUri?.let(historyStorage.value::canAddUri) == true
            }
        }
    }

    private suspend fun purgeUri(uri: String) = purger.purge(uri)
}

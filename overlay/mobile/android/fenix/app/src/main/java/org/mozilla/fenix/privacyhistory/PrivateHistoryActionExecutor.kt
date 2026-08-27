/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.privacyhistory

import java.net.URI
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import mozilla.components.browser.state.store.BrowserStore
import mozilla.components.concept.engine.Engine
import org.mozilla.fenix.components.UseCases

/** Executes only the destructive actions that the user explicitly enabled on a visual rule. */
class PrivateHistoryActionExecutor(
    private val engine: Lazy<Engine>,
    private val store: Lazy<BrowserStore>,
    private val useCases: Lazy<UseCases>,
    private val rules: PrivateHistoryRules,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main),
) {
    private val pending = ConcurrentHashMap.newKeySet<String>()

    fun execute(uri: String, rule: PrivateHistoryRule?) {
        if (rule == null || !rule.isDestructive || uri.isBlank()) return
        // Tab removal is intentionally excluded here. A matching page must remain usable for the
        // current Firefox session; closeTab only prevents restored tabs from surviving restart.
        if (!rule.clearCookies && !rule.clearCache && !rule.clearDownloads) return
        val key = "${rule.id}:${uri.hashCode()}"
        if (!pending.add(key)) return

        scope.launch {
            try {
                val host = runCatching { URI(uri).host }.getOrNull().orEmpty()
                if (rule.clearCookies && host.isNotBlank()) {
                    engine.value.clearData(
                        host = host,
                        data = Engine.BrowsingData.select(
                            Engine.BrowsingData.COOKIES,
                            Engine.BrowsingData.AUTH_SESSIONS,
                            Engine.BrowsingData.DOM_STORAGES,
                        ),
                    )
                }
                if (rule.clearCache && host.isNotBlank()) {
                    engine.value.clearData(
                        host = host,
                        data = Engine.BrowsingData.select(Engine.BrowsingData.ALL_CACHES),
                    )
                }
                if (rule.clearDownloads) {
                    // Fenix's public use case currently exposes safe removal of the download list
                    // as a whole. Files on disk remain untouched.
                    useCases.value.downloadUseCases.removeAllDownloads.invoke()
                }
            } finally {
                pending.remove(key)
            }
        }
    }

    /** Removes matching tabs from restored session state without interrupting live navigation. */
    suspend fun closeRestoredTabs(): Int = withContext(Dispatchers.Main) {
        closeRestoredMatchingTabs()
    }

    private suspend fun closeRestoredMatchingTabs(): Int {
        repeat(CLOSE_TAB_ATTEMPTS) { attempt ->
            val ids = store.value.state.tabs
                .filter { tab ->
                    rules.shouldCloseTab(
                        uri = tab.content.url,
                        title = tab.content.title,
                        searchTerm = tab.content.searchTerms,
                    )
                }
                .map { it.id }
                .distinct()
            if (ids.isNotEmpty()) {
                ids.forEach { useCases.value.tabsUseCases.removeTab.invoke(it) }
                return ids.size
            }
            if (attempt + 1 < CLOSE_TAB_ATTEMPTS) delay(CLOSE_TAB_RETRY_MILLIS)
        }
        return 0
    }

    private companion object {
        const val CLOSE_TAB_ATTEMPTS = 8
        const val CLOSE_TAB_RETRY_MILLIS = 250L
    }
}

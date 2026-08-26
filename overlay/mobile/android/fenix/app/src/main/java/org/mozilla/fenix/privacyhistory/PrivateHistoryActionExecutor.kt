/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.privacyhistory

import java.net.URI
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import mozilla.components.browser.state.store.BrowserStore
import mozilla.components.concept.engine.Engine
import org.mozilla.fenix.components.UseCases

/** Executes only the destructive actions that the user explicitly enabled on a visual rule. */
class PrivateHistoryActionExecutor(
    private val engine: Lazy<Engine>,
    private val store: Lazy<BrowserStore>,
    private val useCases: Lazy<UseCases>,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main),
) {
    private val pending = ConcurrentHashMap.newKeySet<String>()

    fun execute(uri: String, rule: PrivateHistoryRule?) {
        if (rule == null || !rule.isDestructive || uri.isBlank()) return
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
                if (rule.closeTab) {
                    store.value.state.tabs
                        .filter { it.content.url == uri }
                        .map { it.id }
                        .forEach { useCases.value.tabsUseCases.removeTab.invoke(it) }
                }
            } finally {
                pending.remove(key)
            }
        }
    }
}

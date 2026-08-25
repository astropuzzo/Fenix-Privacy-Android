/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.historymetadata

import io.mockk.mockk
import io.mockk.verify
import mozilla.components.browser.state.action.ContentAction
import mozilla.components.browser.state.action.TabListAction
import mozilla.components.browser.state.state.BrowserState
import mozilla.components.browser.state.state.createTab
import mozilla.components.browser.state.store.BrowserStore
import org.junit.Assert.assertEquals
import org.junit.Test

class PrivateHistoryMetadataSuppressionTest {
    @Test
    fun `blocked toolbar search term purges the real URL and skips metadata`() {
        val service = mockk<HistoryMetadataService>(relaxed = true)
        val purged = mutableListOf<String>()
        val middleware = HistoryMetadataMiddleware(
            historyMetadataService = service,
            shouldSuppress = { _, _, searchTerm -> searchTerm == "blocked phrase" },
            onSuppressed = purged::add,
        )
        val store = BrowserStore(
            middleware = listOf(middleware),
            initialState = BrowserState(),
        )
        val tab = createTab(
            url = "https://search.example/results",
            searchTerms = "blocked phrase",
        )

        store.dispatch(TabListAction.AddTabAction(tab))

        assertEquals(listOf(tab.content.url), purged)
        verify(exactly = 0) { service.createMetadata(any(), any(), any()) }
    }

    @Test
    fun `browser-state title update triggers independent purge fallback`() {
        val service = mockk<HistoryMetadataService>(relaxed = true)
        val purged = mutableListOf<String>()
        val middleware = HistoryMetadataMiddleware(
            historyMetadataService = service,
            shouldSuppress = { _, title, _ -> title?.contains("blocked", ignoreCase = true) == true },
            onSuppressed = purged::add,
        )
        val tab = createTab("https://clean.example/article")
        val store = BrowserStore(
            middleware = listOf(middleware),
            initialState = BrowserState(tabs = listOf(tab)),
        )

        store.dispatch(ContentAction.UpdateTitleAction(tab.id, "Blocked title"))

        assertEquals(listOf(tab.content.url), purged)
    }
}

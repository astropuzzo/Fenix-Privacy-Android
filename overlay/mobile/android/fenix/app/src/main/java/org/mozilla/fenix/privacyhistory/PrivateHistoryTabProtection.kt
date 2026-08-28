/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.privacyhistory

import java.util.concurrent.ConcurrentHashMap

/** Process-only tab protection. URLs are never persisted, synced, logged, or added to counters. */
object PrivateHistoryTabProtection {
    private data class OneShot(var armedUrl: String, var protectedUrl: String = "")

    private val protectedTabs = ConcurrentHashMap.newKeySet<String>()
    private val inheritingTabs = ConcurrentHashMap.newKeySet<String>()
    private val currentUrls = ConcurrentHashMap<String, String>()
    private val oneShots = ConcurrentHashMap<String, OneShot>()

    fun onNavigation(tabId: String, parentId: String?, url: String) {
        if (parentId != null && parentId in protectedTabs && parentId in inheritingTabs) {
            protectedTabs += tabId
            inheritingTabs += tabId
        }
        oneShots[tabId]?.let { oneShot ->
            when {
                oneShot.protectedUrl.isBlank() && url != oneShot.armedUrl -> oneShot.protectedUrl = url
                oneShot.protectedUrl.isNotBlank() && url != oneShot.protectedUrl -> oneShots.remove(tabId)
            }
        }
        currentUrls[tabId] = url
    }

    fun prune(openTabIds: Set<String>) {
        protectedTabs.retainAll(openTabIds)
        inheritingTabs.retainAll(openTabIds)
        currentUrls.keys.retainAll(openTabIds)
        oneShots.keys.retainAll(openTabIds)
    }

    fun toggle(tabId: String, inherit: Boolean = true): Boolean {
        if (!protectedTabs.add(tabId)) {
            protectedTabs.remove(tabId)
            inheritingTabs.remove(tabId)
            oneShots.remove(tabId)
            return false
        }
        if (inherit) inheritingTabs += tabId else inheritingTabs -= tabId
        return true
    }

    fun armNext(tabId: String, currentUrl: String) {
        oneShots[tabId] = OneShot(armedUrl = currentUrl)
    }

    fun isTabProtected(tabId: String): Boolean = tabId in protectedTabs

    fun isNextArmed(tabId: String): Boolean = tabId in oneShots

    fun isProtectedUri(uri: String): Boolean =
        protectedTabs.any { currentUrls[it] == uri } || oneShots.values.any { it.protectedUrl == uri }

    internal fun clearForTests() {
        protectedTabs.clear()
        inheritingTabs.clear()
        currentUrls.clear()
        oneShots.clear()
    }
}

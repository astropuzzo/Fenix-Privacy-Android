/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.privacyhistory

import android.content.Context
import android.os.SystemClock

/** Aggregate, device-local protection counters. No URL, title, query, or rule is persisted. */
class PrivateHistoryStats(
    context: Context,
    private val elapsedRealtime: () -> Long = SystemClock::elapsedRealtime,
) {
    private val preferences = context.applicationContext.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE,
    )
    private val lock = Any()

    // Only an integer String hash is kept briefly in memory. This prevents Gecko's
    // overlapping callbacks from making a single protection look like several events.
    private val recentProtections = mutableMapOf<Int, Long>()

    fun recordPreventedBeforeWrite(uri: String) {
        recordLiveProtection(uri, KEY_PREVENTED_BEFORE_WRITE)
    }

    fun recordRemovedAfterMatch(uri: String) {
        recordLiveProtection(uri, KEY_REMOVED_AFTER_MATCH)
    }

    fun recordRemovedDuringCleanup(count: Int) {
        if (count <= 0) return
        increment(KEY_REMOVED_DURING_CLEANUP, count.toLong())
    }

    fun snapshot(): Snapshot = synchronized(lock) {
        val preventedBeforeWrite = preferences.getLong(KEY_PREVENTED_BEFORE_WRITE, 0L)
        val removedAfterMatch = preferences.getLong(KEY_REMOVED_AFTER_MATCH, 0L)
        val removedDuringCleanup = preferences.getLong(KEY_REMOVED_DURING_CLEANUP, 0L)
        Snapshot(
            preventedBeforeWrite = preventedBeforeWrite,
            removedAfterMatch = removedAfterMatch,
            removedDuringCleanup = removedDuringCleanup,
        )
    }

    fun reset() = synchronized(lock) {
        recentProtections.clear()
        preferences.edit().clear().apply()
    }

    private fun recordLiveProtection(uri: String, counterKey: String) {
        val now = elapsedRealtime()
        val fingerprint = uri.hashCode()
        synchronized(lock) {
            recentProtections.entries.removeAll { now - it.value > DEDUPLICATION_WINDOW_MILLIS }
            val previous = recentProtections[fingerprint]
            if (previous != null && now - previous <= DEDUPLICATION_WINDOW_MILLIS) return

            if (recentProtections.size >= MAX_RECENT_PROTECTIONS) {
                recentProtections.minByOrNull { it.value }?.key?.let(recentProtections::remove)
            }
            recentProtections[fingerprint] = now
            incrementLocked(counterKey, 1L)
        }
    }

    private fun increment(key: String, amount: Long) = synchronized(lock) {
        incrementLocked(key, amount)
    }

    private fun incrementLocked(key: String, amount: Long) {
        val current = preferences.getLong(key, 0L)
        preferences.edit().putLong(key, saturatedAdd(current, amount)).apply()
    }

    data class Snapshot(
        val preventedBeforeWrite: Long,
        val removedAfterMatch: Long,
        val removedDuringCleanup: Long,
    ) {
        val total: Long = saturatedAdd(
            saturatedAdd(preventedBeforeWrite, removedAfterMatch),
            removedDuringCleanup,
        )
    }

    companion object {
        internal const val PREFERENCES_NAME = "fenix_privacy_history_stats"
        private const val KEY_PREVENTED_BEFORE_WRITE = "prevented_before_write"
        private const val KEY_REMOVED_AFTER_MATCH = "removed_after_match"
        private const val KEY_REMOVED_DURING_CLEANUP = "removed_during_cleanup"
        private const val DEDUPLICATION_WINDOW_MILLIS = 2_000L
        private const val MAX_RECENT_PROTECTIONS = 256

        private fun saturatedAdd(left: Long, right: Long): Long =
            if (right > Long.MAX_VALUE - left) Long.MAX_VALUE else left + right
    }
}

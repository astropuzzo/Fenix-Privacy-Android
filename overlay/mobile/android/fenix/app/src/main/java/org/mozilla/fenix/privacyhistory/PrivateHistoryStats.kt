/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.privacyhistory

import android.content.Context
import android.os.SystemClock

/** Aggregate, device-local protection counters. No URL, title, query, or rule is persisted. */
class PrivateHistoryStats(
    context: Context,
    private val wallClock: () -> Long = System::currentTimeMillis,
    private val elapsedRealtime: () -> Long = SystemClock::elapsedRealtime,
) {
    private val preferences = context.applicationContext.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE,
    )
    private val lock = Any()

    // Only an integer hash is kept briefly in memory to deduplicate overlapping Gecko callbacks.
    private val recentProtections = mutableMapOf<Int, Long>()

    fun recordPreventedBeforeWrite(uri: String) =
        recordLiveProtection(uri, KEY_PREVENTED_BEFORE_WRITE, EVENT_PREVENTED)

    fun recordRemovedAfterMatch(uri: String) =
        recordLiveProtection(uri, KEY_REMOVED_AFTER_MATCH, EVENT_REMOVED_AFTER_MATCH)

    fun recordCollapsedToRoot(uri: String) =
        recordLiveProtection(uri, KEY_COLLAPSED_TO_ROOT, EVENT_COLLAPSED)

    fun recordRemovedDuringCleanup(count: Int) {
        if (count <= 0) return
        increment(KEY_REMOVED_DURING_CLEANUP, count.toLong(), EVENT_CLEANUP)
    }

    fun recordCollapsedDuringCleanup(count: Int) {
        if (count <= 0) return
        synchronized(lock) {
            incrementLocked(KEY_COLLAPSED_DURING_CLEANUP, count.toLong())
        }
    }

    fun snapshot(): Snapshot = synchronized(lock) {
        rollPeriodsLocked()
        val preventedBeforeWrite = preferences.getLong(KEY_PREVENTED_BEFORE_WRITE, 0L)
        val removedAfterMatch = preferences.getLong(KEY_REMOVED_AFTER_MATCH, 0L)
        val removedDuringCleanup = preferences.getLong(KEY_REMOVED_DURING_CLEANUP, 0L)
        val collapsedToRoot = preferences.getLong(KEY_COLLAPSED_TO_ROOT, 0L)
        Snapshot(
            preventedBeforeWrite = preventedBeforeWrite,
            removedAfterMatch = removedAfterMatch,
            removedDuringCleanup = removedDuringCleanup,
            collapsedToRoot = collapsedToRoot,
            collapsedDuringCleanup = preferences.getLong(KEY_COLLAPSED_DURING_CLEANUP, 0L),
            today = preferences.getLong(KEY_TODAY_COUNT, 0L),
            thisWeek = preferences.getLong(KEY_WEEK_COUNT, 0L),
            lastEventAt = preferences.getLong(KEY_LAST_EVENT_AT, 0L),
            lastEventCode = preferences.getLong(KEY_LAST_EVENT_CODE, 0L).toInt(),
        )
    }

    fun reset() = synchronized(lock) {
        recentProtections.clear()
        preferences.edit().clear().apply()
    }

    private fun recordLiveProtection(uri: String, counterKey: String, eventCode: Int) {
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
            recordPeriodAndLastEventLocked(1L, eventCode)
        }
    }

    private fun increment(key: String, amount: Long, eventCode: Int) = synchronized(lock) {
        incrementLocked(key, amount)
        recordPeriodAndLastEventLocked(amount, eventCode)
    }

    private fun incrementLocked(key: String, amount: Long) {
        val current = preferences.getLong(key, 0L)
        preferences.edit().putLong(key, saturatedAdd(current, amount)).apply()
    }

    private fun recordPeriodAndLastEventLocked(amount: Long, eventCode: Int) {
        rollPeriodsLocked()
        val today = preferences.getLong(KEY_TODAY_COUNT, 0L)
        val week = preferences.getLong(KEY_WEEK_COUNT, 0L)
        preferences.edit()
            .putLong(KEY_TODAY_COUNT, saturatedAdd(today, amount))
            .putLong(KEY_WEEK_COUNT, saturatedAdd(week, amount))
            .putLong(KEY_LAST_EVENT_AT, wallClock())
            .putLong(KEY_LAST_EVENT_CODE, eventCode.toLong())
            .apply()
    }

    private fun rollPeriodsLocked() {
        val day = wallClock() / MILLIS_PER_DAY
        val week = day / DAYS_PER_WEEK
        val storedDay = preferences.getLong(KEY_DAY_BUCKET, Long.MIN_VALUE)
        val storedWeek = preferences.getLong(KEY_WEEK_BUCKET, Long.MIN_VALUE)
        if (storedDay == day && storedWeek == week) return

        preferences.edit().apply {
            if (storedDay != day) {
                putLong(KEY_DAY_BUCKET, day)
                putLong(KEY_TODAY_COUNT, 0L)
            }
            if (storedWeek != week) {
                putLong(KEY_WEEK_BUCKET, week)
                putLong(KEY_WEEK_COUNT, 0L)
            }
        }.apply()
    }

    data class Snapshot(
        val preventedBeforeWrite: Long,
        val removedAfterMatch: Long,
        val removedDuringCleanup: Long,
        val collapsedToRoot: Long,
        val collapsedDuringCleanup: Long,
        val today: Long,
        val thisWeek: Long,
        val lastEventAt: Long,
        val lastEventCode: Int,
    ) {
        val collapsedTotal: Long = saturatedAdd(collapsedToRoot, collapsedDuringCleanup)

        val total: Long = saturatedAdd(
            saturatedAdd(preventedBeforeWrite, removedAfterMatch),
            saturatedAdd(removedDuringCleanup, collapsedToRoot),
        )

        val nextMilestone: Long = MILESTONES.firstOrNull { it > total }
            ?: saturatedAdd((total / 10_000L) * 10_000L, 10_000L)
    }

    companion object {
        internal const val PREFERENCES_NAME = "fenix_privacy_history_stats"
        private const val KEY_PREVENTED_BEFORE_WRITE = "prevented_before_write"
        private const val KEY_REMOVED_AFTER_MATCH = "removed_after_match"
        private const val KEY_REMOVED_DURING_CLEANUP = "removed_during_cleanup"
        private const val KEY_COLLAPSED_TO_ROOT = "collapsed_to_root"
        private const val KEY_COLLAPSED_DURING_CLEANUP = "collapsed_during_cleanup"
        private const val KEY_TODAY_COUNT = "today_count"
        private const val KEY_WEEK_COUNT = "week_count"
        private const val KEY_DAY_BUCKET = "day_bucket"
        private const val KEY_WEEK_BUCKET = "week_bucket"
        private const val KEY_LAST_EVENT_AT = "last_event_at"
        private const val KEY_LAST_EVENT_CODE = "last_event_code"
        private const val DEDUPLICATION_WINDOW_MILLIS = 2_000L
        private const val MAX_RECENT_PROTECTIONS = 256
        private const val MILLIS_PER_DAY = 86_400_000L
        private const val DAYS_PER_WEEK = 7L

        const val EVENT_PREVENTED = 1
        const val EVENT_REMOVED_AFTER_MATCH = 2
        const val EVENT_CLEANUP = 3
        const val EVENT_COLLAPSED = 4

        private val MILESTONES = longArrayOf(10L, 50L, 100L, 250L, 500L, 1_000L, 2_500L, 5_000L, 10_000L)

        private fun saturatedAdd(left: Long, right: Long): Long =
            if (right > Long.MAX_VALUE - left) Long.MAX_VALUE else left + right
    }
}

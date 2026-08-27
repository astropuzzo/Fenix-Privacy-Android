/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.privacyhistory

import android.content.Context
import androidx.preference.PreferenceManager
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit
import org.mozilla.fenix.FenixApplication

/** Periodically removes matching entries that could arrive through Firefox Sync. */
class PrivateHistoryMaintenanceWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext as? FenixApplication ?: return Result.success()
        return runCatching {
            PrivateHistoryCleaner(
                app.components.core.historyStorage,
                PrivateHistoryRules(applicationContext),
                app.components.core.privateHistoryStats,
            ).purgeMatchingHistory()
            app.components.core.privateHistoryActionExecutor.closeRestoredTabs()
            val selfTest = PrivateHistorySelfTest.run(applicationContext)
            PreferenceManager.getDefaultSharedPreferences(applicationContext).edit()
                .putLong(KEY_SELF_TEST_AT, System.currentTimeMillis())
                .putInt(KEY_SELF_TEST_PASSED, selfTest.passed)
                .putInt(KEY_SELF_TEST_TOTAL, selfTest.total)
                .putBoolean(KEY_SELF_TEST_OK, selfTest.ok)
                .apply()
        }.fold(
            onSuccess = { Result.success() },
            onFailure = { Result.retry() },
        )
    }

    companion object {
        private const val PERIODIC_NAME = "fenix-privacy-history-scrub"
        private const val STARTUP_NAME = "fenix-privacy-history-startup-scrub"
        const val KEY_SELF_TEST_AT = "fenix_privacy_self_test_at"
        const val KEY_SELF_TEST_PASSED = "fenix_privacy_self_test_passed"
        const val KEY_SELF_TEST_TOTAL = "fenix_privacy_self_test_total"
        const val KEY_SELF_TEST_OK = "fenix_privacy_self_test_ok"

        fun schedule(context: Context) {
            val manager = WorkManager.getInstance(context)
            manager.enqueueUniqueWork(
                STARTUP_NAME,
                ExistingWorkPolicy.KEEP,
                OneTimeWorkRequestBuilder<PrivateHistoryMaintenanceWorker>().build(),
            )
            manager.enqueueUniquePeriodicWork(
                PERIODIC_NAME,
                ExistingPeriodicWorkPolicy.UPDATE,
                PeriodicWorkRequestBuilder<PrivateHistoryMaintenanceWorker>(15, TimeUnit.MINUTES).build(),
            )
        }
    }
}

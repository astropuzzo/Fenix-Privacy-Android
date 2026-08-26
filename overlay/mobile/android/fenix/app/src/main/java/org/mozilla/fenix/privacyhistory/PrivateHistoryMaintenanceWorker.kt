/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.privacyhistory

import android.content.Context
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
        }.fold(
            onSuccess = { Result.success() },
            onFailure = { Result.retry() },
        )
    }

    companion object {
        private const val PERIODIC_NAME = "fenix-privacy-history-scrub"
        private const val STARTUP_NAME = "fenix-privacy-history-startup-scrub"

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

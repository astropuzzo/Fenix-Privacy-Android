/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.privacyhistory

import android.Manifest
import android.app.DownloadManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.preference.PreferenceManager
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.mozilla.fenix.R

/** GitHub-release updater: checks automatically, downloads automatically, Android confirms install. */
object FenixPrivacyUpdater {
    private const val PERIODIC_NAME = "fenix-privacy-update-check"
    private const val STARTUP_NAME = "fenix-privacy-update-startup"

    fun schedule(context: Context) {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        val manager = WorkManager.getInstance(context)
        if (!prefs.getBoolean(PrivateHistoryRules.KEY_AUTO_UPDATE, true)) {
            manager.cancelUniqueWork(PERIODIC_NAME)
            manager.cancelUniqueWork(STARTUP_NAME)
            return
        }

        val network = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
        manager.enqueueUniqueWork(
            STARTUP_NAME,
            ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<FenixPrivacyUpdateWorker>().setConstraints(network).build(),
        )
        manager.enqueueUniquePeriodicWork(
            PERIODIC_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            PeriodicWorkRequestBuilder<FenixPrivacyUpdateWorker>(12, TimeUnit.HOURS)
                .setConstraints(network)
                .build(),
        )
    }
}

class FenixPrivacyUpdateWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        runCatching {
            val metadata = fetchMetadata() ?: return@runCatching Result.success()
            val currentCode = currentVersionCode(applicationContext)
            if (metadata.versionCode <= currentCode) return@runCatching Result.success()

            val prefs = PreferenceManager.getDefaultSharedPreferences(applicationContext)
            val alreadyQueued = prefs.getLong(KEY_QUEUED_VERSION, 0L)
            if (alreadyQueued >= metadata.versionCode) return@runCatching Result.success()

            val manager = applicationContext.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            val request = DownloadManager.Request(Uri.parse(metadata.apkUrl))
                .setTitle(applicationContext.getString(R.string.fenix_privacy_update_download_title, metadata.versionName))
                .setDescription(applicationContext.getString(R.string.fenix_privacy_update_download_summary))
                .setMimeType(APK_MIME)
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setAllowedOverMetered(true)
                .setAllowedOverRoaming(false)
                .setDestinationInExternalFilesDir(
                    applicationContext,
                    Environment.DIRECTORY_DOWNLOADS,
                    "Fenix-Privacy-${metadata.versionCode}.apk",
                )

            val id = manager.enqueue(request)
            prefs.edit {
                putLong(KEY_DOWNLOAD_ID, id)
                putLong(KEY_QUEUED_VERSION, metadata.versionCode)
                putString(KEY_EXPECTED_SHA256, metadata.sha256.lowercase())
                putString(KEY_QUEUED_VERSION_NAME, metadata.versionName)
            }
            Result.success()
        }.getOrElse { Result.retry() }
    }

    private fun fetchMetadata(): UpdateMetadata? {
        val connection = URL(UPDATE_METADATA_URL).openConnection() as HttpURLConnection
        connection.connectTimeout = 15_000
        connection.readTimeout = 20_000
        connection.instanceFollowRedirects = true
        connection.setRequestProperty("Accept", "application/json")
        return try {
            if (connection.responseCode !in 200..299) return null
            val json = JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
            UpdateMetadata(
                versionCode = json.getLong("versionCode"),
                versionName = json.getString("versionName"),
                apkUrl = json.getString("apkUrl"),
                sha256 = json.getString("sha256"),
            )
        } finally {
            connection.disconnect()
        }
    }
}

class FenixPrivacyDownloadReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != DownloadManager.ACTION_DOWNLOAD_COMPLETE) return
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        val expectedId = prefs.getLong(KEY_DOWNLOAD_ID, -1L)
        val completedId = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -2L)
        if (completedId != expectedId) return

        val manager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val uri = manager.getUriForDownloadedFile(completedId)
        if (uri == null) {
            // DOWNLOAD_COMPLETE is also broadcast for failed downloads. Allow the next worker to retry.
            clearQueuedUpdate(prefs)
            return
        }

        val expectedSha = prefs.getString(KEY_EXPECTED_SHA256, "").orEmpty()
        if (expectedSha.isBlank() || !sha256Matches(context, uri, expectedSha)) {
            manager.remove(completedId)
            clearQueuedUpdate(prefs)
            return
        }

        val versionName = prefs.getString(KEY_QUEUED_VERSION_NAME, "").orEmpty()
        showInstallNotification(context, uri, versionName)
    }
}

private fun clearQueuedUpdate(prefs: SharedPreferences) {
    prefs.edit {
        remove(KEY_DOWNLOAD_ID)
        remove(KEY_QUEUED_VERSION)
        remove(KEY_EXPECTED_SHA256)
        remove(KEY_QUEUED_VERSION_NAME)
    }
}

private data class UpdateMetadata(
    val versionCode: Long,
    val versionName: String,
    val apkUrl: String,
    val sha256: String,
)

@Suppress("DEPRECATION")
private fun currentVersionCode(context: Context): Long {
    val info = context.packageManager.getPackageInfo(context.packageName, 0)
    return if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong()
}

private fun sha256Matches(context: Context, uri: Uri, expected: String): Boolean = runCatching {
    val digest = MessageDigest.getInstance("SHA-256")
    context.contentResolver.openInputStream(uri)?.use { input ->
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val read = input.read(buffer)
            if (read <= 0) break
            digest.update(buffer, 0, read)
        }
    } ?: return@runCatching false
    digest.digest().joinToString("") { "%02x".format(it) }.equals(expected, ignoreCase = true)
}.getOrDefault(false)

private fun showInstallNotification(context: Context, apkUri: Uri, versionName: String) {
    val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        notificationManager.createNotificationChannel(
            NotificationChannel(
                UPDATE_CHANNEL,
                context.getString(R.string.fenix_privacy_update_channel),
                NotificationManager.IMPORTANCE_HIGH,
            ),
        )
    }

    if (Build.VERSION.SDK_INT >= 33 &&
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
    ) return

    val installIntent = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(apkUri, APK_MIME)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    val pendingIntent = PendingIntent.getActivity(
        context,
        9801,
        installIntent,
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    val notification = NotificationCompat.Builder(context, UPDATE_CHANNEL)
        .setSmallIcon(R.drawable.ic_status_logo)
        .setContentTitle(context.getString(R.string.fenix_privacy_update_ready, versionName))
        .setContentText(context.getString(R.string.fenix_privacy_update_ready_summary))
        .setPriority(NotificationCompat.PRIORITY_HIGH)
        .setAutoCancel(true)
        .setContentIntent(pendingIntent)
        .build()
    notificationManager.notify(9801, notification)
}

private const val UPDATE_METADATA_URL =
    "https://github.com/astropuzzo/Fenix-Privacy-Android/releases/latest/download/update.json"
private const val APK_MIME = "application/vnd.android.package-archive"
private const val UPDATE_CHANNEL = "fenix_privacy_updates"
private const val KEY_DOWNLOAD_ID = "fenix_privacy_download_id"
private const val KEY_QUEUED_VERSION = "fenix_privacy_queued_version"
private const val KEY_EXPECTED_SHA256 = "fenix_privacy_expected_sha256"
private const val KEY_QUEUED_VERSION_NAME = "fenix_privacy_queued_version_name"

/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.privacyhistory

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import java.security.MessageDigest
import org.mozilla.fenix.BuildConfig
import org.mozilla.geckoview.BuildConfig as GeckoViewBuildConfig

/** Runtime update-center facts, including verification of the APK certificate actually installed. */
object PrivateHistoryUpdateInfo {
    data class Snapshot(
        val installedVersion: String,
        val mozillaVersion: String,
        val upstreamRef: String,
        val signatureVerified: Boolean,
        val actualCertificateSha256: String,
    )

    @Suppress("DEPRECATION")
    fun snapshot(context: Context): Snapshot {
        val packageInfo = context.packageManager.getPackageInfo(
            context.packageName,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) PackageManager.GET_SIGNING_CERTIFICATES
            else PackageManager.GET_SIGNATURES,
        )
        val signatures = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            packageInfo.signingInfo?.apkContentsSigners.orEmpty()
        } else {
            packageInfo.signatures.orEmpty()
        }
        val actual = signatures.firstOrNull()?.toByteArray()?.let { certificate ->
            MessageDigest.getInstance("SHA-256").digest(certificate)
                .joinToString("") { "%02x".format(it) }
        }.orEmpty()
        val expected = BuildConfig.FENIX_PRIVACY_SIGNING_CERT_SHA256.lowercase()
        return Snapshot(
            installedVersion = packageInfo.versionName.orEmpty(),
            mozillaVersion = GeckoViewBuildConfig.MOZ_APP_VERSION,
            upstreamRef = BuildConfig.FENIX_PRIVACY_UPSTREAM_REF,
            signatureVerified = actual.isNotBlank() && actual == expected,
            actualCertificateSha256 = actual,
        )
    }
}

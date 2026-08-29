/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.privacyhistory

import android.app.Activity
import android.hardware.biometrics.BiometricManager.Authenticators.BIOMETRIC_STRONG
import android.hardware.biometrics.BiometricPrompt
import android.os.Build
import android.os.CancellationSignal
import java.util.concurrent.atomic.AtomicBoolean

/** Protects sensitive rule metadata with a fresh strong biometric and never accepts the device PIN. */
object PrivateHistoryAuthenticator {
    fun authenticate(
        activity: Activity,
        title: String,
        subtitle: String,
        onResult: (Boolean) -> Unit,
    ) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            onResult(false)
            return
        }

        val completed = AtomicBoolean(false)
        fun complete(success: Boolean) {
            if (completed.compareAndSet(false, true)) onResult(success)
        }

        val builder = BiometricPrompt.Builder(activity)
            .setTitle(title)
            .setSubtitle(subtitle)

        builder
            .setAllowedAuthenticators(BIOMETRIC_STRONG)
            .setNegativeButton(
                activity.getString(android.R.string.cancel),
                activity.mainExecutor,
            ) { _, _ -> complete(false) }

        builder.build().authenticate(
            CancellationSignal(),
            activity.mainExecutor,
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult?) {
                    complete(true)
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence?) {
                    complete(false)
                }
            },
        )
    }
}

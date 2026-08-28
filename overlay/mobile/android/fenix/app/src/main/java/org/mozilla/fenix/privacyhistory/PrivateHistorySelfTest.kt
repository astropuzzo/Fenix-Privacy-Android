/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.privacyhistory

import android.content.Context

/** Local, network-free checks run after an upstream update or on demand. */
object PrivateHistorySelfTest {
    data class Result(val passed: Int, val total: Int, val failures: List<String>) {
        val ok: Boolean get() = passed == total
    }

    fun run(context: Context): Result {
        val failures = mutableListOf<String>()
        var total = 0
        fun check(name: String, condition: () -> Boolean) {
            total += 1
            if (!runCatching(condition).getOrDefault(false)) failures += name
        }

        val sample = PrivateHistoryRule(
            name = "Self-test",
            matcher = PrivateHistoryRule.Matcher.DOMAIN_EXCEPT_ROOT,
            value = "privacy-self-test.invalid",
            action = PrivateHistoryRule.Action.COLLAPSE_TO_ROOT,
        )
        check("rule-codec") { PrivateHistoryRule.decode(PrivateHistoryRule.encode(listOf(sample))) == listOf(sample) }
        val delayed = sample.copy(
            action = PrivateHistoryRule.Action.FORGET_AFTER,
            retentionMillis = 3_600_000L,
        )
        check("delayed-rule-codec") {
            PrivateHistoryRule.decode(PrivateHistoryRule.encode(listOf(delayed))).single() == delayed
        }
        check("root-canonicalization") {
            PrivateHistoryRules(context).siteRoot("https://privacy-self-test.invalid/path?q=x") ==
                "https://privacy-self-test.invalid/"
        }
        check("counter-has-no-text") {
            context.getSharedPreferences(PrivateHistoryStats.PREFERENCES_NAME, Context.MODE_PRIVATE)
                .all.values.all { it is Long }
        }
        check("session-storage-is-separate") {
            val sentinel = context.getSharedPreferences(SELF_TEST_PREFS, Context.MODE_PRIVATE)
            sentinel.edit().putString(SELF_TEST_SENTINEL, "keep").commit()
            PrivateHistoryStats(context).snapshot()
            sentinel.getString(SELF_TEST_SENTINEL, null) == "keep"
        }
        check("encrypted-backup") {
            val encrypted = PrivateHistoryBackup.exportEncrypted(context, "self-test-passphrase".toCharArray())
            encrypted.startsWith("FENIX-PRIVACY-2\n") && !encrypted.contains("privacy-self-test.invalid")
        }

        context.getSharedPreferences(SELF_TEST_PREFS, Context.MODE_PRIVATE).edit().clear().apply()
        return Result(passed = total - failures.size, total = total, failures = failures)
    }

    private const val SELF_TEST_PREFS = "fenix_privacy_self_test"
    private const val SELF_TEST_SENTINEL = "session"
}

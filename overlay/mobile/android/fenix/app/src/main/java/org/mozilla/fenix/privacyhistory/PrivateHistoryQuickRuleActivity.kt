/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.privacyhistory

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import java.net.URI
import org.mozilla.fenix.R

/** Share-sheet action for creating a rule directly from the current page. */
class PrivateHistoryQuickRuleActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val shared = when (intent?.action) {
            Intent.ACTION_SEND -> intent.getStringExtra(Intent.EXTRA_TEXT)
            Intent.ACTION_PROCESS_TEXT -> intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)?.toString()
            else -> intent?.dataString
        }.orEmpty().trim()
        val parsedUri = runCatching { URI(shared) }.getOrNull()
        val host = parsedUri?.host.orEmpty()
        if (parsedUri == null || host.isBlank() || parsedUri.scheme?.lowercase() !in setOf("http", "https")) {
            Toast.makeText(this, R.string.private_history_quick_invalid_url, Toast.LENGTH_LONG).show()
            finish()
            return
        }
        val uri: URI = parsedUri

        val labels = resources.getStringArray(R.array.private_history_quick_actions)
        AlertDialog.Builder(this)
            .setTitle(R.string.private_history_quick_title)
            .setItems(labels) { _, which -> handleChoice(which, shared, uri, host) }
            .setOnCancelListener { finish() }
            .show()
    }

    private fun handleChoice(which: Int, url: String, uri: URI, host: String) {
        val section = uri.rawPath.orEmpty().split('/').firstOrNull { it.isNotBlank() }
        val preset = when (which) {
            0 -> rule("Block $host", PrivateHistoryRule.Matcher.DOMAIN, host, PrivateHistoryRule.Action.BLOCK)
            1 -> rule(
                "Block section",
                PrivateHistoryRule.Matcher.PATH_PREFIX,
                if (section == null) "$host/" else "$host/$section",
                PrivateHistoryRule.Action.BLOCK,
            )
            2 -> rule(
                "Keep only homepage",
                PrivateHistoryRule.Matcher.DOMAIN_EXCEPT_ROOT,
                host,
                PrivateHistoryRule.Action.BLOCK,
            )
            3 -> rule("Allow page", PrivateHistoryRule.Matcher.EXACT_URL, url, PrivateHistoryRule.Action.ALLOW)
            4 -> rule(
                "Collapse to homepage",
                PrivateHistoryRule.Matcher.DOMAIN_EXCEPT_ROOT,
                host,
                PrivateHistoryRule.Action.COLLAPSE_TO_ROOT,
            )
            5 -> rule(
                "Temporary $host",
                PrivateHistoryRule.Matcher.DOMAIN,
                host,
                PrivateHistoryRule.Action.BLOCK,
                expiresAt = System.currentTimeMillis() + 15 * 60_000L,
            )
            else -> null
        }

        if (preset == null) {
            PrivateHistoryRuleBuilder(this, PrivateHistoryRules(this)) { finish() }.show(presetValue = url)
            return
        }
        PrivateHistoryRules(this).addOrReplaceRule(preset)
        Toast.makeText(this, R.string.private_history_quick_saved, Toast.LENGTH_SHORT).show()
        finish()
    }

    private fun rule(
        name: String,
        matcher: PrivateHistoryRule.Matcher,
        value: String,
        action: PrivateHistoryRule.Action,
        expiresAt: Long = 0L,
    ) = PrivateHistoryRule(
        name = name,
        matcher = matcher,
        value = value,
        action = action,
        expiresAtEpochMillis = expiresAt,
    )
}

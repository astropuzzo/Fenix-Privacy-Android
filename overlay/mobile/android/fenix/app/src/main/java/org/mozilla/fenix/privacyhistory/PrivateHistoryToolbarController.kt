/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.privacyhistory

import android.app.AlertDialog
import android.content.Context
import android.widget.Toast
import java.net.URI
import java.util.UUID
import mozilla.components.browser.state.selector.selectedTab
import mozilla.components.browser.state.store.BrowserStore
import org.mozilla.fenix.R

/** Contextual shield opened directly from the address bar. */
class PrivateHistoryToolbarController(
    private val context: Context,
    private val browserStore: BrowserStore,
    private val openStudio: () -> Unit,
    private val onChanged: () -> Unit = {},
) {
    fun show() {
        val tab = browserStore.state.selectedTab ?: return
        val url = tab.content.url.takeIf(::isWebUrl) ?: return
        PrivateHistoryTabProtection.onNavigation(tab.id, tab.parentId, url)
        val rules = PrivateHistoryRules(context)
        val decision = rules.decide(url, tab.content.title)
        val host = host(url) ?: return
        val status = when {
            PrivateHistoryTabProtection.isTabProtected(tab.id) ->
                context.getString(R.string.private_history_toolbar_status_tab)
            decision.action == PrivateHistoryRule.Action.ALLOW ->
                context.getString(R.string.private_history_toolbar_status_allow)
            decision.action == PrivateHistoryRule.Action.BLOCK ->
                context.getString(R.string.private_history_toolbar_status_block)
            decision.action == PrivateHistoryRule.Action.COLLAPSE_TO_ROOT ->
                context.getString(R.string.private_history_toolbar_status_collapse)
            decision.action == PrivateHistoryRule.Action.FORGET_AFTER ->
                context.getString(R.string.private_history_toolbar_status_temporary)
            else -> context.getString(R.string.private_history_toolbar_status_restart)
        }

        AlertDialog.Builder(context)
            .setTitle(context.getString(R.string.private_history_toolbar_title, host))
            .setMessage(status)
            .setItems(context.resources.getStringArray(R.array.private_history_toolbar_actions)) { _, which ->
                when (which) {
                    0 -> addRule(rules, host, PrivateHistoryRule.Matcher.DOMAIN, PrivateHistoryRule.Action.BLOCK)
                    1 -> addRule(
                        rules,
                        host,
                        PrivateHistoryRule.Matcher.DOMAIN_EXCEPT_ROOT,
                        PrivateHistoryRule.Action.COLLAPSE_TO_ROOT,
                    )
                    2 -> addRule(rules, url, PrivateHistoryRule.Matcher.EXACT_URL, PrivateHistoryRule.Action.ALLOW)
                    3 -> addRule(
                        rules,
                        host,
                        PrivateHistoryRule.Matcher.DOMAIN,
                        PrivateHistoryRule.Action.FORGET_ON_RESTART,
                    )
                    4 -> addRule(
                        rules,
                        host,
                        PrivateHistoryRule.Matcher.DOMAIN,
                        PrivateHistoryRule.Action.FORGET_AFTER,
                        retentionMillis = DAY_MILLIS,
                    )
                    5 -> toggleCurrentTab()
                    6 -> {
                        PrivateHistoryTabProtection.armNext(tab.id, url)
                        onChanged()
                        toast(R.string.private_history_toolbar_next_armed)
                    }
                    7 -> {
                        rules.setTemporaryMode(15 * 60_000L)
                        onChanged()
                        toast(R.string.private_history_toolbar_temporary_enabled)
                    }
                    8 -> openStudio()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    fun toggleCurrentTab() {
        val tab = browserStore.state.selectedTab ?: return
        PrivateHistoryTabProtection.onNavigation(tab.id, tab.parentId, tab.content.url)
        val enabled = PrivateHistoryTabProtection.toggle(tab.id, inherit = true)
        onChanged()
        toast(
            if (enabled) R.string.private_history_toolbar_tab_enabled
            else R.string.private_history_toolbar_tab_disabled,
        )
    }

    private fun addRule(
        rules: PrivateHistoryRules,
        value: String,
        matcher: PrivateHistoryRule.Matcher,
        action: PrivateHistoryRule.Action,
        retentionMillis: Long = 0L,
    ) {
        rules.addOrReplaceRule(
            PrivateHistoryRule(
                id = UUID.randomUUID().toString(),
                name = context.getString(R.string.private_history_toolbar_rule_name, host(value) ?: value),
                matcher = matcher,
                value = value,
                action = action,
                retentionMillis = retentionMillis,
            ),
        )
        onChanged()
        toast(R.string.private_history_toolbar_rule_saved)
    }

    private fun toast(message: Int) = Toast.makeText(context, message, Toast.LENGTH_SHORT).show()

    private fun host(url: String): String? = runCatching { URI(url).host }.getOrNull()?.takeIf(String::isNotBlank)

    private fun isWebUrl(url: String): Boolean = url.startsWith("https://") || url.startsWith("http://")

    private companion object {
        const val DAY_MILLIS = 24 * 60 * 60 * 1000L
    }
}

/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.privacyhistory

import android.app.AlertDialog
import android.content.Context
import android.text.InputType
import android.view.View
import android.widget.ArrayAdapter
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import org.mozilla.fenix.R

/** Programmatic visual builder, kept independent from upstream Fenix layout resources. */
class PrivateHistoryRuleBuilder(
    private val context: Context,
    private val rules: PrivateHistoryRules,
    private val onSaved: () -> Unit = {},
) {
    fun show(existing: PrivateHistoryRule? = null, presetValue: String = "") {
        val padding = (20 * context.resources.displayMetrics.density).toInt()
        val layout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, padding / 2, padding, 0)
        }
        val name = field(R.string.private_history_builder_name, existing?.name.orEmpty())
        val profile = field(
            R.string.private_history_builder_profile,
            existing?.profile ?: PrivateHistoryRule.DEFAULT_PROFILE,
        )
        val matcher = spinner(
            context.resources.getStringArray(R.array.private_history_matcher_labels),
            existing?.matcher?.ordinal ?: PrivateHistoryRule.Matcher.DOMAIN.ordinal,
        )
        val value = field(R.string.private_history_builder_value, existing?.value ?: presetValue)
        val queryParameter = field(
            R.string.private_history_builder_query_parameter,
            existing?.queryParameter.orEmpty(),
        )
        val action = spinner(
            context.resources.getStringArray(R.array.private_history_action_labels),
            existing?.action?.ordinal ?: PrivateHistoryRule.Action.BLOCK.ordinal,
        )
        val expiryMinutes = field(
            R.string.private_history_builder_expiry,
            existing?.expiresAtEpochMillis?.takeIf { it > System.currentTimeMillis() }
                ?.let { ((it - System.currentTimeMillis()) / 60_000L).coerceAtLeast(1L).toString() }
                .orEmpty(),
            InputType.TYPE_CLASS_NUMBER,
        )
        val enabled = checkbox(R.string.private_history_builder_enabled, existing?.enabled ?: true)
        val clearCookies = checkbox(R.string.private_history_builder_clear_cookies, existing?.clearCookies ?: false)
        val clearCache = checkbox(R.string.private_history_builder_clear_cache, existing?.clearCache ?: false)
        val clearDownloads = checkbox(
            R.string.private_history_builder_clear_downloads,
            existing?.clearDownloads ?: false,
        )
        val closeTab = checkbox(R.string.private_history_builder_close_tab, existing?.closeTab ?: false)

        listOf(
            label(R.string.private_history_builder_name), name,
            label(R.string.private_history_builder_profile), profile,
            label(R.string.private_history_builder_matcher), matcher,
            label(R.string.private_history_builder_value), value,
            label(R.string.private_history_builder_query_parameter), queryParameter,
            label(R.string.private_history_builder_action), action,
            label(R.string.private_history_builder_expiry), expiryMinutes,
            enabled,
            label(R.string.private_history_builder_optional_actions),
            clearCookies, clearCache, clearDownloads, closeTab,
        ).forEach(layout::addView)

        fun refreshConditionalFields() {
            queryParameter.visibility = if (
                matcher.selectedItemPosition == PrivateHistoryRule.Matcher.QUERY_PARAMETER.ordinal
            ) View.VISIBLE else View.GONE
        }
        matcher.setSelection(existing?.matcher?.ordinal ?: PrivateHistoryRule.Matcher.DOMAIN.ordinal)
        refreshConditionalFields()
        matcher.onItemSelectedListener = SimpleItemSelectedListener { refreshConditionalFields() }

        val dialog = AlertDialog.Builder(context)
            .setTitle(
                if (existing == null) R.string.private_history_builder_add_title
                else R.string.private_history_builder_edit_title,
            )
            .setView(layout)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.private_history_builder_save, null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val pattern = value.text.toString().trim()
                if (pattern.isBlank()) {
                    Toast.makeText(context, R.string.private_history_builder_value_required, Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                val expiry = expiryMinutes.text.toString().toLongOrNull()?.takeIf { it > 0L }
                    ?.let { minutes ->
                        val duration = (minutes.coerceAtMost(MAX_EXPIRY_MINUTES) * 60_000L)
                        System.currentTimeMillis() + duration
                    } ?: 0L
                val next = PrivateHistoryRule(
                    id = existing?.id ?: java.util.UUID.randomUUID().toString(),
                    name = name.text.toString().trim().ifBlank { pattern },
                    profile = profile.text.toString().trim().ifBlank { PrivateHistoryRule.DEFAULT_PROFILE },
                    matcher = PrivateHistoryRule.Matcher.entries[matcher.selectedItemPosition],
                    value = pattern,
                    queryParameter = queryParameter.text.toString().trim(),
                    action = PrivateHistoryRule.Action.entries[action.selectedItemPosition],
                    enabled = enabled.isChecked,
                    expiresAtEpochMillis = expiry,
                    clearCookies = clearCookies.isChecked,
                    clearCache = clearCache.isChecked,
                    clearDownloads = clearDownloads.isChecked,
                    closeTab = closeTab.isChecked,
                )
                rules.addOrReplaceRule(next)
                onSaved()
                dialog.dismiss()
            }
        }
        dialog.show()
    }

    private fun label(resId: Int) = TextView(context).apply {
        setText(resId)
        setPadding(0, 12, 0, 2)
    }

    private fun field(resId: Int, initial: String, type: Int = InputType.TYPE_CLASS_TEXT) =
        EditText(context).apply {
            hint = context.getString(resId)
            inputType = type
            setText(initial)
            isSingleLine = true
        }

    private fun checkbox(resId: Int, initial: Boolean) = CheckBox(context).apply {
        setText(resId)
        isChecked = initial
    }

    private fun spinner(labels: Array<String>, initial: Int) = Spinner(context).apply {
        adapter = ArrayAdapter(context, android.R.layout.simple_spinner_dropdown_item, labels)
        setSelection(initial)
    }

    private class SimpleItemSelectedListener(
        private val callback: () -> Unit,
    ) : android.widget.AdapterView.OnItemSelectedListener {
        override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) {
            callback()
        }

        override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit
    }

    companion object {
        private const val MAX_EXPIRY_MINUTES = 525_600L
    }
}

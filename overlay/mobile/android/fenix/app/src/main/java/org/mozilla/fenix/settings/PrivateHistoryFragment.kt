/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.settings

import android.os.Bundle
import android.text.InputType
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import androidx.preference.EditTextPreference
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.mozilla.fenix.R
import org.mozilla.fenix.e2e.SystemInsetsPaddedFragment
import org.mozilla.fenix.ext.requireComponents
import org.mozilla.fenix.ext.showToolbar
import org.mozilla.fenix.privacyhistory.FenixPrivacyUpdater
import org.mozilla.fenix.privacyhistory.PrivateHistoryCleaner
import org.mozilla.fenix.privacyhistory.PrivateHistoryRules

/** Settings for Fenix Privacy's per-domain and keyword history suppression. */
class PrivateHistoryFragment : PreferenceFragmentCompat(), SystemInsetsPaddedFragment {
    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.private_history_preferences, rootKey)

        configureRuleEditor(PrivateHistoryRules.KEY_DOMAINS, R.string.private_history_none_domains)
        configureRuleEditor(PrivateHistoryRules.KEY_KEYWORDS, R.string.private_history_none_keywords)
        configureRuleEditor(PrivateHistoryRules.KEY_REGEX, R.string.private_history_none_regex)

        findPreference<Preference>("private_history_cleanup")?.setOnPreferenceClickListener { preference ->
            preference.isEnabled = false
            preference.summary = getString(R.string.private_history_cleanup_running)

            viewLifecycleOwner.lifecycleScope.launch {
                val rules = PrivateHistoryRules(requireContext())
                val removed = withContext(Dispatchers.IO) {
                    PrivateHistoryCleaner(requireComponents.core.historyStorage, rules).purgeMatchingHistory()
                }

                preference.isEnabled = true
                preference.summary = getString(R.string.private_history_cleanup_summary)
                Toast.makeText(
                    requireContext(),
                    resources.getQuantityString(
                        R.plurals.private_history_cleanup_result,
                        removed,
                        removed,
                    ),
                    Toast.LENGTH_LONG,
                ).show()
            }
            true
        }
    }

    override fun onResume() {
        super.onResume()
        showToolbar(getString(R.string.private_history_title))
    }

    override fun onPause() {
        FenixPrivacyUpdater.schedule(requireContext())
        super.onPause()
    }

    private fun configureRuleEditor(key: String, emptySummary: Int) {
        findPreference<EditTextPreference>(key)?.apply {
            setOnBindEditTextListener { editText ->
                editText.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
                editText.isSingleLine = false
                editText.minLines = 5
                editText.maxLines = 12
                editText.setHorizontallyScrolling(false)
            }
            summaryProvider = Preference.SummaryProvider<EditTextPreference> { preference ->
                val count = preference.text
                    .orEmpty()
                    .lineSequence()
                    .map(String::trim)
                    .count { it.isNotBlank() && !it.startsWith('#') }

                if (count == 0) {
                    getString(emptySummary)
                } else {
                    resources.getQuantityString(R.plurals.private_history_rules_count, count, count)
                }
            }
        }
    }
}

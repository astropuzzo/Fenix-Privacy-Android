/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.settings

import android.app.AlertDialog
import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.view.WindowManager
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.preference.EditTextPreference
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import mozilla.components.concept.storage.Login
import mozilla.components.feature.qr.QrScanActivity
import org.mozilla.fenix.R
import org.mozilla.fenix.components.menu.share.QRCodeGenerator
import org.mozilla.fenix.e2e.SystemInsetsPaddedFragment
import org.mozilla.fenix.ext.requireComponents
import org.mozilla.fenix.ext.showToolbar
import org.mozilla.fenix.privacyhistory.FenixPrivacyUpdater
import org.mozilla.fenix.privacyhistory.PrivateHistoryAuthenticator
import org.mozilla.fenix.privacyhistory.PrivateHistoryBackup
import org.mozilla.fenix.privacyhistory.PrivateHistoryCleaner
import org.mozilla.fenix.privacyhistory.PrivateHistoryRule
import org.mozilla.fenix.privacyhistory.PrivateHistoryRuleBuilder
import org.mozilla.fenix.privacyhistory.PrivateHistoryRules
import org.mozilla.fenix.privacyhistory.PrivateHistorySelfTest
import org.mozilla.fenix.privacyhistory.PrivateHistoryStats
import org.mozilla.fenix.privacyhistory.PrivateHistoryUpdateInfo
import org.mozilla.fenix.privacyhistory.PrivatePasswordManager
import org.mozilla.fenix.privacyhistory.PrivatePasswordMetadata

/** Privacy Studio: visual rules, diagnostics, encrypted transfer and selective-history controls. */
class PrivateHistoryFragment : PreferenceFragmentCompat(), SystemInsetsPaddedFragment {
    private var pendingExportPassphrase: CharArray? = null
    private var unlockedThisSession = false
    private var sensitiveDialog: AlertDialog? = null

    private val exportLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream"),
    ) { uri ->
        val passphrase = pendingExportPassphrase
        pendingExportPassphrase = null
        if (uri == null || passphrase == null) return@registerForActivityResult
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            runCatching {
                val bundle = PrivateHistoryBackup.exportEncrypted(requireContext(), passphrase)
                requireContext().contentResolver.openOutputStream(uri)?.bufferedWriter()?.use { it.write(bundle) }
                    ?: error("Unable to open destination")
            }.fold(
                onSuccess = { showToast(R.string.private_history_backup_exported) },
                onFailure = { showToast(R.string.private_history_backup_failed) },
            )
        }
    }

    private val importLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@registerForActivityResult
        showPassphraseDialog(R.string.private_history_backup_import_title) { passphrase ->
            viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
                runCatching {
                    val bundle = requireContext().contentResolver.openInputStream(uri)
                        ?.bufferedReader()?.use { it.readText() } ?: error("Unable to open bundle")
                    PrivateHistoryBackup.importEncrypted(requireContext(), bundle, passphrase)
                }.fold(
                    onSuccess = { count ->
                        withContext(Dispatchers.Main) {
                            refreshRuleSummaries()
                            Toast.makeText(
                                requireContext(),
                                getString(R.string.private_history_backup_imported, count),
                                Toast.LENGTH_LONG,
                            ).show()
                        }
                    },
                    onFailure = { showToast(R.string.private_history_backup_failed) },
                )
            }
        }
    }

    private val qrImportLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        val bundle = result.data
            ?.takeIf { result.resultCode == Activity.RESULT_OK }
            ?.getStringExtra(QrScanActivity.EXTRA_SCAN_RESULT_DATA)
            ?.takeIf(String::isNotBlank)
            ?: return@registerForActivityResult
        showPassphraseDialog(R.string.private_history_backup_import_title) { passphrase ->
            importEncryptedBundle(bundle, passphrase)
        }
    }

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.private_history_preferences, rootKey)
        configureRuleEditor(PrivateHistoryRules.KEY_DOMAINS, R.string.private_history_none_domains)
        configureRuleEditor(PrivateHistoryRules.KEY_KEYWORDS, R.string.private_history_none_keywords)
        configureRuleEditor(PrivateHistoryRules.KEY_REGEX, R.string.private_history_none_regex)
        configureRuleEditor(PrivateHistoryRules.KEY_ACTIVE_PROFILES, R.string.private_history_profiles_default)

        findPreference<Preference>(KEY_RULE_BUILDER)?.setOnPreferenceClickListener {
            PrivateHistoryRuleBuilder(requireContext(), rules(), ::refreshRuleSummaries).show()
            true
        }
        findPreference<Preference>(KEY_RULE_MANAGER)?.setOnPreferenceClickListener {
            showRuleManager()
            true
        }
        findPreference<Preference>(KEY_RULE_TESTER)?.setOnPreferenceClickListener {
            showRuleTester()
            true
        }
        findPreference<Preference>(KEY_TEMPORARY_MODE)?.setOnPreferenceClickListener {
            showTemporaryModes()
            true
        }
        findPreference<Preference>(KEY_PASSWORD_MANAGER)?.setOnPreferenceClickListener {
            showPasswordManager()
            true
        }
        findPreference<Preference>(KEY_BACKUP_EXPORT)?.setOnPreferenceClickListener {
            showPassphraseDialog(R.string.private_history_backup_export_title) { passphrase ->
                pendingExportPassphrase = passphrase
                exportLauncher.launch("fenix-privacy-rules.fprules")
            }
            true
        }
        findPreference<Preference>(KEY_BACKUP_IMPORT)?.setOnPreferenceClickListener {
            importLauncher.launch(arrayOf("application/octet-stream", "text/plain", "application/json"))
            true
        }
        findPreference<Preference>(KEY_BACKUP_QR_EXPORT)?.setOnPreferenceClickListener {
            showPassphraseDialog(R.string.private_history_backup_export_title, ::showEncryptedQr)
            true
        }
        findPreference<Preference>(KEY_BACKUP_QR_IMPORT)?.setOnPreferenceClickListener {
            qrImportLauncher.launch(QrScanActivity.newIntent(requireContext()))
            true
        }
        findPreference<Preference>(KEY_SELF_TEST)?.setOnPreferenceClickListener {
            runSelfTest()
            true
        }
        findPreference<Preference>(KEY_UPDATE_STATUS)?.setOnPreferenceClickListener {
            FenixPrivacyUpdater.checkNow(requireContext())
            Toast.makeText(requireContext(), R.string.fenix_privacy_update_check_started, Toast.LENGTH_SHORT).show()
            refreshUpdateStatus()
            true
        }
        findPreference<Preference>(KEY_RELEASE_NOTES)?.setOnPreferenceClickListener {
            val url = FenixPrivacyUpdater.snapshot(requireContext()).releaseNotesUrl
            if (url.isBlank()) {
                Toast.makeText(requireContext(), R.string.fenix_privacy_release_notes_unavailable, Toast.LENGTH_SHORT).show()
            } else {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
            }
            true
        }

        findPreference<Preference>(KEY_STATS_RESET)?.setOnPreferenceClickListener {
            requireComponents.core.privateHistoryStats.reset()
            refreshStats()
            Toast.makeText(requireContext(), R.string.private_history_stats_reset_done, Toast.LENGTH_SHORT).show()
            true
        }
        findPreference<Preference>(KEY_CLEANUP)?.setOnPreferenceClickListener { preference ->
            previewCleanup(preference)
            true
        }
        refreshRuleSummaries()
    }

    override fun onResume() {
        super.onResume()
        showToolbar(getString(R.string.private_history_title))
        refreshStats()
        refreshRuleSummaries()
        refreshTemporaryMode()
        refreshUpdateStatus()
        if (!unlockedThisSession) {
            preferenceScreen.isEnabled = false
            authenticate { success ->
                unlockedThisSession = success
                preferenceScreen.isEnabled = success
                if (success) {
                    requireActivity().window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
                } else {
                    findNavController().popBackStack()
                }
            }
        }
    }

    override fun onPause() {
        sensitiveDialog?.dismiss()
        sensitiveDialog = null
        unlockedThisSession = false
        FenixPrivacyUpdater.schedule(requireContext())
        super.onPause()
    }

    override fun onDestroyView() {
        activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        super.onDestroyView()
    }

    private fun rules() = PrivateHistoryRules(requireContext())

    private fun configureRuleEditor(key: String, emptySummary: Int) {
        findPreference<EditTextPreference>(key)?.apply {
            setOnBindEditTextListener { editText ->
                editText.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
                editText.isSingleLine = false
                editText.minLines = 4
                editText.maxLines = 12
                editText.setHorizontallyScrolling(false)
            }
            summaryProvider = Preference.SummaryProvider<EditTextPreference> { preference ->
                val count = preference.text.orEmpty().lineSequence().map(String::trim)
                    .count { it.isNotBlank() && !it.startsWith('#') }
                if (count == 0) getString(emptySummary)
                else resources.getQuantityString(R.plurals.private_history_rules_count, count, count)
            }
        }
    }

    private fun refreshRuleSummaries() {
        val current = rules()
        val visual = current.visualRules()
        findPreference<Preference>(KEY_RULE_MANAGER)?.summary = getString(
            R.string.private_history_visual_rules_summary,
            visual.count { it.enabled },
            visual.size,
        )
    }

    private fun showRuleManager() {
        val currentRules = rules().visualRules()
        if (currentRules.isEmpty()) {
            Toast.makeText(requireContext(), R.string.private_history_visual_rules_empty, Toast.LENGTH_SHORT).show()
            return
        }
        val labels = currentRules.map { rule ->
            val state = if (rule.enabled) "🛡️" else "⏸"
            "$state ${rule.name} · ${rule.profile} · ${rule.action.name.lowercase()}"
        }.toTypedArray()
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.private_history_manage_rules_title)
            .setItems(labels) { _, index -> showRuleActions(currentRules[index]) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showRuleActions(rule: PrivateHistoryRule) {
        AlertDialog.Builder(requireContext())
            .setTitle(rule.name)
            .setItems(resources.getStringArray(R.array.private_history_rule_actions)) { _, which ->
                when (which) {
                    0 -> PrivateHistoryRuleBuilder(requireContext(), rules(), ::refreshRuleSummaries).show(rule)
                    1 -> {
                        rules().addOrReplaceRule(rule.copy(enabled = !rule.enabled))
                        refreshRuleSummaries()
                    }
                    2 -> AlertDialog.Builder(requireContext())
                        .setMessage(R.string.private_history_delete_rule_confirm)
                        .setNegativeButton(android.R.string.cancel, null)
                        .setPositiveButton(R.string.private_history_delete_rule) { _, _ ->
                            rules().deleteRule(rule.id)
                            refreshRuleSummaries()
                        }.show()
                }
            }
            .show()
    }

    private fun showRuleTester() {
        val density = resources.displayMetrics.density
        val layout = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((20 * density).toInt(), 0, (20 * density).toInt(), 0)
        }
        val url = EditText(requireContext()).apply {
            hint = getString(R.string.private_history_tester_url)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        }
        val title = EditText(requireContext()).apply { hint = getString(R.string.private_history_tester_title) }
        layout.addView(url)
        layout.addView(title)
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.private_history_tester_dialog_title)
            .setView(layout)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.private_history_tester_run) { _, _ ->
                val decision = rules().decide(url.text.toString(), title.text.toString())
                val message = when (decision.action) {
                    PrivateHistoryRule.Action.ALLOW -> getString(R.string.private_history_tester_allowed)
                    PrivateHistoryRule.Action.BLOCK -> getString(R.string.private_history_tester_blocked)
                    PrivateHistoryRule.Action.COLLAPSE_TO_ROOT -> getString(
                        R.string.private_history_tester_collapsed,
                        decision.collapsedUri.orEmpty(),
                    )
                    PrivateHistoryRule.Action.FORGET_AFTER ->
                        getString(R.string.private_history_tester_temporary)
                    PrivateHistoryRule.Action.FORGET_ON_RESTART ->
                        getString(R.string.private_history_tester_restart)
                }
                AlertDialog.Builder(requireContext()).setMessage(message)
                    .setPositiveButton(android.R.string.ok, null).show()
            }.show()
    }

    private fun showTemporaryModes() {
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.private_history_temporary_title)
            .setItems(resources.getStringArray(R.array.private_history_temporary_modes)) { _, which ->
                when (which) {
                    0 -> rules().setTemporaryMode(15 * 60_000L)
                    1 -> rules().setTemporaryMode(60 * 60_000L)
                    2 -> rules().setSessionMode(true)
                    3 -> rules().clearTemporaryMode()
                }
                refreshTemporaryMode()
            }.show()
    }

    private fun refreshTemporaryMode() {
        val current = rules()
        findPreference<Preference>(KEY_TEMPORARY_MODE)?.summary = when {
            current.sessionModeActive() -> getString(R.string.private_history_temporary_session_active)
            current.temporaryProtectionActive() -> getString(
                R.string.private_history_temporary_until,
                DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(current.temporaryProtectionUntil())),
            )
            else -> getString(R.string.private_history_temporary_off)
        }
    }


    private fun passwordManager() = PrivatePasswordManager(
        requireComponents.core.passwordsStorage,
        rules(),
    )

    private fun showPasswordManager() {
        val preference = findPreference<Preference>(KEY_PASSWORD_MANAGER) ?: return
        preference.isEnabled = false
        preference.summary = getString(R.string.private_password_manager_loading)
        viewLifecycleOwner.lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { passwordManager().listForManagement() }
            }
            preference.isEnabled = true
            preference.summary = getString(R.string.private_password_manager_summary)
            result.fold(
                onSuccess = { logins ->
                    if (logins.isEmpty()) {
                        Toast.makeText(
                            requireContext(),
                            R.string.private_password_manager_empty,
                            Toast.LENGTH_SHORT,
                        ).show()
                        return@fold
                    }
                    val labels = logins.map { login ->
                        val access = if (PrivatePasswordMetadata.isProtected(login)) {
                            getString(R.string.private_password_manager_private)
                        } else {
                            getString(R.string.private_password_manager_standard)
                        }
                        "${login.origin}\n${login.username.ifBlank { "—" }} · $access"
                    }.toTypedArray()
                    showSensitiveDialog(
                        AlertDialog.Builder(requireContext())
                            .setTitle(R.string.private_password_manager_dialog_title)
                            .setItems(labels) { _, index -> showPasswordActions(logins[index]) }
                            .setNegativeButton(android.R.string.cancel, null)
                            .create(),
                    )
                },
                onFailure = { showToast(R.string.private_password_manager_failed) },
            )
        }
    }

    private fun showPasswordActions(login: Login) {
        val toggle = if (PrivatePasswordMetadata.isProtected(login)) {
            getString(R.string.private_password_manager_make_standard)
        } else {
            getString(R.string.private_password_manager_make_private)
        }
        val actions = arrayOf(
            toggle,
            getString(R.string.private_password_manager_edit),
            getString(R.string.private_password_manager_delete),
        )
        showSensitiveDialog(
            AlertDialog.Builder(requireContext())
                .setTitle(login.origin)
                .setItems(actions) { _, which ->
                    when (which) {
                        0 -> setPasswordProtection(login, !PrivatePasswordMetadata.isProtected(login))
                        1 -> showPasswordEditor(login)
                        2 -> confirmPasswordDeletion(login)
                    }
                }
                .setNegativeButton(android.R.string.cancel, null)
                .create(),
        )
    }

    private fun setPasswordProtection(login: Login, protected: Boolean) {
        if (protected && !PrivatePasswordMetadata.canProtect(login)) {
            Toast.makeText(
                requireContext(),
                R.string.private_password_manager_http_unsupported,
                Toast.LENGTH_LONG,
            ).show()
            return
        }
        viewLifecycleOwner.lifecycleScope.launch {
            runCatching {
                withContext(Dispatchers.IO) { passwordManager().setProtected(login, protected) }
            }.fold(
                onSuccess = {
                    val message = if (protected) {
                        R.string.private_password_manager_private_ready
                    } else {
                        R.string.private_password_manager_updated
                    }
                    Toast.makeText(
                        requireContext(),
                        message,
                        if (protected) Toast.LENGTH_LONG else Toast.LENGTH_SHORT,
                    ).show()
                    showPasswordManager()
                },
                onFailure = { showToast(R.string.private_password_manager_failed) },
            )
        }
    }

    private fun showPasswordEditor(login: Login) {
        val density = resources.displayMetrics.density
        val layout = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((20 * density).toInt(), 0, (20 * density).toInt(), 0)
        }
        val origin = EditText(requireContext()).apply {
            hint = getString(R.string.private_password_manager_site)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            setText(login.origin)
        }
        val username = EditText(requireContext()).apply {
            hint = getString(R.string.private_password_manager_username)
            inputType = InputType.TYPE_CLASS_TEXT
            setText(login.username)
        }
        val password = EditText(requireContext()).apply {
            hint = getString(R.string.private_password_manager_password)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setText(login.password)
        }
        layout.addView(origin)
        layout.addView(username)
        layout.addView(password)

        val dialog = AlertDialog.Builder(requireContext())
            .setTitle(R.string.private_password_manager_edit)
            .setView(layout)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.private_password_manager_save, null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                viewLifecycleOwner.lifecycleScope.launch {
                    runCatching {
                        withContext(Dispatchers.IO) {
                            passwordManager().update(
                                login,
                                origin.text.toString(),
                                username.text.toString(),
                                password.text.toString(),
                            )
                        }
                    }.fold(
                        onSuccess = {
                            dialog.dismiss()
                            Toast.makeText(
                                requireContext(),
                                R.string.private_password_manager_updated,
                                Toast.LENGTH_SHORT,
                            ).show()
                            showPasswordManager()
                        },
                        onFailure = {
                            password.error = getString(R.string.private_password_manager_failed)
                        },
                    )
                }
            }
        }
        showSensitiveDialog(dialog) { password.text.clear() }
    }

    private fun confirmPasswordDeletion(login: Login) {
        showSensitiveDialog(
            AlertDialog.Builder(requireContext())
                .setTitle(login.origin)
                .setMessage(R.string.private_password_manager_delete_confirm)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.private_password_manager_delete) { _, _ ->
                    viewLifecycleOwner.lifecycleScope.launch {
                        runCatching {
                            withContext(Dispatchers.IO) {
                                check(passwordManager().delete(login))
                            }
                        }.fold(
                            onSuccess = {
                                Toast.makeText(
                                    requireContext(),
                                    R.string.private_password_manager_deleted,
                                    Toast.LENGTH_SHORT,
                                ).show()
                                showPasswordManager()
                            },
                            onFailure = { showToast(R.string.private_password_manager_failed) },
                        )
                    }
                }
                .create(),
        )
    }

    private fun showSensitiveDialog(
        dialog: AlertDialog,
        onDismiss: () -> Unit = {},
    ) {
        sensitiveDialog?.dismiss()
        sensitiveDialog = dialog
        dialog.setOnDismissListener {
            onDismiss()
            if (sensitiveDialog === dialog) sensitiveDialog = null
        }
        dialog.show()
    }

    private fun previewCleanup(preference: Preference) {
        preference.isEnabled = false
        preference.summary = getString(R.string.private_history_cleanup_previewing)
        viewLifecycleOwner.lifecycleScope.launch {
            val cleaner = cleaner()
            val preview = withContext(Dispatchers.IO) { cleaner.previewMatchingHistory() }
            preference.isEnabled = true
            preference.summary = getString(R.string.private_history_cleanup_summary)
            if (preview.matching == 0) {
                Toast.makeText(requireContext(), R.string.private_history_cleanup_nothing, Toast.LENGTH_SHORT).show()
                return@launch
            }
            AlertDialog.Builder(requireContext())
                .setTitle(R.string.private_history_cleanup_confirm_title)
                .setMessage(
                    getString(
                        R.string.private_history_cleanup_preview,
                        preview.matching,
                        preview.collapsedToRoot,
                    ),
                )
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.private_history_cleanup_confirm) { _, _ -> performCleanup(preference) }
                .show()
        }
    }

    private fun performCleanup(preference: Preference) {
        preference.isEnabled = false
        preference.summary = getString(R.string.private_history_cleanup_running)
        viewLifecycleOwner.lifecycleScope.launch {
            val removed = withContext(Dispatchers.IO) {
                cleaner().purgeMatchingHistory(includeRestartRules = true)
            }
            preference.isEnabled = true
            preference.summary = getString(R.string.private_history_cleanup_summary)
            refreshStats()
            Toast.makeText(
                requireContext(),
                resources.getQuantityString(R.plurals.private_history_cleanup_result, removed, removed),
                Toast.LENGTH_LONG,
            ).show()
        }
    }

    private fun cleaner(): PrivateHistoryCleaner {
        val core = requireComponents.core
        return PrivateHistoryCleaner(core.historyStorage, rules(), core.privateHistoryStats)
    }

    private fun runSelfTest() {
        val result = PrivateHistorySelfTest.run(requireContext())
        findPreference<Preference>(KEY_SELF_TEST)?.summary = if (result.ok) {
            getString(R.string.private_history_self_test_ok, result.passed, result.total)
        } else {
            getString(R.string.private_history_self_test_failed, result.failures.joinToString())
        }
    }

    private fun refreshStats() {
        val snapshot = requireComponents.core.privateHistoryStats.snapshot()
        findPreference<Preference>(KEY_STATS_COUNTER)?.summary = getString(
            R.string.private_history_stats_summary_v2,
            snapshot.total,
            snapshot.today,
            snapshot.thisWeek,
            snapshot.preventedBeforeWrite,
            snapshot.removedAfterMatch,
            snapshot.removedDuringCleanup,
            snapshot.collapsedTotal,
        )
        findPreference<Preference>(KEY_STATS_MILESTONE)?.summary = getString(
            R.string.private_history_stats_milestone_summary,
            snapshot.nextMilestone,
        )
        findPreference<Preference>(KEY_SHIELD_STATUS)?.summary = when (snapshot.lastEventCode) {
            PrivateHistoryStats.EVENT_PREVENTED -> getString(R.string.private_history_last_prevented)
            PrivateHistoryStats.EVENT_REMOVED_AFTER_MATCH -> getString(R.string.private_history_last_removed)
            PrivateHistoryStats.EVENT_CLEANUP -> getString(R.string.private_history_last_cleanup)
            PrivateHistoryStats.EVENT_COLLAPSED -> getString(R.string.private_history_last_collapsed)
            else -> getString(R.string.private_history_last_none)
        }
    }

    private fun refreshUpdateStatus() {
        val snapshot = FenixPrivacyUpdater.snapshot(requireContext())
        val build = runCatching { PrivateHistoryUpdateInfo.snapshot(requireContext()) }.getOrNull()
        val whenText = snapshot.lastCheckAt.takeIf { it > 0L }?.let {
            DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(it))
        } ?: getString(R.string.fenix_privacy_update_never)
        findPreference<Preference>(KEY_UPDATE_STATUS)?.summary = getString(
            R.string.fenix_privacy_update_status_summary_v2,
            build?.installedVersion ?: "—",
            build?.mozillaVersion ?: "—",
            if (build?.signatureVerified == true) getString(R.string.fenix_privacy_signature_verified)
            else getString(R.string.fenix_privacy_signature_unverified),
            snapshot.lastStatus,
            snapshot.availableVersion.ifBlank { "—" },
            whenText,
        )
        findPreference<Preference>(KEY_RELEASE_NOTES)?.summary = getString(
            R.string.fenix_privacy_release_notes_summary_v2,
            snapshot.upstreamRef.ifBlank { build?.upstreamRef ?: "—" },
        )
    }

    private fun authenticate(onResult: (Boolean) -> Unit) {
        PrivateHistoryAuthenticator.authenticate(
            requireActivity(),
            getString(R.string.private_history_auth_title),
            getString(R.string.private_history_auth_subtitle),
            onResult,
        )
    }

    private fun showPassphraseDialog(title: Int, onPassphrase: (CharArray) -> Unit) {
        val input = EditText(requireContext()).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            hint = getString(R.string.private_history_backup_passphrase_hint)
        }
        val dialog = AlertDialog.Builder(requireContext())
            .setTitle(title)
            .setView(input)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(android.R.string.ok, null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                if (input.text.length < 8) {
                    input.error = getString(R.string.private_history_backup_passphrase_short)
                } else {
                    onPassphrase(input.text.toString().toCharArray())
                    input.text.clear()
                    dialog.dismiss()
                }
            }
        }
        dialog.show()
    }

    private fun showEncryptedQr(passphrase: CharArray) {
        viewLifecycleOwner.lifecycleScope.launch {
            val bitmap = withContext(Dispatchers.Default) {
                runCatching {
                    val bundle = PrivateHistoryBackup.exportEncrypted(requireContext(), passphrase)
                    QRCodeGenerator().generateQRCodeImage(bundle, QR_SIZE, QR_SIZE, requireContext())
                }
            }.getOrElse {
                showToast(R.string.private_history_backup_qr_too_large)
                return@launch
            }
            val padding = (16 * resources.displayMetrics.density).toInt()
            val image = ImageView(requireContext()).apply {
                adjustViewBounds = true
                contentDescription = getString(R.string.private_history_backup_qr_ready)
                setPadding(padding, padding, padding, padding)
                setImageBitmap(bitmap)
            }
            AlertDialog.Builder(requireContext())
                .setTitle(R.string.private_history_backup_qr_export_title)
                .setMessage(R.string.private_history_backup_qr_ready)
                .setView(image)
                .setPositiveButton(android.R.string.ok, null)
                .show()
        }
    }

    private fun importEncryptedBundle(bundle: String, passphrase: CharArray) {
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            runCatching { PrivateHistoryBackup.importEncrypted(requireContext(), bundle, passphrase) }.fold(
                onSuccess = { count ->
                    withContext(Dispatchers.Main) {
                        refreshRuleSummaries()
                        Toast.makeText(
                            requireContext(),
                            getString(R.string.private_history_backup_imported, count),
                            Toast.LENGTH_LONG,
                        ).show()
                    }
                },
                onFailure = { showToast(R.string.private_history_backup_failed) },
            )
        }
    }

    private suspend fun showToast(resId: Int) = withContext(Dispatchers.Main) {
        Toast.makeText(requireContext(), resId, Toast.LENGTH_LONG).show()
    }

    companion object {
        private const val KEY_STATS_COUNTER = "private_history_stats_counter"
        private const val KEY_STATS_MILESTONE = "private_history_stats_milestone"
        private const val KEY_STATS_RESET = "private_history_stats_reset"
        private const val KEY_SHIELD_STATUS = "private_history_shield_status"
        private const val KEY_RULE_BUILDER = "private_history_rule_builder"
        private const val KEY_RULE_MANAGER = "private_history_rule_manager"
        private const val KEY_RULE_TESTER = "private_history_rule_tester"
        private const val KEY_TEMPORARY_MODE = "private_history_temporary_mode"
        private const val KEY_PASSWORD_MANAGER = "private_history_passwords"
        private const val KEY_BACKUP_EXPORT = "private_history_backup_export"
        private const val KEY_BACKUP_IMPORT = "private_history_backup_import"
        private const val KEY_BACKUP_QR_EXPORT = "private_history_backup_qr_export"
        private const val KEY_BACKUP_QR_IMPORT = "private_history_backup_qr_import"
        private const val KEY_SELF_TEST = "private_history_self_test"
        private const val KEY_UPDATE_STATUS = "fenix_privacy_update_status"
        private const val KEY_RELEASE_NOTES = "fenix_privacy_release_notes"
        private const val KEY_CLEANUP = "private_history_cleanup"
        private const val QR_SIZE = 1024
    }
}

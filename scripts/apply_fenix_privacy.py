#!/usr/bin/env python3
"""Apply the small Fenix Privacy downstream patch to a Firefox source checkout.

The script intentionally fails if an upstream anchor moved. A failed patch means CI
stops before signing/releasing, which is safer than publishing a subtly broken browser.
"""
from __future__ import annotations

import argparse
import re
import shutil
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
OVERLAY = ROOT / "overlay"


def read(path: Path) -> str:
    if not path.exists():
        raise SystemExit(f"Missing upstream file: {path}")
    return path.read_text(encoding="utf-8")


def write(path: Path, text: str) -> None:
    path.write_text(text, encoding="utf-8")


def replace_once(text: str, old: str, new: str, label: str) -> str:
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"Patch anchor '{label}' expected once, found {count}")
    return text.replace(old, new, 1)


def copy_overlay(target: Path) -> None:
    for src in OVERLAY.rglob("*"):
        if not src.is_file():
            continue
        rel = src.relative_to(OVERLAY)
        dst = target / rel
        dst.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(src, dst)


def patch_core(target: Path) -> None:
    path = target / "mobile/android/fenix/app/src/main/java/org/mozilla/fenix/components/Core.kt"
    s = read(path)
    s = replace_once(s, "import mozilla.components.feature.session.HistoryDelegate\n", "", "Core HistoryDelegate import")
    anchor = "import org.mozilla.fenix.perf.lazyMonitored\n"
    imports = (
        anchor
        + "import org.mozilla.fenix.privacyhistory.PrivateHistoryActionExecutor\n"
        + "import org.mozilla.fenix.privacyhistory.PrivateHistoryDelegate\n"
        + "import org.mozilla.fenix.privacyhistory.PrivateHistoryPurger\n"
        + "import org.mozilla.fenix.privacyhistory.PrivateHistoryRules\n"
        + "import org.mozilla.fenix.privacyhistory.PrivateHistoryStats\n"
    )
    s = replace_once(s, anchor, imports, "Core privacy imports")
    class_anchor = ") {\n    /**\n     * The browser engine component"
    class_repl = (
        ") {\n"
        "    val privateHistoryRules by lazyMonitored { PrivateHistoryRules(context) }\n"
        "    val privateHistoryStats by lazyMonitored { PrivateHistoryStats(context) }\n"
        "    val privateHistoryPurger by lazyMonitored { PrivateHistoryPurger(lazyHistoryStorage) }\n\n"
        "    val privateHistoryActionExecutor by lazyMonitored {\n"
        "        PrivateHistoryActionExecutor(\n"
        "            lazy { engine },\n"
        "            lazy { store },\n"
        "            lazy { context.components.useCases },\n"
        "            privateHistoryRules,\n"
        "        )\n"
        "    }\n\n"
        "    /**\n     * The browser engine component"
    )
    s = replace_once(s, class_anchor, class_repl, "Core rules property")
    s = replace_once(
        s,
        "historyTrackingDelegate = HistoryDelegate(lazyHistoryStorage),",
        "historyTrackingDelegate = PrivateHistoryDelegate(\n"
        "                lazyHistoryStorage,\n"
        "                privateHistoryRules,\n"
        "                privateHistoryPurger,\n"
        "                privateHistoryStats,\n"
        "                privateHistoryActionExecutor,\n"
        "            ),",
        "Core history delegate",
    )
    s = replace_once(
        s,
        "HistoryMetadataMiddleware(historyMetadataService),",
        "HistoryMetadataMiddleware(\n"
        "                    historyMetadataService,\n"
        "                    shouldSuppress = { url, title, searchTerm ->\n"
        "                        privateHistoryRules.shouldBlockVisit(url, title, searchTerm)\n"
        "                    },\n"
        "                    onSuppressed = { url ->\n"
        "                        privateHistoryStats.recordRemovedAfterMatch(url)\n"
        "                        privateHistoryPurger.purgeAsync(url)\n"
        "                    },\n"
        "                ),",
        "Core history metadata middleware",
    )
    write(path, s)


def patch_history_metadata(target: Path) -> None:
    path = target / "mobile/android/fenix/app/src/main/java/org/mozilla/fenix/historymetadata/HistoryMetadataMiddleware.kt"
    s = read(path)
    constructors = [
        (
            "class HistoryMetadataMiddleware(\n"
            "    private val historyMetadataService: HistoryMetadataService,\n"
            ") : Middleware<BrowserState, BrowserAction> {"
        ),
        (
            "class HistoryMetadataMiddleware(private val historyMetadataService: HistoryMetadataService) :\n"
            "    Middleware<BrowserState, BrowserAction> {"
        ),
    ]
    matches = [candidate for candidate in constructors if s.count(candidate) == 1]
    if len(matches) != 1:
        raise SystemExit(
            "HistoryMetadataMiddleware constructor anchor moved; "
            f"matched {len(matches)} supported forms"
        )
    old = matches[0]
    new = (
        "class HistoryMetadataMiddleware(\n"
        "    private val historyMetadataService: HistoryMetadataService,\n"
        "    private val shouldSuppress: (url: String, title: String?, searchTerm: String?) -> Boolean = { _, _, _ -> false },\n"
        "    private val onSuppressed: (url: String) -> Unit = {},\n"
        ") : Middleware<BrowserState, BrowserAction> {"
    )
    s = replace_once(s, old, new, "HistoryMetadataMiddleware constructor")
    anchor = "        val key = historyMetadataService.createMetadata(tab, searchTerm, referrerUrl)"
    replacement = (
        "        if (shouldSuppress(tab.content.url, tab.content.title, searchTerm)) {\n"
        "            onSuppressed(tab.content.url)\n"
        "            return\n"
        "        }\n\n"
        + anchor
    )
    s = replace_once(s, anchor, replacement, "HistoryMetadata suppression")

    title_anchor = (
        "            is MediaSessionAction.UpdateMediaMetadataAction -> {\n"
        "                store.state.findNormalTab(action.tabId)?.let { tab ->\n"
        "                    createHistoryMetadata(store, tab)\n"
        "                }\n"
        "            }\n"
        "            else -> {\n"
    )
    title_replacement = (
        "            is MediaSessionAction.UpdateMediaMetadataAction -> {\n"
        "                store.state.findNormalTab(action.tabId)?.let { tab ->\n"
        "                    createHistoryMetadata(store, tab)\n"
        "                }\n"
        "            }\n"
        "            is ContentAction.UpdateTitleAction -> {\n"
        "                store.state.findNormalTab(action.sessionId)?.let { tab ->\n"
        "                    if (shouldSuppress(tab.content.url, action.title, tab.content.searchTerms)) {\n"
        "                        onSuppressed(tab.content.url)\n"
        "                    }\n"
        "                }\n"
        "            }\n"
        "            else -> {\n"
    )
    s = replace_once(s, title_anchor, title_replacement, "HistoryMetadata title suppression")
    write(path, s)


def patch_toolbar(target: Path) -> None:
    """Add a native contextual shield button without introducing omnibox commands."""
    path = target / "mobile/android/fenix/app/src/main/java/org/mozilla/fenix/components/toolbar/BrowserToolbarMiddleware.kt"
    s = read(path)

    anchor = "import org.mozilla.fenix.settings.ShortcutType\n"
    imports = (
        "import org.mozilla.fenix.privacyhistory.PrivateHistoryRule\n"
        "import org.mozilla.fenix.privacyhistory.PrivateHistoryRules\n"
        "import org.mozilla.fenix.privacyhistory.PrivateHistoryTabProtection\n"
        "import org.mozilla.fenix.privacyhistory.PrivateHistoryToolbarController\n"
        + anchor
    )
    s = replace_once(s, anchor, imports, "Toolbar privacy imports")

    event_anchor = (
        "    data class ReaderModeClicked(\n"
        "        val isActive: Boolean,\n"
        "    ) : PageEndActionsInteractions(Source.AddressBar.PageEnd)\n"
        "}\n\ninternal object BrowserToolbarTestTags"
    )
    event_replacement = (
        "    data class ReaderModeClicked(\n"
        "        val isActive: Boolean,\n"
        "    ) : PageEndActionsInteractions(Source.AddressBar.PageEnd)\n"
        "    data object PrivacyShieldClicked : PageEndActionsInteractions(Source.AddressBar.PageEnd)\n"
        "    data object PrivacyShieldLongClicked : PageEndActionsInteractions(Source.AddressBar.PageEnd)\n"
        "}\n\ninternal object BrowserToolbarTestTags"
    )
    s = replace_once(s, event_anchor, event_replacement, "Toolbar shield events")

    reader_case = "            is ReaderModeClicked -> {\n"
    privacy_cases = (
        "            is PageEndActionsInteractions.PrivacyShieldClicked -> {\n"
        "                PrivateHistoryToolbarController(\n"
        "                    context = uiContext,\n"
        "                    browserStore = browserStore,\n"
        "                    openStudio = { navController.navigate(R.id.privateHistoryFragment) },\n"
        "                    onChanged = { updateEndPageActions(store) },\n"
        "                ).show()\n"
        "                next(action)\n"
        "            }\n"
        "            is PageEndActionsInteractions.PrivacyShieldLongClicked -> {\n"
        "                PrivateHistoryToolbarController(\n"
        "                    context = uiContext,\n"
        "                    browserStore = browserStore,\n"
        "                    openStudio = { navController.navigate(R.id.privateHistoryFragment) },\n"
        "                    onChanged = { updateEndPageActions(store) },\n"
        "                ).toggleCurrentTab()\n"
        "                next(action)\n"
        "            }\n\n"
        + reader_case
    )
    s = replace_once(s, reader_case, privacy_cases, "Toolbar shield interaction handling")

    page_actions = "        return listOf(\n            ToolbarActionConfig(ToolbarAction.ReaderMode) {"
    s = replace_once(
        s,
        page_actions,
        "        return listOf(\n"
        "            ToolbarActionConfig(ToolbarAction.PrivacyShield),\n"
        "            ToolbarActionConfig(ToolbarAction.ReaderMode) {",
        "Toolbar shield page action",
    )

    observer_anchor = (
        "            distinctUntilChangedBy { it.selectedTab?.content?.url }\n"
        "            .collect {\n"
        "                updateCurrentPageOrigin(store)\n"
    )
    observer_replacement = (
        "            distinctUntilChangedBy { it.selectedTab?.content?.url }\n"
        "            .collect { state ->\n"
        "                state.selectedTab?.let { tab ->\n"
        "                    PrivateHistoryTabProtection.onNavigation(tab.id, tab.parentId, tab.content.url)\n"
        "                }\n"
        "                updateCurrentPageOrigin(store)\n"
        "                updateEndPageActions(store)\n"
    )
    s = replace_once(s, observer_anchor, observer_replacement, "Toolbar tab protection navigation")

    tabs_anchor = (
        "            distinctUntilChangedBy { it.tabs.size }\n"
        "            .collect {\n"
        "                updateEndBrowserActions(store)\n"
    )
    tabs_replacement = (
        "            distinctUntilChangedBy { it.tabs.size }\n"
        "            .collect { state ->\n"
        "                state.tabs.forEach { tab ->\n"
        "                    PrivateHistoryTabProtection.onNavigation(tab.id, tab.parentId, tab.content.url)\n"
        "                }\n"
        "                PrivateHistoryTabProtection.prune(state.tabs.map { it.id }.toSet())\n"
        "                updateEndBrowserActions(store)\n"
    )
    s = replace_once(s, tabs_anchor, tabs_replacement, "Toolbar tab protection cleanup")

    enum_anchor = "        Menu,\n        ReaderMode,\n"
    s = replace_once(s, enum_anchor, "        Menu,\n        PrivacyShield,\n        ReaderMode,\n", "Toolbar shield enum")

    action_anchor = "        ToolbarAction.ReaderMode -> ActionButtonRes(\n"
    action_replacement = (
        "        ToolbarAction.PrivacyShield -> {\n"
        "            val tab = browserStore.state.selectedTab\n"
        "            val decision = tab?.let { PrivateHistoryRules(uiContext).decide(it.content.url, it.content.title) }\n"
        "            ActionButtonRes(\n"
        "                drawableResId = R.drawable.ic_fenix_privacy_shield,\n"
        "                contentDescription = R.string.private_history_toolbar_description,\n"
        "                state = if (tab != null && (\n"
        "                    PrivateHistoryTabProtection.isTabProtected(tab.id) ||\n"
        "                        decision?.action != PrivateHistoryRule.Action.ALLOW\n"
        "                )) ActionButton.State.ACTIVE else ActionButton.State.DEFAULT,\n"
        "                onClick = PageEndActionsInteractions.PrivacyShieldClicked,\n"
        "                onLongClick = PageEndActionsInteractions.PrivacyShieldLongClicked,\n"
        "                testTag = \"browser.toolbar.fenix.privacy.shield\",\n"
        "            )\n"
        "        }\n\n"
        + action_anchor
    )
    s = replace_once(s, action_anchor, action_replacement, "Toolbar shield action")
    write(path, s)


def patch_settings(target: Path) -> None:
    path = target / "mobile/android/fenix/app/src/main/java/org/mozilla/fenix/settings/SettingsFragment.kt"
    s = read(path)
    navigation_anchors = [
        "        val directions: NavDirections? = when (preference.key) {\n",
        "        val directions: NavDirections? =\n",
    ]
    matches = [candidate for candidate in navigation_anchors if s.count(candidate) == 1]
    if len(matches) != 1:
        raise SystemExit(
            "Settings navigation anchor moved; "
            f"matched {len(matches)} supported forms"
        )
    anchor = matches[0]
    replacement = (
        "        if (preference.key == resources.getString(R.string.pref_key_private_history_rules)) {\n"
        "            findNavController().navigate(R.id.privateHistoryFragment)\n"
        "            return true\n"
        "        }\n\n"
        + anchor
    )
    s = replace_once(s, anchor, replacement, "Settings navigation hook")
    write(path, s)

    path = target / "mobile/android/fenix/app/src/main/res/xml/preferences.xml"
    s = read(path)
    anchor = "        <androidx.preference.Preference\n            android:key=\"@string/pref_key_private_browsing\""
    pref = (
        "        <androidx.preference.Preference\n"
        "            android:key=\"@string/pref_key_private_history_rules\"\n"
        "            app:iconSpaceReserved=\"false\"\n"
        "            android:title=\"@string/private_history_title\"\n"
        "            android:summary=\"@string/private_history_settings_summary\" />\n\n"
        + anchor
    )
    s = replace_once(s, anchor, pref, "Preferences privacy entry")
    write(path, s)

    path = target / "mobile/android/fenix/app/src/main/res/navigation/nav_graph.xml"
    s = read(path)
    fragment = (
        "\n    <fragment\n"
        "        android:id=\"@+id/privateHistoryFragment\"\n"
        "        android:name=\"org.mozilla.fenix.settings.PrivateHistoryFragment\" />\n"
    )
    s = replace_once(s, "\n</navigation>", fragment + "\n</navigation>", "Navigation graph destination")
    write(path, s)


def patch_application(target: Path) -> None:
    path = target / "mobile/android/fenix/app/src/main/java/org/mozilla/fenix/FenixApplication.kt"
    s = read(path)
    anchor = "import org.mozilla.fenix.push.WebPushEngineIntegration\n"
    imports = (
        anchor
        + "import org.mozilla.fenix.privacyhistory.FenixPrivacyUpdater\n"
        + "import org.mozilla.fenix.privacyhistory.PrivateHistoryMaintenanceWorker\n"
    )
    s = replace_once(s, anchor, imports, "FenixApplication privacy imports")
    anchor = "            setupPostMegazord()\n"
    replacement = (
        anchor
        + "            PrivateHistoryMaintenanceWorker.schedule(applicationContext)\n"
        + "            FenixPrivacyUpdater.schedule(applicationContext)\n"
    )
    s = replace_once(s, anchor, replacement, "FenixApplication maintenance scheduling")
    write(path, s)


def patch_manifest(target: Path) -> None:
    path = target / "mobile/android/fenix/app/src/main/AndroidManifest.xml"
    s = read(path)
    if "android.permission.REQUEST_INSTALL_PACKAGES" not in s:
        permission = (
            '    <uses-permission android:name="android.permission.REQUEST_INSTALL_PACKAGES" />\n'
            '    <uses-permission android:name="android.permission.USE_BIOMETRIC" />\n\n'
        )
        s = replace_once(s, "    <application\n", permission + "    <application\n", "Updater install permission")
    receiver = (
        "\n        <activity\n"
        "            android:name=\".privacyhistory.PrivateHistoryQuickRuleActivity\"\n"
        "            android:exported=\"true\"\n"
        "            android:excludeFromRecents=\"true\"\n"
        "            android:label=\"@string/private_history_quick_title\">\n"
        "            <intent-filter>\n"
        "                <action android:name=\"android.intent.action.SEND\" />\n"
        "                <category android:name=\"android.intent.category.DEFAULT\" />\n"
        "                <data android:mimeType=\"text/plain\" />\n"
        "            </intent-filter>\n"
        "            <intent-filter>\n"
        "                <action android:name=\"android.intent.action.PROCESS_TEXT\" />\n"
        "                <category android:name=\"android.intent.category.DEFAULT\" />\n"
        "                <data android:mimeType=\"text/plain\" />\n"
        "            </intent-filter>\n"
        "        </activity>\n"
        "\n        <service\n"
        "            android:name=\".privacyhistory.PrivateHistoryTileService\"\n"
        "            android:exported=\"true\"\n"
        "            android:icon=\"@drawable/ic_status_logo\"\n"
        "            android:label=\"@string/private_history_tile_label\"\n"
        "            android:permission=\"android.permission.BIND_QUICK_SETTINGS_TILE\">\n"
        "            <intent-filter>\n"
        "                <action android:name=\"android.service.quicksettings.action.QS_TILE\" />\n"
        "            </intent-filter>\n"
        "        </service>\n"
        "\n        <receiver\n"
        "            android:name=\".privacyhistory.FenixPrivacyDownloadReceiver\"\n"
        "            android:exported=\"false\">\n"
        "            <intent-filter>\n"
        "                <action android:name=\"android.intent.action.DOWNLOAD_COMPLETE\" />\n"
        "            </intent-filter>\n"
        "        </receiver>\n"
    )
    s = replace_once(s, "\n    </application>", receiver + "\n    </application>", "Updater receiver")
    write(path, s)


def patch_gradle(target: Path) -> None:
    path = target / "mobile/android/fenix/app/build.gradle"
    s = read(path)
    s = replace_once(s, 'applicationId "org.mozilla"', 'applicationId "io.github.astropuzzo.fenixprivacy"', "applicationId")

    release_pattern = re.compile(r"(\n        release releaseTemplate >> \{)(.*?)(\n        \}\n        benchmark releaseTemplate)", re.S)
    m = release_pattern.search(s)
    if not m:
        raise SystemExit("Could not locate release buildType")
    body = m.group(2)
    if 'applicationIdSuffix ".firefox"' not in body:
        raise SystemExit("Release applicationIdSuffix anchor missing")
    body = body.replace('            applicationIdSuffix ".firefox"\n', '', 1)
    body = body.replace('def deepLinkSchemeValue = "fenix"', 'def deepLinkSchemeValue = "fenix-privacy"', 1)
    s = s[:m.start()] + m.group(1) + body + m.group(3) + s[m.end():]

    marker = "        if (buildType in ['nightly', 'beta', 'release', 'benchmark']) {"
    custom = (
        "        def privacyVersionCode = System.getenv(\"FENIX_PRIVACY_VERSION_CODE\")\n"
        "        def privacyVersionName = System.getenv(\"FENIX_PRIVACY_VERSION_NAME\")\n"
        "        if (buildType == 'release' && privacyVersionCode && privacyVersionName) {\n"
        "            variant.outputs.each { output ->\n"
        "                output.versionName.set(privacyVersionName)\n"
        "                output.versionCode.set(Integer.parseInt(privacyVersionCode))\n"
        "            }\n"
        "        } else if (buildType in ['nightly', 'beta', 'release', 'benchmark']) {"
    )
    s = replace_once(s, marker, custom, "Custom versioning")
    s = replace_once(s, "buildConfigField 'boolean', 'CRASH_REPORTING', 'true'", "buildConfigField 'boolean', 'CRASH_REPORTING', 'false'", "Crash reporting off")
    expected_cert = read(ROOT / "SIGNING_CERT_SHA256").strip().replace(":", "").lower()
    privacy_build_fields = (
        "buildConfigField 'boolean', 'TELEMETRY', 'false'\n"
        "    def fenixPrivacyUpstreamRef = System.getenv(\"FENIX_PRIVACY_UPSTREAM_REF\") ?: \"unknown\"\n"
        "    buildConfigField 'String', 'FENIX_PRIVACY_UPSTREAM_REF', '\"' + fenixPrivacyUpstreamRef + '\"'\n"
        f"    buildConfigField 'String', 'FENIX_PRIVACY_SIGNING_CERT_SHA256', '\"{expected_cert}\"'"
    )
    s = replace_once(
        s,
        "buildConfigField 'boolean', 'TELEMETRY', 'true'",
        privacy_build_fields,
        "Telemetry off and privacy build metadata",
    )
    write(path, s)


def patch_strong_authentication(target: Path) -> None:
    """Require a fresh strong biometric; an already-known device PIN is never sufficient."""
    path = target / (
        "mobile/android/fenix/app/src/main/java/org/mozilla/fenix/settings/logins/ui/"
        "BiometricAuthenticationUtils.kt"
    )
    s = read(path)
    s = replace_once(
        s,
        "import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK\n"
        "import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL\n",
        "import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG\n",
        "Saved logins strong biometric imports",
    )
    s = replace_once(
        s,
        ".setAllowedAuthenticators(BIOMETRIC_WEAK or DEVICE_CREDENTIAL)",
        ".setAllowedAuthenticators(BIOMETRIC_STRONG)\n"
        "            .setNegativeButtonText(activity.getString(android.R.string.cancel))",
        "Saved logins strong biometric prompt",
    )
    write(path, s)

    path = target / (
        "mobile/android/fenix/app/src/test/java/org/mozilla/fenix/settings/biometric/"
        "BiometricPromptFeatureTest.kt"
    )
    s = read(path)
    s = replace_once(
        s,
        "import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK\n"
        "import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL\n",
        "import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG\n",
        "Biometric feature test strong import",
    )
    s = replace_once(
        s,
        "import org.mozilla.fenix.settings.biometric.ext.isBiometricHardwareAvailable\n"
        "import org.mozilla.fenix.settings.biometric.ext.isEnrolled\n",
        "",
        "Biometric feature test obsolete helpers",
    )
    s = replace_once(
        s,
        "        verify { manager.isEnrolled() }\n"
        "        verify { manager.isBiometricHardwareAvailable() }",
        "        verify(exactly = 2) { manager.canAuthenticate(BIOMETRIC_STRONG) }",
        "Biometric feature availability test",
    )
    s = replace_once(
        s,
        "        assertEquals(BIOMETRIC_WEAK or DEVICE_CREDENTIAL, promptInfo.captured.allowedAuthenticators)\n"
        "        assertEquals(\"test\", promptInfo.captured.title)",
        "        assertEquals(BIOMETRIC_STRONG, promptInfo.captured.allowedAuthenticators)\n"
        "        assertEquals(testContext.getString(android.R.string.cancel), promptInfo.captured.negativeButtonText)\n"
        "        assertEquals(\"test\", promptInfo.captured.title)",
        "Biometric feature prompt test",
    )
    s = replace_once(
        s,
        "        callback.onAuthenticationFailed()\n\n"
        "        assertEquals(2, authFailureCount)",
        "        callback.onAuthenticationFailed()\n\n"
        "        assertEquals(1, authFailureCount)",
        "Biometric feature non-terminal mismatch test",
    )
    write(path, s)


def patch_login_data_gate(target: Path) -> None:
    """Keep real login records out of Gecko and ordinary lists until the origin-bound unlock."""
    path = target / (
        "mobile/android/android-components/components/service/sync-logins/src/main/java/"
        "mozilla/components/service/sync/logins/GeckoLoginStorageDelegate.kt"
    )
    s = read(path)
    s = replace_once(
        s,
        "    private val isLoginAutofillEnabled: () -> Boolean = { false },\n"
        "    private val logger: Logger = Logger(\"GeckoLoginStorageDelegate\"),",
        "    private val isLoginAutofillEnabled: () -> Boolean = { false },\n"
        "    private val requiresAuthentication: (domain: String) -> Boolean = { false },\n"
        "    private val lockedLoginFactory: (domain: String) -> Login? = { null },\n"
        "    private val isLockedLogin: (login: Login) -> Boolean = { false },\n"
        "    private val prepareLoginForSave: suspend (LoginsStorage, LoginEntry) -> LoginEntry =\n"
        "        { _, login -> login },\n"
        "    private val logger: Logger = Logger(\"GeckoLoginStorageDelegate\"),",
        "Gecko login privacy callbacks",
    )
    s = replace_once(
        s,
        "    override fun onLoginUsed(login: Login) {\n"
        "        scope.launch {",
        "    override fun onLoginUsed(login: Login) {\n"
        "        if (isLockedLogin(login)) return\n"
        "        scope.launch {",
        "Gecko decoy login touch suppression",
    )
    s = replace_once(
        s,
        "        if (!isLoginAutofillEnabled()) {\n"
        "            return CompletableDeferred(listOf())\n"
        "        }\n"
        "        return scope.async {",
        "        if (!isLoginAutofillEnabled()) {\n"
        "            return CompletableDeferred(listOf())\n"
        "        }\n"
        "        if (requiresAuthentication(domain)) {\n"
        "            return CompletableDeferred(listOfNotNull(lockedLoginFactory(domain)))\n"
        "        }\n"
        "        return scope.async {",
        "Gecko pre-authentication decoy",
    )
    s = replace_once(
        s,
        "        scope.launch {\n"
        "            try {\n"
        "                loginStorage.value.addOrUpdate(login)",
        "        scope.launch {\n"
        "            try {\n"
        "                val storage = loginStorage.value\n"
        "                storage.addOrUpdate(prepareLoginForSave(storage, login))",
        "Gecko preserve private metadata on password save",
    )
    write(path, s)

    path = target / "mobile/android/fenix/app/src/main/java/org/mozilla/fenix/gecko/GeckoProvider.kt"
    s = read(path)
    anchor = "import org.mozilla.fenix.nimbus.FxNimbus\n"
    s = replace_once(
        s,
        anchor,
        anchor +
        "import org.mozilla.fenix.privacyhistory.PrivatePasswordAccess\n"
        "import org.mozilla.fenix.privacyhistory.PrivatePasswordMetadata\n",
        "Gecko private password import",
    )
    s = replace_once(
        s,
        "                isLoginAutofillEnabled = { context.components.settings.shouldAutofillLogins },\n"
        "            ),",
        "                isLoginAutofillEnabled = { context.components.settings.shouldAutofillLogins },\n"
        "                requiresAuthentication = { true },\n"
        "                lockedLoginFactory = PrivatePasswordAccess::lockedLogin,\n"
        "                isLockedLogin = PrivatePasswordAccess::isLockedLogin,\n"
        "                prepareLoginForSave = { storage, login ->\n"
        "                    PrivatePasswordMetadata.preserveProtection(\n"
        "                        storage.findLoginToUpdate(login),\n"
        "                        login,\n"
        "                    )\n"
        "                },\n"
        "            ),",
        "Gecko origin-bound password gate",
    )
    write(path, s)

    path = target / (
        "mobile/android/android-components/components/feature/autofill/src/main/java/"
        "mozilla/components/feature/autofill/AutofillConfiguration.kt"
    )
    s = read(path)
    s = replace_once(
        s,
        "import mozilla.components.concept.storage.LoginsStorage\n",
        "import mozilla.components.concept.storage.Login\n"
        "import mozilla.components.concept.storage.LoginsStorage\n",
        "Autofill login filter import",
    )
    s = replace_once(
        s,
        "    val applicationName: String,\n"
        "    val httpClient: Client,",
        "    val applicationName: String,\n"
        "    val httpClient: Client,\n"
        "    val loginFilter: (Login) -> Boolean = { true },",
        "Autofill login filter configuration",
    )
    write(path, s)

    path = target / (
        "mobile/android/android-components/components/feature/autofill/src/main/java/"
        "mozilla/components/feature/autofill/handler/FillRequestHandler.kt"
    )
    s = read(path)
    s = replace_once(
        s,
        "            .getByBaseDomain(lookupDomain)\n"
        "            .take(min(MAX_LOGINS, maxSuggestionCount))",
        "            .getByBaseDomain(lookupDomain)\n"
        "            .filter(configuration.loginFilter)\n"
        "            .take(min(MAX_LOGINS, maxSuggestionCount))",
        "Autofill origin results privacy filter",
    )
    s = replace_once(
        s,
        "        val logins = configuration.storage.getByBaseDomain(lookupDomain)\n",
        "        val logins = configuration.storage.getByBaseDomain(lookupDomain)\n"
        "            .filter(configuration.loginFilter)\n",
        "Autofill confirmation privacy filter",
    )
    old = (
        "        val logins = configuration.storage\n"
        "            .getByBaseDomain(lookupDomain)\n"
        "            .filter(configuration.loginFilter)\n"
        "            .take(min(MAX_LOGINS, maxSuggestionCount))\n\n"
        "        return if (!configuration.lock.keepUnlocked() && !forceUnlock) {\n"
        "            AuthFillResponseBuilder(parsedStructure, maxSuggestionCount)\n"
        "        } else {\n"
        "            emitAutofillRequestFact(hasLogins = logins.isNotEmpty(), needsConfirmation)\n"
        "            LoginFillResponseBuilder(parsedStructure, logins, needsConfirmation)\n"
        "        }"
    )
    new = (
        "        if (!configuration.lock.keepUnlocked() && !forceUnlock) {\n"
        "            return AuthFillResponseBuilder(parsedStructure, maxSuggestionCount)\n"
        "        }\n\n"
        "        // Do not even read credential metadata until the fresh authentication succeeds.\n"
        "        val logins = configuration.storage\n"
        "            .getByBaseDomain(lookupDomain)\n"
        "            .filter(configuration.loginFilter)\n"
        "            .take(min(MAX_LOGINS, maxSuggestionCount))\n"
        "        emitAutofillRequestFact(hasLogins = logins.isNotEmpty(), needsConfirmation)\n"
        "        return LoginFillResponseBuilder(parsedStructure, logins, needsConfirmation)"
    )
    s = replace_once(s, old, new, "Autofill defer metadata lookup until authentication")
    write(path, s)

    path = target / (
        "mobile/android/android-components/components/feature/autofill/src/test/java/"
        "mozilla/components/feature/autofill/handler/FillRequestHandlerTest.kt"
    )
    s = read(path)
    s = replace_once(
        s,
        "    val builder = handler.handle(structure)\n",
        "    val builder = handler.handle(structure, forceUnlock = true)\n",
        "Autofill response tests authenticate explicitly",
    )
    write(path, s)

    path = target / (
        "mobile/android/android-components/components/feature/autofill/src/main/java/"
        "mozilla/components/feature/autofill/ui/AbstractAutofillSearchActivity.kt"
    )
    s = read(path)
    s = replace_once(
        s,
        "            configuration.storage.list()",
        "            configuration.storage.list().filter(configuration.loginFilter)",
        "Autofill global search privacy filter",
    )
    write(path, s)

    path = target / "mobile/android/fenix/app/src/main/java/org/mozilla/fenix/components/Components.kt"
    s = read(path)
    s = replace_once(
        s,
        "            storage = core.passwordsStorage,\n"
        "            publicSuffixList = publicSuffixList,",
        "            storage = core.passwordsStorage,\n"
        "            loginFilter = { login ->\n"
        "                !org.mozilla.fenix.privacyhistory.PrivatePasswordAccess.isProtected(\n"
        "                    login,\n"
        "                    core.privateHistoryRules,\n"
        "                )\n"
        "            },\n"
        "            publicSuffixList = publicSuffixList,",
        "Fenix external autofill private-login filter",
    )
    write(path, s)

    path = target / (
        "mobile/android/fenix/app/src/main/java/org/mozilla/fenix/settings/logins/ui/"
        "LoginsMiddleware.kt"
    )
    s = read(path)
    s = replace_once(
        s,
        "import mozilla.components.concept.storage.LoginEntry\n",
        "import mozilla.components.concept.storage.Login\n"
        "import mozilla.components.concept.storage.LoginEntry\n",
        "Saved login visibility type import",
    )
    s = replace_once(
        s,
        "    private val clipboardManager: ClipboardManager?,\n"
        ") : Middleware<LoginsState, LoginsAction> {",
        "    private val clipboardManager: ClipboardManager?,\n"
        "    private val isLoginVisible: (Login) -> Boolean = { true },\n"
        ") : Middleware<LoginsState, LoginsAction> {",
        "Saved login visibility predicate",
    )
    s = replace_once(
        s,
        "        loginsStorage.list().forEach { login ->\n"
        "            loginItems.add(",
        "        loginsStorage.list().filter(isLoginVisible).forEach { login ->\n"
        "            loginItems.add(",
        "Saved login private-tier filtering",
    )
    write(path, s)

    path = target / (
        "mobile/android/fenix/app/src/main/java/org/mozilla/fenix/settings/logins/fragment/"
        "SavedLoginsFragment.kt"
    )
    s = read(path)
    s = replace_once(
        s,
        "            val navController = findNavController()\n\n"
        "            val store by fragmentStore(",
        "            val navController = findNavController()\n"
        "            val privateHistoryRules = requireComponents.core.privateHistoryRules\n\n"
        "            val store by fragmentStore(",
        "Saved logins private rules capture",
    )
    s = replace_once(
        s,
        "                            clipboardManager = requireContext().getSystemService(),\n"
        "                        ),",
        "                            clipboardManager = requireContext().getSystemService(),\n"
        "                            isLoginVisible = { login ->\n"
        "                                !org.mozilla.fenix.privacyhistory.PrivatePasswordAccess\n"
        "                                    .isProtected(login, privateHistoryRules)\n"
        "                            },\n"
        "                        ),",
        "Saved logins ordinary-list privacy filter",
    )
    write(path, s)

    path = target / (
        "mobile/android/fenix/app/src/main/java/org/mozilla/fenix/settings/logins/ui/"
        "BiometricAuthenticationHelper.kt"
    )
    s = read(path)
    old = (
        "    if (DefaultBiometricUtils.canUseBiometricAuthentication(activity = activity)) {\n"
        "        ShowBiometricAuthenticationDialog(\n"
        "            title = title,\n"
        "            activity = activity,\n"
        "            onAuthSuccess = onAuthSuccess,\n"
        "            onAuthFailure = onAuthFailure,\n"
        "        )\n"
        "    } else if (DefaultBiometricUtils.canUsePinVerification(activity = activity)) {\n"
        "        ShowPinVerificationDialog(\n"
        "            title = title,\n"
        "            activity = activity,\n"
        "            onAuthSuccess = onAuthSuccess,\n"
        "            onAuthFailure = onAuthFailure,\n"
        "        )\n"
        "    } else {\n"
        "        ShowPinWarningDialog(\n"
        "            activity = activity,\n"
        "            onAuthSuccess = onAuthSuccess,\n"
        "        )\n"
        "    }"
    )
    new = (
        "    if (DefaultBiometricUtils.canUseBiometricAuthentication(activity = activity)) {\n"
        "        ShowBiometricAuthenticationDialog(\n"
        "            title = title,\n"
        "            activity = activity,\n"
        "            onAuthSuccess = onAuthSuccess,\n"
        "            onAuthFailure = onAuthFailure,\n"
        "        )\n"
        "    } else {\n"
        "        // Fail closed: the phone's device PIN must not unlock Firefox secrets.\n"
        "        onAuthFailure()\n"
        "    }"
    )
    s = replace_once(s, old, new, "Saved logins remove device credential fallback")
    write(path, s)

    path = target / (
        "mobile/android/fenix/app/src/main/java/org/mozilla/fenix/settings/biometric/"
        "BiometricPromptFeature.kt"
    )
    s = read(path)
    s = replace_once(
        s,
        "import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK\n"
        "import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL\n",
        "import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG\n",
        "Browser strong biometric imports",
    )
    s = replace_once(
        s,
        "import org.mozilla.fenix.settings.biometric.ext.isBiometricHardwareAvailable\n"
        "import org.mozilla.fenix.settings.biometric.ext.isEnrolled\n",
        "",
        "Browser obsolete biometric helpers",
    )
    s = replace_once(
        s,
        ".setAllowedAuthenticators(BIOMETRIC_WEAK or DEVICE_CREDENTIAL)",
        ".setAllowedAuthenticators(BIOMETRIC_STRONG)\n"
        "            .setNegativeButtonText(context.getString(android.R.string.cancel))",
        "Browser strong biometric prompt",
    )
    s = replace_once(
        s,
        "        override fun onAuthenticationFailed() {\n"
        "            logger.error(\"onAuthenticationFailed\")\n"
        "            onAuthFailure.invoke()\n"
        "        }",
        "        override fun onAuthenticationFailed() {\n"
        "            // A non-matching scan is not terminal; Android keeps the prompt open.\n"
        "            logger.error(\"onAuthenticationFailed\")\n"
        "        }",
        "Browser biometric non-terminal mismatch",
    )
    s = replace_once(
        s,
        "        fun canUseFeature(manager: BiometricManager): Boolean =\n"
        "            manager.isBiometricHardwareAvailable() && manager.isEnrolled()",
        "        fun canUseFeature(manager: BiometricManager): Boolean =\n"
        "            manager.canAuthenticate(BIOMETRIC_STRONG) == BiometricManager.BIOMETRIC_SUCCESS",
        "Browser strong biometric availability",
    )
    write(path, s)

    path = target / (
        "mobile/android/android-components/components/feature/autofill/src/main/java/"
        "mozilla/components/feature/autofill/authenticator/BiometricAuthenticator.kt"
    )
    s = read(path)
    s = replace_once(
        s,
        "private const val AUTHENTICATORS =\n"
        "    BiometricManager.Authenticators.BIOMETRIC_WEAK or BiometricManager.Authenticators.DEVICE_CREDENTIAL",
        "private const val AUTHENTICATORS = BiometricManager.Authenticators.BIOMETRIC_STRONG",
        "Android autofill strong biometric",
    )
    s = replace_once(
        s,
        "            .setAllowedAuthenticators(AUTHENTICATORS)\n"
        "            .setTitle(",
        "            .setAllowedAuthenticators(AUTHENTICATORS)\n"
        "            .setNegativeButtonText(activity.getString(android.R.string.cancel))\n"
        "            .setTitle(",
        "Android autofill biometric cancel action",
    )
    write(path, s)

    path = target / (
        "mobile/android/android-components/components/feature/autofill/src/main/java/"
        "mozilla/components/feature/autofill/lock/AutofillLock.kt"
    )
    s = read(path)
    old = (
        "    fun keepUnlocked(): Boolean {\n"
        "        return if (isUnlocked()) {\n"
        "            unlock()\n"
        "            true\n"
        "        } else {\n"
        "            false\n"
        "        }\n"
        "    }"
    )
    new = (
        "    fun keepUnlocked(): Boolean {\n"
        "        // Fenix Privacy requires a new biometric for every autofill request.\n"
        "        return false\n"
        "    }"
    )
    s = replace_once(s, old, new, "Android autofill immediate relock")
    write(path, s)


def patch_origin_bound_login_picker(target: Path) -> None:
    """Render one neutral action and release only current-origin records after authentication."""
    path = target / (
        "mobile/android/android-components/components/feature/prompts/src/main/java/"
        "mozilla/components/feature/prompts/concept/SelectablePromptView.kt"
    )
    s = read(path)
    s = replace_once(
        s,
        "        fun onOptionSelect(option: T)\n\n"
        "        /**\n"
        "         * Called when the user invokes the option to manage the list of options.",
        "        fun onOptionSelect(option: T)\n\n"
        "        /**\n"
        "         * Called when the user deliberately long-presses an option. Existing prompts\n"
        "         * retain normal click behaviour unless they opt into a distinct private path.\n"
        "         */\n"
        "        fun onOptionLongSelect(option: T) = onOptionSelect(option)\n\n"
        "        /**\n"
        "         * Called when the user invokes the option to manage the list of options.",
        "Selectable prompt deliberate long press",
    )
    write(path, s)

    path = target / (
        "mobile/android/android-components/components/feature/prompts/src/main/java/"
        "mozilla/components/feature/prompts/login/LoginPickerView.kt"
    )
    s = read(path)
    s = replace_once(
        s,
        "import androidx.compose.foundation.clickable\n",
        "import androidx.compose.foundation.clickable\n"
        "import androidx.compose.foundation.combinedClickable\n",
        "Login picker long-press import",
    )
    s = replace_once(
        s,
        "    onListItemClicked: () -> Unit,\n"
        ") {",
        "    onListItemClicked: () -> Unit,\n"
        "    onListItemLongClicked: () -> Unit,\n"
        ") {",
        "Login picker item long-press callback",
    )
    s = replace_once(
        s,
        "            .padding(start = 64.dp, top = 8.dp, end = 8.dp, bottom = 8.dp)\n"
        "            .clickable { onListItemClicked() },",
        "            .padding(start = 64.dp, top = 8.dp, end = 8.dp, bottom = 8.dp)\n"
        "            .combinedClickable(\n"
        "                onClick = onListItemClicked,\n"
        "                onLongClick = onListItemLongClicked,\n"
        "            ),",
        "Login picker item deliberate long press",
    )
    s = replace_once(
        s,
        "    onLoginSelected: (Login) -> Unit,\n"
        "    onManagePasswordClicked: () -> Unit,",
        "    onLoginSelected: (Login) -> Unit,\n"
        "    onLoginLongSelected: (Login) -> Unit = onLoginSelected,\n"
        "    onManagePasswordClicked: () -> Unit,",
        "Login picker long-selection parameter",
    )
    s = replace_once(
        s,
        "                        onListItemClicked = { onLoginSelected(login) },\n"
        "                    )",
        "                        onListItemClicked = { onLoginSelected(login) },\n"
        "                        onListItemLongClicked = { onLoginLongSelected(login) },\n"
        "                    )",
        "Login picker long-selection wiring",
    )
    write(path, s)

    path = target / (
        "mobile/android/android-components/components/feature/prompts/src/main/java/"
        "mozilla/components/feature/prompts/login/LoginSelectBar.kt"
    )
    s = read(path)
    s = replace_once(
        s,
        "                onLoginSelected = { selectablePromptListener?.onOptionSelect(it) },\n"
        "                onManagePasswordClicked = { selectablePromptListener?.onManageOptions() },",
        "                onLoginSelected = { selectablePromptListener?.onOptionSelect(it) },\n"
        "                onLoginLongSelected = { selectablePromptListener?.onOptionLongSelect(it) },\n"
        "                onManagePasswordClicked = { selectablePromptListener?.onManageOptions() },",
        "Login select bar private gesture wiring",
    )
    write(path, s)

    path = target / (
        "mobile/android/android-components/components/feature/prompts/src/main/java/"
        "mozilla/components/feature/prompts/login/LoginDelegate.kt"
    )
    s = read(path)
    s = replace_once(
        s,
        "    val onManageLogins: () -> Unit\n"
        "        get() = {}\n",
        "    val onManageLogins: () -> Unit\n"
        "        get() = {}\n\n"
        "    /** Whether [login] is the transient metadata-free authentication action. */\n"
        "    val isLockedLogin: (login: Login) -> Boolean\n"
        "        get() = { false }\n\n"
        "    /** Neutral text rendered instead of a saved origin or username. */\n"
        "    val lockedLoginLabel: String\n"
        "        get() = \"\"\n\n"
        "    /** Starts a fresh strong biometric without revealing whether a login exists. */\n"
        "    val onUnlockLogins: () -> Unit\n"
        "        get() = {}\n\n"
        "    /** Fetches only current-origin standard or private records after authentication. */\n"
        "    val fetchUnlockedLogins: (\n"
        "        origin: String,\n"
        "        privateAccess: Boolean,\n"
        "        onResult: (List<Login>) -> Unit,\n"
        "    ) -> Unit\n"
        "        get() = { _, _, onResult -> onResult(emptyList()) }\n",
        "Login delegate origin-bound unlock callbacks",
    )
    write(path, s)

    path = target / (
        "mobile/android/android-components/components/feature/prompts/src/main/java/"
        "mozilla/components/feature/prompts/login/LoginPicker.kt"
    )
    s = read(path)
    old = (
        "internal class LoginPicker(\n"
        "    private val store: BrowserStore,\n"
        "    private val loginSelectBar: AutocompletePrompt<Login>,\n"
        "    private val manageLoginsCallback: () -> Unit = {},\n"
        "    private var sessionId: String? = null,\n"
        ") : SelectablePromptView.Listener<Login> {\n\n"
        "    init {\n"
        "        loginSelectBar.selectablePromptListener = this\n"
        "    }\n\n"
        "    internal fun handleSelectLoginRequest(request: PromptRequest.SelectLoginPrompt) {\n"
        "        emitLoginAutofillShownFact()\n"
        "        loginSelectBar.showPrompt()\n"
        "        loginSelectBar.populate(request.logins)\n"
        "    }\n\n"
        "    override fun onOptionSelect(option: Login) {\n"
        "        store.consumePromptFrom<PromptRequest.SelectLoginPrompt>(sessionId) {\n"
        "            it.onConfirm(option)\n"
        "        }\n"
        "        emitLoginAutofillPerformedFact()\n"
        "        loginSelectBar.hidePrompt()\n"
        "    }\n\n"
        "    override fun onManageOptions() {\n"
        "        manageLoginsCallback.invoke()\n"
        "        dismissCurrentLoginSelect()\n"
        "    }\n\n"
        "    @Suppress(\"TooGenericExceptionCaught\")\n"
        "    fun dismissCurrentLoginSelect(promptRequest: PromptRequest.SelectLoginPrompt? = null) {\n"
        "        try {\n"
        "            if (promptRequest != null) {\n"
        "                promptRequest.onDismiss()\n"
        "                sessionId?.let {\n"
        "                    store.dispatch(ContentAction.ConsumePromptRequestAction(it, promptRequest))\n"
        "                }\n"
        "                loginSelectBar.hidePrompt()\n"
        "                return\n"
        "            }\n\n"
        "            store.consumePromptFrom<PromptRequest.SelectLoginPrompt>(sessionId) {\n"
        "                it.onDismiss()\n"
        "            }\n"
        "        } catch (e: RuntimeException) {\n"
        "            Logger.error(\"Can't dismiss this login select prompt\", e)\n"
        "        }\n"
        "        emitLoginAutofillDismissedFact()\n"
        "        loginSelectBar.hidePrompt()\n"
        "    }\n"
        "}"
    )
    new = (
        "internal class LoginPicker(\n"
        "    private val store: BrowserStore,\n"
        "    private val loginSelectBar: AutocompletePrompt<Login>,\n"
        "    private val manageLoginsCallback: () -> Unit = {},\n"
        "    private var sessionId: String? = null,\n"
        "    private val isLockedLogin: (Login) -> Boolean = { false },\n"
        "    private val lockedLoginLabel: String = \"\",\n"
        "    private val requestUnlock: () -> Unit = {},\n"
        "    private val fetchUnlockedLogins: (String, Boolean, (List<Login>) -> Unit) -> Unit =\n"
        "        { _, _, onResult -> onResult(emptyList()) },\n"
        ") : SelectablePromptView.Listener<Login> {\n"
        "    private var pendingOrigin: String? = null\n"
        "    private var pendingPrivateAccess = false\n\n"
        "    init {\n"
        "        loginSelectBar.selectablePromptListener = this\n"
        "    }\n\n"
        "    internal fun handleSelectLoginRequest(request: PromptRequest.SelectLoginPrompt) {\n"
        "        emitLoginAutofillShownFact()\n"
        "        loginSelectBar.showPrompt()\n"
        "        val locked = request.logins.firstOrNull(isLockedLogin)\n"
        "        if (locked == null) {\n"
        "            loginSelectBar.populate(request.logins)\n"
        "        } else {\n"
        "            loginSelectBar.populate(\n"
        "                listOf(\n"
        "                    locked.copy(\n"
        "                        origin = lockedLoginLabel.ifBlank { \"Passwords\" },\n"
        "                        username = \"\",\n"
        "                        password = \"\",\n"
        "                    ),\n"
        "                ),\n"
        "            )\n"
        "        }\n"
        "    }\n\n"
        "    override fun onOptionSelect(option: Login) {\n"
        "        if (isLockedLogin(option)) {\n"
        "            beginUnlock(option, privateAccess = false)\n"
        "        } else {\n"
        "            confirm(option)\n"
        "        }\n"
        "    }\n\n"
        "    override fun onOptionLongSelect(option: Login) {\n"
        "        if (isLockedLogin(option)) {\n"
        "            beginUnlock(option, privateAccess = true)\n"
        "        } else {\n"
        "            confirm(option)\n"
        "        }\n"
        "    }\n\n"
        "    private fun beginUnlock(option: Login, privateAccess: Boolean) {\n"
        "        pendingOrigin = option.formActionOrigin?.takeIf(String::isNotBlank) ?: option.origin\n"
        "        pendingPrivateAccess = privateAccess\n"
        "        requestUnlock()\n"
        "    }\n\n"
        "    /** Returns true when the biometric result belonged to the login gate. */\n"
        "    internal fun onBiometricResult(isAuthenticated: Boolean): Boolean {\n"
        "        val origin = pendingOrigin ?: return false\n"
        "        if (!isAuthenticated) {\n"
        "            pendingOrigin = null\n"
        "            pendingPrivateAccess = false\n"
        "            return true\n"
        "        }\n"
        "        val privateAccess = pendingPrivateAccess\n"
        "        fetchUnlockedLogins(origin, privateAccess) { logins ->\n"
        "            if (pendingOrigin != origin) return@fetchUnlockedLogins\n"
        "            pendingOrigin = null\n"
        "            pendingPrivateAccess = false\n"
        "            when (logins.size) {\n"
        "                0 -> dismissCurrentLoginSelect()\n"
        "                1 -> confirm(logins.single())\n"
        "                else -> loginSelectBar.populate(logins)\n"
        "            }\n"
        "        }\n"
        "        return true\n"
        "    }\n\n"
        "    private fun confirm(option: Login) {\n"
        "        store.consumePromptFrom<PromptRequest.SelectLoginPrompt>(sessionId) {\n"
        "            it.onConfirm(option)\n"
        "        }\n"
        "        pendingOrigin = null\n"
        "        pendingPrivateAccess = false\n"
        "        emitLoginAutofillPerformedFact()\n"
        "        loginSelectBar.hidePrompt()\n"
        "    }\n\n"
        "    override fun onManageOptions() {\n"
        "        manageLoginsCallback.invoke()\n"
        "        dismissCurrentLoginSelect()\n"
        "    }\n\n"
        "    @Suppress(\"TooGenericExceptionCaught\")\n"
        "    fun dismissCurrentLoginSelect(promptRequest: PromptRequest.SelectLoginPrompt? = null) {\n"
        "        pendingOrigin = null\n"
        "        pendingPrivateAccess = false\n"
        "        try {\n"
        "            if (promptRequest != null) {\n"
        "                promptRequest.onDismiss()\n"
        "                sessionId?.let {\n"
        "                    store.dispatch(ContentAction.ConsumePromptRequestAction(it, promptRequest))\n"
        "                }\n"
        "                loginSelectBar.hidePrompt()\n"
        "                return\n"
        "            }\n\n"
        "            store.consumePromptFrom<PromptRequest.SelectLoginPrompt>(sessionId) {\n"
        "                it.onDismiss()\n"
        "            }\n"
        "        } catch (e: RuntimeException) {\n"
        "            Logger.error(\"Can't dismiss this login select prompt\", e)\n"
        "        }\n"
        "        emitLoginAutofillDismissedFact()\n"
        "        loginSelectBar.hidePrompt()\n"
        "    }\n"
        "}"
    )
    s = replace_once(s, old, new, "Origin-bound login picker implementation")
    write(path, s)

    path = target / (
        "mobile/android/android-components/components/feature/prompts/src/main/java/"
        "mozilla/components/feature/prompts/PromptFeature.kt"
    )
    s = read(path)
    s = replace_once(
        s,
        "                LoginPicker(store, it, onManageLogins, customTabId)",
        "                LoginPicker(\n"
        "                    store = store,\n"
        "                    loginSelectBar = it,\n"
        "                    manageLoginsCallback = onManageLogins,\n"
        "                    sessionId = customTabId,\n"
        "                    isLockedLogin = isLockedLogin,\n"
        "                    lockedLoginLabel = lockedLoginLabel,\n"
        "                    requestUnlock = onUnlockLogins,\n"
        "                    fetchUnlockedLogins = fetchUnlockedLogins,\n"
        "                )",
        "Prompt feature origin-bound login picker",
    )
    s = replace_once(
        s,
        "    fun onBiometricResult(isAuthenticated: Boolean) {\n"
        "        if (isAuthenticated) {",
        "    fun onBiometricResult(isAuthenticated: Boolean) {\n"
        "        if (loginPicker?.onBiometricResult(isAuthenticated) == true) return\n\n"
        "        if (isAuthenticated) {",
        "Prompt feature login biometric result",
    )
    write(path, s)

    path = target / "mobile/android/fenix/app/src/main/java/org/mozilla/fenix/browser/BaseBrowserFragment.kt"
    s = read(path)
    anchor = "import org.mozilla.fenix.ReaderViewBinding\n"
    s = replace_once(
        s,
        anchor,
        anchor +
        "import org.mozilla.fenix.privacyhistory.PrivatePasswordAccess\n"
        "import org.mozilla.fenix.privacyhistory.PrivatePasswordMetadata\n",
        "Browser private password access import",
    )
    old = (
        "                loginDelegate = object : LoginDelegate {\n"
        "                    override val loginPickerView\n"
        "                        get() = loginSelectBar\n"
        "                    override val onManageLogins = {\n"
        "                        val directions =\n"
        "                            NavGraphDirections.actionGlobalSavedLoginsAuthFragment()\n"
        "                        findNavController().navigate(directions)\n"
        "                    }\n"
        "                },"
    )
    new = (
        "                loginDelegate = object : LoginDelegate {\n"
        "                    override val loginPickerView\n"
        "                        get() = loginSelectBar\n"
        "                    override val onManageLogins = {\n"
        "                        val directions =\n"
        "                            NavGraphDirections.actionGlobalSavedLoginsAuthFragment()\n"
        "                        findNavController().navigate(directions)\n"
        "                    }\n"
        "                    override val isLockedLogin = PrivatePasswordAccess::isLockedLogin\n"
        "                    override val lockedLoginLabel\n"
        "                        get() = getString(R.string.private_password_unlock_label)\n"
        "                    override val onUnlockLogins = {\n"
        "                        biometricPromptFeature.get()?.requestAuthentication(\n"
        "                            getString(R.string.private_password_auth_title),\n"
        "                        ) ?: promptsFeature.get()?.onBiometricResult(isAuthenticated = false)\n"
        "                        Unit\n"
        "                    }\n"
        "                    override val fetchUnlockedLogins =\n"
        "                        { origin: String, privateAccess: Boolean, onResult: (List<Login>) -> Unit ->\n"
        "                            viewLifecycleOwner.lifecycleScope.launch {\n"
        "                                val logins = withContext(Dispatchers.IO) {\n"
        "                                    context.components.core.passwordsStorage\n"
        "                                        .getByBaseDomain(origin)\n"
        "                                        .filter { login ->\n"
        "                                            PrivatePasswordMetadata.matchesOrigin(login, origin)\n"
        "                                        }\n"
        "                                        .filter { login ->\n"
        "                                            PrivatePasswordAccess.isProtected(\n"
        "                                                login,\n"
        "                                                context.components.core.privateHistoryRules,\n"
        "                                            ) == privateAccess\n"
        "                                        }\n"
        "                                        .map(PrivatePasswordMetadata::forUse)\n"
        "                                }\n"
        "                                onResult(logins)\n"
        "                            }\n"
        "                            Unit\n"
        "                        }\n"
        "                },"
    )
    s = replace_once(s, old, new, "Fenix current-origin login unlock")
    write(path, s)


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("target", type=Path, help="Path to Firefox source checkout")
    args = parser.parse_args()
    target = args.target.resolve()
    copy_overlay(target)
    patch_core(target)
    patch_history_metadata(target)
    patch_toolbar(target)
    patch_settings(target)
    patch_application(target)
    patch_manifest(target)
    patch_gradle(target)
    patch_strong_authentication(target)
    patch_login_data_gate(target)
    patch_origin_bound_login_picker(target)
    print("Fenix Privacy patch applied successfully")


if __name__ == "__main__":
    main()

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
        permission = '    <uses-permission android:name="android.permission.REQUEST_INSTALL_PACKAGES" />\n\n'
        s = replace_once(s, "    <application\n", permission + "    <application\n", "Updater install permission")
    receiver = (
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
    s = replace_once(s, "buildConfigField 'boolean', 'TELEMETRY', 'true'", "buildConfigField 'boolean', 'TELEMETRY', 'false'", "Telemetry off")
    write(path, s)


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("target", type=Path, help="Path to Firefox source checkout")
    args = parser.parse_args()
    target = args.target.resolve()
    copy_overlay(target)
    patch_core(target)
    patch_history_metadata(target)
    patch_settings(target)
    patch_application(target)
    patch_manifest(target)
    patch_gradle(target)
    print("Fenix Privacy patch applied successfully")


if __name__ == "__main__":
    main()

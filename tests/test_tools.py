import importlib.util
import subprocess
import tempfile
import unittest
from datetime import date
from pathlib import Path
import xml.etree.ElementTree as ET


ROOT = Path(__file__).resolve().parents[1]


def load_module(name: str, path: Path):
    spec = importlib.util.spec_from_file_location(name, path)
    if spec is None or spec.loader is None:
        raise RuntimeError(f"Unable to load {path}")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


class ToolingTests(unittest.TestCase):
    def test_latest_stable_tag_parser(self):
        data = (
            "a refs/tags/FIREFOX_152_0_RELEASE\n"
            "b refs/tags/FIREFOX_153_0_RELEASE\n"
            "c refs/tags/FIREFOX_153_0_4_RELEASE\n"
            "d refs/tags/FIREFOX_154_0b8_RELEASE\n"
        )
        proc = subprocess.run(
            ["python3", str(ROOT / "scripts/find_latest_stable_ref.py")],
            input=data,
            text=True,
            capture_output=True,
            check=True,
        )
        self.assertEqual(proc.stdout.strip(), "FIREFOX_153_0_4_RELEASE c")

    def test_overlay_xml_is_well_formed(self):
        overlays = list((ROOT / "overlay").rglob("*.xml"))
        self.assertTrue(overlays)
        for path in overlays:
            with self.subTest(path=path):
                ET.parse(path)

    def test_no_private_signing_material_in_repo(self):
        forbidden = {".jks", ".keystore", ".p12"}
        found = [
            path
            for path in ROOT.rglob("*")
            if path.is_file() and path.suffix.lower() in forbidden
        ]
        self.assertEqual(found, [])

    def test_history_metadata_patch_accepts_stable_constructor(self):
        module = load_module(
            "fenix_privacy_patcher_stable",
            ROOT / "scripts/apply_fenix_privacy.py",
        )
        with tempfile.TemporaryDirectory() as temp_dir:
            target = Path(temp_dir)
            path = target / (
                "mobile/android/fenix/app/src/main/java/org/mozilla/fenix/"
                "historymetadata/HistoryMetadataMiddleware.kt"
            )
            path.parent.mkdir(parents=True)
            path.write_text(
                "class HistoryMetadataMiddleware(\n"
                "    private val historyMetadataService: HistoryMetadataService,\n"
                ") : Middleware<BrowserState, BrowserAction> {\n"
                "            is MediaSessionAction.UpdateMediaMetadataAction -> {\n"
                "                store.state.findNormalTab(action.tabId)?.let { tab ->\n"
                "                    createHistoryMetadata(store, tab)\n"
                "                }\n"
                "            }\n"
                "            else -> {\n"
                "        val key = historyMetadataService.createMetadata(tab, searchTerm, referrerUrl)\n"
                "}\n",
                encoding="utf-8",
            )
            module.patch_history_metadata(target)
            patched = path.read_text(encoding="utf-8")
            self.assertIn("private val shouldSuppress:", patched)
            self.assertIn(
                "if (shouldSuppress(tab.content.url, tab.content.title, searchTerm))",
                patched,
            )
            self.assertIn("onSuppressed(tab.content.url)", patched)
            self.assertIn("is ContentAction.UpdateTitleAction", patched)

    def test_history_metadata_patch_accepts_legacy_constructor(self):
        module = load_module(
            "fenix_privacy_patcher_legacy",
            ROOT / "scripts/apply_fenix_privacy.py",
        )
        with tempfile.TemporaryDirectory() as temp_dir:
            target = Path(temp_dir)
            path = target / (
                "mobile/android/fenix/app/src/main/java/org/mozilla/fenix/"
                "historymetadata/HistoryMetadataMiddleware.kt"
            )
            path.parent.mkdir(parents=True)
            path.write_text(
                "class HistoryMetadataMiddleware(private val historyMetadataService: HistoryMetadataService) :\n"
                "    Middleware<BrowserState, BrowserAction> {\n"
                "            is MediaSessionAction.UpdateMediaMetadataAction -> {\n"
                "                store.state.findNormalTab(action.tabId)?.let { tab ->\n"
                "                    createHistoryMetadata(store, tab)\n"
                "                }\n"
                "            }\n"
                "            else -> {\n"
                "        val key = historyMetadataService.createMetadata(tab, searchTerm, referrerUrl)\n"
                "}\n",
                encoding="utf-8",
            )
            module.patch_history_metadata(target)
            patched = path.read_text(encoding="utf-8")
            self.assertIn("private val shouldSuppress:", patched)

    def test_core_patch_wires_aggregate_privacy_stats(self):
        module = load_module(
            "fenix_privacy_patcher_core_stats",
            ROOT / "scripts/apply_fenix_privacy.py",
        )
        with tempfile.TemporaryDirectory() as temp_dir:
            target = Path(temp_dir)
            path = target / (
                "mobile/android/fenix/app/src/main/java/org/mozilla/fenix/"
                "components/Core.kt"
            )
            path.parent.mkdir(parents=True)
            path.write_text(
                "import mozilla.components.feature.session.HistoryDelegate\n"
                "import org.mozilla.fenix.perf.lazyMonitored\n"
                "class Core(\n"
                "    private val context: Context,\n"
                ") {\n"
                "    /**\n"
                "     * The browser engine component\n"
                "     */\n"
                "    val engine = Engine(\n"
                "        historyTrackingDelegate = HistoryDelegate(lazyHistoryStorage),\n"
                "    )\n"
                "    val store = Store(\n"
                "        HistoryMetadataMiddleware(historyMetadataService),\n"
                "    )\n"
                "}\n",
                encoding="utf-8",
            )

            module.patch_core(target)

            patched = path.read_text(encoding="utf-8")
            self.assertIn("PrivateHistoryStats", patched)
            self.assertIn("val privateHistoryStats by lazyMonitored", patched)
            self.assertIn("privateHistoryStats.recordRemovedAfterMatch(url)", patched)
            self.assertIn("privateHistoryPurger.purgeAsync(url)", patched)

    def test_destructive_site_actions_are_explicit_and_opt_in(self):
        source_dir = (
            ROOT
            / "overlay/mobile/android/fenix/app/src/main/java/org/mozilla/fenix/"
            / "privacyhistory"
        )
        source = "\n".join(path.read_text(encoding="utf-8") for path in source_dir.rglob("*.kt"))
        rule_source = (source_dir / "PrivateHistoryRule.kt").read_text(encoding="utf-8")
        self.assertIn("val clearCookies: Boolean = false", rule_source)
        self.assertIn("val clearCache: Boolean = false", rule_source)
        self.assertIn("val clearDownloads: Boolean = false", rule_source)
        self.assertIn("val closeTab: Boolean = false", rule_source)
        self.assertIn("if (rule == null || !rule.isDestructive", source)
        self.assertIn("Cookies and logins stay saved", (ROOT / "README.md").read_text(encoding="utf-8"))

    def test_public_android_release_confirmation_uses_release_date(self):
        stable = load_module(
            "fenix_privacy_stable_ref",
            ROOT / "scripts/find_latest_stable_ref.py",
        )
        body = """
        <h1>153.0.4 Firefox for Android Release</h1>
        <p>Version 153.0.4, first offered to Release channel users on August 11, 2026</p>
        """
        self.assertTrue(
            stable.release_page_confirms("153.0.4", body, date(2026, 8, 20))
        )
        self.assertFalse(
            stable.release_page_confirms("153.0.4", body, date(2026, 8, 10))
        )
        self.assertFalse(
            stable.release_page_confirms("154.0", body, date(2026, 8, 20))
        )

    def test_settings_patch_accepts_stable_when_style(self):
        module = load_module(
            "fenix_privacy_patcher_settings",
            ROOT / "scripts/apply_fenix_privacy.py",
        )
        with tempfile.TemporaryDirectory() as temp_dir:
            target = Path(temp_dir)
            settings = target / (
                "mobile/android/fenix/app/src/main/java/org/mozilla/fenix/"
                "settings/SettingsFragment.kt"
            )
            prefs = target / "mobile/android/fenix/app/src/main/res/xml/preferences.xml"
            nav = target / "mobile/android/fenix/app/src/main/res/navigation/nav_graph.xml"
            settings.parent.mkdir(parents=True)
            prefs.parent.mkdir(parents=True)
            nav.parent.mkdir(parents=True)
            settings.write_text(
                "        val directions: NavDirections? = when (preference.key) {\n"
                "            else -> null\n"
                "        }\n",
                encoding="utf-8",
            )
            prefs.write_text(
                "<root>\n"
                "        <androidx.preference.Preference\n"
                "            android:key=\"@string/pref_key_private_browsing\" />\n"
                "</root>\n",
                encoding="utf-8",
            )
            nav.write_text("<navigation>\n</navigation>\n", encoding="utf-8")

            module.patch_settings(target)

            self.assertIn("R.id.privateHistoryFragment", settings.read_text(encoding="utf-8"))
            self.assertIn("pref_key_private_history_rules", prefs.read_text(encoding="utf-8"))
            self.assertIn("privateHistoryFragment", nav.read_text(encoding="utf-8"))


if __name__ == "__main__":
    unittest.main()

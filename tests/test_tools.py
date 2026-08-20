import subprocess
import tempfile
from pathlib import Path
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]


def test_latest_stable_tag_parser():
    data = """a refs/tags/FIREFOX_152_0_RELEASE\nb refs/tags/FIREFOX_153_0_RELEASE\nc refs/tags/FIREFOX_153_0_4_RELEASE\nd refs/tags/FIREFOX_154_0b8_RELEASE\n"""
    proc = subprocess.run(
        ["python3", str(ROOT / "scripts/find_latest_stable_ref.py")],
        input=data,
        text=True,
        capture_output=True,
        check=True,
    )
    assert proc.stdout.strip() == "FIREFOX_153_0_4_RELEASE c"


def test_overlay_xml_is_well_formed():
    for p in (ROOT / "overlay").rglob("*.xml"):
        ET.parse(p)


def test_no_private_signing_material_in_repo():
    bad = {".jks", ".keystore", ".p12"}
    assert not [p for p in ROOT.rglob("*") if p.is_file() and p.suffix.lower() in bad]


def _load_patcher_module():
    import importlib.util

    path = ROOT / "scripts/apply_fenix_privacy.py"
    spec = importlib.util.spec_from_file_location("fenix_privacy_patcher", path)
    assert spec and spec.loader
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def test_history_metadata_patch_accepts_stable_constructor(tmp_path):
    module = _load_patcher_module()
    path = tmp_path / "mobile/android/fenix/app/src/main/java/org/mozilla/fenix/historymetadata/HistoryMetadataMiddleware.kt"
    path.parent.mkdir(parents=True)
    path.write_text(
        "class HistoryMetadataMiddleware(\n"
        "    private val historyMetadataService: HistoryMetadataService,\n"
        ") : Middleware<BrowserState, BrowserAction> {\n"
        "        val key = historyMetadataService.createMetadata(tab, searchTerm, referrerUrl)\n"
        "}\n",
        encoding="utf-8",
    )
    module.patch_history_metadata(tmp_path)
    patched = path.read_text(encoding="utf-8")
    assert "private val shouldSuppress:" in patched
    assert "if (shouldSuppress(tab.content.url, tab.content.title, searchTerm))" in patched


def test_history_metadata_patch_accepts_legacy_constructor(tmp_path):
    module = _load_patcher_module()
    path = tmp_path / "mobile/android/fenix/app/src/main/java/org/mozilla/fenix/historymetadata/HistoryMetadataMiddleware.kt"
    path.parent.mkdir(parents=True)
    path.write_text(
        "class HistoryMetadataMiddleware(private val historyMetadataService: HistoryMetadataService) :\n"
        "    Middleware<BrowserState, BrowserAction> {\n"
        "        val key = historyMetadataService.createMetadata(tab, searchTerm, referrerUrl)\n"
        "}\n",
        encoding="utf-8",
    )
    module.patch_history_metadata(tmp_path)
    patched = path.read_text(encoding="utf-8")
    assert "private val shouldSuppress:" in patched


def test_public_android_release_confirmation_uses_release_date():
    from datetime import date

    import importlib.util
    script = ROOT / "scripts/find_latest_stable_ref.py"
    spec = importlib.util.spec_from_file_location("stable_ref", script)
    assert spec and spec.loader
    stable = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(stable)

    body = """
    <h1>153.0.4 Firefox for Android Release</h1>
    <p>Version 153.0.4, first offered to Release channel users on August 11, 2026</p>
    """
    assert stable.release_page_confirms("153.0.4", body, date(2026, 8, 20))
    assert not stable.release_page_confirms("153.0.4", body, date(2026, 8, 10))
    assert not stable.release_page_confirms("154.0", body, date(2026, 8, 20))


def test_settings_patch_accepts_stable_when_style(tmp_path):
    module = _load_patcher_module()
    settings = tmp_path / "mobile/android/fenix/app/src/main/java/org/mozilla/fenix/settings/SettingsFragment.kt"
    prefs = tmp_path / "mobile/android/fenix/app/src/main/res/xml/preferences.xml"
    nav = tmp_path / "mobile/android/fenix/app/src/main/res/navigation/nav_graph.xml"
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
    module.patch_settings(tmp_path)
    assert "R.id.privateHistoryFragment" in settings.read_text(encoding="utf-8")
    assert "pref_key_private_history_rules" in prefs.read_text(encoding="utf-8")
    assert "privateHistoryFragment" in nav.read_text(encoding="utf-8")

import importlib.util
import struct
import tempfile
import unittest
import zipfile
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]


def load_verifier():
    path = ROOT / "scripts/verify_android_apk.py"
    spec = importlib.util.spec_from_file_location("verify_android_apk", path)
    if spec is None or spec.loader is None:
        raise RuntimeError(f"Unable to load {path}")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def elf_header(machine: int) -> bytes:
    header = bytearray(64)
    header[:6] = b"\x7fELF\x02\x01"
    struct.pack_into("<H", header, 18, machine)
    return bytes(header)


class AndroidApkVerifierTests(unittest.TestCase):
    def setUp(self):
        self.verifier = load_verifier()

    def make_apk(self, path: Path, abi: str, machine: int, omit: str | None = None):
        with zipfile.ZipFile(path, "w") as archive:
            archive.writestr("AndroidManifest.xml", b"manifest")
            archive.writestr("classes.dex", b"dex")
            for library in self.verifier.REQUIRED_GECKO_LIBRARIES:
                if library != omit:
                    archive.writestr(f"lib/{abi}/{library}", elf_header(machine))

    def test_accepts_complete_arm64_gecko_runtime(self):
        with tempfile.TemporaryDirectory() as temp_dir:
            apk = Path(temp_dir) / "arm64.apk"
            self.make_apk(apk, "arm64-v8a", 183)
            self.verifier.verify_apk(apk, "arm64-v8a")

    def test_rejects_x86_gecko_labeled_as_arm64(self):
        with tempfile.TemporaryDirectory() as temp_dir:
            apk = Path(temp_dir) / "broken.apk"
            self.make_apk(apk, "x86_64", 62)
            with self.assertRaisesRegex(ValueError, "expected only arm64-v8a"):
                self.verifier.verify_apk(apk, "arm64-v8a")

    def test_rejects_missing_required_gecko_library(self):
        with tempfile.TemporaryDirectory() as temp_dir:
            apk = Path(temp_dir) / "broken.apk"
            self.make_apk(apk, "arm64-v8a", 183, omit="libmozglue.so")
            with self.assertRaisesRegex(ValueError, "missing required arm64-v8a"):
                self.verifier.verify_apk(apk, "arm64-v8a")

    def test_rejects_wrong_elf_machine(self):
        with tempfile.TemporaryDirectory() as temp_dir:
            apk = Path(temp_dir) / "broken.apk"
            self.make_apk(apk, "arm64-v8a", 62)
            with self.assertRaisesRegex(ValueError, "expected 183"):
                self.verifier.verify_apk(apk, "arm64-v8a")


if __name__ == "__main__":
    unittest.main()

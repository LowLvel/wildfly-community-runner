import io
from pathlib import Path
import tempfile
import unittest
import zipfile

from release_manifest import PLUGIN_ID, prepare, verify


class ReleaseCandidateTest(unittest.TestCase):
    def candidate(self, root, version="0.6.0", plugin_id=PLUGIN_ID):
        jar = io.BytesIO()
        with zipfile.ZipFile(jar, "w") as library:
            library.writestr("META-INF/plugin.xml", f"<idea-plugin><id>{plugin_id}</id><version>{version}</version></idea-plugin>")
        candidate = Path(root) / "candidate.zip"
        with zipfile.ZipFile(candidate, "w") as archive:
            archive.writestr("plugin/lib/plugin.jar", jar.getvalue())
        return candidate

    def test_accepts_expected_identity_and_version_and_hashes_bytes(self):
        with tempfile.TemporaryDirectory() as root:
            candidate = self.candidate(root)
            result = verify(candidate, "0.6.0")
            self.assertEqual(64, len(result["sha256"]))
            self.assertEqual(candidate.stat().st_size, result["bytes"])

    def test_rejects_wrong_identity_or_version(self):
        with tempfile.TemporaryDirectory() as root:
            for version, identity in (("0.5.2", PLUGIN_ID), ("0.6.0", "other.plugin")):
                with self.subTest(version=version, identity=identity), self.assertRaises(ValueError):
                    verify(self.candidate(root, version, identity), "0.6.0")

    def test_requires_single_downloaded_candidate(self):
        with tempfile.TemporaryDirectory() as root:
            source = Path(root) / "source"; source.mkdir()
            with self.assertRaises(ValueError):
                prepare(source, Path(root) / "release", "0.6.0")
            original = self.candidate(source)
            (source / "unexpected.zip").write_bytes(original.read_bytes())
            with self.assertRaises(ValueError):
                prepare(source, Path(root) / "release", "0.6.0")

    def test_preparation_preserves_exact_candidate_bytes(self):
        with tempfile.TemporaryDirectory() as root:
            source = Path(root) / "source"; source.mkdir()
            original = self.candidate(source)
            result = prepare(source, Path(root) / "release", "0.6.0")
            self.assertEqual(original.read_bytes(), result.read_bytes())

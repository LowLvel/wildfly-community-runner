import io
from pathlib import Path
import tempfile
import unittest
import zipfile

from prepare_wildfly import VERSION, extract


class DistributionExtractionTest(unittest.TestCase):
    def archive(self, path):
        data = io.BytesIO()
        with zipfile.ZipFile(data, "w") as archive:
            archive.writestr(path, "fixture")
        data.seek(0)
        return data

    def test_rejects_traversal_and_foreign_roots(self):
        with tempfile.TemporaryDirectory() as directory:
            for name in ("../outside", f"wildfly-{VERSION}/../outside", "/absolute", "other/bin/server.sh"):
                with self.subTest(name=name), self.assertRaises(ValueError):
                    extract(self.archive(name), Path(directory))

    def test_extracts_pinned_root(self):
        with tempfile.TemporaryDirectory() as directory:
            name = f"wildfly-{VERSION}/bin/standalone.sh"
            extract(self.archive(name), Path(directory))
            self.assertEqual("fixture", (Path(directory) / name).read_text())

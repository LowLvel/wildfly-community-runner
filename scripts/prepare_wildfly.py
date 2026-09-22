"""Download the pinned real-server test fixture; never used by plugin onboarding."""
import hashlib
import os
from pathlib import Path, PurePosixPath
import shutil
import stat
import tempfile
import urllib.request
import zipfile

VERSION = "41.0.1.Final"
SHA256 = "783e6405a132a1f088b1a8f885040ef1e0ae3976a788a992147bc3e443dfe1b6"
URL = f"https://github.com/wildfly/wildfly/releases/download/{VERSION}/wildfly-{VERSION}.zip"
ROOT = Path(__file__).resolve().parent.parent


def extract(archive, destination):
    destination = destination.resolve()
    with zipfile.ZipFile(archive) as source:
        for entry in source.infolist():
            relative = PurePosixPath(entry.filename.replace("\\", "/"))
            mode = entry.external_attr >> 16
            if (relative.is_absolute() or ".." in relative.parts or ":" in entry.filename
                    or not relative.parts or relative.parts[0] != f"wildfly-{VERSION}"
                    or stat.S_ISLNK(mode)):
                raise ValueError("Unsafe path in the WildFly distribution")
            target = destination.joinpath(*relative.parts)
            if not target.resolve().is_relative_to(destination):
                raise ValueError("Archive path escapes the fixture directory")
            if entry.is_dir():
                target.mkdir(parents=True, exist_ok=True)
            else:
                target.parent.mkdir(parents=True, exist_ok=True)
                with source.open(entry) as incoming, target.open("wb") as outgoing:
                    shutil.copyfileobj(incoming, outgoing)
                if os.name != "nt" and (mode & 0o111 or target.suffix == ".sh"):
                    target.chmod(0o755)


def main():
    parent = ROOT / "build" / "runtime"
    parent.mkdir(parents=True, exist_ok=True)
    destination = parent / "WildFly home with spaces"
    if destination.exists():
        raise SystemExit(f"Fixture destination already exists: {destination}. Use a fresh build/runtime directory.")
    with tempfile.TemporaryDirectory(prefix="wildfly-download-", dir=parent) as temporary:
        archive = Path(temporary) / "server.zip"
        digest = hashlib.sha256()
        size = 0
        with urllib.request.urlopen(URL, timeout=90) as response, archive.open("wb") as output:
            while chunk := response.read(1024 * 1024):
                size += len(chunk)
                if size > 300 * 1024 * 1024:
                    raise ValueError("WildFly fixture exceeds the download limit")
                digest.update(chunk)
                output.write(chunk)
        if digest.hexdigest() != SHA256:
            raise ValueError("WildFly distribution SHA-256 mismatch")
        unpacked = Path(temporary) / "unpacked"
        extract(archive, unpacked)
        unpacked.rename(destination)
    home = destination / f"wildfly-{VERSION}"
    print(f"Verified WildFly {VERSION}: {home}")
    if os.environ.get("GITHUB_ENV"):
        with open(os.environ["GITHUB_ENV"], "a", encoding="utf-8") as output:
            output.write(f"WILDFLY_TEST_HOME={home}\n")
    else:
        print(f"Set WILDFLY_TEST_HOME to {home} before running the integration test.")


if __name__ == "__main__":
    main()

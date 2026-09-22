"""Validate and identify the exact candidate used by the manual release workflow."""
import argparse
import hashlib
import io
import json
import os
from pathlib import Path
import shutil
import xml.etree.ElementTree as ET
import zipfile

PLUGIN_ID = "io.github.wildflycommunityrunner"
ROOT = Path(__file__).resolve().parent.parent


def version():
    properties = dict(line.split("=", 1) for line in (ROOT / "gradle.properties").read_text().splitlines()
                      if line.strip() and not line.lstrip().startswith("#"))
    return properties["pluginVersion"]


def verify(archive, expected_version):
    descriptors = []
    with zipfile.ZipFile(archive) as distribution:
        for item in distribution.infolist():
            if "/lib/" not in item.filename or not item.filename.endswith(".jar"):
                continue
            with zipfile.ZipFile(io.BytesIO(distribution.read(item))) as library:
                if "META-INF/plugin.xml" in library.namelist():
                    descriptors.append(ET.fromstring(library.read("META-INF/plugin.xml")))
    if len(descriptors) != 1:
        raise ValueError("Expected exactly one plugin descriptor in the candidate")
    descriptor = descriptors[0]
    if descriptor.findtext("id") != PLUGIN_ID or descriptor.findtext("version") != expected_version:
        raise ValueError("Candidate plugin identity/version does not match this checkout")
    return {"file": Path(archive).name, "sha256": hashlib.sha256(Path(archive).read_bytes()).hexdigest(),
            "bytes": Path(archive).stat().st_size}


def prepare(source, output, expected_version):
    archives = list(source.glob("*.zip"))
    if len(archives) != 1:
        raise ValueError("Expected one unsigned candidate from this validation run")
    verify(archives[0], expected_version)
    output.mkdir(parents=True, exist_ok=True)
    candidate = output / f"wildfly-community-runner-{expected_version}.zip"
    shutil.copyfile(archives[0], candidate)
    return candidate.resolve()


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--candidate-directory", type=Path)
    parser.add_argument("--output", type=Path, default=ROOT / "build/release")
    parser.add_argument("--commit", required=True)
    args = parser.parse_args()
    expected = version()
    if args.candidate_directory:
        candidate = prepare(args.candidate_directory, args.output, expected)
        if os.environ.get("GITHUB_ENV"):
            with open(os.environ["GITHUB_ENV"], "a", encoding="utf-8") as environment:
                environment.write(f"CANDIDATE_ARCHIVE={candidate}\n")
        print(f"Validated candidate: {candidate.name}")
    else:
        archives = sorted(args.output.glob("*.zip"))
        expected_names = {f"wildfly-community-runner-{expected}.zip", f"wildfly-community-runner-{expected}-signed.zip"}
        if {archive.name for archive in archives} != expected_names:
            raise ValueError("Release must contain exactly the unsigned candidate and its signed archive")
        manifest = {"pluginId": PLUGIN_ID, "version": expected, "commit": args.commit,
                    "artifacts": [verify(archive, expected) for archive in archives]}
        (args.output / "manifest.json").write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf-8")
        (args.output / "SHA256SUMS").write_text("".join(f"{item['sha256']}  {item['file']}\n" for item in manifest["artifacts"]), encoding="utf-8")
        print(f"Release manifest written for {expected} at {args.commit}")


if __name__ == "__main__":
    main()

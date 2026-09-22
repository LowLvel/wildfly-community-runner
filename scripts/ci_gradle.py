"""Run CI checks, retrying only JetBrains' pre-task layout-index race (#2192)."""
from pathlib import Path
import os
import subprocess
import sys

ROOT = Path(__file__).resolve().parent.parent
MAX_ATTEMPTS = 3


def retryable(output):
    return (
        "java.nio.file.ClosedFileSystemException" in output
        and "Could not find bundled plugin with ID:" in output
        and "> Task :" not in output
    )


def clear_layout_index(root):
    # Only generated JSON indices; never remove downloaded IDEs or source files.
    root = root.resolve()
    cache = root / ".intellijPlatform" / "ides" / "layoutIndex"
    if not cache.resolve().is_relative_to(root):
        raise RuntimeError("Layout cache resolves outside the checkout")
    if cache.is_dir():
        for path in cache.glob("*.json"):
            if path.is_file() and not path.is_symlink():
                path.unlink()


def gradle_command(arguments, windows):
    launcher = ["cmd.exe", "/d", "/c", "gradlew.bat"] if windows else ["bash", "./gradlew"]
    return [*launcher, "--no-daemon", "--console=plain", *arguments]


def run_gradle(arguments):
    process = subprocess.Popen(
        gradle_command(arguments, os.name == "nt"),
        cwd=ROOT, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
        text=True, encoding="utf-8", errors="replace",
    )
    lines = []
    for line in process.stdout:
        print(line, end="", flush=True)
        lines.append(line)
    return process.wait(), "".join(lines)


def main(arguments):
    for attempt in range(1, MAX_ATTEMPTS + 1):
        code, output = run_gradle(arguments)
        if code == 0 or not retryable(output) or attempt == MAX_ATTEMPTS:
            return code
        print(f"Retrying JetBrains layout-index race ({attempt}/{MAX_ATTEMPTS}); no tasks executed.", flush=True)
        clear_layout_index(ROOT)
    raise AssertionError("Unreachable")


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))

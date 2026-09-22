import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch
import ci_gradle

RACE = "java.nio.file.ClosedFileSystemException\nCould not find bundled plugin with ID: 'com.intellij.java'"


class RetryPolicyTest(unittest.TestCase):
    def test_windows_uses_native_wrapper_without_resolving_wsl_bash(self):
        args = ["test", "-PverifierIde=IC-2025.1"]
        command = ci_gradle.gradle_command(args, windows=True)
        self.assertEqual(["cmd.exe", "/d", "/c", "gradlew.bat"], command[:4])
        self.assertEqual(args, command[-2:])
        self.assertEqual(["bash", "./gradlew"], ci_gradle.gradle_command(args, windows=False)[:2])

    def test_does_not_retry_compiler_tests_or_verifier_failures(self):
        for output in ["Compilation failed", "Test failed", "Compatibility problems", "> Task :test\n" + RACE]:
            with self.subTest(output=output), patch.object(ci_gradle, "run_gradle", return_value=(1, output)) as run:
                self.assertEqual(1, ci_gradle.main(["test"]))
                self.assertEqual(1, run.call_count)

    def test_retries_only_matching_pre_task_failure(self):
        with patch.object(ci_gradle, "run_gradle", side_effect=[(1, RACE), (0, "ok")]) as run:
            with patch.object(ci_gradle, "clear_layout_index") as clear:
                self.assertEqual(0, ci_gradle.main(["test"]))
                self.assertEqual(2, run.call_count)
                clear.assert_called_once_with(ci_gradle.ROOT)

    def test_failure_remains_failure_after_retry_limit(self):
        with patch.object(ci_gradle, "run_gradle", return_value=(1, RACE)) as run:
            with patch.object(ci_gradle, "clear_layout_index"):
                self.assertEqual(1, ci_gradle.main(["test"]))
                self.assertEqual(ci_gradle.MAX_ATTEMPTS, run.call_count)

    def test_cleanup_only_removes_generated_index_json(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            cache = root / ".intellijPlatform/ides/layoutIndex"
            cache.mkdir(parents=True)
            (cache / "index.json").write_text("{}")
            keep = cache / "keep.txt"
            keep.write_text("keep")
            ci_gradle.clear_layout_index(root)
            self.assertFalse((cache / "index.json").exists())
            self.assertEqual("keep", keep.read_text())


if __name__ == "__main__":
    unittest.main()

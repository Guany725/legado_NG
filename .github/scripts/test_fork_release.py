import importlib.util
from pathlib import Path
import unittest
from unittest.mock import patch
import os
import tempfile

spec = importlib.util.spec_from_file_location("fork_release", Path(__file__).with_name("fork_release.py"))
release = importlib.util.module_from_spec(spec)
spec.loader.exec_module(release)


class ReleasePolicyTest(unittest.TestCase):
    def test_only_a_successfully_published_matching_release_is_skipped(self):
        state = {"release_id": 7, "upstream_sha": "a"}
        upstream = {"id": 7}
        published = {"draft": False, "prerelease": False}
        self.assertTrue(release.release_is_current(state, upstream, "a", published))
        self.assertFalse(release.release_is_current(state, upstream, "b", published))
        self.assertFalse(release.release_is_current(state, {"id": 8}, "a", published))
        self.assertFalse(release.release_is_current(state, upstream, "a", None))
        self.assertFalse(release.release_is_current(state, upstream, "a", {"draft": True}))

    def test_rejects_multiline_or_option_like_tags(self):
        for value in ("", "-evil", "v1\nfoo", None):
            self.assertFalse(release.valid_tag(value))
        self.assertTrue(release.valid_tag("3.26.112720"))

    def test_publish_does_not_write_when_main_changed_during_build(self):
        with patch.object(Path, "read_text", return_value='{}'), \
                patch.dict(release.os.environ, {"CANDIDATE_SHA": "candidate", "BASE_SHA": "base"}), \
                patch.object(release, "command", side_effect=["candidate", "new-main refs/heads/main"]) as command:
            with self.assertRaisesRegex(RuntimeError, "Main changed"):
                release.publish()
            self.assertFalse(any("push" in call.args or "gh" in call.args for call in command.call_args_list))

    def test_publish_requires_apk_before_pushing_main(self):
        with patch.object(Path, "read_text", return_value='{}'), \
                patch.object(Path, "glob", return_value=[]), \
                patch.dict(release.os.environ, {"CANDIDATE_SHA": "candidate", "BASE_SHA": "base"}), \
                patch.object(release, "command", side_effect=["candidate", "base refs/heads/main"]) as command:
            with self.assertRaisesRegex(RuntimeError, "exactly one signed APK"):
                release.publish()
            self.assertFalse(any("push" in call.args or "gh" in call.args for call in command.call_args_list))


class MergePolicyTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.previous = os.getcwd()
        os.chdir(self.directory.name)
        release.command("git", "init", "-b", "main")
        release.command("git", "config", "user.name", "Test")
        release.command("git", "config", "user.email", "test@example.invalid")
        Path(".github/workflows").mkdir(parents=True)
        Path(".github/workflows/build.yml").write_text("base\n")
        Path("app.txt").write_text("base\n")
        self.commit()
        release.command("git", "checkout", "-b", "upstream")
        Path(".github/workflows/build.yml").write_text("upstream\n")
        Path(".github/workflows/new.yml").write_text("upstream\n")
        Path("app.txt").write_text("upstream\n")
        self.commit()
        self.upstream = release.command("git", "rev-parse", "HEAD")
        release.command("git", "checkout", "main")
        Path(".github/workflows/build.yml").write_text("fork\n")
        self.commit()

    def tearDown(self):
        os.chdir(self.previous)
        self.directory.cleanup()

    def commit(self):
        release.command("git", "add", ".")
        release.command("git", "commit", "-m", "test")

    def test_merge_keeps_workflows_and_takes_application_updates(self):
        base = release.command("git", "rev-parse", "HEAD")
        release.merge_release(self.upstream, base)
        self.assertEqual("fork\n", Path(".github/workflows/build.yml").read_text())
        self.assertFalse(Path(".github/workflows/new.yml").exists())
        self.assertEqual("upstream\n", Path("app.txt").read_text())
        self.assertEqual("", release.command("git", "diff", base, "--", ".github/workflows"))

    def test_application_conflicts_abort_without_changing_branch(self):
        Path("app.txt").write_text("fork\n")
        self.commit()
        base = release.command("git", "rev-parse", "HEAD")
        with patch.dict(os.environ, {"GITHUB_STEP_SUMMARY": str(Path(self.directory.name) / "summary.md")}):
            with self.assertRaisesRegex(RuntimeError, "merge conflicts"):
                release.merge_release(self.upstream, base)
        self.assertEqual(base, release.command("git", "rev-parse", "HEAD"))
        self.assertEqual("fork\n", Path("app.txt").read_text())


if __name__ == "__main__":
    unittest.main()

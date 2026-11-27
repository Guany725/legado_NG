import importlib.util
from pathlib import Path
import unittest

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


if __name__ == "__main__":
    unittest.main()

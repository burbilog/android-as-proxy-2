"""Pure, offline tests for the public release guardrails (no signing or GitHub)."""
import importlib.util
from pathlib import Path
import unittest


SPEC = importlib.util.spec_from_file_location(
    "aap2_release", Path(__file__).with_name("aap2-release.py"))
release = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(release)


def props(name, code):
    return "versionCode={}\nversionName={}\n".format(code, name)


class ReleasePolicyTests(unittest.TestCase):
    def test_valid_types(self):
        self.assertIsNone(release.release_type("release"))
        for n in (1, 9, 10, 98, 99):
            self.assertEqual(n, release.release_type("rc{}".format(n)))
        for invalid in ("", "rc0", "rc00", "rc01", "rc100", "rc-1",
                        "RC1", "stable", "latest", "preview", "rc1;rm"):
            with self.subTest(invalid=invalid):
                with self.assertRaises(release.ReleaseError):
                    release.release_type(invalid)

    def test_release_version_blocks(self):
        stable = release.parse_version(props("2.1.0", 21000), "release")
        self.assertEqual(("v2.1.0", 21000, 21000),
                         (stable["tag"], stable["code"], stable["stableCode"]))
        rc1 = release.parse_version(props("2.1.0-rc1", 20901), "rc1")
        self.assertEqual(("v2.1.0-rc1", 20901, 21000),
                         (rc1["tag"], rc1["code"], rc1["stableCode"]))
        rc99 = release.parse_version(props("2.1.0-rc99", 20999), "rc99")
        self.assertEqual(21000, rc99["stableCode"])
        next_rc = release.parse_version(props("2.1.1-rc1", 21001), "rc1")
        self.assertEqual(21100, next_rc["stableCode"])

    def test_invalid_version_or_code(self):
        samples = [
            ("2.1.0", 21000, "rc1"),
            ("2.1.0-rc1", 21000, "rc1"),
            ("2.1.0-rc2", 20901, "rc2"),
            ("2.1.0-rc01", 20901, "rc1"),
            ("2.1.0-rc100", 21000, "rc100"),
            ("2.1.0-rc1", 20901, "release"),
            ("2.1.0", 21001, "release"),
            ("2.1.0", 20999, "release"),
            ("2.1.0", 0, "release"),
            ("2.1.0", 2100000001, "release"),
        ]
        for name, code, t in samples:
            with self.subTest(name=name, code=code, type=t):
                with self.assertRaises(release.ReleaseError):
                    release.parse_version(props(name, code), t)
        for data in ("versionCode=21000\nversionName=2.1.0\nversionName=2.1.0\n",
                     "versionCode=21000\nversionName=2.1.0\nstrange=1\n",
                     "versionCode=21000\n", "versionCode=0\nversionName=2.1.0\n"):
            with self.assertRaises(release.ReleaseError):
                release.parse_version(data, "release")

    def test_semver_comparison_not_lexical(self):
        self.assertLess(release.rank_tag("v2.1.9"), release.rank_tag("v2.1.10"))
        self.assertLess(release.rank_tag("v2.1.0-rc2"), release.rank_tag("v2.1.0-rc10"))
        self.assertLess(release.rank_tag("v2.1.0-rc99"), release.rank_tag("v2.1.0"))
        self.assertLess(release.rank_tag("v2.1.0"), release.rank_tag("v2.1.1-rc1"))
        self.assertIsNone(release.rank_tag("not-a-version"))

    def test_cannot_reuse_or_go_backwards(self):
        def items(*tags):
            return [{"tagName": tag} for tag in tags]
        self.assertEqual("v2.0.6", release.check_published_sequence(
            release.parse_version(props("2.1.0-rc1", 20901), "rc1"),
            items("v2.0.6")))
        self.assertEqual("v2.1.0-rc1", release.check_published_sequence(
            release.parse_version(props("2.1.0-rc2", 20902), "rc2"),
            items("v2.0.6", "v2.1.0-rc1")))
        for candidate, value, t, history in [
            ("2.1.0-rc1", 20901, "rc1", ["v2.1.0-rc1"]),
            ("2.1.0-rc2", 20902, "rc2", ["v2.1.0-rc3"]),
            ("2.1.0-rc3", 20903, "rc3", ["v2.1.0"]),
            ("2.1.1-rc1", 21001, "rc1", ["v2.2.0"]),
        ]:
            with self.subTest(tag=candidate):
                with self.assertRaises(release.ReleaseError):
                    release.check_published_sequence(
                        release.parse_version(props(candidate, value), t),
                        items(*history))

    def test_publication_flags_cannot_promote_rc(self):
        from pathlib import Path
        rc = release.parse_version(props("2.1.0-rc1", 20901), "rc1")
        stable = release.parse_version(props("2.1.0", 21000), "release")
        rc_args = release.publish_args(rc, "deadbeef", Path("notes"), [Path("aap2.apk")])
        release_args = release.publish_args(stable, "deadbeef", Path("notes"), [Path("aap2.apk")])
        self.assertIn("--prerelease", rc_args)
        self.assertIn("--latest=false", rc_args)
        self.assertNotIn("--latest", rc_args)
        self.assertIn("--latest", release_args)
        self.assertNotIn("--prerelease", release_args)
        self.assertNotIn("--latest=false", release_args)


if __name__ == "__main__":
    unittest.main()

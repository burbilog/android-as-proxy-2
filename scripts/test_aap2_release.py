"""Pure, offline tests for the public release guardrails (no signing or GitHub)."""
import importlib.util
from pathlib import Path
import unittest
from unittest import mock
from tempfile import TemporaryDirectory
from types import SimpleNamespace


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

    def test_remote_tag_lightweight_and_annotated_must_match_source(self):
        commit = "a" * 40
        other = "b" * 40
        ref = "refs/tags/v2.1.0-rc2"
        lightweight = f"{commit}\t{ref}\n"
        annotated = f"{other}\t{ref}\n{commit}\t{ref}^{{}}\n"
        for response in (lightweight, annotated):
            with mock.patch.object(release, "run",
                                   return_value=SimpleNamespace(stdout=response)) as run:
                release.verify_remote_tag("v2.1.0-rc2", commit)
                run.assert_called_once_with(
                    ["git", "ls-remote", "--exit-code", "--tags",
                     "https://github.com/" + release.REPO + ".git",
                     ref, ref + "^{}"], capture=True)
        for response in ("", f"{other}\t{ref}\n",
                         f"{commit}\t{ref}\n{other}\t{ref}^{{}}\n",
                         f"{commit}\t{ref}^{{}}\n",
                         lightweight + lightweight, "garbage\n"):
            with self.subTest(response=response):
                with mock.patch.object(release, "run",
                                       return_value=SimpleNamespace(stdout=response)):
                    with self.assertRaises(release.ReleaseError):
                        release.verify_remote_tag("v2.1.0-rc2", commit)
        with mock.patch.object(release, "run",
                               side_effect=release.ReleaseError("git failed")):
            with self.assertRaises(release.ReleaseError):
                release.verify_remote_tag("v2.1.0-rc2", commit)

    def test_public_prepare_rejects_untracked_source_and_build_inputs(self):
        # Use real git status/ls-files in a temporary repository, never the
        # maintainer's checkout. Only fetch is simulated (no network).
        real_run = release.run
        with TemporaryDirectory() as td:
            root = Path(td)
            def git(*args):
                return real_run(["git", *args], cwd=root, capture=True)
            git("init", "-b", "master")
            (root / ".gitignore").write_text("app/build/\n__pycache__/\n*.ignored\n")
            (root / "app/src").mkdir(parents=True)
            (root / "app/src/Main.kt").write_text("tracked source")
            git("add", ".")
            git("-c", "user.name=Test", "-c", "user.email=test@example.invalid",
                "-c", "commit.gpgsign=false", "commit", "-m", "fixture")
            commit = git("rev-parse", "HEAD").stdout.strip()
            git("update-ref", "refs/remotes/origin/master", commit)
            (root / "app/build").mkdir()
            (root / "app/build/generated.apk").touch()
            (root / "__pycache__").mkdir()
            (root / "__pycache__/test.pyc").touch()

            def local_run(args, **kwargs):
                if args == ["git", "fetch", "origin", "master"]:
                    return SimpleNamespace(stdout="", returncode=0)
                return real_run(args, cwd=root, **kwargs)

            with mock.patch.object(release, "run", side_effect=local_run):
                self.assertEqual(commit, release.require_clean_master())
                for name in ("app/src/Extra.kt", "app/src/Extra.ignored", "build.gradle"):
                    with self.subTest(name=name):
                        path = root / name
                        path.touch()
                        try:
                            with self.assertRaises(release.ReleaseError):
                                release.require_clean_master()
                        finally:
                            path.unlink()
                (root / "app/src/Main.kt").write_text("modified source")
                with self.assertRaises(release.ReleaseError):
                    release.require_clean_master()

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

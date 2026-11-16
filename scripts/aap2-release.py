#!/usr/bin/env python3
"""Locally signed AAP2 public releases: one fail-closed path for RC and stable.

No signing credentials leave the maintainer's workstation. This does NOT
create release notes, bump versions, modify the repository or auto-publish.
"""
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import tempfile

REPO = "burbilog/android-as-proxy-2"
ROOT = Path(__file__).resolve().parent.parent
PKG = "net.isaeff.android.asproxy2"
RC_TYPE = re.compile(r"rc([1-9][0-9]?)\Z")
STABLE = re.compile(r"([0-9]+)\.([0-9]+)\.([0-9]+)\Z")
RC_VERSION = re.compile(r"([0-9]+)\.([0-9]+)\.([0-9]+)-rc([1-9][0-9]?)\Z")
TAG_VERSION = re.compile(r"v([0-9]+)\.([0-9]+)\.([0-9]+)(?:-rc([1-9][0-9]?))?\Z")


class ReleaseError(Exception):
    pass


def require(ok, message):
    if not ok:
        raise ReleaseError(message)


def run(args, *, cwd=ROOT, capture=False, check=True, env=None):
    process = subprocess.run(args, cwd=cwd, text=True, env=env,
                             stdout=subprocess.PIPE if capture else None,
                             stderr=subprocess.PIPE if capture else None)
    if check and process.returncode:
        detail = (process.stderr or "").strip() if capture else ""
        raise ReleaseError("{} failed (exit {}): {}".format(
            args[0], process.returncode, detail[:300]))
    return process


def release_type(value):
    require(value is not None and value != "", "TYPE is required: release or rc1..rc99. Run make for help.")
    if value == "release":
        return None
    match = RC_TYPE.fullmatch(value)
    require(match is not None, "invalid TYPE {!r}: use release or rc1..rc99 (no leading zeroes)".format(value))
    return int(match.group(1))


def parse_version(text, type_value):
    rc = release_type(type_value)
    values = {}
    for line in text.splitlines():
        line = line.strip()
        if not line or line.startswith("#"):
            continue
        require("=" in line, "invalid version.properties line")
        key, val = (s.strip() for s in line.split("=", 1))
        require(key in ("versionCode", "versionName") and key not in values,
                "unexpected or duplicated version.properties key: {}".format(key))
        values[key] = val
    require(set(values) == {"versionCode", "versionName"}, "missing version.properties fields")
    require(re.fullmatch(r"[1-9][0-9]*", values["versionCode"]) is not None,
            "versionCode must be a positive integer")
    code = int(values["versionCode"])
    require(code <= 2100000000, "versionCode exceeds safe Android limit")
    version = values["versionName"]
    if rc is None:
        match = STABLE.fullmatch(version)
        require(match is not None, "TYPE=release requires versionName=X.Y.Z")
        require(code % 100 == 0, "stable versionCode must end in 00 (reserved block of 100)")
    else:
        match = RC_VERSION.fullmatch(version)
        require(match is not None and int(match.group(4)) == rc,
                "TYPE={} requires versionName=X.Y.Z-{}".format(type_value, type_value))
        require(code % 100 == rc,
                "TYPE={} requires versionCode ending in {:02d} (99 RC slots before stable)".format(type_value, rc))
    return {"name": version, "code": code, "type": type_value,
            "tag": "v" + version, "rc": rc,
            "base": ".".join(match.group(i) for i in (1, 2, 3)),
            "stableCode": code if rc is None else code + 100 - rc}


def rank_tag(tag):
    match = TAG_VERSION.fullmatch(tag)
    if match is None:
        return None
    version = tuple(int(match.group(i)) for i in (1, 2, 3))
    stage = int(match.group(4)) if match.group(4) else 100
    return version + (stage,)


def check_published_sequence(candidate, releases):
    current = rank_tag(candidate["tag"])
    require(current is not None, "not a valid release tag")
    ordered = []
    for item in releases:
        tag = item.get("tagName", "")
        order = rank_tag(tag)
        if order is not None:
            ordered.append((order, tag))
    if not ordered:
        return None
    latest_rank, latest_tag = max(ordered)
    require(current > latest_rank, "cannot publish {}: {} is already published "
            "(version/RC sequence must increase)".format(candidate["tag"], latest_tag))
    return latest_tag


def gh_releases():
    result = run(["gh", "release", "list", "--repo", REPO, "--limit", "1000",
                  "--json", "tagName,isDraft,isPrerelease,isLatest"], capture=True)
    releases = json.loads(result.stdout)
    require(isinstance(releases, list), "invalid GitHub releases response")
    require(len(releases) < 1000,
            "more than 999 releases: extend pagination before publishing")
    return releases


def ensure_tag_absent(tag):
    result = run(["git", "ls-remote", "--exit-code", "--tags", "origin",
                  "refs/tags/" + tag], capture=True, check=False)
    if result.returncode == 0:
        raise ReleaseError("{} already exists as a Git tag; refusing to reuse it".format(tag))
    require(result.returncode == 2, "could not verify remote tag absence: {}".format(
        (result.stderr or "").strip()[:300]))


def require_clean_master():
    require(run(["git", "branch", "--show-current"], capture=True).stdout.strip() == "master",
            "public releases require master; see docs/RELEASE_POLICY.md")
    require(run(["git", "status", "--porcelain", "--untracked-files=no"],
                capture=True).stdout.strip() == "", "tracked working tree is dirty")
    run(["git", "fetch", "origin", "master"])
    head = run(["git", "rev-parse", "HEAD"], capture=True).stdout.strip()
    upstream = run(["git", "rev-parse", "origin/master"], capture=True).stdout.strip()
    require(head == upstream, "local master differs from origin/master; run git pull --ff-only")
    return head


def metadata_path(directory):
    return directory / "release.json"


def read_metadata(directory, candidate, commit):
    path = metadata_path(directory)
    if not path.exists():
        return {"schema": 1, "tag": candidate["tag"], "type": candidate["type"],
                "versionName": candidate["name"], "versionCode": candidate["code"],
                "sourceCommit": commit, "artifacts": {}}
    meta = json.loads(path.read_text(encoding="utf-8"))
    for key, val in (("schema", 1), ("tag", candidate["tag"]),
                     ("type", candidate["type"]), ("versionName", candidate["name"]),
                     ("versionCode", candidate["code"]), ("sourceCommit", commit)):
        require(meta.get(key) == val, "archived candidate metadata mismatch: {}".format(key))
    require(isinstance(meta.get("artifacts"), dict), "corrupt archive metadata")
    return meta


def write_metadata(directory, meta):
    target = metadata_path(directory)
    with tempfile.NamedTemporaryFile(mode="w", encoding="utf-8", dir=directory,
                                     prefix=".release-", delete=False) as tmp:
        tmp.write(json.dumps(meta, sort_keys=True, indent=2) + "\n")
        path = Path(tmp.name)
    try:
        path.replace(target)
    finally:
        path.unlink(missing_ok=True)


def sha256(path):
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for chunk in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def build_tools():
    sdk = Path(os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT")
               or str(Path.home() / "Android/Sdk"))
    tools = Path(os.environ.get("AAP2_BUILD_TOOLS")
                 or str(sdk / "build-tools/35.0.0"))
    for name in ("aapt", "apksigner"):
        require((tools / name).is_file(), "missing Android tool: {}".format(tools / name))
    return tools


def apk_details(path, tools, candidate=None, fdroid=False):
    require(path.is_file() and path.stat().st_size > 0, "APK missing: {}".format(path))
    cert_output = run([str(tools / "apksigner"), "verify", "--print-certs",
                       str(path)], capture=True).stdout
    match = re.search(r"Signer #1 certificate SHA-256 digest:\s*([0-9a-fA-F]{64})", cert_output)
    require(match is not None, "cannot identify release signing certificate")
    cert = match.group(1).lower()
    badging = run([str(tools / "aapt"), "dump", "badging", str(path)],
                  capture=True).stdout
    package = next((x for x in badging.splitlines() if x.startswith("package:")), "")
    require("name='{}'".format(PKG) in package, "unexpected APK package")
    require("application-debuggable" not in badging, "debuggable APK cannot be published")
    codes = re.search(r"versionCode='([0-9]+)'", package)
    names = re.search(r"versionName='([^']+)'", package)
    require(codes is not None and names is not None, "APK lacks version metadata")
    code, version = int(codes.group(1)), names.group(1)
    if candidate is not None:
        require(code == candidate["code"] and version == candidate["name"],
                "APK version differs from version.properties: {} / {}".format(code, version))
    if fdroid:
        permissions = run([str(tools / "aapt"), "dump", "permissions", str(path)],
                          capture=True).stdout
        require("android.permission.REQUEST_INSTALL_PACKAGES" not in permissions,
                "F-Droid APK requests install-packages permission")
    return {"sha256": sha256(path), "signerSha256": cert,
            "versionCode": code, "versionName": version}


def previous_public_apk(tag, directory):
    with tempfile.TemporaryDirectory(prefix="aap2-prev-") as temp:
        run(["gh", "release", "download", tag, "--repo", REPO,
             "--pattern", "aap2.apk", "--dir", temp])
        path = Path(temp) / "aap2.apk"
        require(path.is_file(), "previous public release lacks aap2.apk: {}".format(tag))
        return apk_details(path, directory)


def check_against_previous(details, previous_tag, tools):
    if previous_tag is None:
        print("WARNING: no previous release available; check signer manually")
        return
    old = previous_public_apk(previous_tag, tools)
    require(details["versionCode"] > old["versionCode"],
            "versionCode {} is not greater than public {} ({})".format(
                details["versionCode"], previous_tag, old["versionCode"]))
    require(details["signerSha256"] == old["signerSha256"],
            "APK signer differs from previous public release {}; STOP".format(previous_tag))


def verify_archive(directory, candidate, head, tools, required_github=True):
    meta = read_metadata(directory, candidate, head)
    artifacts = meta["artifacts"]
    if required_github:
        require("github" in artifacts, "missing signed GitHub candidate; run make release TYPE=...")
    for channel, name in (("github", "aap2.apk"), ("fdroid", "aap2-fdroid.apk")):
        if channel not in artifacts:
            continue
        actual = apk_details(directory / name, tools, candidate, fdroid=(channel == "fdroid"))
        require(actual == artifacts[channel], "archived {} APK differs from recorded metadata".format(channel))
    if "github" in artifacts and "fdroid" in artifacts:
        require(artifacts["github"]["signerSha256"] == artifacts["fdroid"]["signerSha256"],
                "GitHub and F-Droid certificates differ")
    return meta


def check_remote(candidate):
    releases = gh_releases()
    existing = {r.get("tagName") for r in releases}
    require(candidate["tag"] not in existing,
            "release {} already exists; never overwrite a published release".format(candidate["tag"]))
    ensure_tag_absent(candidate["tag"])
    return check_published_sequence(candidate, releases)


def prepare(candidate, head, directory, channel):
    tools = build_tools()
    # F-Droid candidate preparation must not depend on GitHub Releases.
    # It is an upstream-signed verification candidate, not a publication.
    previous = check_remote(candidate) if channel == "github" else None
    destination = directory / ("aap2.apk" if channel == "github" else "aap2-fdroid.apk")
    require(not destination.exists(), "candidate already exists: {}".format(destination))
    directory.mkdir(parents=True, exist_ok=True)
    meta = read_metadata(directory, candidate, head)
    require(channel not in meta["artifacts"], "already prepared: {}".format(channel))
    if meta["artifacts"]:
        verify_archive(directory, candidate, head, tools, required_github=False)
    print("Preparing {} {} (versionCode={}, source={})".format(
        channel, candidate["tag"], candidate["code"], head))
    env = os.environ.copy()
    env.pop("AAP2_VERSION_CODE", None)
    env.pop("AAP2_VERSION_NAME", None)
    run(["sh", "scripts/build-signed-apk.sh", channel], env=env)
    source = ROOT / "app/build/outputs/apk/local-release" / destination.name
    actual = apk_details(source, tools, candidate, fdroid=(channel == "fdroid"))
    if channel == "github":
        check_against_previous(actual, previous, tools)
    counterpart = meta["artifacts"].get("fdroid" if channel == "github" else "github")
    if counterpart is not None:
        require(actual["signerSha256"] == counterpart["signerSha256"],
                "candidate signer differs between channels")
    elif channel == "fdroid":
        print("WARNING: F-Droid-only candidate signer is not yet compared with a "
              "previous public APK; verify continuity before any distribution.")
    with source.open("rb") as reader, destination.open("xb") as writer:
        shutil.copyfileobj(reader, writer)
    meta["artifacts"][channel] = actual
    write_metadata(directory, meta)
    print("READY:", destination, "SHA256:", actual["sha256"])


def release_notes(candidate):
    notes = ROOT / "docs" / ("RELEASE_NOTES_" + candidate["name"] + ".md")
    require(notes.is_file() and notes.stat().st_size > 0,
            "missing reviewed release notes: {}".format(notes))
    return notes


def publish_args(candidate, commit, notes, assets):
    args = ["gh", "release", "create", candidate["tag"]] + [str(x) for x in assets]
    args += ["--repo", REPO, "--target", commit,
             "--title", "AAP2 " + candidate["tag"], "--notes-file", str(notes)]
    if candidate["rc"] is not None:
        args += ["--prerelease", "--latest=false"]
    else:
        args += ["--latest"]
    return args


def publish(candidate, head, directory):
    tools = build_tools()
    previous = check_remote(candidate)
    meta = verify_archive(directory, candidate, head, tools)
    check_against_previous(meta["artifacts"]["github"], previous, tools)
    notes = release_notes(candidate)
    print("Publishing {} as {} (source {})".format(
        candidate["tag"], "PRE-RELEASE (NOT Latest)" if candidate["rc"] else "STABLE Latest", head))
    with tempfile.TemporaryDirectory(prefix="aap2-publish-") as td:
        assets = []
        for name in ("aap2.apk", "aap2-fdroid.apk"):
            if name == "aap2-fdroid.apk" and "fdroid" not in meta["artifacts"]:
                continue
            target = Path(td) / name
            shutil.copyfile(directory / name, target)
            assets.append(target)
        sums = Path(td) / "SHA256SUMS"
        sums.write_text("".join("{}  {}\n".format(sha256(p), p.name) for p in assets),
                        encoding="utf-8")
        assets.append(sums)
        # Recheck tag immediately before creation; gh must not reuse an old tag.
        ensure_tag_absent(candidate["tag"])
        run(publish_args(candidate, head, notes, assets))
    print("Published {}; next: make verify-release TYPE={}".format(
        candidate["tag"], candidate["type"]))


def verify(candidate, head, directory):
    tools = build_tools()
    meta = verify_archive(directory, candidate, head, tools)
    view = json.loads(run(["gh", "release", "view", candidate["tag"], "--repo", REPO,
                           "--json", "tagName,isPrerelease,assets"],
                          capture=True).stdout)
    require(view.get("tagName") == candidate["tag"], "GitHub release tag mismatch")
    require(bool(view.get("isPrerelease")) == (candidate["rc"] is not None),
            "GitHub release Prerelease flag mismatch")
    listing = gh_releases()
    published = next((r for r in listing if r.get("tagName") == candidate["tag"]), None)
    require(published is not None, "GitHub release not listed")
    require(bool(published.get("isPrerelease")) == (candidate["rc"] is not None),
            "published release type differs from requested TYPE")
    if candidate["rc"] is not None:
        require(not published.get("isLatest"), "DANGER: RC is marked Latest")
    else:
        regular = [r for r in listing if not r.get("isPrerelease") and
                   not r.get("isDraft") and rank_tag(r.get("tagName", "")) is not None]
        if regular and max(regular, key=lambda r: rank_tag(r["tagName"]))["tagName"] == candidate["tag"]:
            require(published.get("isLatest"), "newest stable release is not marked Latest")
    names = {a["name"] for a in view.get("assets", [])}
    require({"aap2.apk", "SHA256SUMS"} <= names, "published release missing required assets")
    with tempfile.TemporaryDirectory(prefix="aap2-verify-") as td:
        run(["gh", "release", "download", candidate["tag"], "--repo", REPO,
             "--pattern", "aap2.apk", "--pattern", "aap2-fdroid.apk",
             "--pattern", "SHA256SUMS", "--dir", td])
        for filename, channel in (("aap2.apk", "github"), ("aap2-fdroid.apk", "fdroid")):
            remote = Path(td) / filename
            if filename not in names:
                print("Not published (optional):", filename)
                continue
            require(channel in meta["artifacts"],
                    "published {} has no corresponding local signed candidate".format(filename))
            require(remote.is_file() and sha256(remote) == sha256(directory / filename),
                    "download differs from locally archived {} candidate".format(channel))
        published_sums = (Path(td) / "SHA256SUMS").read_text(encoding="utf-8").splitlines()
        expected_sums = []
        for filename in ("aap2.apk", "aap2-fdroid.apk"):
            if filename in names:
                expected_sums.append("{}  {}".format(sha256(Path(td) / filename), filename))
        require(published_sums == expected_sums, "published SHA256SUMS is not correct")
    print("RELEASE_OK: {} matches signed local candidate; prerelease={}".format(
        candidate["tag"], candidate["rc"] is not None))


def main():
    require(len(sys.argv) == 2 and sys.argv[1] in
            ("prepare", "prepare-fdroid", "publish", "verify"),
            "usage: aap2-release.py <prepare|prepare-fdroid|publish|verify> (TYPE=release|rcN)")
    type_value = os.environ.get("AAP2_RELEASE_TYPE", "")
    candidate = parse_version((ROOT / "version.properties").read_text(encoding="utf-8"),
                              type_value)
    archive_root = Path(os.environ.get("AAP2_RELEASE_DIR", str(Path.home() / "aap2-releases")))
    directory = archive_root / "public" / candidate["tag"]
    head = require_clean_master() if sys.argv[1] != "verify" else run(
        ["git", "rev-parse", "HEAD"], capture=True).stdout.strip()
    if sys.argv[1] == "prepare":
        prepare(candidate, head, directory, "github")
    elif sys.argv[1] == "prepare-fdroid":
        prepare(candidate, head, directory, "fdroid")
    elif sys.argv[1] == "publish":
        publish(candidate, head, directory)
    else:
        verify(candidate, head, directory)


if __name__ == "__main__":
    try:
        main()
    except (ReleaseError, OSError, ValueError, json.JSONDecodeError) as error:
        print("ERROR:", error, file=sys.stderr)
        sys.exit(2)

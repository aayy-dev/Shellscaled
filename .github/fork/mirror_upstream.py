#!/usr/bin/env python3
"""Fork-only automation: mirror upstream Shellscaled releases with arm64, arm, amd64 and universal packages.

This file and .github/workflows/fork-upstream-release.yml exist only in the fork.
Upstream never touches them, so upstream commits cannot conflict with or break
this automation. The upstream tree is checked out separately (read-only) and its
own scripts/build.py builds both packages.

Subcommands:
  plan            decide which upstream release tags still need a fork release
  prepare-source  install upstream's verified arm64 CLI core and pin the Tailscale source
  notes           write the release title/body derived from the upstream release
  compare         report whether the rebuilt arm64 ZIP is byte-identical to upstream's
"""
import argparse
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import urllib.error
import urllib.request

API = "https://api.github.com"
TAG = re.compile(r"v([0-9]{1,6}\.[0-9]{1,6}\.[0-9]{1,6})-r([0-9]{1,2})\Z")
ARCHES = ("arm64", "arm")


def fail(message):
    print(f"::error::{message}", file=sys.stderr)
    raise SystemExit(1)


def api(path, allow_404=False):
    headers = {"Accept": "application/vnd.github+json", "User-Agent": "Shellscaled-fork-mirror",
               "X-GitHub-Api-Version": "2022-11-28"}
    token = os.environ.get("GH_TOKEN") or os.environ.get("GITHUB_TOKEN")
    if token:
        headers["Authorization"] = "Bearer " + token
    request = urllib.request.Request(API + path, headers=headers)
    try:
        with urllib.request.urlopen(request, timeout=60) as response:
            return json.loads(response.read())
    except urllib.error.HTTPError as error:
        if allow_404 and error.code == 404:
            return None
        fail(f"GitHub API {path} returned HTTP {error.code}")


def releases(repo, pages=5):
    result = []
    for page in range(1, pages + 1):
        batch = api(f"/repos/{repo}/releases?per_page=100&page={page}")
        result += [item for item in batch if not item.get("draft")]
        if len(batch) < 100:
            break
    return result


def outputs(**values):
    destination = os.environ.get("GITHUB_OUTPUT")
    if destination:
        with open(destination, "a", encoding="utf-8") as file:
            for key, value in values.items():
                text = value if isinstance(value, str) else json.dumps(value, separators=(",", ":"))
                if "\n" in text:
                    fail("Multi-line workflow output")
                file.write(f"{key}={text}\n")
    print(json.dumps(values, indent=2))


def summary(text):
    destination = os.environ.get("GITHUB_STEP_SUMMARY")
    if destination:
        with open(destination, "a", encoding="utf-8") as file:
            file.write(text + "\n")
    print(text)


def plan(args):
    upstream = [item for item in releases(args.upstream) if TAG.fullmatch(item["tag_name"])]
    upstream.sort(key=lambda item: item["published_at"])
    if not upstream:
        outputs(matrix={"include": []}, count="0")
        return
    mirrored = {item["tag_name"] for item in releases(args.fork)}
    latest = api(f"/repos/{args.upstream}/releases/latest", allow_404=True) or {}
    if args.tag:
        wanted = [item for item in upstream if item["tag_name"] == args.tag]
        if not wanted:
            fail(f"Upstream has no published release {args.tag}")
        if args.tag in mirrored and not args.rebuild:
            print(f"{args.tag} is already mirrored; set rebuild to replace it")
            wanted = []
    else:
        done = [item["published_at"] for item in upstream if item["tag_name"] in mirrored]
        if done:
            cutoff = max(done)
            wanted = [item for item in upstream if item["tag_name"] not in mirrored and item["published_at"] > cutoff]
        else:
            # First run: start from upstream's newest release instead of rebuilding history.
            wanted = [upstream[-1]]
    include = [{"tag": item["tag_name"], "prerelease": bool(item["prerelease"]),
                "latest": item["tag_name"] == latest.get("tag_name")} for item in wanted]
    outputs(matrix={"include": include}, count=str(len(include)))


def load_ci_release(src):
    path = src / "scripts/ci-release.py"
    spec = importlib.util.spec_from_file_location("upstream_ci_release", path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def git(*args, cwd):
    return subprocess.check_output(["git", *args], cwd=cwd, text=True).strip()


def prepare_source(args):
    src = Path(args.src).resolve()
    match = TAG.fullmatch(args.tag)
    if not match:
        fail("Unexpected upstream tag format")
    version, revision = match[1], int(match[2])
    meta = json.loads((src / "module/version.json").read_text())
    if meta.get("tailscaleVersion") != version or meta.get("moduleRevision") != revision:
        fail(f"module/version.json at {args.tag} does not describe {args.tag}")
    channel = meta.get("releaseChannel")
    if channel not in ("stable", "prerelease"):
        fail("Unexpected release channel in module/version.json")
    if (channel == "prerelease") != args.prerelease:
        fail("Upstream release prerelease flag disagrees with its tracked release channel")
    gitlink = git("rev-parse", "HEAD:tailscale", cwd=src)
    if gitlink != meta.get("sourceCommit"):
        fail("Tailscale gitlink differs from module/version.json sourceCommit")
    cli = load_ci_release(src)
    cli_tag = meta.get("cliReleaseTag", "")
    if not cli.TAG.fullmatch(cli_tag):
        fail("Tracked CLI release tag is missing or invalid")
    # Reuse upstream's own verification: GitHub asset digests, manifest schema,
    # ZIP build-info and ELF checks, then pin the submodule to the same source.
    release = json.loads(cli.fetch(f"{cli.API}/repos/{cli.CLI_REPO}/releases/tags/{cli_tag}", 1 << 20))
    loaded = cli.load_release(release)
    if loaded is None:
        fail(f"CLI release {cli_tag} has no arm64 manifest")
    manifest, package = loaded
    if manifest["sourceCommit"] != gitlink or manifest["tailscaleVersion"] != version:
        fail("CLI release source/version differs from the module tag")
    cli.install_binary(manifest, package)
    cli.checkout_source(manifest)
    outputs(version=version, revision=str(revision), channel=channel, cli_tag=cli_tag, source=gitlink)


def notes(args):
    release = api(f"/repos/{args.upstream}/releases/tags/{args.tag}")
    body = release.get("body") or ""
    # Upstream ships arm64 only; the fork's release adds arm, x86_64 and a universal ZIP.
    body = re.sub(r"\bfor ARM64 Android\b", "for ARM64, ARM32 and x86_64 Android", body, count=1)
    Path(args.out).write_text(body if body.endswith("\n") or not body else body + "\n", encoding="utf-8")
    outputs(title=release.get("name") or args.tag)


def compare(args):
    release = api(f"/repos/{args.upstream}/releases/tags/{args.tag}")
    upstream_assets = {item["name"]: item.get("digest", "") for item in release.get("assets", [])}
    dist = Path(args.dist)
    lines = [f"### {args.tag}", "", "| Asset | SHA-256 | Upstream |", "| --- | --- | --- |"]
    for path in sorted(dist.iterdir()):
        digest = hashlib.sha256(path.read_bytes()).hexdigest()
        theirs = upstream_assets.get(path.name)
        status = "n/a (fork only)" if theirs is None else ("identical" if theirs == "sha256:" + digest else "differs")
        lines.append(f"| `{path.name}` | `{digest[:16]}…` | {status} |")
    summary("\n".join(lines) + "\n")


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = parser.add_subparsers(dest="command", required=True)
    p = sub.add_parser("plan")
    p.add_argument("--upstream", required=True)
    p.add_argument("--fork", required=True)
    p.add_argument("--tag", default="")
    p.add_argument("--rebuild", action="store_true")
    p = sub.add_parser("prepare-source")
    p.add_argument("--src", required=True)
    p.add_argument("--tag", required=True)
    p.add_argument("--prerelease", action="store_true")
    p = sub.add_parser("notes")
    p.add_argument("--upstream", required=True)
    p.add_argument("--tag", required=True)
    p.add_argument("--out", required=True)
    p = sub.add_parser("compare")
    p.add_argument("--upstream", required=True)
    p.add_argument("--tag", required=True)
    p.add_argument("--dist", required=True)
    args = parser.parse_args()
    {"plan": plan, "prepare-source": prepare_source, "notes": notes, "compare": compare}[args.command](args)


if __name__ == "__main__":
    main()

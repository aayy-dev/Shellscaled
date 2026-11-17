#!/usr/bin/env python3
"""Combine upstream build.py's per-ABI package stages into one universal module ZIP.

Fork-only. Reads <src>/.work/package/<arch>/ (left by scripts/build.py) and writes
<src>/dist/Shellscaled-<version>-r<N>-universal.zip and its .zip.sha256.

Layout: shared files at the root (taken from the first ABI and required to be
identical in every ABI), per-ABI files under arch/<arch>/, upstream's
customize.sh under arch/customize.sh, and the fork's selector as customize.sh.
At install time the selector restores the exact single-ABI tree, so upstream's
SHA256SUMS and architecture checks run unchanged.
"""
import argparse
import hashlib
import json
from pathlib import Path
import zipfile

PER_ARCH = ("module.prop", "build-info.json", "SHA256SUMS", "bin/tailscaled", "bin/shellscaled-web")
HERE = Path(__file__).resolve().parent


def files(stage):
    return {p.relative_to(stage).as_posix(): p for p in sorted(stage.rglob("*")) if p.is_file()}


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--src", required=True, help="upstream checkout that ran scripts/build.py")
    parser.add_argument("--arch", action="append", required=True, help="ABI to include (repeatable, first is the base)")
    args = parser.parse_args()
    src = Path(args.src).resolve()
    stages = {arch: src / ".work/package" / arch for arch in args.arch}
    trees = {arch: files(stage) for arch, stage in stages.items()}
    base = args.arch[0]
    shared = sorted(set(trees[base]) - set(PER_ARCH))
    infos = {}
    for arch, tree in trees.items():
        missing = [name for name in PER_ARCH if name not in tree]
        if missing:
            raise SystemExit(f"{arch} stage lacks {missing}")
        extra = sorted(set(tree) - set(PER_ARCH) - set(shared))
        if extra or set(shared) - set(tree):
            raise SystemExit(f"{arch} stage has a different file set: {extra or sorted(set(shared) - set(tree))}")
        for name in shared:
            if tree[name].read_bytes() != trees[base][name].read_bytes():
                raise SystemExit(f"{name} differs between {base} and {arch}; it must become a per-ABI file")
        info = json.loads(tree["build-info.json"].read_text())
        if info["architecture"] != arch:
            raise SystemExit(f"{arch} build-info reports {info['architecture']}")
        infos[arch] = info
    keys = ("tailscaleVersion", "moduleRevision", "versionCode", "moduleSourceCommit", "sourceCommit", "releaseChannel")
    for arch, info in infos.items():
        if any(info.get(k) != infos[base].get(k) for k in keys):
            raise SystemExit(f"{arch} was built from a different source/version than {base}")
    if "customize.sh" not in shared:
        raise SystemExit("Upstream customize.sh is missing")

    entries = {}
    for name in shared:
        if name != "customize.sh":
            entries[name] = trees[base][name].read_bytes()
    entries["arch/customize.sh"] = trees[base]["customize.sh"].read_bytes()
    entries["customize.sh"] = (HERE / "universal-customize.sh").read_bytes()
    root_prop = trees[base]["module.prop"].read_text().replace(f"architecture={base}\n", "architecture=universal\n")
    entries["module.prop"] = root_prop.encode()
    for arch, tree in trees.items():
        for name in PER_ARCH:
            entries[f"arch/{arch}/{name}"] = tree[name].read_bytes()

    version, revision = infos[base]["tailscaleVersion"], infos[base]["moduleRevision"]
    dest = src / "dist" / f"Shellscaled-{version}-r{revision}-universal.zip"
    dest.parent.mkdir(exist_ok=True)
    # Same deterministic ZIP conventions as upstream scripts/build.py.
    with zipfile.ZipFile(dest, "w", compression=zipfile.ZIP_DEFLATED, compresslevel=9) as archive:
        for name in sorted(entries):
            path = Path(name)
            info = zipfile.ZipInfo(name, date_time=(2020, 1, 1, 0, 0, 0))
            executable = path.suffix == ".sh" or path.parent.name == "bin" or path.name == "update-binary"
            info.external_attr = (0o100755 if executable else 0o100644) << 16
            archive.writestr(info, entries[name], compress_type=zipfile.ZIP_DEFLATED, compresslevel=9)
    digest = hashlib.sha256(dest.read_bytes()).hexdigest()
    dest.with_suffix(".zip.sha256").write_text(f"{digest}  {dest.name}\n")
    print(f"Built {dest.name} ({', '.join(args.arch)}): {dest.stat().st_size // 1024 // 1024} MiB, sha256 {digest}")


if __name__ == "__main__":
    main()

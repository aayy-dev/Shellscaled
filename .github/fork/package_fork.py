#!/usr/bin/env python3
"""Build the fork's module ZIPs from upstream build.py's per-ABI package stages.

Fork-only. Reads <src>/.work/package/<arch>/ (left by scripts/build.py) and writes one ZIP
containing the given ABIs, plus its .zip.sha256 (and, for a single ABI, an updated
.binary.json):

  python3 package_fork.py --src src --arch arm64 [--apk Shellscaled.apk]              -> ...-arm64.zip
  python3 package_fork.py --src src --arch arm64 --arch arm --arch amd64 --name universal

Layout: shared files at the root (byte-identical across ABIs), per-ABI files under
arch/<arch>/, upstream's customize.sh under arch/customize.sh, and the fork front end
(fork-customize.sh) as customize.sh. At install time the front end restores the exact
single-ABI tree and sources upstream's unchanged installer, so its SHA256SUMS and
architecture checks still run; then it installs the bundled app.

With --apk, each ABI tree also gets app/Shellscaled.apk, an app-removal hook at the top of
uninstall.sh, and a regenerated SHA256SUMS (same rules as upstream build.py) so the APK
is integrity-checked too. The final per-ABI trees are written to
<src>/.work/fork-package/<arch>/ for test-package.sh.
"""
import argparse
import hashlib
import json
from pathlib import Path, PurePosixPath
import zipfile

PER_ARCH = ("module.prop", "build-info.json", "SHA256SUMS", "bin/tailscaled", "bin/shellscaled-web")
APP_PATH = "app/Shellscaled.apk"
APP_ID = "io.github.aayydev.shellscaled"
# Root managers run uninstall.sh early in boot, before Android's package manager exists, so the
# hook waits in the background (at most 10 minutes) for boot to finish. It sits at the top so that
# upstream's early exits cannot skip it, and it only ever removes this module's own app.
UNINSTALL_HOOK = (
    "# Fork: remove the companion app once the package manager is running (no-op if absent).\n"
    "(\n"
    "    sc_fork_wait=0\n"
    "    while [ \"$(/system/bin/getprop sys.boot_completed)\" != 1 ] && [ \"$sc_fork_wait\" -lt 600 ]; do\n"
    "        sleep 1\n"
    "        sc_fork_wait=$((sc_fork_wait + 1))\n"
    "    done\n"
    f"    /system/bin/pm uninstall {APP_ID}\n"
    ") </dev/null >/dev/null 2>&1 &\n"
)
HERE = Path(__file__).resolve().parent


def read_stage(stage):
    return {p.relative_to(stage).as_posix(): p.read_bytes() for p in sorted(stage.rglob("*")) if p.is_file()}


def sha256sums(tree):
    # Mirrors scripts/build.py: every file except META-INF/ and SHA256SUMS itself, sorted by path.
    lines = []
    for name in sorted(tree, key=PurePosixPath):
        if name == "SHA256SUMS" or name.split("/", 1)[0] == "META-INF":
            continue
        lines.append(f"{hashlib.sha256(tree[name]).hexdigest()}  {name}\n")
    return "".join(lines).encode()


def with_app(tree, apk):
    tree = dict(tree)
    tree[APP_PATH] = apk
    uninstall = tree["uninstall.sh"].decode()
    if UNINSTALL_HOOK not in uninstall:
        shebang, newline, rest = uninstall.partition("\n")
        if not shebang.startswith("#!"):
            raise SystemExit("Upstream uninstall.sh has no shebang line")
        tree["uninstall.sh"] = (shebang + newline + UNINSTALL_HOOK + rest).encode()
    tree["SHA256SUMS"] = sha256sums(tree)
    return tree


def write_zip(dest, entries):
    # Same deterministic ZIP conventions as upstream scripts/build.py.
    with zipfile.ZipFile(dest, "w", compression=zipfile.ZIP_DEFLATED, compresslevel=9) as archive:
        for name in sorted(entries):
            path = PurePosixPath(name)
            info = zipfile.ZipInfo(name, date_time=(2020, 1, 1, 0, 0, 0))
            executable = path.suffix == ".sh" or path.parent.name == "bin" or path.name == "update-binary"
            info.external_attr = (0o100755 if executable else 0o100644) << 16
            archive.writestr(info, entries[name], compress_type=zipfile.ZIP_DEFLATED, compresslevel=9)


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--src", required=True, help="upstream checkout that ran scripts/build.py")
    parser.add_argument("--arch", action="append", required=True, help="ABI to include (repeatable; first is the base)")
    parser.add_argument("--name", help="ZIP suffix (default: the single ABI)")
    parser.add_argument("--apk", help="signed app APK to bundle")
    args = parser.parse_args()
    if len(args.arch) > 1 and not args.name:
        parser.error("--name is required for multi-ABI packages")
    src = Path(args.src).resolve()
    apk = Path(args.apk).read_bytes() if args.apk else None

    trees = {arch: read_stage(src / ".work/package" / arch) for arch in args.arch}
    if apk is not None:
        trees = {arch: with_app(tree, apk) for arch, tree in trees.items()}
    base = args.arch[0]
    shared = sorted(set(trees[base]) - set(PER_ARCH))
    infos = {}
    for arch, tree in trees.items():
        missing = [name for name in PER_ARCH if name not in tree]
        if missing:
            raise SystemExit(f"{arch} stage lacks {missing}")
        if set(tree) - set(PER_ARCH) != set(shared):
            raise SystemExit(f"{arch} stage has a different file set than {base}")
        for name in shared:
            if tree[name] != trees[base][name]:
                raise SystemExit(f"{name} differs between {base} and {arch}; it must become a per-ABI file")
        info = json.loads(tree["build-info.json"])
        if info["architecture"] != arch:
            raise SystemExit(f"{arch} build-info reports {info['architecture']}")
        infos[arch] = info
    keys = ("tailscaleVersion", "moduleRevision", "versionCode", "moduleSourceCommit", "sourceCommit", "releaseChannel")
    for arch, info in infos.items():
        if any(info.get(k) != infos[base].get(k) for k in keys):
            raise SystemExit(f"{arch} was built from a different source/version than {base}")
    if "customize.sh" not in shared:
        raise SystemExit("Upstream customize.sh is missing")
    # The installer front end creates bin/ itself; keep the assumption explicit anyway.
    if not any(name.startswith("bin/") for name in trees[base]):
        raise SystemExit("Upstream package has no bin/ directory")

    # Final single-ABI trees, exactly as they must look after installation.
    for arch, tree in trees.items():
        out = src / ".work/fork-package" / arch
        for name, data in tree.items():
            (out / name).parent.mkdir(parents=True, exist_ok=True)
            (out / name).write_bytes(data)

    entries = {name: trees[base][name] for name in shared if name != "customize.sh"}
    entries["arch/customize.sh"] = trees[base]["customize.sh"]
    entries["customize.sh"] = (HERE / "fork-customize.sh").read_bytes()
    label = args.name or base
    prop = trees[base]["module.prop"].decode()
    if len(args.arch) > 1:
        prop = prop.replace(f"architecture={base}\n", "architecture=universal\n")
    entries["module.prop"] = prop.encode()
    for arch, tree in trees.items():
        for name in PER_ARCH:
            entries[f"arch/{arch}/{name}"] = tree[name]

    version, revision = infos[base]["tailscaleVersion"], infos[base]["moduleRevision"]
    dest = src / "dist" / f"Shellscaled-{version}-r{revision}-{label}.zip"
    dest.parent.mkdir(exist_ok=True)
    write_zip(dest, entries)
    digest = hashlib.sha256(dest.read_bytes()).hexdigest()
    dest.with_suffix(".zip.sha256").write_text(f"{digest}  {dest.name}\n")
    manifest_path = dest.with_suffix(".binary.json")
    if len(args.arch) == 1 and manifest_path.exists():
        manifest = json.loads(manifest_path.read_text())
        if manifest.get("packageName") != dest.name or manifest.get("architecture") != base:
            raise SystemExit("Upstream binary manifest does not describe this package")
        manifest["packageSHA256"] = digest
        manifest_path.write_text(json.dumps(manifest, indent=2) + "\n")
    print(f"Built {dest.name} ({', '.join(args.arch)}{', app' if apk else ''}): "
          f"{dest.stat().st_size // 1024 // 1024} MiB, sha256 {digest}")


if __name__ == "__main__":
    main()

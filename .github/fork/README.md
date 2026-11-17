# Fork release mirror (arm64 + arm + x86_64 + universal)

Fork-only automation that mirrors every new [upstream](https://github.com/KyuubiRan/Shellscaled) release with these packages:

| Asset | ABI |
| --- | --- |
| `Shellscaled-<version>-r<N>-arm64.zip` (+ `.zip.sha256`, `.binary.json`) | `arm64-v8a` |
| `Shellscaled-<version>-r<N>-arm.zip` (+ `.zip.sha256`, `.binary.json`) | `armeabi-v7a` (32-bit ARM) |
| `Shellscaled-<version>-r<N>-amd64.zip` (+ `.zip.sha256`, `.binary.json`) | `x86_64` |
| `Shellscaled-<version>-r<N>-universal.zip` (+ `.zip.sha256`) | any of the three; about 3× the download size |

### Universal ZIP

[`make_universal.py`](make_universal.py) combines the three per-ABI package trees:

- Files shared by every ABI go at the root, and must be byte-identical across ABIs.
- `module.prop`, `build-info.json`, `SHA256SUMS`, `bin/tailscaled` and `bin/shellscaled-web` go under `arch/<abi>/`.
- Upstream's `customize.sh` goes under `arch/customize.sh`.
- [`universal-customize.sh`](universal-customize.sh) becomes the installer.

At install time the selector reads `ro.product.cpu.abi` (the same property upstream checks). It moves that ABI's files into place, deletes `arch/`, restores upstream's `customize.sh`, and sources it. Upstream's checksum and architecture checks then run unchanged. The installed tree is identical to the single-ABI package, so the service, the WebUI and the binary updater behave the same as with a per-ABI ZIP.

[`test-universal.sh`](test-universal.sh) checks this on the host after every CI build:

- Each ABI ends with exactly the single-ABI file tree.
- 32-bit x86 is rejected.
- A tampered binary fails upstream's checksum check.

The files live outside upstream's paths (`.github/workflows/fork-upstream-release.yml` and `.github/fork/`), so upstream changes to its own `module-release.yml` or scripts never conflict with or break them.

## Process

[`fork-upstream-release.yml`](../workflows/fork-upstream-release.yml) runs every hour and can also be started manually:

1. **Plan:** list upstream releases published after the newest one already mirrored. The first run mirrors only upstream's newest release.
2. **Sync:** merge upstream `main` into the fork's `main` with the `GH_TOKEN` secret, a PAT with `repo` + `workflow` scopes. Re-disable upstream's own workflows inherited by the fork.
3. **Build:** check out the exact upstream release tag and run upstream's `scripts/build.py`.
   - `arm64` repackages the verified `tailscaled` from the linked `KyuubiRan/tailscale-android-cli` release, using upstream's `ci-release.py` checks. This is the same process upstream uses.
   - `arm` (`GOARM=7`) and `amd64` compile `tailscaled` with the NDK from the same pinned Tailscale source commit. Upstream's build script already supports both.
   - The universal ZIP is assembled from the three package trees and tested.
4. **Release:** push the upstream tag (same commit) to the fork, then publish a release with upstream's title, channel (Pre-release or stable/Latest), and description. The arch phrase changes to "ARM64, ARM32 and x86_64", and all eleven assets are attached.

The job summary shows whether each arm64 asset is byte-identical to upstream's.

Manual run options: `tag` (mirror one upstream tag), `rebuild` (replace an existing fork release), `sync` (merge upstream `main` even when nothing was released).

## Limitations

- The arm32, x86_64 and universal packages have passed build checks and the host installer test only. They have not been installed on a device or emulator; this Mac currently has no `Medium_Phone` AVD.
- In-app binary updates read only `KyuubiRan/tailscale-android-cli` releases, which ship only arm64. On arm32 and x86_64 installs, the binary updater offers nothing. Update by installing a newer fork ZIP.
- A merge conflict between upstream `main` and fork-only commits fails the sync job. Releases still build from the upstream tag.

# Fork release mirror (arm64 + arm + x86_64 + universal + app)

Fork-only automation that mirrors every new [upstream](https://github.com/KyuubiRan/Shellscaled) release with these assets:

| Asset | Contents |
| --- | --- |
| `Shellscaled-<version>-r<N>-arm64.zip` (+ `.zip.sha256`, `.binary.json`) | module for `arm64-v8a` |
| `Shellscaled-<version>-r<N>-arm.zip` (+ `.zip.sha256`, `.binary.json`) | module for `armeabi-v7a` (32-bit ARM) |
| `Shellscaled-<version>-r<N>-amd64.zip` (+ `.zip.sha256`, `.binary.json`) | module for `x86_64` |
| `Shellscaled-<version>-r<N>-universal.zip` (+ `.zip.sha256`) | module for any of the three; about 3× the download size |
| `Shellscaled-<version>-r<N>.apk` (+ `.apk.sha256`) | the [native app](../../android/README.md), also bundled into every ZIP |

### Fork ZIP layout

[`package_fork.py`](package_fork.py) builds every fork ZIP from upstream `build.py`'s per-ABI package trees.

**Layout:**
- Files shared by every ABI go at the root, and must be byte-identical across ABIs.
- `module.prop`, `build-info.json`, `SHA256SUMS`, `bin/tailscaled` and `bin/shellscaled-web` go under `arch/<abi>/`.
- Upstream's `customize.sh` goes under `arch/customize.sh`.
- [`fork-customize.sh`](fork-customize.sh) becomes the installer.

**With `--apk`, each ABI tree also gets:**
- `app/Shellscaled.apk`;
- a `pm uninstall` line appended to `uninstall.sh`;
- a regenerated `SHA256SUMS` (same rules as upstream `build.py`), so the APK is integrity-checked too.

**At install time** the front end reads `ro.product.cpu.abi` (the same property upstream checks). It moves that ABI's files into place, deletes `arch/`, restores upstream's `customize.sh`, and sources it, so upstream's checksum and architecture checks run unchanged. If those pass, it installs the bundled APK with `pm install -r` (streamed over stdin, with a `/data/local/tmp` fallback). A failure there doesn't fail the module install.

[`test-package.sh`](test-package.sh) runs on the host for all four ZIPs after every CI build. It checks that:
- each accepted ABI ends with exactly the expected single-ABI tree;
- the app is installed through a stubbed `pm`;
- wrong ABIs and 32-bit x86 are rejected;
- a tampered binary or APK fails upstream's checksum check before `pm` runs.

The files live outside upstream's paths (`.github/workflows/fork-upstream-release.yml` and `.github/fork/`), so upstream changes to its own `module-release.yml` or scripts never conflict with or break them.

## Process

[`fork-upstream-release.yml`](../workflows/fork-upstream-release.yml) runs every hour and can also be started manually:

1. **Plan:** list upstream releases published after the newest one already mirrored. The first run mirrors only upstream's newest release.
2. **Sync:** merge upstream `main` into the fork's `main` with the `GH_TOKEN` secret, a PAT with `repo` + `workflow` scopes. Re-disable upstream's own workflows inherited by the fork.
3. **Build:** check out the exact upstream release tag and run upstream's `scripts/build.py`.
   - `arm64` repackages the verified `tailscaled` from the linked `KyuubiRan/tailscale-android-cli` release, using upstream's `ci-release.py` checks. This is the same process upstream uses.
   - `arm` (`GOARM=7`) and `amd64` compile `tailscaled` with the NDK from the same pinned Tailscale source commit. Upstream's build script already supports both.
   - The app is built, unit-tested, linted and signed with the fork's release key. The signer is checked against [`app-signing-cert.sha256`](app-signing-cert.sha256).
   - Every ZIP is repackaged with the app, then the universal ZIP is assembled; all four are tested.
4. **Release:** push the upstream tag (same commit) to the fork, then publish a release with upstream's title, channel (Pre-release or stable/Latest), and description. The arch phrase changes to "ARM64, ARM32 and x86_64", and all thirteen assets are attached.

The job summary compares each asset with upstream's. The arm64 ZIP now differs because it bundles the app and the fork front end; the `tailscaled` inside it is still upstream's verified binary.

Manual run options: `tag` (mirror one upstream tag), `rebuild` (replace an existing fork release), `sync` (merge upstream `main` even when nothing was released).

## Limitations

- The arm32, x86_64 and universal packages and the app have passed build checks, unit tests and the host installer test only. They have not been installed on a device or emulator; this Mac currently has no `Medium_Phone` AVD.
- In-app binary updates read only `KyuubiRan/tailscale-android-cli` releases, which ship only arm64. On arm32 and x86_64 installs, the binary updater offers nothing. Update by installing a newer fork ZIP.
- A merge conflict between upstream `main` and fork-only commits fails the sync job. Releases still build from the upstream tag.

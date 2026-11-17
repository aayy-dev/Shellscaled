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
- an app-removal hook at the top of `uninstall.sh` (see below);
- a regenerated `SHA256SUMS` (same rules as upstream `build.py`), so the APK is integrity-checked too.

**At install time** the front end reads `ro.product.cpu.abi` (the same property upstream checks). It moves that ABI's files into place, deletes `arch/`, restores upstream's `customize.sh`, and sources it, so upstream's checksum and architecture checks run unchanged. If those pass, it installs the bundled APK with `pm install -r`: streamed over stdin, with a `/data/local/tmp` fallback and a 120-second limit. A failure there doesn't fail the module install.

**Removing the app.** Root managers run `uninstall.sh` early in boot, before Android's package manager exists. The hook is therefore placed first, so upstream's early exits can't skip it. It waits in the background (up to 10 minutes) for boot to finish, then runs `pm uninstall` on this module's own app only. This is unverified on devices. As a fallback, the app shows an "Uninstall this app" button once it finds the module gone.

[`test-package.sh`](test-package.sh) runs on the host for all four ZIPs after every CI build. It checks that:
- each accepted ABI ends with exactly the expected single-ABI tree;
- the whole APK is streamed to a stubbed `pm`;
- the `/data/local/tmp` fallback works and cleans up;
- a `pm` refusal still leaves a working module install;
- the uninstall hook comes first;
- wrong ABIs and 32-bit x86 are rejected;
- a tampered binary or APK fails upstream's checksum check before `pm` runs.

The files live outside upstream's paths (`.github/workflows/fork-upstream-release.yml` and `.github/fork/`), so upstream changes to its own `module-release.yml` or scripts never conflict with or break them.

## Trust boundaries

- **Upstream code only in jobs without secrets.** Upstream code (`build.py`, `ci-release.py`, and upstream's `customize.sh` in the host installer test) runs only in the `build` and `package` jobs. Those jobs have a read-only token and no secrets.
- **The signing key is reachable from one job.** It lives in the `app-signing` environment, which only accepts runs from `main`, and only the `app` job can read it. That job checks out fork code (`android/`, `.github/fork/`) and nothing from upstream.
- **The PAT (`GH_TOKEN`) only pushes git refs.** It pushes the merged `main` and release tags.
- **New upstream workflow files block the sync.** If upstream adds a workflow file, the sync job refuses to push it. Such a file would run with the fork's secrets, so a person has to review and disable it first.
- **Jobs pass data, not credentials.** Packages, the APK and assets travel between jobs as artifacts.

## Process

[`fork-upstream-release.yml`](../workflows/fork-upstream-release.yml) runs every hour and can also be started manually:

1. **Plan:** list every upstream release since the first one mirrored that the fork still lacks, at most 5 per run. A failed release is therefore retried later. The first run mirrors only upstream's newest release.
2. **Sync:** merge upstream `main` into the fork's `main` with the `GH_TOKEN` secret, a PAT with `repo` + `workflow` scopes. Re-disable upstream's own workflows inherited by the fork.
3. **Build:** check out the exact upstream release tag and run upstream's `scripts/build.py`.
   - `arm64` repackages the verified `tailscaled` from the linked `KyuubiRan/tailscale-android-cli` release, using upstream's `ci-release.py` checks. This is the same process upstream uses.
   - `arm` (`GOARM=7`) and `amd64` compile `tailscaled` with the NDK from the same pinned Tailscale source commit. Upstream's build script already supports both.
   - The app is built, unit-tested, linted and signed with the fork's release key. The signer is checked against [`app-signing-cert.sha256`](app-signing-cert.sha256).
   - Every ZIP is repackaged with the app, then the universal ZIP is assembled; all four are tested.
4. **Release:** push the upstream tag (same commit) to the fork. Then publish draft-first:
   - Upload every asset to a new draft and verify its size and digest.
   - Only then replace the old release (on rebuild) and publish with upstream's title, channel (Pre-release or stable/Latest), and description.
   - Drafts left behind by interrupted runs are removed first. The arch phrase changes to "ARM64, ARM32 and x86_64", and all thirteen assets are attached.

The job summary compares each asset with upstream's. The arm64 ZIP now differs because it bundles the app and the fork front end; the `tailscaled` inside it is still upstream's verified binary.

Manual run options: `tag` (mirror one upstream tag), `rebuild` (replace an existing fork release), `sync` (merge upstream `main` even when nothing was released).

## Limitations

- The arm32, x86_64 and universal packages and the app have passed build checks, unit tests and the host installer test only. They have not been installed on a device or emulator; this Mac currently has no `Medium_Phone` AVD.
- In-app binary updates read only `KyuubiRan/tailscale-android-cli` releases, which ship only arm64. On arm32 and x86_64 installs, the binary updater offers nothing. Update by installing a newer fork ZIP.
- A merge conflict between upstream `main` and fork-only commits fails the sync job, as does an upstream change that adds a workflow. Releases still build from the upstream tag.

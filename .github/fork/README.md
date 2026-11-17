# Fork release mirror (arm64 + arm)

Fork-only automation that mirrors every new [upstream](https://github.com/KyuubiRan/Shellscaled) release with two packages:

| Asset | ABI |
| --- | --- |
| `Shellscaled-<version>-r<N>-arm64.zip` (+ `.zip.sha256`, `.binary.json`) | `arm64-v8a` |
| `Shellscaled-<version>-r<N>-arm.zip` (+ `.zip.sha256`, `.binary.json`) | `armeabi-v7a` (32-bit ARM) |

The files live outside upstream's paths (`.github/workflows/fork-upstream-release.yml` and `.github/fork/`), so upstream changes to its own `module-release.yml` or scripts never conflict with or break them.

## Process

[`fork-upstream-release.yml`](../workflows/fork-upstream-release.yml) runs every hour and can also be started manually:

1. **Plan:** list upstream releases published after the newest one already mirrored. The first run mirrors only upstream's newest release.
2. **Sync:** merge upstream `main` into the fork's `main` with the `GH_TOKEN` secret, a PAT with `repo` + `workflow` scopes. Re-disable upstream's own workflows inherited by the fork.
3. **Build:** check out the exact upstream release tag and run upstream's `scripts/build.py`.
   - `arm64` repackages the verified `tailscaled` from the linked `KyuubiRan/tailscale-android-cli` release, using upstream's `ci-release.py` checks. This is the same process upstream uses.
   - `arm` compiles `tailscaled` with `GOARCH=arm GOARM=7` and NDK `armv7a-linux-androideabi23-clang` from the same pinned Tailscale source commit.
4. **Release:** push the upstream tag (same commit) to the fork, then publish a release with upstream's title, channel (Pre-release or stable/Latest), and description. The arch phrase changes to "ARM64 and ARM32", and all six assets are attached.

The job summary shows whether each arm64 asset is byte-identical to upstream's.

Manual run options: `tag` (mirror one upstream tag), `rebuild` (replace an existing fork release), `sync` (merge upstream `main` even when nothing was released).

## Limitations

- The arm32 package has passed build-only checks. It has not been run on a 32-bit device. The runtime-test emulator (`Medium_Phone`) is 64-bit.
- In-app binary updates read only `KyuubiRan/tailscale-android-cli` releases, which ship only arm64. On arm32 installs, the binary updater offers nothing. Update by installing a newer fork ZIP.
- A merge conflict between upstream `main` and fork-only commits fails the sync job. Releases still build from the upstream tag.

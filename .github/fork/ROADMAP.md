# Fork roadmap: custom Tailscale core and a native Android app

Status: Part 2 (native app) has an implemented and CI-built MVP with WebUI parity; see `android/README.md`. Part 1 is still a proposal. "Verified" means checked by reading code, compiling, or running a CI job; "inferred" means a conclusion from code that has not been runtime-tested. Runtime testing happens only on an emulator: `Medium_Phone` (AGENTS.md), or the owner-approved `play` AVD (arm64, API 37, Magisk), where the app and the arm64 package were exercised on 2026-11-17 (`android/README.md`).

## Ground rules for every phase

- Fork-only code lives in paths upstream does not use (`.github/fork/`, `.github/workflows/fork-*.yml`, a new top-level `app/`). Changes to upstream files are applied as patch series at build time and committed to a fork release commit, never edited on `main`. A patch that stops applying fails the release job loudly instead of shipping something half-patched.
- Release trigger stays the same: a new upstream Shellscaled release tag. Fork tags keep upstream's name (`vX.Y.Z-rN`).
- Release provenance: once fork patches change shipped content, the fork tag points at a fork release commit made on top of the upstream tag commit. That commit holds the patched files and the gitlink to the patched Tailscale commit, so every published ZIP can be rebuilt from a public commit.

---

## Part 1. Ship the custom Tailscale core (`aayy-dev/tailscale`)

### Findings (verified unless marked)

- **Fork base:** `android-rooted` is official Tailscale `main` at `e3c3ef18a`, where `VERSION.txt` reads 1.105.0 (unstable), plus 13 commits.
- **Fork build:** `GOOS=linux`, `CGO_ENABLED=0`, a static binary, built with only `ts_omit_clientupdate`. It sets state and socket paths for the owner's own device at link time.
- **Current core:** Shellscaled builds from KyuubiRan's tree, which is exactly v1.104.6 plus 33 Android commits. It is built as `GOOS=android` with cgo and `/system/bin/linker64`. It carries the routing rules that leave other VPNs alone, the DNS guard, helper hooks (`debug?action=reconfig`, the IPv6 exit-node opt-out, the Taildrop inbox), and the safe defaults.
- **Swapping in the fork binary as-is breaks things:**
  1. The binary updater and release CI reject a static Linux ELF (`helper/update.go:807-845`, `scripts/ci-release.py:317-339`). The updater would also quietly reinstall KyuubiRan's core (`update.go:30`).
  2. The fork's routing rule (fwmark `0x80000`, priority 5270) sits ahead of Android's own VPN rules. With an exit node selected, it could take traffic away from another active VPN. That conflicts with AGENTS.md's "never silently replace the active Android VPN" (inferred).
  3. The helper hardcodes KyuubiRan's packet marks in its DNS proxy, its update downloads and its socket cleanup (`dnsproxy.go:491,515,778`, `update.go:123`, `sockdestroy.go:233`).
  4. The fork lacks the helper hooks: the IPv6 exit-node switch and reconfiguration.
  5. Its version (1.105.0, unstable) gets ahead of the 1.104.x release track.
- **Cherry-picking the fork onto KyuubiRan's 1.104.6 source:** 11 of the 13 commits apply cleanly and compile with Shellscaled's `build.py` flags. The two SSH user-lookup commits conflict. The fork's router commit merges cleanly but silently disables KyuubiRan's Android routing rules, so it must be dropped.
- **Tailscale SSH:** this is the main feature the fork adds. Upstream Tailscale excludes SSH from `GOOS=android` builds (`feature/ssh`, `ssh/tailssh` are tagged `linux && !android`), and Shellscaled builds with `ts_omit_ssh`. Enabling it means porting, not just cherry-picking.

### Recommended approach: keep the Android core and add a maintained patch series

1. In `aayy-dev/tailscale`, create a `shellscaled-patches` branch: KyuubiRan's pinned commit plus only the fork commits that make sense on Android.
   - **Keep:** the DNS forwarder fix (`7c37ea13c`), the CLI's Android support (`a4297bb8c`), and the `ping` full-name fix (`8b62b19be`).
   - **Drop:** the router rule (`a3beec8c7`) and `build_android_rooted.sh`.
   - **Optional:** the path overrides (`ea402cc60`, `a3efa71e8`). Harmless, but not needed.
2. **Optional Tailscale SSH**, as new commits:
   - Lift the `!android` build constraints in `feature/ssh` and `ssh/tailssh`.
   - Port the fork's root-user lookup onto KyuubiRan's `user_android.go`.
   - Pick Android defaults: `/system/bin/sh` as the shell, home under `/data/adb/shellscaled`.
   - Keep the OOM fix (`a0f7ae9ab`).
   - Build Shellscaled without `ts_omit_ssh`.
   - Ship SSH **off by default**. Turning it on is an explicit user action (`tailscale set --ssh`), plus a switch in the app.
   - Security review before release: it hands tailnet peers that your ACLs allow a root shell.
3. **Release workflow changes** (`fork-upstream-release.yml`):
   1. Read the upstream tag's `tailscale` gitlink, which is KyuubiRan's pinned commit.
   2. Rebase `shellscaled-patches` onto that commit. On conflict, fail and open an issue.
   3. Push the result as `aayy-dev/tailscale` tag `shellscaled-vX.Y.Z-rN`.
   4. Make the fork release commit: `.gitmodules` URL pointing at `aayy-dev/tailscale` plus the new gitlink.
   5. Build **every** ABI from source. arm64 stops using `--package-only` and is no longer byte-identical to upstream.
   - The version stays the upstream Tailscale version from `VERSION.txt`. The build stamp already appends `-shellscaled-<commit>`.
4. **Updater:** the in-app binary updater must never put KyuubiRan's core back.
   - Phase 1: turn the updater off in fork packages via a small build-time patch to `helper/update.go`. Updates come from installing the next fork ZIP.
   - Phase 2, optional: publish fork core assets in the same manifest format and point the updater at `aayy-dev/tailscale` releases.
5. **Verification each release:**
   - CI: compile, plus the existing ELF and manifest checks.
   - On `Medium_Phone`: connect, MagicDNS, exit node with another VPN active, Taildrop, live update. If SSH is enabled, also SSH in from a test tailnet node.

### Alternative not recommended: a separate Linux-mode package

Shipping the static `GOOS=linux` binary as its own module variant would need all of the following: a second ELF policy, configurable packet marks in the helper, routing-rule cleanup when switching between binaries, its own updater source, and a fix for the VPN-precedence conflict above. That amounts to maintaining a second product. Revisit only if a specific Linux-only feature cannot be ported to the Android build.

---

## Part 2. Native Android app (phones, tablets, foldables, Android TV / Google TV / Fire TV)

### Architecture

- **The app is a UI client only.** The root helper `shellscaled-web` stays the backend and keeps every existing guard: the JSON RPC with per-action field allowlists, the command allowlist, URL validation, and the Taildrop path rules. The app sends exactly the same RPC payloads the WebUI sends today (`{"action":..., "locale":...}`, base64). No new root capability is added, and the app never gets a general shell.
- **Transport, phase 1:** [libsu](https://github.com/topjohnwu/libsu) keeps one root shell open and runs `/data/adb/modules/shellscaled/bin/shellscaled-web rpc <b64>`. This works the same on Magisk, KernelSU and APatch.
  - Root access is granted per manager. Magisk shows a prompt. KernelSU and APatch show no prompt; the user must allow the app in the manager. The app detects a missing grant and shows step-by-step instructions for the detected manager.
- **Transport, phase 2 (evaluate on the emulator):** a no-grant transport, so users never have to hand the app root.
  - **Shape:** the helper runs a socket that admits only the app's UID and signing certificate.
  - **Open question:** on enforcing SELinux this needs a narrowly scoped `sepolicy.rule` that both Magisk and KernelSU accept. Adopt it only if the rule can be limited to this socket.
- **Packaging:**
  - Add a fork-only `app/` Gradle project in Kotlin with Jetpack Compose (Material 3), plus `androidx.tv` components for TV.
  - minSdk 23 to match the NDK API level. One APK works on every ABI (Compose's small native graphics library ships for all four ABIs). No Google Play Services, so it works on Fire OS, de-Googled ROMs and Huawei. No `INTERNET` permission; all networking stays in the root helper.
  - CI builds and signs the APK and attaches it to each release as `Shellscaled-<version>-r<N>.apk`.
  - CI also places it inside every module ZIP (`app/Shellscaled.apk`). The packaging step re-runs upstream's `SHA256SUMS` generation so the APK is integrity-checked.
- **Module install:**
  - The fork installer front end (`fork-customize.sh`, used by every fork ZIP) runs upstream's checks first.
  - It then installs the bundled APK with `pm install -r -S <size> < apk`. Streaming it over stdin avoids SELinux file-context problems.
  - If the user already has a differently signed copy, the installer reports it but does not fail the module install.
  - A hook at the top of `uninstall.sh` waits for boot to finish, then runs `pm uninstall`. The app also offers self-removal once the module is gone.
- **WebUI:** keep it until the app reaches feature parity, then drop `webroot/` from fork packages at build time. KernelSU users without the app keep a working UI throughout.

### Screens (parity with today's WebUI)

- **Overview:**
  - One state-dependent connect button.
  - Login card showing the validated URL as text, as a **QR code** (generated locally; TV and devices without a browser can scan it on a phone), and with an Open button when a browser exists.
  - Tailscale IPs, Console and About.
- **Devices:** search, online/offline status and roles. Kept in memory only and cleared when the app goes to the background, matching the current WebUI.
- **Taildrop:** folder chooser limited to the helper's allowed roots; Receive.
- **Settings (language first):**
  - Language: system, en, zh-CN or zh-TW, through Android 13's per-app language with an AppCompat fallback.
  - Exit node and its IPv6 option, MagicDNS, subnet routes, advertise as exit node, accept routes, shields up, LAN access.
  - Update channel and binary update (or "updates come with the module" while the updater is off).
  - SSH switch, if Part 1 enables it.
  - Each switch sends a single-field change and reconciles on failure, the same rule as today.
- **Command panel:** same allowlist, literal examples.
- **Extras that make it feel native:**
  - Quick Settings tile for connect/disconnect (phones and tablets).
  - Optional home-screen widget.
  - Launcher shortcut "Connect"/"Disconnect".
  - No background service: the daemon is supervised by the module, so OEM battery killers (MIUI/HyperOS, ColorOS, One UI) cannot break connectivity.

### Making it work on every kind of device

| Device / situation | Handling |
| --- | --- |
| Android TV / Google TV / Fire TV | `LEANBACK_LAUNCHER` activity with a 320×180 banner; `touchscreen` and `leanback` both `required=false`; every control reachable with the D-pad and showing a visible focus state; overscan-safe padding; QR login; no text entry needed for common tasks. Subnet routes and the command panel stay usable with a remote, but phone/tablet is the expected place to edit them. |
| Phones, tablets, foldables, ChromeOS-style windows | Window size classes (compact / medium / expanded): bottom nav, then nav rail, then two panes. Handles resizing, rotation and multi-window without losing in-flight requests (state in `ViewModel`). |
| Accessibility | TalkBack labels, 200% font scale, keyboard navigation, dark/light theme, dynamic color on Android 12+. |
| 32-bit, low-RAM, Android Go | No native code; small dependency set; R8 minification. |
| Root manager differences | Detects Magisk, KernelSU or APatch through the helper (never through hidden sysfs, per AGENTS.md); explains how to grant root for each; handles a denied or revoked grant. |
| Module missing, disabled, or a version mismatch | Clear status screen; the app never tries to install or start anything itself beyond the existing RPC actions. |
| Secondary users / work profile | The helper's Taildrop is limited to user 0; the app tells the user when it runs in another profile. |

### Testing

- **JVM and Robolectric, no device needed:**
  - Compose UI tests for state handling and D-pad focus order.
  - Roborazzi screenshot tests: phone, tablet, foldable, TV 1080p and 4K, three languages, dark/light, large font. All with synthetic devices, never real account data.
- **On `Medium_Phone` only:**
  - Root RPC end to end on Magisk and KernelSU (the setups in `docs/emulator.md`).
  - QS tile, install/uninstall hooks, live module update while the app is open.
- **TV on real hardware:** AGENTS.md currently allows runtime tests only on `Medium_Phone`. A TV AVD (for example `Medium_TV`) needs the owner's explicit approval and an AGENTS.md update.
- **Not testable here:** Fire TV and Chromebook hardware. Document them as unverified until someone tests on hardware.

---

## Suggested order

1. **Restore `Medium_Phone`:** recreate the AVD and its rooted boot setups per `docs/emulator.md`. Everything runtime-related depends on it.
2. **Part 1, steps 1, 3 and 4:** patch series on KyuubiRan's tree, release commit and gitlink, all ABIs from source, updater off. No user-visible feature change; proves the pipeline.
3. **App MVP:** Overview, Settings and Devices over libsu; QR login; signed APK as a release asset.
4. **Bundle the APK in the module ZIPs, then reach full parity:** Taildrop, command panel, binary update; then drop the WebUI from fork packages.
5. **TV polish and screenshot test matrix;** QS tile and widget.
6. **Optional:** Tailscale SSH port (Part 1, step 2), with a security review; no-grant transport (Part 2, phase 2).

## Decisions needed from the owner

- What do you need from the "full Linux" build: Tailscale SSH, or other features too? (This decides how much of Part 1, step 2 is needed.)
- APK signing: may CI generate a new release keystore and store it as repository secrets? Its fingerprint must stay stable forever for updates to work.
- Is a TV emulator allowed for runtime tests, in addition to `Medium_Phone`?
- When the app reaches parity, should fork packages drop the KernelSU WebUI, or keep it?

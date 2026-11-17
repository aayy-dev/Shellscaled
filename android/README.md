# Shellscaled app (fork-only)

A native Android app that controls the Shellscaled module. It runs on phones, tablets, foldables, ChromeOS-style windows and Android TV / Google TV / Fire TV.

The app is only a UI. It talks to the module's root helper (`/data/adb/modules/shellscaled/bin/shellscaled-web rpc <base64>`) using the same JSON protocol as the KernelSU WebUI. The helper's allowlists therefore still decide what is possible: actions, fields, command-panel programs and flags, login URL validation, and Taildrop paths. The app adds no new root capability and never runs user input in a shell. Any command text is only a JSON string inside the base64 argument.

## Feature parity with the WebUI

| WebUI | App |
| --- | --- |
| Overview: state, one connection button, sign-in link, IPs, console, About | Same. Sign-in and the fixed console/About links can also be shown as a **QR code**, for TVs or devices without a browser. |
| Devices: search, status, roles, DNS/IPs, last seen | Same. Kept in memory only and cleared when the app leaves the foreground. |
| Taildrop: choose folder, receive | Same. |
| Settings: language first, exit node (+ IPv6), MagicDNS, subnet routes, advertise exit node, accept routes, shields up, LAN access, update channel, binary update | Same. Single-field changes, optimistic only while in flight, and reconciled with the daemon on failure. |
| Commands: allowlisted `tailscale`/`tailscaled`, literal examples | Same. The helper still enforces the allowlist. |
| — | Quick Settings tile (connect/disconnect) and a TV launcher banner |

The interface strings come from upstream's WebUI translations: `tools/import_webui_strings.py` regenerates `strings_webui.xml` for en, zh-CN and zh-TW. App-only strings live in `strings_app.xml`.

## Branding

At the owner's request, the launcher icon, Quick Settings tile and TV banner use Tailscale's 3×3 dot mark. They are drawn as vectors in `res/drawable` and `res/mipmap-anydpi`. Tailscale and its logo are trademarks of Tailscale Inc. This app is unofficial and is not affiliated with or endorsed by Tailscale.

## Compatibility

- **Android versions:** minSdk 23 (Android 6), matching the module's NDK API level. targetSdk 37.
- **Device types:**
  - Touch is optional, and leanback is declared but not required.
  - TV gets a side rail, overscan-safe margins, and D-pad focus that starts on the connection button.
  - Every control shows a visible focus ring.
  - Layout uses the window size (not the screen size), so split-screen and freeform windows adapt.
- **Permissions:** no Google Play Services, no `INTERNET` permission, and nothing backed up.
- **Root grant:** uses libsu.
  - Magisk shows its own prompt.
  - KernelSU and APatch have no prompt; the app explains how to allow it in the manager and re-checks on Retry.
- **Root shells:**
  - Quick calls (status, settings) share one root shell. If a quick call times out, that shell is replaced.
  - Slow or user-initiated actions each get their own short-lived shell: connect, sign-in, binary updates, commands and Taildrop receive. They never hold up status polling or the tile.
- **Language:** the chosen language is also kept in the app's own preferences (only the language tag). This way the Quick Settings tile sends the same language to the helper when no activity is open.

## Build and test

Run these from this directory with JDK 21 and the Android SDK (platform 37.2):

```bash
./gradlew :app:testDebugUnitTest :app:lintRelease :app:assembleDebug
```

The unit tests use synthetic data only:
- RPC encoding (one base64 argument; shell metacharacters never reach the shell).
- Login-URL rules mirrored from the helper.
- State rules: single-field settings, reconciliation, one mutation at a time, wiping data in the background.
- Compose rendering through Robolectric:
  - phone, tablet rail, TV D-pad focus, Traditional Chinese, sign-in QR, and missing root;
  - also phone and TV on Android 6 (API 23).

## Release signing and packaging

CI builds and signs the release APK with the repository secrets `SHELLSCALED_KEYSTORE_B64`, `SHELLSCALED_KEYSTORE_PASSWORD`, `SHELLSCALED_KEY_ALIAS` and `SHELLSCALED_KEY_PASSWORD`. It then checks the signer against [`app-signing-cert.sha256`](../.github/fork/app-signing-cert.sha256). Losing or changing the key prevents updates for existing installs.

The APK is published as `Shellscaled-<version>-r<N>.apk` and is also bundled into every module ZIP as `app/Shellscaled.apk`, where it is covered by `SHA256SUMS`. The fork installer front end installs it with `pm install -r` after upstream's checks pass.

When the module is removed, a hook at the top of `uninstall.sh` waits for boot to finish and uninstalls the app. Root managers run that script before Android's package manager starts. If the module is gone but the app remains, the app offers "Uninstall this app", which uses the system's confirmation dialog.

The signing secrets are in the `app-signing` environment (restricted to `main`). Only the workflow's `app` job can read them, and that job builds fork code only.

## Emulator verification

Checked on 2026-11-17 on the owner's `play` AVD (Android 17 / API 37, arm64, Google Play image, Magisk 30.6, SELinux enforcing), using the v1.104.6-r1 arm64 release ZIP installed with `magisk --install-module`:

- **Installer:** upstream's payload and architecture checks pass, then the bundled app installs through `pm`.
- **Boot:** after a reboot the supervisor, `tailscaled` and `tailscale0` start. The app reads the real daemon state, and Magisk records an allow policy for the app.
- **Commands:**
  - `tailscale version` runs.
  - `tailscale version;id` is refused by the helper. This exposed the "Not run" label running into the reason; it is now fixed and covered by a test.
- **Settings:**
  - Changing the language (Traditional Chinese, then back to system) applies immediately.
  - "Block incoming connections" toggles `ShieldsUp` in the daemon, and toggling it back restores it.
  - `CorpDNS` and `NetfilterMode` stay off.
  - The update check reports that Tailscale is up to date.
- **Devices and Taildrop:** both render.
- **D-pad:** a visible focus ring moves between controls and skips disabled switches.
- **Quick Settings tile:** it can be added, and while sign-in is needed, tapping it opens the app.
- **Module removal:** with `remove` set, the next boot uninstalls the app. The module, its state, `tailscaled` and `tailscale0` are gone.

Signed in (the owner signed in on the emulator; `accept-dns` and netfilter stayed off):
- **Connection:** Overview shows Connected and both tailnet addresses. Disconnect and Connect switch the daemon between `Stopped` and `Running`.
- **Tile:** shown as active while connected. Tapping it disconnects and reconnects without leaving the shade.
- **Devices:** the tab's device and online counts match `tailscale status`, and searching for a name that doesn't exist shows "No matching devices".
- **Exit nodes:**
  - The picker lists the daemon's exit-node options and disables offline ones.
  - Choosing one sets `ExitNodeID` and routes default traffic through `tailscale0`.
  - Through a Linux VPS exit node, root DNS, TCP and ping worked.
  - Through an Android TV exit node, traffic timed out, although that peer answered `tailscale ping` over DERP. This is most likely a limitation of that node and has not been diagnosed.

Not yet verified:
- Taildrop transfers. These need a file sent from another device.
- The sign-in QR with a real link. Testing it would mean signing the owner out.
- KernelSU and APatch.
- Real TV hardware.

Observed on Magisk (upstream behavior, not fork code): upstream's live update waits for the installer's parent process. When the module is installed with `magisk --install-module`, that parent is the long-lived `magiskd`, so the live update gives up after its 120-second limit. The update then takes effect on the next reboot.

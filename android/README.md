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

The APK is published as `Shellscaled-<version>-r<N>.apk` and is also bundled into every module ZIP as `app/Shellscaled.apk`, where it is covered by `SHA256SUMS`. The fork installer front end installs it with `pm install -r` after upstream's checks pass. A line appended to `uninstall.sh` removes it when the module is uninstalled.

## Not yet verified

- None of this has run on a device or emulator. The rooted `Medium_Phone` AVD must be recreated first (`docs/emulator.md`).
- Untested end to end: Magisk/KernelSU/APatch grant flows, `pm install` from each manager's installer, the Quick Settings tile, and real TV hardware.

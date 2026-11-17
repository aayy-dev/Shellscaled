#!/bin/sh
# Host-side check of the universal ZIP installer (fork-only). Extracts the ZIP
# into a private directory per ABI, stubs the root manager callbacks the way
# upstream tests/install.sh does, and runs the fork selector, which then
# sources upstream's unchanged customize.sh (checksum + architecture checks).
# Never touches a device, a real module directory or account state.
# Usage: test-universal.sh <universal.zip> <src-with-.work/package>
set -eu
zip=$1
packages=$2/.work/package
root=$(mktemp -d)
trap 'rm -rf "$root"' EXIT
command -v sha256sum >/dev/null 2>&1 || sha256sum() { shasum -a 256 "$@"; }

install_for() ( # <abi> <dir>
    MODPATH=$2 KSU=true BOOTMODE=true APATCH='' MAGISK_VER_CODE=''
    sc_test_abi=$1
    abort() { printf '%s\n' "$*" >&2; exit 1; }
    ui_print() { printf '%s\n' "$*"; }
    getprop() { printf '%s\n' "$sc_test_abi"; }
    id() { echo 0; }
    set_perm_recursive() { :; }
    set_perm() { chmod "$4" "$1"; }
    # Upstream then schedules a live update through Android's /proc and
    # busybox; on a host that background launch is a harmless no-op.
    readlink() { :; }
    . "$MODPATH/customize.sh"
)

for case in arm64-v8a:arm64 armeabi-v7a:arm x86_64:amd64; do
    abi=${case%%:*} arch=${case#*:}
    dir=$root/$arch
    mkdir -p "$dir" && unzip -q "$zip" -d "$dir"
    install_for "$abi" "$dir" > "$root/$arch.log" 2>&1 || { cat "$root/$arch.log"; echo "FAIL: $abi install"; exit 1; }
    grep -q 'Offline payload and architecture verified' "$root/$arch.log"
    [ ! -e "$dir/arch" ] || { echo "FAIL: $abi left arch/ behind"; exit 1; }
    grep -qx "architecture=$arch" "$dir/module.prop"
    cmp -s "$dir/bin/tailscaled" "$packages/$arch/bin/tailscaled"
    cmp -s "$dir/bin/shellscaled-web" "$packages/$arch/bin/shellscaled-web"
    cmp -s "$dir/customize.sh" "$packages/$arch/customize.sh"
    # The installed tree must equal the single-ABI package's tree exactly.
    (cd "$packages/$arch" && find . -type f | sort) > "$root/want"
    (cd "$dir" && find . -type f ! -name .sc-upgrade ! -name skip_mount | sort) > "$root/got"
    cmp -s "$root/want" "$root/got" || { diff "$root/want" "$root/got"; echo "FAIL: $abi file set"; exit 1; }
    echo "PASS: $abi -> $arch"
done

# Unsupported and tampered payloads must still be rejected.
dir=$root/x86 && mkdir -p "$dir" && unzip -q "$zip" -d "$dir"
if install_for x86 "$dir" > "$root/x86.log" 2>&1; then echo 'FAIL: x86 accepted'; exit 1; fi
grep -q 'Unsupported device architecture' "$root/x86.log" && echo 'PASS: x86 rejected'
dir=$root/tampered && mkdir -p "$dir" && unzip -q "$zip" -d "$dir"
printf 'tampered\n' >> "$dir/arch/arm/bin/tailscaled"
if install_for armeabi-v7a "$dir" > "$root/tampered.log" 2>&1; then echo 'FAIL: tampered payload accepted'; exit 1; fi
grep -q 'checksum verification failed' "$root/tampered.log" && echo 'PASS: tampered payload rejected'

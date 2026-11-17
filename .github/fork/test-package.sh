#!/bin/sh
# Host-side check of a fork module ZIP (fork-only). For each ABI the ZIP should accept,
# extract it into a private directory, stub the root-manager callbacks the way upstream
# tests/install.sh does, run the fork front end (which sources upstream's unchanged
# customize.sh), and compare the result with the expected single-ABI tree written by
# package_fork.py. Never touches a device, a real module directory or account state.
# Usage: test-package.sh <zip> <src> <accepted abi:arch>... [-- <rejected abi>...]
set -eu
zip=$1 src=$2
shift 2
expected=$src/.work/fork-package
root=$(mktemp -d)
trap 'rm -rf "$root"' EXIT
command -v sha256sum >/dev/null 2>&1 || sha256sum() { shasum -a 256 "$@"; }
cat > "$root/pm" <<'EOF'
#!/bin/sh
# Records the install and consumes the streamed APK like `pm install -S`.
echo "$*" >> "$PM_LOG"
cat > /dev/null
echo Success
EOF
chmod +x "$root/pm"

install_for() ( # <abi> <dir>
    MODPATH=$2 KSU=true BOOTMODE=true APATCH='' MAGISK_VER_CODE=''
    SC_FORK_PM=$root/pm PM_LOG=$2.pm
    export PM_LOG
    sc_test_abi=$1
    abort() { printf '%s\n' "$*" >&2; exit 1; }
    ui_print() { printf '%s\n' "$*"; }
    getprop() { printf '%s\n' "$sc_test_abi"; }
    id() { echo 0; }
    set_perm_recursive() { :; }
    set_perm() { chmod "$4" "$1"; }
    # Upstream then schedules a live update through Android's /proc and busybox;
    # on a host that background launch is a harmless no-op.
    readlink() { :; }
    . "$MODPATH/customize.sh"
)

accepted=true first=''
for case in "$@"; do
    if [ "$case" = -- ]; then accepted=false; continue; fi
    abi=${case%%:*} arch=${case#*:}
    dir=$root/$abi
    mkdir -p "$dir" && unzip -q "$zip" -d "$dir"
    if [ "$accepted" = false ]; then
        if install_for "$abi" "$dir" > "$dir.log" 2>&1; then cat "$dir.log"; echo "FAIL: $abi accepted"; exit 1; fi
        grep -Eq 'Unsupported device architecture|built for a different architecture' "$dir.log"
        echo "PASS: $abi rejected"
        continue
    fi
    [ -n "$first" ] || first=$abi:$arch
    install_for "$abi" "$dir" > "$dir.log" 2>&1 || { cat "$dir.log"; echo "FAIL: $abi install"; exit 1; }
    grep -q 'Offline payload and architecture verified' "$dir.log"
    [ ! -e "$dir/arch" ] || { echo "FAIL: $abi left arch/ behind"; exit 1; }
    grep -qx "architecture=$arch" "$dir/module.prop"
    # The installed tree must equal the expected single-ABI tree exactly.
    (cd "$expected/$arch" && find . -type f | sort) > "$root/want"
    (cd "$dir" && find . -type f ! -name .sc-upgrade ! -name skip_mount | sort) > "$root/got"
    cmp -s "$root/want" "$root/got" || { diff "$root/want" "$root/got"; echo "FAIL: $abi file set"; exit 1; }
    (cd "$expected/$arch" && find . -type f) | while read -r f; do
        cmp -s "$expected/$arch/$f" "$dir/$f" || { echo "FAIL: $abi $f differs"; exit 1; }
    done
    if [ -f "$dir/app/Shellscaled.apk" ]; then
        grep -q -- '-r -S' "$dir.pm" || { echo "FAIL: $abi app not installed"; exit 1; }
        grep -q 'Shellscaled app installed' "$dir.log"
        grep -q 'pm uninstall io.github.aayydev.shellscaled' "$dir/uninstall.sh"
        echo "PASS: $abi -> $arch (app installed)"
    else
        echo "PASS: $abi -> $arch"
    fi
done

# A tampered payload must fail upstream's checksum verification.
abi=${first%%:*}
dir=$root/tampered && mkdir -p "$dir" && unzip -q "$zip" -d "$dir"
arch=${first#*:}
printf 'tampered\n' >> "$dir/arch/$arch/bin/tailscaled"
if install_for "$abi" "$dir" > "$dir.log" 2>&1; then echo 'FAIL: tampered payload accepted'; exit 1; fi
grep -q 'checksum verification failed' "$dir.log" && echo 'PASS: tampered binary rejected'
if [ -f "$dir/app/Shellscaled.apk" ]; then
    dir=$root/tampered-app && mkdir -p "$dir" && unzip -q "$zip" -d "$dir"
    printf 'tampered\n' >> "$dir/app/Shellscaled.apk"
    if install_for "$abi" "$dir" > "$dir.log" 2>&1; then echo 'FAIL: tampered app accepted'; exit 1; fi
    grep -q 'checksum verification failed' "$dir.log" && echo 'PASS: tampered app rejected'
    [ ! -s "$dir.pm" ] || { echo 'FAIL: tampered app reached pm'; exit 1; }
fi

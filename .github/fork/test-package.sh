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
fail() { echo "FAIL: $*"; exit 1; }

# Stub for `pm install`. Records the arguments and, for -S, how many bytes were streamed.
# PM_MODE: ok (default) | fail-stream (stdin install fails, file install works) | fail (both fail)
cat > "$root/pm" <<'STUB'
#!/bin/sh
if [ "$3" = -S ]; then
    echo "stream $4 $(wc -c | tr -d ' ')" >> "$PM_LOG"
    if [ "${PM_MODE:-ok}" = ok ]; then echo Success; else echo 'Failure [INSTALL_FAILED_ABORTED]'; fi
else
    echo "file $(wc -c < "$3" | tr -d ' ')" >> "$PM_LOG"
    if [ "${PM_MODE:-ok}" = fail ]; then echo 'Failure [INSTALL_FAILED_UPDATE_INCOMPATIBLE]'; else echo Success; fi
fi
STUB
chmod +x "$root/pm"
mkdir "$root/tmp"

install_for() ( # <abi> <dir>
    MODPATH=$2 KSU=true BOOTMODE=true APATCH='' MAGISK_VER_CODE=''
    SC_FORK_PM=$root/pm SC_FORK_TMPDIR=$root/tmp PM_LOG=$2.pm
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
        if install_for "$abi" "$dir" > "$dir.log" 2>&1; then cat "$dir.log"; fail "$abi accepted"; fi
        grep -Eq 'Unsupported device architecture|built for a different architecture' "$dir.log" ||
            { cat "$dir.log"; fail "$abi rejected for another reason"; }
        echo "PASS: $abi rejected"
        continue
    fi
    [ -n "$first" ] || first=$abi:$arch
    install_for "$abi" "$dir" > "$dir.log" 2>&1 || { cat "$dir.log"; fail "$abi install"; }
    grep -q 'Offline payload and architecture verified' "$dir.log" || fail "$abi upstream checks did not run"
    [ ! -e "$dir/arch" ] || fail "$abi left arch/ behind"
    grep -qx "architecture=$arch" "$dir/module.prop" || fail "$abi module.prop architecture"
    # The installed tree must equal the expected single-ABI tree exactly.
    (cd "$expected/$arch" && find . -type f | sort) > "$root/want"
    (cd "$dir" && find . -type f ! -name .sc-upgrade ! -name skip_mount | sort) > "$root/got"
    cmp -s "$root/want" "$root/got" || { diff "$root/want" "$root/got"; fail "$abi file set"; }
    while read -r f; do
        cmp -s "$expected/$arch/$f" "$dir/$f" || fail "$abi $f differs"
    done < "$root/want"
    if [ -f "$dir/app/Shellscaled.apk" ]; then
        size=$(wc -c < "$dir/app/Shellscaled.apk" | tr -d ' ')
        grep -qx "stream $size $size" "$dir.pm" || fail "$abi app was not streamed whole to pm"
        grep -q 'Shellscaled app installed' "$dir.log" || fail "$abi install message"
        # The removal hook must run before anything in upstream's uninstall.sh could exit.
        sed -n 2p "$dir/uninstall.sh" | grep -q '^# Fork: remove the companion app' || fail "$abi uninstall hook not first"
        grep -q 'pm uninstall io.github.aayydev.shellscaled$' "$dir/uninstall.sh" || fail "$abi uninstall hook"
        sh -n "$dir/uninstall.sh" || fail "$abi uninstall.sh syntax"
        echo "PASS: $abi -> $arch (app installed)"
    else
        echo "PASS: $abi -> $arch"
    fi
done
[ -n "$first" ] || fail 'no accepted ABI given'

# A tampered payload must fail upstream's checksum verification.
abi=${first%%:*}
arch=${first#*:}
dir=$root/tampered && mkdir -p "$dir" && unzip -q "$zip" -d "$dir"
printf 'tampered\n' >> "$dir/arch/$arch/bin/tailscaled"
if install_for "$abi" "$dir" > "$dir.log" 2>&1; then fail 'tampered payload accepted'; fi
grep -q 'checksum verification failed' "$dir.log" || { cat "$dir.log"; fail 'tampered binary rejected for another reason'; }
echo 'PASS: tampered binary rejected'

if unzip -l "$zip" app/Shellscaled.apk >/dev/null 2>&1; then
    dir=$root/tampered-app && mkdir -p "$dir" && unzip -q "$zip" -d "$dir"
    printf 'tampered\n' >> "$dir/app/Shellscaled.apk"
    if install_for "$abi" "$dir" > "$dir.log" 2>&1; then fail 'tampered app accepted'; fi
    grep -q 'checksum verification failed' "$dir.log" || { cat "$dir.log"; fail 'tampered app rejected for another reason'; }
    [ ! -s "$dir.pm" ] || fail 'tampered app reached pm'
    echo 'PASS: tampered app rejected before pm'

    # If streaming over stdin fails, a shell-readable copy is installed, then removed.
    dir=$root/fallback && mkdir -p "$dir" && unzip -q "$zip" -d "$dir"
    PM_MODE=fail-stream install_for "$abi" "$dir" > "$dir.log" 2>&1 || { cat "$dir.log"; fail 'fallback install'; }
    size=$(wc -c < "$dir/app/Shellscaled.apk" | tr -d ' ')
    grep -qx "file $size" "$dir.pm" || fail 'fallback did not install the whole APK'
    grep -q 'Shellscaled app installed' "$dir.log" || fail 'fallback message'
    [ -z "$(ls "$root/tmp")" ] || fail 'fallback copy left behind'
    echo 'PASS: pm stdin failure falls back to a file install'

    # If pm refuses the app (for example, a differently signed copy), the module still installs.
    dir=$root/refused && mkdir -p "$dir" && unzip -q "$zip" -d "$dir"
    PM_MODE=fail install_for "$abi" "$dir" > "$dir.log" 2>&1 || { cat "$dir.log"; fail 'module install failed when pm refused the app'; }
    grep -q 'could not be installed automatically' "$dir.log" || fail 'refusal message'
    grep -q 'Offline payload and architecture verified' "$dir.log" || fail 'refused app skipped module checks'
    echo 'PASS: pm refusal keeps the module install'
fi

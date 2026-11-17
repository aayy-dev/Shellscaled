#!/system/bin/sh
# Fork-only installer front end for every fork module ZIP (see .github/fork/README.md).
# The manager has already extracted the ZIP into $MODPATH. Select the payload for this
# device's primary ABI, restore upstream's unchanged customize.sh and per-ABI files, then
# source it: its checksum and architecture checks run exactly as for an upstream package.
# Afterwards install the bundled Shellscaled app, if this ZIP carries one.
[ -n "${MODPATH:-}" ] && [ -d "$MODPATH/arch" ] || abort '! Missing module payload.'
case "$(getprop ro.product.cpu.abi)" in
    arm64-v8a) sc_fork_arch=arm64 ;;
    armeabi-v7a|armeabi) sc_fork_arch=arm ;;
    x86_64) sc_fork_arch=amd64 ;;
    *) abort '! Unsupported device architecture.' ;;
esac
sc_fork_src="$MODPATH/arch/$sc_fork_arch"
[ -d "$sc_fork_src" ] || abort '! This ZIP was built for a different architecture.'
[ "$(ls "$MODPATH/arch" | wc -l)" -gt 2 ] && ui_print "- Universal package: installing the $sc_fork_arch payload."
for sc_fork_file in module.prop build-info.json SHA256SUMS bin/tailscaled bin/shellscaled-web; do
    [ -f "$sc_fork_src/$sc_fork_file" ] || abort '! Incomplete module payload.'
    mv -f "$sc_fork_src/$sc_fork_file" "$MODPATH/$sc_fork_file" || abort '! Could not stage the payload.'
done
# rename() keeps this running script's open file intact while replacing the path.
mv -f "$MODPATH/arch/customize.sh" "$MODPATH/customize.sh" || abort '! Could not stage the installer.'
rm -rf "$MODPATH/arch"
unset sc_fork_src sc_fork_file
. "$MODPATH/customize.sh"

# Upstream's installer has verified SHA256SUMS, which covers the APK.
sc_fork_apk="$MODPATH/app/Shellscaled.apk"
if [ -f "$sc_fork_apk" ]; then
    ui_print '- Installing the Shellscaled app.'
    sc_fork_pm=${SC_FORK_PM:-/system/bin/pm} # Overridable only for the host-side package test.
    sc_fork_size=$(wc -c < "$sc_fork_apk" | tr -d ' ')
    # Stream over stdin first (no file-context issues); fall back to a shell-readable copy.
    sc_fork_out=$("$sc_fork_pm" install -r -S "$sc_fork_size" < "$sc_fork_apk" 2>&1)
    case "$sc_fork_out" in
        *Success*) sc_fork_ok=true ;;
        *)
            sc_fork_tmp=/data/local/tmp/shellscaled-app-$$.apk
            if cp "$sc_fork_apk" "$sc_fork_tmp" && chmod 0644 "$sc_fork_tmp"; then
                sc_fork_out=$("$sc_fork_pm" install -r "$sc_fork_tmp" 2>&1)
            fi
            rm -f "$sc_fork_tmp"
            case "$sc_fork_out" in *Success*) sc_fork_ok=true ;; *) sc_fork_ok=false ;; esac
            ;;
    esac
    if [ "$sc_fork_ok" = true ]; then
        ui_print '- Shellscaled app installed. Open it from your launcher or TV home screen.'
    else
        ui_print '! The Shellscaled app could not be installed automatically.'
        ui_print '  The module still works. Install the Shellscaled APK from the release page,'
        ui_print '  or uninstall a differently signed copy first.'
    fi
    unset sc_fork_size sc_fork_out sc_fork_tmp sc_fork_ok sc_fork_pm
fi
unset sc_fork_apk sc_fork_arch

#!/system/bin/sh
# Fork-only universal installer front end (see .github/fork/README.md).
# The manager has already extracted the ZIP into $MODPATH. Select the payload
# for this device's primary ABI, restore upstream's unchanged customize.sh and
# per-ABI files, then source it: its checksum and architecture checks run
# exactly as they do for a single-architecture package.
[ -n "${MODPATH:-}" ] && [ -d "$MODPATH/arch" ] || abort '! Missing universal payload.'
case "$(getprop ro.product.cpu.abi)" in
    arm64-v8a) sc_universal_arch=arm64 ;;
    armeabi-v7a|armeabi) sc_universal_arch=arm ;;
    x86_64) sc_universal_arch=amd64 ;;
    *) abort '! Unsupported device architecture.' ;;
esac
sc_universal_src="$MODPATH/arch/$sc_universal_arch"
[ -d "$sc_universal_src" ] || abort "! This ZIP has no $sc_universal_arch payload."
ui_print "- Universal package: installing the $sc_universal_arch payload."
for sc_universal_file in module.prop build-info.json SHA256SUMS bin/tailscaled bin/shellscaled-web; do
    [ -f "$sc_universal_src/$sc_universal_file" ] || abort '! Incomplete universal payload.'
    mv -f "$sc_universal_src/$sc_universal_file" "$MODPATH/$sc_universal_file" || abort '! Could not stage the payload.'
done
# rename() keeps this running script's open file intact while replacing the path.
mv -f "$MODPATH/arch/customize.sh" "$MODPATH/customize.sh" || abort '! Could not stage the installer.'
rm -rf "$MODPATH/arch"
unset sc_universal_arch sc_universal_src sc_universal_file
. "$MODPATH/customize.sh"

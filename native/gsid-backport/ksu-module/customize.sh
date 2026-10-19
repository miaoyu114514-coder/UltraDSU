#!/system/bin/sh
[ "$KSU" = true ] || abort "This module requires KernelSU."
[ "$ARCH" = arm64 ] || abort "Only ARM64 is supported."
[ "$API" = 34 ] || abort "Only the tested Android 14 firmware is supported."
STOCK=a7360f6b305d6e1f7b3a51ee08146d3e5a4d5769c7cb11c3de38c734d6e8bcf3
PATCHED=6bea182e737e238ce8c054717f48e64afc1c8a989aab53850bc9a564651bd323
CURRENT=$(sha256sum /system/bin/gsid | cut -d ' ' -f 1)
case "$CURRENT" in
    "$STOCK"|"$PATCHED") ;;
    *) abort "Unrecognized system gsid. No replacement was installed." ;;
esac
[ "$(sha256sum "$MODPATH/system/bin/gsid" | cut -d ' ' -f 1)" = "$PATCHED" ] || abort "Payload checksum mismatch."
(cd "$MODPATH" && sha256sum -c SHA256SUMS) || abort "Module checksum verification failed."
set_perm_recursive "$MODPATH" 0 0 0755 0644
set_perm "$MODPATH/system/bin/gsid" 0 0 0755 u:object_r:gsid_exec:s0
for SCRIPT in post-fs-data.sh service.sh action.sh; do
    set_perm "$MODPATH/$SCRIPT" 0 0 0755
done
ui_print "- Verified moto API 34 factory/backport GSID"
ui_print "- Standard system/bin/gsid module for SukiSU Ultra Magic Mount"
ui_print "- No metamodule, manual bind script or SELinux rule changes"
ui_print "- Disable/uninstall and reboot restores the stock binary"
ui_print "- Reboot to activate the persistent module mount"

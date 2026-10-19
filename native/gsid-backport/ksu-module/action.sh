#!/system/bin/sh
MODDIR=${0%/*}
printf '%s\n' 'UltraDSU Moto API 34 multi-slot GSID'
cat "$MODDIR/mount-status.log" 2>/dev/null
sha256sum /system/bin/gsid
getenforce
printf '%s\n' 'Disable/uninstall this module and reboot to restore the original gsid.'

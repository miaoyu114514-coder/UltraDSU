#!/system/bin/sh
MODDIR=${0%/*}
PATCHED=6bea182e737e238ce8c054717f48e64afc1c8a989aab53850bc9a564651bd323
CURRENT=$(sha256sum /system/bin/gsid | cut -d ' ' -f 1)
if [ "$CURRENT" = "$PATCHED" ] && [ ! -f "$MODDIR/skip_mount" ]; then
    printf '%s\n' 'Active: verified multi-slot GSID; SukiSU Ultra Magic Mount' > "$MODDIR/mount-status.log"
else
    printf '%s\n' 'Inactive: see skip_mount/firmware compatibility; original retained' > "$MODDIR/mount-status.log"
fi

#!/system/bin/sh
MODDIR=${0%/*}
# Guard runs before SukiSU Ultra's built-in Magic Mount. It never mounts files,
# calls Binder, waits for a service, or writes system properties.
STOCK=a7360f6b305d6e1f7b3a51ee08146d3e5a4d5769c7cb11c3de38c734d6e8bcf3
PATCHED=6bea182e737e238ce8c054717f48e64afc1c8a989aab53850bc9a564651bd323
CURRENT=$(sha256sum /system/bin/gsid | cut -d ' ' -f 1)
PAYLOAD=$(sha256sum "$MODDIR/system/bin/gsid" | cut -d ' ' -f 1)
if [ "$(getprop ro.build.version.sdk)" != 34 ] || [ "$PAYLOAD" != "$PATCHED" ]; then
    touch "$MODDIR/skip_mount"
    printf '%s\n' 'Skipped: Android version changed or payload checksum invalid' > "$MODDIR/mount-status.log"
    exit 0
fi
case "$CURRENT" in
    "$STOCK"|"$PATCHED")
        rm -f "$MODDIR/skip_mount"
        printf '%s\n' 'Verified: waiting for SukiSU Ultra Magic Mount' > "$MODDIR/mount-status.log"
        ;;
    *)
        touch "$MODDIR/skip_mount"
        printf '%s\n' 'Skipped: unrecognized firmware; original retained' > "$MODDIR/mount-status.log"
        ;;
esac

#!/system/bin/sh
# Run with su -M. Only this task's hash-pinned temporary bind mount is managed.
set -eu
DIR=/data/local/tmp/ultradsu-gsid-6bea182e
TARGET=/system/bin/gsid
STOCK=a7360f6b305d6e1f7b3a51ee08146d3e5a4d5769c7cb11c3de38c734d6e8bcf3
PATCHED=6bea182e737e238ce8c054717f48e64afc1c8a989aab53850bc9a564651bd323
file_digest() { sha256sum "$1" | cut -d ' ' -f 1; }
stop_service() {
    setprop ctl.stop gsid
    count=0
    while [ "$(getprop init.svc.gsid)" != stopped ]; do
        count=$((count + 1))
        [ "$count" -lt 50 ] || return 1
        sleep 0.1
    done
}
rollback() {
    current=$(file_digest "$TARGET")
    if [ "$current" = "$STOCK" ]; then return 0; fi
    [ "$current" = "$PATCHED" ] || { echo 'Unexpected binary; refusing unmount'; return 1; }
    stop_service
    umount "$TARGET"
    [ "$(file_digest "$TARGET")" = "$STOCK" ]
    setprop ctl.start gsid
    echo 'Original gsid restored'
}
[ "$(id -u)" = 0 ]
[ "$(readlink /proc/self/ns/mnt)" = "$(readlink /proc/1/ns/mnt)" ] || { echo 'Global mount namespace required'; exit 1; }
case "${1:-}" in
    enable)
        [ "$(file_digest "$TARGET")" = "$STOCK" ]
        [ "$(file_digest "$DIR/gsid")" = "$PATCHED" ]
        [ "$(file_digest "$DIR/gsid.stock")" = "$STOCK" ]
        [ "$(getprop ro.gsid.image_running)" != 1 ]
        timeout 12 gsi_tool status > "$DIR/status-before.txt"
        sha256sum /metadata/gsi/dsu/active /metadata/gsi/dsu/install_status /metadata/gsi/dsu/dsu/complete /metadata/gsi/dsu/dsu/install_dir > "$DIR/metadata-before.sha256"
        chmod 0755 "$DIR/gsid"
        chcon u:object_r:gsid_exec:s0 "$DIR/gsid"
        trap 'rollback' EXIT
        stop_service
        mount --bind "$DIR/gsid" "$TARGET"
        # /data is nosuid: clear that flag on this read-only file bind so init's
        # existing gs id domain transition works without any SELinux rule change.
        "$DIR/remount-file"
        [ "$(file_digest "$TARGET")" = "$PATCHED" ]
        setprop ctl.start gsid
        timeout 12 gsi_tool status > "$DIR/status-after.txt"
        sha256sum -c "$DIR/metadata-before.sha256"
        trap - EXIT
        echo 'Temporary gsid overlay active; reboot or rollback restores the original'
        ;;
    rollback) rollback ;;
    *) echo 'Usage: temporary-overlay.sh enable|rollback'; exit 2 ;;
esac

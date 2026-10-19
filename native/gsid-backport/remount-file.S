/* Single-purpose raw mount syscall: toybox remount lookup is incompatible
 * with this device's filtered mount table. Only the fixed temporary bind is used.
 * MS_REMOUNT | MS_BIND | MS_RDONLY | MS_NODEV, clearing MS_NOSUID for init's
 * ordinary SELinux gs id transition. No setuid permission bits are introduced.
 */
.text
.global _start
_start:
    mov x0, xzr
    adr x1, target
    mov x2, xzr
    mov x3, #0x1025
    mov x4, xzr
    mov x8, #40
    svc #0
    neg x0, x0
    mov x8, #93
    svc #0
target:
    .asciz "/system/bin/gsid"

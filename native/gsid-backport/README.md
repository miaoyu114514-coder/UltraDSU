# Moto Android 14 internal-slot backport

This is a factory-binary backport with compiled AArch64 instruction replacements,
not a full AOSP source rebuild. It retains the device's existing Binder, libc++,
crypto and static filesystem implementations. No Qualcomm or MediaTek-specific
replacement library is added.

Target: moto razr 2022, XT2251-1/oneli, Android 14 API 34,
`motorola/oneli_cn/oneli:14/U2SL34.1-42-14/9efa9-15a04e:user/release-keys`.

- Factory SHA-256: `a7360f6b305d6e1f7b3a51ee08146d3e5a4d5769c7cb11c3de38c734d6e8bcf3`.
- Patched SHA-256: `6bea182e737e238ce8c054717f48e64afc1c8a989aab53850bc9a564651bd323`.
- Binary size: 875,632 bytes; two instructions changed, four differing bytes.
- The patcher refuses any other input binary or instruction sequence.

The existing `GsiService::ValidateInstallParams` canonicalizes the path and adds
a trailing slash. At file/virtual address `0x4a690`, reject-if-length-is-not-14
becomes reject-if-length-is-less-than-14. At `0x4a6a4`, the existing
`std::string::compare(0, count, prefix, 14)` gets count=14 instead of npos. The
remaining comparison and error path stay unchanged. Thus `/data/gsi/dsu/` and
its canonical subdirectories are accepted; sibling prefixes are not accepted.
The app creates and labels the new directory before calling openInstall.

`build.sh` assembles the old/new instructions, verifies the input SHA and original
bytes, then emits `dist/gsid-moto-api34-multislot`. It uses the existing WSL AOSP
Clang toolchain; all task outputs remain in this workspace. Existing Android source
trees are read only.

`temporary-overlay.sh` manages only the hash-pinned file bind mount in PID 1's mount
namespace. It does not alter the original system image or install a persistent
module. A read-only bind clears inherited nosuid on that file to permit init's
normal SELinux transition; SELinux remains enforcing. `remount-file.S` is a
single-purpose mount syscall helper because this device filters the mount table
and toybox cannot locate the bind for remount. It has no library dependencies.

Manual recovery on this test device:

```sh
adb -s DEVICE_SERIAL shell "su -M -c 'sh /data/local/tmp/ultradsu-gsid-6bea182e/temporary-overlay.sh rollback'"
```

The rollback checks the currently mounted hash before unmounting; an unexpected
binary is never unmounted. Reboot also removes the temporary overlay. Installed
DSU data is separate and is not removed by rollback. Before changing a slot's
partitions after rollback, re-enable the compatible gsid; the app checks this
before writing a maintenance marker.

The app recognizes this exact backport by SHA-256 on API 34. A missing or unknown
binary does not override the platform compatibility restriction.

## KernelSU module

`build-module.py` packages the verified gsid at `system/bin/gsid` into
`dist/UltraDSU-GSID-Moto-API34-KSU-v1.0.1.zip`. Install this ZIP through SukiSU Ultra.
The installer checks KernelSU, ARM64, API 34, the current factory/backport hash,
and every packaged file checksum. SukiSU Ultra stages it for the next reboot.

The module uses SukiSU Ultra's built-in Magic Mount and requires no metamodule.
There is no custom bind mounting script, remount helper or SELinux policy file
in the module. `post-fs-data.sh` checks the firmware/payload hashes before Magic
Mount and creates skip_mount for unknown firmware. It performs no Binder waits
or property writes. A late service script records the verified mounted checksum.
The Action button displays the status, checksum and SELinux mode.

Disable or uninstall `ultradsu_gsid_moto34` and reboot to restore the original
system binary. Installed DSU slots are retained. The standalone temporary-overlay
rollback command above is for the earlier temporary test mount; module removal is
managed separately by KernelSU.

The optional `GsidBackportDeviceTest` requires explicit `allowDsuInstall=NEW_SLOT_ONLY`,
`slotId` and `slotName=los` instrumentation arguments. Normal test runs skip it.
It installs only an already prepared new slot and verifies the previous slot's
file identities, sizes, mtimes, registration hashes and selected boot state.
It does not reboot or prove that the new guest OS can boot.

The factory gsid and consulted AOSP sources retain their respective upstream
licenses. The instruction sources and tooling in this directory use the project's
Apache-2.0 license.

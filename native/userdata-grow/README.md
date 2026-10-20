# Offline userdata growth tools

The Android app remains API 29+. This experimental maintenance bundle currently
requires Android 16+ on arm64, a standard internal DSU slot, and a successful
per-device preflight. Unsupported ciphers, wrapped metadata keys, and encrypted
ext4 offline growth are rejected before copying or allocating userdata.

`maintenance.cpp` is an Apache-2.0 standalone process linked with AOSP vold
KeyStorage/Keystore, libdm and libfstab. It creates a private device-mapper view
using the existing slot key; it never generates a replacement slot key or puts
secret key material in arguments or plaintext output. The build replaces KeyStorage's operation-opening function with a
read-only version: an upgraded blob may be used in memory, but it is neither
committed nor written to the slot. Native library logging is suppressed because
libdm failure messages can include serialized key-bearing tables. Core dumps
are disabled. The host `/data` mapping and vold daemon are not replaced.

Android 16+ is a tool runtime requirement, not a guarantee that an existing
slot key can be used from the host. KeyMint binds key blobs to OS and patch
versions. A slot that boots a newer guest can remain usable in that guest while
the older host rejects its key. Preflight retains only an allowlisted numeric
Keystore error for this case; it does not persist upgraded blobs or bypass
version binding. `INVALID_ARGUMENT` alone is not treated as proof of a specific
version mismatch. See the `upgradeKey` contract in the upstream KeyMint AIDL.

`guest-assistance.inc` implements an optional encrypted cross-boot handoff. The
host creates a hardware-protected Android Keystore RSA key pair; only its public
key is placed in a root-owned `/metadata` mailbox. In the matching booted guest,
the helper retrieves the existing ordinary metadata and system-wide DE keys in
memory, seals them with AES-256-GCM, and wraps the random transport key using
RSA-OAEP SHA-256 / MGF1 SHA-1. Slot, session, sealed-key identity and public-key
hash are authenticated. The helper never persists plaintext or reads user CE
keys. The host app unwraps the envelope with its non-exportable private key and
passes bounded key material through anonymous stdin pipes, with dump disabled
and buffers cleared after use. The offline F2FS and sealed-record checks still
run before any backup or allocation. Original slot key records are not rewritten.

Recovery credentials and the encrypted mailbox are retained with the full raw
backup, then removed together on explicit backup cleanup. An assisted transaction
can also restore its exact verified original raw backup if the host app loses
its temporary Keystore credential; the distinct `ROLLED_BACK_RAW` state requires
guest boot verification and does not claim a successful host filesystem check.
See [the app workflow](../../docs/DSU_GUEST_ASSISTANCE.md).

`f2fs-ciphertext-mover.inc`, `f2fs-direct-io.inc` and the patched F2FS executable
are GPL-2.0-only.
The original complete f2fs-tools source and upstream license are distributed in
`f2fs-tools-source.tar.gz` (revision
`e5aaa2909bcb04d0037f121e6514d0e2016f5338`). `build.py` applies the complete
changes: optionally enable encryption-aware local defragmentation and copy
fscrypt-encrypted regular-file data through the raw mapping while moving node,
directory, and other metadata through the decrypted mapping. Inodes are looked
up from SSA/NAT; logical file offsets and inode numbers remain unchanged.
No encrypted block is moved through a view that applies the wrong default key.
Both raw ciphertext and decrypted metadata writes use aligned direct I/O.
Sub-block metadata writes use an aligned read/modify/write buffer. This prevents
dirty pages at old metadata locations from later overwriting ciphertext moved
to those locations through the raw alias. Kernel replay, block-device reads,
and the decrypted mapping have separate caches; the tool flushes and invalidates
both block aliases after replay and after offline writes.

`fscrypt-record-check.inc` checks sealed FBE key records before replay, after
replay and after growth. A read-only private mount installs the existing system
DE key in that filesystem's kernel keyring, hashes the sealed per-user records
in memory, removes the key and unmounts. Version records must remain valid and
the snapshots must match; a clean fsck alone is not accepted as sufficient.
These records and key material are never written to logs or exported. The
system DE key must use the supported ordinary 64-byte format.

Other source baselines used by this build:

- AOSP/Lineage vold: `cd2c5dea3e93c07be61576f658d002ecc4570de2`.
- AOSP system hardware interfaces: `8266ce79ba801eb1cb71f123651ab083ad1c1044`.
- AOSP hardware interfaces (KeyMint): `13d687a84c834af855bfb314b853b8372a7aa6d9`.
- Keystore2 V5 and KeyMint V4 NDK client source is supplied in the adjacent
  AIDL source archives under its upstream Apache-2.0 license.

## Rebuilding

Use a corresponding, already built arm64 AOSP tree containing `vold` and
`fsck.f2fs`. The build does not modify that tree and can write its outputs to a
different filesystem. Obtain the existing compile/link commands with:

```sh
ninja -f out/combined-PRODUCT.ninja -t commands -s VOLD_MAIN_OBJECT > vold-compile.txt
ninja -f out/combined-PRODUCT.ninja -t commands -s VOLD_UNSTRIPPED_BINARY > vold-link.txt
ninja -f out/combined-PRODUCT.ninja -t commands -s F2FS_RESIZE_OBJECT F2FS_UNSTRIPPED_BINARY > f2fs-commands.txt
python3 native/userdata-grow/build.py --aosp ANDROID_BUILD_TOP \
  --vold-compile vold-compile.txt --vold-link vold-link.txt \
  --f2fs-commands f2fs-commands.txt --out OUTPUT_DIRECTORY --local-growth
```

Use `build.py --help` for the command format. Source revisions must match: the
script deliberately stops if the migration implementation changed. Copy the
two generated executables and the matching Keystore2 V5 / KeyMint V4 NDK shared
client libraries into `app/src/main/assets/userdata-grow/arm64-v8a/`. The Java
bridge extracts the APK's exact bytes to a root-owned, content-addressed private
directory, with a process-local library search path. The system binaries and
libraries remain unchanged. The main app's normal Gradle build uses these
checked-in assets and does not require an AOSP checkout.

`--local-growth` enables the experimental encryption-aware local relocation
branch. It patches `defrag.c` as well as the full migration fallback: encrypted
regular-file blocks use the raw ciphertext view, while nodes and directories
use the metadata-decrypted view. A source-summary snapshot is taken before the
move and reused when updating SSA and node pointers. Direct I/O and sealed-key
record checks remain required. Without this flag, the existing encrypted full
migration path remains in use. Benchmark and guest-boot results must be checked
before distributing a changed native asset; no benchmark-only device-name
allowlist belongs in a production executable.

Alpha 11 packages the build with `--local-growth`; earlier Alpha 9/10 assets
used full migration. The flag remains explicit for developers rebuilding native
tools. The ordinary Gradle app build uses the packaged asset. If upstream local
relocation cannot complete, its full migration fallback remains available.

`f2fs-progress.inc` emits only fixed numeric phase IDs and the completed/total
logical range of local relocation or full migration. Metadata-table and
checkpoint phases remain indeterminate. The Java bridge accepts only bounded
numeric records, replaces phase IDs with fixed UI text, and forwards them
immediately to the existing task bubble. These are phase counts, not overall
transaction percentages or physical write totals. Files, keys and dm tables are
never included in this progress protocol.

## Transaction and limits

The root bridge writes a durable journal before any replacement, copies and
independently verifies the entire raw image, retains the backup, allocates the
new image through gsid, verifies the restored raw prefix, then invokes offline
filesystem growth and a dry-run check. Before F2FS growth, a private mount
namespace mounts and normally unmounts the restored candidate so the kernel can
replay an unclean checkpoint. This never mounts the sole original before backup
and does not load user CE/DE keys. Native inspection is read-only; growth
requires a stopped slot, and the offline filesystem resizer runs unmounted.
Errors after replacement block
startup until recovery. Backup deletion requires a separate user action.

A separate durable boot-selection record allows recovery of this transaction's
own interrupted gsid session. Private decryption mappings use a slot-specific
name; only this tool's stale mapping is removed. Parent-death signals prevent
orphaned maintenance and filesystem-tool processes from continuing after the
root bridge exits. An unrelated gsid installation is never taken over.

The root-owned backup journal lives under `/data/adb/ultradsu/growth/<slot>/`.
It contains sizes, phases and digests, never key material. A fixed per-slot
encrypted-key record digest prevents recovery into a recreated slot with a
different key. A process lock serializes this app's root commands. Other root
DSU tools do not participate in that lock and must not operate concurrently.

Encrypted ext4 requires a separately validated online or encryption-aware path;
it is deliberately unavailable in this build. No broader compatibility is
implied by a successful test on one device. Corresponding source, licenses,
patched build recipe and the copyright-bearing upstream archive accompany the
native assets.

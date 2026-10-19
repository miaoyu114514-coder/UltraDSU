#!/bin/bash
set -euo pipefail
ROOT=$(cd "$(dirname "$0")/../.." && pwd)
CLANG=/opt/lineage/prebuilts/clang/host/linux-x86/clang-r574158/bin
OUT="$ROOT/.tooling/gsid-backport"
mkdir -p "$OUT"
"$CLANG/clang" --target=aarch64-linux-android34 -c "$ROOT/native/gsid-backport/path-policy.S" -o "$OUT/path-policy.o"
"$CLANG/llvm-objcopy" --only-section=.text -O binary "$OUT/path-policy.o" "$OUT/path-policy.bin"
python3 "$ROOT/native/gsid-backport/patch.py" "$ROOT"
"$CLANG/llvm-objdump" -d "$OUT/path-policy.o" > "$OUT/compiled-instructions.txt"
"$CLANG/clang" --target=aarch64-linux-android34 -nostdlib -static -Wl,-e,_start -Wl,--build-id=none "$ROOT/native/gsid-backport/remount-file.S" -o "$OUT/remount-file"

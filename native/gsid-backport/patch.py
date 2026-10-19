"""Reproducible, hash-pinned factory-binary backport; no added library imports."""
import hashlib
from pathlib import Path
import sys

root = Path(sys.argv[1])
source = root / '.tooling/gsid-Moto-Razr2022.bin'
original = source.read_bytes()
expected = 'a7360f6b305d6e1f7b3a51ee08146d3e5a4d5769c7cb11c3de38c734d6e8bcf3'
assert hashlib.sha256(original).hexdigest() == expected, 'Factory gsid hash mismatch'
instructions = (root / '.tooling/gsid-backport/path-policy.bin').read_bytes()
assert len(instructions) == 16
patched = bytearray(original)
for offset, old, new in ((0x4a690, instructions[0:4], instructions[4:8]),
                         (0x4a6a4, instructions[8:12], instructions[12:16])):
    assert original[offset:offset+4] == old, f'Unexpected instruction at {offset:#x}'
    patched[offset:offset+4] = new
    print(f'{offset:#x}: {old.hex()} -> {new.hex()}')
changed = [i for i, (a, b) in enumerate(zip(original, patched)) if a != b]
assert all(0x4a690 <= i < 0x4a694 or 0x4a6a4 <= i < 0x4a6a8 for i in changed)
target = root / 'dist/gsid-moto-api34-multislot'
target.write_bytes(patched)
digest = hashlib.sha256(patched).hexdigest()
(root / '.tooling/gsid-backport/SHA256SUMS.txt').write_text(f'{digest}  {target.name}\n')
print('Changed byte offsets:', [hex(i) for i in changed])
print('SHA256:', digest)
print('Size:', len(patched))

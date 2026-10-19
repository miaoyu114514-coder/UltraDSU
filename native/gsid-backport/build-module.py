import hashlib
from pathlib import Path
import zipfile

root = Path(__file__).resolve().parents[2]
module = root / 'native/gsid-backport/ksu-module'
payload = root / 'dist/gsid-moto-api34-multislot'
assert hashlib.sha256(payload.read_bytes()).hexdigest() == '6bea182e737e238ce8c054717f48e64afc1c8a989aab53850bc9a564651bd323'
files = {p.name: p.read_bytes() for p in module.iterdir() if p.is_file()}
files['system/bin/gsid'] = payload.read_bytes()
files['LICENSE'] = (root / 'LICENSE').read_bytes()
files['README.md'] = (root / 'native/gsid-backport/README.md').read_bytes()
files['SHA256SUMS'] = ''.join(f'{hashlib.sha256(data).hexdigest()}  {name}\n' for name, data in sorted(files.items())).encode()
target = root / 'dist/UltraDSU-GSID-Moto-API34-KSU-v1.0.1.zip'
with zipfile.ZipFile(target, 'w', zipfile.ZIP_DEFLATED, compresslevel=9) as archive:
    for name, data in sorted(files.items()):
        entry = zipfile.ZipInfo(name, (2026, 10, 19, 0, 0, 0))
        entry.compress_type = zipfile.ZIP_DEFLATED
        entry.external_attr = (0o100755 if name.endswith('.sh') or name.startswith('system/bin/') else 0o100644) << 16
        archive.writestr(entry, data)
with zipfile.ZipFile(target) as archive:
    assert archive.testzip() is None
    assert all(not n.startswith('/') and '..' not in n.split('/') for n in archive.namelist())
print(target)
print('Size:', target.stat().st_size)
print('SHA256:', hashlib.sha256(target.read_bytes()).hexdigest())

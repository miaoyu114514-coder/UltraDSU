#!/usr/bin/env python3
"""Build against an already compiled AOSP vold/fsck tree, without changing it.

Usage: python3 native/userdata-grow/build.py --aosp ANDROID_BUILD_TOP
       --vold-compile FILE --vold-link FILE --f2fs-commands FILE --out DIRECTORY
Command files are `ninja -t commands -s` output for main.o, unstripped/vold,
and fsck resize.o plus unstripped/fsck.f2fs respectively. No credentials needed.
"""
import argparse
import os
from pathlib import Path
import shlex
import subprocess

p = argparse.ArgumentParser(description=__doc__)
p.add_argument('--aosp', type=Path, required=True)
p.add_argument('--vold-compile', type=Path, required=True)
p.add_argument('--vold-link', type=Path, required=True)
p.add_argument('--f2fs-commands', type=Path, required=True)
p.add_argument('--out', type=Path, required=True)
p.add_argument('--local-growth', action='store_true', help='Experimental encryption-aware local relocation; validate on an independent copy first')
a = p.parse_args()
root, out = a.aosp.resolve(), a.out.resolve()
source = Path(__file__).resolve().parent
out.mkdir(parents=True, exist_ok=True)
env = dict(os.environ, TMPDIR=str(out))

def run(args):
    subprocess.run(args, cwd=root, env=env, check=True)

def compile_using(command, input_file, object_file):
    args = [v for v in shlex.split(command) if not v.startswith('PWD=')]
    args[args.index('-o') + 1] = str(object_file)
    args[args.index('-MF') + 1] = str(object_file) + '.d'
    args[-1] = str(input_file)
    args.extend(['-I' + str(source), f'-ffile-prefix-map={source.parents[1]}=.', f'-ffile-prefix-map={root}=aosp', '-Wno-unused-function'])
    run(args)
    return args[0]

def link_using(command, output, replacements):
    args = shlex.split(command)
    args.insert(1, '-Wl,--as-needed')
    response_arg = next(x for x in args if x.startswith('@'))
    objects = shlex.split((root / response_arg[1:]).read_text())
    updated = []
    for item in objects:
        updated.extend(replacements.get(item, [item]))
    response = out / (output.name + '.rsp')
    response.write_text(shlex.join(updated))
    args[args.index(response_arg)] = '@' + str(response)
    args[args.index('-o') + 1] = str(output)
    run(args)
    strip = root / Path(args[0]).parent / 'llvm-strip'
    run([str(strip), str(output)])

# Make retrieval read-only: upgrade a blob in memory when required, but never
# delete stale blobs, write upgraded blobs, commit keys or generate a new key.
key_storage = (root / 'system/vold/KeyStorage.cpp').read_text()
start = key_storage.index('static KeystoreOperation BeginKeystoreOp(')
end = key_storage.index('\nstatic bool encryptWithKeystoreKey', start)
key_storage = key_storage[:start] + '''static KeystoreOperation BeginKeystoreOp(Keystore& keystore, const std::string& dir,
        const km::AuthorizationSet& keyParams, const km::AuthorizationSet& opParams,
        km::AuthorizationSet* outParams) {
    km::AuthorizationSet params(keyParams);
    params.append(opParams.begin(), opParams.end());
    std::string blob;
    if (!readFileToString(dir + "/" + kFn_keymaster_key_blob, &blob)) return KeystoreOperation();
    return keystore.begin(blob, params, outParams);
}
''' + key_storage[end:]
readonly_key = out / 'KeyStorage-readonly.cpp'
readonly_key.write_text(key_storage)
vold_compile = a.vold_compile.read_text().strip()
compile_using(vold_compile, source / 'maintenance.cpp', out / 'maintenance.o')
compile_using(vold_compile, readonly_key, out / 'KeyStorage-readonly.o')
vold_link = a.vold_link.read_text().strip()
vold_response = next(v[1:] for v in shlex.split(vold_link) if v.startswith('@'))
vold_main = shlex.split((root / vold_response).read_text())[0]
link_using(vold_link, out / 'ultradsu-maintenance', {
    vold_main: [str(out / 'maintenance.o'), str(out / 'KeyStorage-readonly.o')]
})

resize = (root / 'external/f2fs-tools/fsck/resize.c').read_text()
resize = resize.replace('#include "fsck.h"', '#include "fsck.h"\n#include "' + str(source / 'f2fs-ciphertext-mover.inc') + '"', 1)
old = '''\t\t\tfrom = START_BLOCK(sbi, i) + j;
\t\t\tret = dev_read_block(raw, from);
\t\t\tASSERT(ret >= 0);

\t\t\tto = from + offset;
\t\t\tret = dev_write_block(raw, to,
\t\t\t\t\t      f2fs_io_type_to_rw_hint(se->type));
\t\t\tASSERT(ret >= 0);

\t\t\tget_sum_entry(sbi, from, &sum);'''
new = '''\t\t\tfrom = START_BLOCK(sbi, i) + j;
            to = from + offset;
            get_sum_entry(sbi, from, &sum);
            if (getenv("ULTRADSU_RAW_DEVICE") && IS_DATASEG(se->type) &&
                    ultradsu_encrypted_data(sbi, le32_to_cpu(sum.nid))) {
                ultradsu_copy_ciphertext(from, to);
            } else {
                ret = dev_read_block(raw, from);
                ASSERT(ret >= 0);
                ret = dev_write_block(raw, to, f2fs_io_type_to_rw_hint(se->type));
                ASSERT(ret >= 0);
            }'''
assert resize.count(old) == 1, 'Unsupported F2FS source revision: migration changed'
resize = resize.replace(old, new)
condition = 'if (new_main_blkaddr < end_blkaddr) {'
assert resize.count(condition) == 1
if not a.local_growth:
    resize = resize.replace(condition, 'if (!getenv("ULTRADSU_RAW_DEVICE") && new_main_blkaddr < end_blkaddr) {')
patched_resize = out / 'resize-ciphertext.c'
resize = resize.replace('#include "fsck.h"', '#include "fsck.h"\n#include "' + str(source / 'f2fs-progress.inc') + '"', 1)
main_loop = '\tfor (i = MAIN_SEGS(sbi) - 1; i >= 0; i--) {'
assert resize.count(main_loop) == 1
resize = resize.replace(main_loop, main_loop + '''
        ultradsu_fs_progress(2, (unsigned long long)(MAIN_SEGS(sbi) - 1 - i) *
                sbi->blocks_per_seg * F2FS_BLKSIZE,
                (unsigned long long)MAIN_SEGS(sbi) * sbi->blocks_per_seg * F2FS_BLKSIZE);''')
resize = resize.replace('\tfree(raw);\n\tDBG(0, "Info: Done to migrate Main area:', '''
    ultradsu_fs_progress(2, (unsigned long long)MAIN_SEGS(sbi) * sbi->blocks_per_seg * F2FS_BLKSIZE,
            (unsigned long long)MAIN_SEGS(sbi) * sbi->blocks_per_seg * F2FS_BLKSIZE);
\tfree(raw);\n\tDBG(0, "Info: Done to migrate Main area:''', 1)
for name, phase in [('migrate_ssa', 5), ('migrate_nat', 3), ('migrate_sit', 4), ('rebuild_checkpoint', 6)]:
    start = resize.index('static void ' + name + '(')
    brace = resize.index('{', start)
    resize = resize[:brace+1] + f'\n    ultradsu_fs_progress({phase}, 0, 0);' + resize[brace+1:]
patched_resize.write_text(resize)
commands = a.f2fs_commands.read_text().splitlines()
assert len(commands) == 2, 'Expected resize.o compile and fsck.f2fs link commands'
compile_using(commands[0], patched_resize, out / 'resize.o')
resize_object = next(v for v in shlex.split((root / next(v[1:] for v in shlex.split(commands[1]) if v.startswith('@'))).read_text()) if v.endswith('/fsck/resize.o'))

io = (root / 'external/f2fs-tools/lib/libf2fs_io.c').read_text()
anchor = 'static int __dev_write(void *buf, __u64 offset, size_t len, enum rw_hint whint)'
assert io.count(anchor) == 1, 'Unsupported F2FS I/O source revision'
io = io.replace(anchor, '#include "' + str(source / 'f2fs-direct-io.inc') + '"\n\n' + anchor)
write_start = io.index('#ifdef HAVE_PWRITE', io.index(anchor))
write_end = io.index('\n\tc.need_fsync = true;', write_start)
io = io[:write_start] + '''if (getenv("ULTRADSU_RAW_DEVICE")) {
        if (ultradsu_direct_write(fd, buf, len, offset) != 0) return -1;
    } else {
''' + io[write_start:write_end] + '\n    }\n' + io[write_end:]
patched_io = out / 'libf2fs_io-direct.c'
patched_io.write_text(io)
compile_using(commands[0], patched_io, out / 'libf2fs_io-direct.o')
link_objects = shlex.split((root / next(v[1:] for v in shlex.split(commands[1]) if v.startswith('@'))).read_text())
io_object = next(v for v in link_objects if v.endswith('/lib/libf2fs_io.o'))
replacements = {
    resize_object: [str(out / 'resize.o')],
    io_object: [str(out / 'libf2fs_io-direct.o')],
}
if a.local_growth:
    defrag = (root / 'external/f2fs-tools/fsck/defrag.c').read_text()
    defrag = defrag.replace('#include "fsck.h"', '#include "fsck.h"\n#include "' + str(source / 'f2fs-ciphertext-mover.inc') + '"\n#include "' + str(source / 'f2fs-progress.inc') + '"', 1)
    scan = '\tfor (idx = from; idx < from + len; idx++) {'
    assert defrag.count(scan) == 1
    defrag = defrag.replace(scan, scan + '''
        if (!((idx - from) & 511))
            ultradsu_fs_progress(1, (idx - from) * F2FS_BLKSIZE, len * F2FS_BLKSIZE);''')
    finish = '\t/* update curseg info; can update sit->types */'
    assert defrag.count(finish) == 1
    defrag = defrag.replace(finish, '\tultradsu_fs_progress(1, len * F2FS_BLKSIZE, len * F2FS_BLKSIZE);\n\n' + finish)
    old_copy = '''\t/* read from */
\tret = dev_read_block(raw, from);
\tASSERT(ret >= 0);

\t/* get segment type */
\tse = get_seg_entry(sbi, GET_SEGNO(sbi, from));
\t/* write to */
\tret = dev_write_block(raw, to, f2fs_io_type_to_rw_hint(se->type));
\tASSERT(ret >= 0);'''
    new_copy = '''se = get_seg_entry(sbi, GET_SEGNO(sbi, from));
    get_sum_entry(sbi, from, &sum);
    if (getenv("ULTRADSU_RAW_DEVICE") && IS_DATASEG(se->type) &&
            ultradsu_encrypted_data(sbi, le32_to_cpu(sum.nid))) {
        ultradsu_copy_ciphertext(from, to);
    } else {
        ret = dev_read_block(raw, from);
        ASSERT(ret >= 0);
        ret = dev_write_block(raw, to, f2fs_io_type_to_rw_hint(se->type));
        ASSERT(ret >= 0);
    }'''
    assert defrag.count(old_copy) == 1, 'Unsupported F2FS local relocation revision'
    defrag = defrag.replace(old_copy, new_copy)
    duplicate = '\t/* read/write SSA */\n\tget_sum_entry(sbi, from, &sum);'
    assert defrag.count(duplicate) == 1
    defrag = defrag.replace(duplicate, '\t/* Source summary was captured before moving the block. */')
    patched_defrag = out / 'defrag-ciphertext.c'
    patched_defrag.write_text(defrag)
    compile_using(commands[0], patched_defrag, out / 'defrag.o')
    defrag_object = next(v for v in link_objects if v.endswith('/fsck/defrag.o'))
    replacements[defrag_object] = [str(out / 'defrag.o')]
link_using(commands[1], out / 'resize.f2fs', replacements)

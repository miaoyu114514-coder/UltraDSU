/* SPDX-License-Identifier: GPL-2.0-only
 * Buffered metadata writes through the decrypted device can otherwise overwrite
 * newer ciphertext written through its raw alias when old source pages flush.
 * Keep encrypted-growth writes coherent through aligned direct I/O, including
 * read-modify-write for superblock fragments.
 */
_Static_assert(__builtin_offsetof(struct f2fs_super_block, devs) == 2201 &&
               sizeof(((struct f2fs_super_block *)0)->devs[0]) == 68 && MAX_DEVICES == 8,
               "Update the maintenance helper's single-device layout guard");
static int ultradsu_direct_write(int original, const void *input, size_t length,
                               __u64 offset)
{
    static int source_fd = -1, direct_fd = -1;
    unsigned char block[4096] __attribute__((aligned(4096)));
    const unsigned char *bytes = input;
    if (source_fd != original || direct_fd < 0) {
        char path[64];
        if (direct_fd >= 0) close(direct_fd);
        snprintf(path, sizeof(path), "/proc/self/fd/%d", original);
        direct_fd = open(path, O_RDWR | O_DIRECT | O_CLOEXEC);
        if (direct_fd < 0) return -1;
        source_fd = original;
    }
    while (length) {
        const __u64 start = offset & ~4095ULL;
        const size_t within = offset - start;
        const size_t count = length < 4096 - within ? length : 4096 - within;
        if (within || count != 4096) {
            ssize_t read_count;
            do { read_count = pread(direct_fd, block, sizeof(block), start); } while (read_count < 0 && errno == EINTR);
            if (read_count != sizeof(block)) return -1;
        }
        memcpy(block + within, bytes, count);
        ssize_t written;
        do { written = pwrite(direct_fd, block, sizeof(block), start); } while (written < 0 && errno == EINTR);
        if (written != sizeof(block)) return -1;
        bytes += count; offset += count; length -= count;
    }
    return 0;
}

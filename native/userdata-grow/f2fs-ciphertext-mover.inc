/* SPDX-License-Identifier: GPL-2.0-only
 * Included by the patched f2fs-tools fsck/resize.c.
 * dm-default-key does not encrypt fscrypt regular-file contents. Moving those
 * blocks through the default-key view changes their ciphertext. Copy them via
 * the raw view while moving metadata through the decrypted view.
 */
#include <fcntl.h>
#include <unistd.h>
#include <stdlib.h>
#include <sys/stat.h>

static int ultradsu_encrypted_data(struct f2fs_sb_info *sbi, nid_t nid)
{
    struct node_info ni;
    /* f2fs_node has flexible block-sized fields; sizeof(struct f2fs_node)
     * deliberately excludes its footer. Always allocate an entire disk block. */
    unsigned char inode_block[F2FS_BLKSIZE] __attribute__((aligned(8)));
    struct f2fs_node *inode = (struct f2fs_node *)inode_block;
    ASSERT(IS_VALID_NID(sbi, nid));
    get_node_info(sbi, nid, &ni);
    ASSERT(IS_VALID_NID(sbi, ni.ino));
    get_node_info(sbi, ni.ino, &ni);
    ASSERT(f2fs_is_valid_blkaddr(sbi, ni.blk_addr, DATA_GENERIC));
    ASSERT(dev_read_block(inode, ni.blk_addr) >= 0);
    ASSERT(le32_to_cpu(F2FS_NODE_FOOTER(inode)->nid) == le32_to_cpu(F2FS_NODE_FOOTER(inode)->ino));
    return S_ISREG(le16_to_cpu(inode->i.i_mode)) && file_is_encrypt(&inode->i);
}

static void ultradsu_copy_ciphertext(block_t from, block_t to)
{
    static int fd = -1;
    unsigned char data[F2FS_BLKSIZE] __attribute__((aligned(4096)));
    if (fd < 0) {
        const char *path = getenv("ULTRADSU_RAW_DEVICE");
        ASSERT(path && !strncmp(path, "/dev/block/dm-", 14));
        // This mapping aliases the decrypted view. Buffered reads can return
        // stale ciphertext after replay or writes through the other mapping.
        fd = open(path, O_RDWR | O_CLOEXEC | O_DIRECT);
        ASSERT(fd >= 0);
    }
    ASSERT(pread(fd, data, sizeof(data), (off_t)from * F2FS_BLKSIZE) == sizeof(data));
    ASSERT(pwrite(fd, data, sizeof(data), (off_t)to * F2FS_BLKSIZE) == sizeof(data));
}

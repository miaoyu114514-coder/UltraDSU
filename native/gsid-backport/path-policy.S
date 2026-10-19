/* Moto XT2251-1 API 34 factory gsid, AArch64.
 * Compile both old and new instructions so the patcher can verify exact sites.
 * std::string::compare(0, count, prefix, 14) keeps the existing libc++ ABI.
 */
.text
.global policy_instructions
policy_instructions:
    b.ne .+36             /* old: reject lengths other than 14 */
    b.lo .+36             /* new: reject lengths below 14 */
    mov x2, #-1           /* old: compare the whole path */
    mov x2, #14           /* new: compare the default directory prefix */

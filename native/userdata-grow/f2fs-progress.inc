/* SPDX-License-Identifier: GPL-2.0-only
 * Fixed numeric progress protocol. No file names, keys, or device tables.
 */
#include <time.h>
#include <stdio.h>

static void ultradsu_fs_progress(unsigned int phase, unsigned long long done,
        unsigned long long total)
{
    static unsigned int last_phase;
    static unsigned long long last_ms;
    struct timespec now;
    if (clock_gettime(CLOCK_MONOTONIC, &now) != 0)
        return;
    unsigned long long ms = (unsigned long long)now.tv_sec * 1000 + now.tv_nsec / 1000000;
    if (phase != last_phase || ms - last_ms >= 1000 || (total && done == total)) {
        printf("ULTRADSU_FS_PROGRESS:%u:%llu:%llu\n", phase, done, total);
        fflush(stdout);
        last_phase = phase;
        last_ms = ms;
    }
}

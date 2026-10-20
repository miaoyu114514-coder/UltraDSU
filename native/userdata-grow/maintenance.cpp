/*
 * SPDX-License-Identifier: Apache-2.0
 * Uses AOSP vold KeyStorage and libdm. Keys are never passed through argv or logs.
 */
#include "KeyStorage.h"
#include <android-base/file.h>
#include <android-base/logging.h>
#include <android-base/properties.h>
#include <android-base/unique_fd.h>
#include <fstab/fstab.h>
#include <libdm/dm.h>
#include <libdm/dm_target.h>
#include <openssl/crypto.h>
#include <sys/ioctl.h>
#include <sys/prctl.h>
#include <sys/stat.h>
#include <sys/wait.h>
#include <linux/fs.h>
#include <fcntl.h>
#include <endian.h>
#include <unistd.h>
#include <chrono>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <regex>
#include <vector>
#include <sched.h>
#include <signal.h>
#include <sys/mount.h>
#include <memory>

namespace android::vold { android::fs_mgr::Fstab fstab_default; }
using android::base::unique_fd;
using namespace std::chrono_literals;

// Only retain the numeric Keystore error. Other native logs can contain dm tables.
static int keyStorageError = 0;
static std::unique_ptr<android::vold::KeyBuffer> assistedMaterial;

#include "fscrypt-record-check.inc"

static int fail(const char* text) { fprintf(stderr, "ULTRADSU_GROW_ERROR:%s\n", text); return 1; }
#include "guest-assistance.inc"
static uint32_t u32(const unsigned char* data) { uint32_t result; memcpy(&result, data, 4); return le32toh(result); }
static uint64_t u64(const unsigned char* data) { uint64_t result; memcpy(&result, data, 8); return le64toh(result); }
struct Geometry {
    const char* filesystem = "unknown";
    uint64_t bytes = 0, safeMaximum = 0;
};
static Geometry inspect(const std::string& path) {
    unsigned char data[4096]{};
    unique_fd fd(open(path.c_str(), O_RDONLY | O_CLOEXEC));
    if (fd < 0 || !android::base::ReadFully(fd, data, sizeof(data))) return {};
    const unsigned char* sb = data + 1024;
    if (u32(sb) == 0xf2f52010 && u32(sb + 16) == 12 && u32(sb + 20) == 9) {
        // Standard DSU userdata uses one device. Never follow block-device
        // paths supplied inside a multi-device filesystem's superblock.
        for (unsigned i = 0; i < 8; ++i) {
            if (sb[2201 + i * 68] != 0) return {"unsupported_f2fs_multidevice", 0, 0};
        }
        Geometry result; result.filesystem = "f2fs"; result.bytes = u64(sb + 36) * 4096;
        // F2FS safe growth cannot move the Main area or exceed existing SSA/SIT capacity.
        const uint64_t ssaSegments = u32(sb + 64);
        const uint64_t sitSegments = u32(sb + 56);
        const uint64_t mainBlock = u32(sb + 92);
        const uint64_t capacitySegments = std::min(ssaSegments * 512, (sitSegments / 2) * 512 * 55);
        result.safeMaximum = (mainBlock + capacitySegments * 512) * 4096;
        return result;
    }
    if (sb[56] == 0x53 && sb[57] == 0xef && u32(sb + 24) <= 6) {
        Geometry result; result.filesystem = "ext4";
        uint64_t blocks = u32(sb + 4);
        if (u32(sb + 96) & 0x80) blocks |= uint64_t(u32(sb + 0x150)) << 32;
        result.bytes = blocks * (1024ULL << u32(sb + 24)); result.safeMaximum = 512ULL << 30;
        return result;
    }
    return {};
}
struct Mapping {
    std::string name;
    ~Mapping() { if (!name.empty()) android::dm::DeviceMapper::Instance().DeleteDevice(name, 10s); }
};
static int runTool(const std::string& executable, const std::vector<std::string>& args,
                   const std::string& raw, bool encrypted) {
    pid_t child = fork();
    if (child < 0) return -1;
    if (child == 0) {
        prctl(PR_SET_PDEATHSIG, SIGKILL);
        if (getppid() == 1) _exit(127);
        if (encrypted) setenv("ULTRADSU_RAW_DEVICE", raw.c_str(), 1);
        else unsetenv("ULTRADSU_RAW_DEVICE");
        std::vector<char*> argv;
        for (const auto& arg : args) argv.push_back(const_cast<char*>(arg.c_str()));
        argv.push_back(nullptr);
        execv(executable.c_str(), argv.data());
        _exit(127);
    }
    int status = 0;
    while (waitpid(child, &status, 0) < 0) { if (errno != EINTR) return -1; }
    return WIFEXITED(status) ? WEXITSTATUS(status) : -1;
}
static bool replayAndUnmount(const std::string& device, const std::string& tools, const std::string& slot) {
    // Replay on the restored candidate, never on the sole original before backup.
    // No CE/DE keys or user file reads are required for ordinary F2FS mount replay.
    if (unshare(CLONE_NEWNS) != 0 || mount(nullptr, "/", nullptr, MS_REC | MS_PRIVATE, nullptr) != 0) return false;
    const std::string point = tools + "/mount-" + slot;
    if (mkdir(point.c_str(), 0700) != 0 && errno != EEXIST) return false;
    struct stat st{};
    if (lstat(point.c_str(), &st) || !S_ISDIR(st.st_mode) || st.st_uid != 0) return false;
    if (mount(device.c_str(), point.c_str(), "f2fs", MS_NODEV | MS_NOSUID | MS_NOEXEC, "inlinecrypt") != 0) { rmdir(point.c_str()); return false; }
    if (umount(point.c_str()) != 0) { umount2(point.c_str(), MNT_DETACH); return false; }
    rmdir(point.c_str());
    return true;
}
static bool synchronizeViews(const std::string& raw, const std::string& view) {
    // Filesystem bios and the two block-device mappings have distinct caches.
    // After kernel replay or offline writes, flush and invalidate both aliases
    // before an offline tool or probe reads them again. No filesystem is mounted.
    for (const std::string& path : {raw, view}) {
        unique_fd fd(open(path.c_str(), O_RDWR | O_CLOEXEC));
        if (fd < 0 || fsync(fd) != 0 || ioctl(fd, BLKFLSBUF) != 0) return false;
    }
    return true;
}
int main(int argc, char** argv) {
    prctl(PR_SET_PDEATHSIG, SIGKILL);
    if (getppid() == 1) return 1;
    prctl(PR_SET_DUMPABLE, 0);
    // libdm error messages can serialize a table. Never forward table/key text.
    android::base::InitLogging(argv, [](android::base::LogId, android::base::LogSeverity severity,
            const char*, const char*, unsigned int, const char* message) {
        constexpr const char* prefix = "keystore2 Keystore createOperation returned service specific error: ";
        if (message && strncmp(message, prefix, strlen(prefix)) == 0) {
            const char* value = message + strlen(prefix);
            char* end = nullptr;
            const long code = strtol(value, &end, 10);
            if (end != value && *end == '\0' && code >= -10000 && code <= 10000) keyStorageError = int(code);
        }
        if (severity >= android::base::ERROR) fprintf(stderr, "ULTRADSU_GROW: native subsystem error\n");
    });
    if (getuid() != 0 || argc < 3) return fail("Root and operation/slot are required");
    const std::string operation = argv[1], slot = argv[2];
    if (!std::regex_match(slot, std::regex("[a-zA-Z0-9][a-zA-Z0-9_-]{0,31}"))) return fail("Invalid slot");
    if (operation == "assist-capture") {
        if (argc != 5) return fail("Invalid capture arguments");
        return captureGuestAssistance(slot, argv[3], argv[4]);
    }
    if (argc < 4) return fail("Raw device is required");
    const std::string raw = argv[3];
    if (access("/metadata/gsi/dsu/booted", F_OK) == 0) return fail("Return to host before maintenance");
    if (std::string(argv[argc-1]) == "--assist") {
        if (operation != "check" && operation != "probe" && operation != "resize") return fail("Invalid assisted operation");
        assistedMaterial = std::make_unique<android::vold::KeyBuffer>(128);
        if (!android::base::ReadFully(STDIN_FILENO, assistedMaterial->data(), assistedMaterial->size())) return fail("Incomplete assisted handoff");
        --argc;
    }
    if (operation == "cleanup") {
        auto& dm = android::dm::DeviceMapper::Instance();
        const std::string name = "ultradsu-grow-" + slot;
        if (dm.GetState(name) != android::dm::DmDeviceState::INVALID && !dm.DeleteDevice(name, 10s)) return fail("Cannot remove stale maintenance mapping; reboot to host");
        puts("ULTRADSU_NATIVE:{\"cleaned\":true}"); return 0;
    }
    if (!std::regex_match(raw, std::regex("/dev/block/dm-[0-9]+"))) return fail("Invalid device");
    std::string deviceName;
    if (!android::base::ReadFileToString("/sys/block/" + raw.substr(11) + "/dm/name", &deviceName) ||
        deviceName != "userdata_gsi\n") return fail("Target is not the DSU userdata mapping");
    unique_fd rawFd(open(raw.c_str(), O_RDONLY | O_CLOEXEC));
    uint64_t blockBytes = 0;
    if (rawFd < 0 || ioctl(rawFd, BLKGETSIZE64, &blockBytes) != 0 || blockBytes < (1ULL << 30) ||
        blockBytes > (512ULL << 30) || blockBytes % 4096) return fail("Invalid DSU block size");
    Geometry geometry = inspect(raw);
    size_t recordsChecked = 0;
    bool encrypted = std::string(geometry.filesystem) == "unknown";
    std::string view = raw;
    Mapping mapping;
    if (encrypted) {
        const std::string keyDir = "/metadata/vold/metadata_encryption/dsu/" + slot;
        std::string recorded;
        if (!android::base::ReadFileToString("/metadata/gsi/dsu/" + slot + "/metadata_encryption_dir", &recorded) ||
            recorded != keyDir) return fail("Unsupported per-slot key directory");
        if (!android::fs_mgr::ReadDefaultFstab(&android::vold::fstab_default)) return fail("Cannot read encryption configuration");
        const auto* entry = android::fs_mgr::GetEntryForMountPoint(&android::vold::fstab_default, "/data");
        if (!entry || (!entry->metadata_encryption_options.empty() && entry->metadata_encryption_options != "aes-256-xts"))
            return fail("Unsupported metadata cipher or wrapped-key format");
        if (android::base::GetIntProperty("ro.crypto.dm_default_key.options_format.version", 2) != 2)
            return fail("Legacy metadata encryption is unsupported");
        android::vold::KeyBuffer key;
        bool retrieved = false;
        if (assistedMaterial) { key = android::vold::KeyBuffer(64); memcpy(key.data(), assistedMaterial->data(), 64); retrieved = true; }
        else retrieved = android::vold::retrieveKey(keyDir + "/key", android::vold::kEmptyAuthentication, &key);
        if (!retrieved || key.size() != 64) {
            if (keyStorageError == -38)
                return fail("Cannot unseal existing slot key: KEYSTORE_INVALID_ARGUMENT(-38); no key was generated");
            return fail("Cannot unseal existing slot key; no key was generated");
        }
        std::string hex; hex.reserve(128);
        constexpr char alphabet[] = "0123456789abcdef";
        for (unsigned char value : key) { hex += alphabet[value >> 4]; hex += alphabet[value & 15]; }
        android::dm::DmTable table;
        auto target = std::make_unique<android::dm::DmTargetDefaultKey>(0, blockBytes / 512, "aes-xts-plain64", hex, raw, 0);
        target->SetSetDun();
        if (!table.AddTarget(std::move(target))) return fail("Invalid private decryption table");
        OPENSSL_cleanse(hex.data(), hex.size());
        table.set_readonly(operation == "probe" || operation == "check");
        const std::string name = "ultradsu-grow-" + slot;
        auto& dm = android::dm::DeviceMapper::Instance();
        if (dm.GetState(name) != android::dm::DmDeviceState::INVALID) return fail("A maintenance mapping already exists");
        if (!dm.CreateDevice(name, table, &view, 10s)) return fail("Cannot create private decryption mapping");
        mapping.name = name;
        geometry = inspect(view);
    }
    if (geometry.bytes == 0 || geometry.bytes > blockBytes) return fail("Filesystem could not be validated with existing key");
    if (std::string(geometry.filesystem) == "ext4" && encrypted)
        return fail("Encrypted ext4 offline growth is not enabled; use a validated guest online grow path");
    if (operation != "probe") {
        const std::string executable = android::base::Dirname(argv[0]) + "/resize.f2fs";
        if (std::string(geometry.filesystem) == "f2fs") {
            if (runTool(executable, {"fsck.f2fs", "--dry-run", view}, raw, false) != 0)
                return fail("F2FS precheck failed; filesystem was not expanded");
            const std::string tools = android::base::Dirname(argv[0]);
            KeyRecords before;
            if (!captureKeyRecords(view, tools, slot, &before))
                return fail("Cannot validate existing FBE key records; filesystem was not expanded");
            recordsChecked = before.size();
            if (operation == "resize") {
                if (argc != 6 || strtoull(argv[4], nullptr, 10) != geometry.bytes ||
                    strtoull(argv[5], nullptr, 10) != blockBytes || geometry.bytes >= blockBytes)
                    return fail("Growth journal size does not match filesystem/device");
                if (!replayAndUnmount(view, android::base::Dirname(argv[0]), slot))
                    return fail("Cannot replay and cleanly unmount candidate F2FS; backup retained");
                if (!synchronizeViews(raw, view)) return fail("Cannot synchronize replayed block views");
                KeyRecords replayed;
                if (!captureKeyRecords(view, tools, slot, &replayed) || replayed != before)
                    return fail("FBE key records changed during replay; restore verified backup");
                // The app already confirmed the target and verified a full backup.
                // -F skips the upstream interactive experimental-use prompt.
                // Lowercase -f ignores errors and must never be used here.
                if (runTool(executable, {"resize.f2fs", "-F", view}, raw, encrypted) != 0)
                    return fail("F2FS growth failed; restore the verified backup");
                if (!synchronizeViews(raw, view))
                    return fail("Cannot synchronize expanded filesystem");
                if (runTool(executable, {"fsck.f2fs", "--dry-run", view}, raw, false) != 0)
                    return fail("Expanded F2FS failed verification; restore the backup");
                KeyRecords after;
                if (!captureKeyRecords(view, tools, slot, &after) || after != before)
                    return fail("Expanded FBE key records failed verification; restore backup");
                geometry = inspect(view);
                if (geometry.bytes != blockBytes) return fail("Filesystem did not reach requested capacity");
            } else if (operation != "check") return fail("Unknown maintenance operation");
        } else {
            if (runTool("/system/bin/e2fsck", {"e2fsck", "-fn", view}, raw, false) != 0)
                return fail("ext4 precheck failed");
            if (operation == "resize") {
                if (argc != 6 || strtoull(argv[4], nullptr, 10) != geometry.bytes ||
                    strtoull(argv[5], nullptr, 10) != blockBytes || geometry.bytes >= blockBytes)
                    return fail("Growth journal size mismatch");
                if (runTool("/system/bin/resize2fs", {"resize2fs", view}, raw, false) != 0)
                    return fail("ext4 growth failed; restore backup");
                geometry = inspect(view);
            } else if (operation != "check") return fail("Unknown maintenance operation");
        }
    }
    printf("ULTRADSU_NATIVE:{\"filesystem\":\"%s\",\"filesystemBytes\":%llu,\"blockBytes\":%llu,\"safeMaximum\":%llu,\"encrypted\":%s,\"keyRecordsChecked\":%zu}\n",
        geometry.filesystem, (unsigned long long)geometry.bytes, (unsigned long long)blockBytes,
        (unsigned long long)geometry.safeMaximum, encrypted ? "true" : "false", recordsChecked);
    return 0;
}

// SPDX-License-Identifier: Apache-2.0
// Check sealed FBE key records through the kernel's fscrypt view. No record or
// key is exported. This catches damage invisible to an ordinary filesystem fsck.
#include <linux/fscrypt.h>
#include <dirent.h>
#include <array>
#include <map>
#include <openssl/sha.h>

using KeyRecords = std::map<std::string, std::array<unsigned char, SHA256_DIGEST_LENGTH>>;

static bool scanKeyRecords(int parent, const std::string& prefix, KeyRecords* records, int depth) {
    if (depth > 8 || records->size() > 512) return false;
    DIR* entries = fdopendir(dup(parent));
    if (!entries) return false;
    bool success = true;
    while (dirent* entry = readdir(entries)) {
        const std::string name = entry->d_name;
        if (name == "." || name == "..") continue;
        struct stat st{};
        if (fstatat(parent, name.c_str(), &st, AT_SYMLINK_NOFOLLOW) != 0) { success = false; break; }
        unique_fd fd(openat(parent, name.c_str(), O_RDONLY | O_CLOEXEC | O_NOFOLLOW));
        if (fd < 0) { success = false; break; }
        if (S_ISDIR(st.st_mode)) {
            if (!scanKeyRecords(fd, prefix + name + "/", records, depth + 1)) { success = false; break; }
        } else if (S_ISREG(st.st_mode) && st.st_size >= 0 && st.st_size <= 65536) {
            std::string data;
            if (!android::base::ReadFdToString(fd, &data) || data.size() != uint64_t(st.st_size) ||
                    (name == "version" && data != "1")) { success = false; break; }
            std::array<unsigned char, SHA256_DIGEST_LENGTH> hash{};
            SHA256(reinterpret_cast<const unsigned char*>(data.data()), data.size(), hash.data());
            (*records)[prefix + name] = hash;
            OPENSSL_cleanse(data.data(), data.size());
        } else { success = false; break; }
    }
    closedir(entries);
    return success;
}

static bool captureKeyRecords(const std::string& device, const std::string& tools,
                              const std::string& slot, KeyRecords* records) {
    if (unshare(CLONE_NEWNS) != 0 || mount(nullptr, "/", nullptr, MS_REC | MS_PRIVATE, nullptr) != 0) return false;
    const std::string point = tools + "/verify-" + slot;
    if (mkdir(point.c_str(), 0700) != 0 && errno != EEXIST) return false;
    struct stat pointStat{};
    if (lstat(point.c_str(), &pointStat) != 0 || !S_ISDIR(pointStat.st_mode) || pointStat.st_uid != 0) return false;
    if (mount(device.c_str(), point.c_str(), "f2fs", MS_RDONLY | MS_NODEV | MS_NOSUID | MS_NOEXEC,
            "inlinecrypt,norecovery") != 0) { rmdir(point.c_str()); return false; }
    bool success = false;
    {
        unique_fd root(open(point.c_str(), O_RDONLY | O_DIRECTORY | O_CLOEXEC | O_NOFOLLOW));
        const std::string systemKey = point + "/unencrypted/key";
        struct stat st{};
        if (root >= 0 && lstat(systemKey.c_str(), &st) != 0 && errno == ENOENT) {
            // An uninitialized or non-FBE filesystem has no sealed user keys.
            success = true;
        } else if (root >= 0 && S_ISDIR(st.st_mode)) {
            bool canonical = true;
            for (const std::string& path : {point + "/unencrypted", systemKey,
                    systemKey + "/version", systemKey + "/encrypted_key",
                    systemKey + "/secdiscardable", systemKey + "/keymaster_key_blob"}) {
                char* actual = realpath(path.c_str(), nullptr);
                if (!actual || path != actual) canonical = false;
                free(actual);
            }
            android::vold::KeyBuffer key;
            bool retrieved = false;
            if (canonical && assistedMaterial) { key = android::vold::KeyBuffer(64); memcpy(key.data(), assistedMaterial->data() + 64, 64); retrieved = true; }
            else if (canonical) retrieved = android::vold::retrieveKey(systemKey, android::vold::kEmptyAuthentication, &key);
            if (retrieved && key.size() == 64) {
                std::vector<unsigned char> buffer(sizeof(fscrypt_add_key_arg) + key.size(), 0);
                auto* add = reinterpret_cast<fscrypt_add_key_arg*>(buffer.data());
                add->key_spec.type = FSCRYPT_KEY_SPEC_TYPE_IDENTIFIER;
                add->raw_size = key.size();
                memcpy(add->raw, key.data(), key.size());
                if (ioctl(root, FS_IOC_ADD_ENCRYPTION_KEY, add) == 0) {
                    fscrypt_remove_key_arg remove{}; remove.key_spec = add->key_spec;
                    // Open each component without following guest-controlled symlinks.
                    unique_fd misc(openat(root, "misc", O_RDONLY | O_DIRECTORY | O_CLOEXEC | O_NOFOLLOW));
                    unique_fd vold(misc >= 0 ? openat(misc, "vold", O_RDONLY | O_DIRECTORY | O_CLOEXEC | O_NOFOLLOW) : -1);
                    unique_fd users(vold >= 0 ? openat(vold, "user_keys", O_RDONLY | O_DIRECTORY | O_CLOEXEC | O_NOFOLLOW) : -1);
                    if (users >= 0) success = scanKeyRecords(users, "", records, 0);
                    else if (vold >= 0 && errno == ENOENT) success = true;
                    users.reset(); vold.reset(); misc.reset();
                    ioctl(root, FS_IOC_REMOVE_ENCRYPTION_KEY, &remove);
                }
                OPENSSL_cleanse(buffer.data(), buffer.size());
            }
        }
    }
    if (umount(point.c_str()) != 0) { umount2(point.c_str(), MNT_DETACH); success = false; }
    rmdir(point.c_str());
    return success;
}

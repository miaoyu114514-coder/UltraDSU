// SPDX-License-Identifier: Apache-2.0
// Guest-to-host encrypted handoff. Plaintext key material stays in process memory.
#include <openssl/evp.h>
#include <openssl/rsa.h>
#include <openssl/rand.h>
#include <openssl/x509.h>
#include <sys/vfs.h>

static std::string assistHex(const unsigned char* bytes, size_t length) {
    static constexpr char alphabet[] = "0123456789abcdef";
    std::string result;
    for (size_t i = 0; i < length; ++i) { result += alphabet[bytes[i] >> 4]; result += alphabet[bytes[i] & 15]; }
    return result;
}
static std::string assistHash(const std::string& text) {
    unsigned char hash[SHA256_DIGEST_LENGTH];
    SHA256(reinterpret_cast<const unsigned char*>(text.data()), text.size(), hash);
    return assistHex(hash, sizeof(hash));
}
static std::string assistBase64(const unsigned char* bytes, size_t size) {
    std::string text(4 * ((size + 2) / 3) + 1, '\0');
    const int count = EVP_EncodeBlock(reinterpret_cast<unsigned char*>(text.data()), bytes, size);
    text.resize(count);
    return text;
}
static int captureGuestAssistance(const std::string& slot, const std::string& session, const std::string& publicText) {
    if (access("/metadata/gsi/dsu/booted", F_OK) != 0 || !std::regex_match(session, std::regex("[a-f0-9]{32}")))
        return fail("Guest capture requires its pending session");
    if (publicText.size() > 1024 || publicText.size() % 4 || !std::regex_match(publicText, std::regex("[A-Za-z0-9+/]+={0,2}")))
        return fail("Invalid host public key");
    std::string der(publicText.size() / 4 * 3, '\0');
    int count = EVP_DecodeBlock(reinterpret_cast<unsigned char*>(der.data()), reinterpret_cast<const unsigned char*>(publicText.data()), publicText.size());
    if (count <= 0) return fail("Invalid host public key encoding");
    for (size_t i = publicText.size(); i > 0 && publicText[i-1] == '='; --i) --count;
    der.resize(count);
    const unsigned char* cursor = reinterpret_cast<const unsigned char*>(der.data());
    bssl::UniquePtr<EVP_PKEY> host(d2i_PUBKEY(nullptr, &cursor, der.size()));
    if (!host || cursor != reinterpret_cast<const unsigned char*>(der.data()) + der.size() || EVP_PKEY_id(host.get()) != EVP_PKEY_RSA ||
        EVP_PKEY_bits(host.get()) != 2048 || BN_get_word(RSA_get0_e(EVP_PKEY_get0_RSA(host.get()))) != 65537)
        return fail("Unsupported host public key");
    const std::string keyDir = "/metadata/vold/metadata_encryption/dsu/" + slot;
    std::string recorded;
    if (!android::base::ReadFileToString("/metadata/gsi/dsu/active", &recorded) || recorded != slot ||
        !android::fs_mgr::ReadDefaultFstab(&android::vold::fstab_default)) return fail("Not the requested guest slot");
    const auto* entry = android::fs_mgr::GetEntryForMountPoint(&android::vold::fstab_default, "/data");
    struct statfs fs{};
    if (!entry || entry->metadata_key_dir != keyDir || statfs("/data", &fs) || uint32_t(fs.f_type) != 0xf2f52010)
        return fail("Guest cooperation requires its standard encrypted F2FS userdata");
    if ((!entry->metadata_encryption_options.empty() && entry->metadata_encryption_options != "aes-256-xts") ||
        android::base::GetIntProperty("ro.crypto.dm_default_key.options_format.version", 2) != 2)
        return fail("Unsupported metadata cipher or wrapped-key format");
    const std::string deDir = "/data/unencrypted/key";
    for (const auto& directory : {keyDir + "/key", deDir}) for (const char* name : {"version", "encrypted_key", "secdiscardable", "keymaster_key_blob"}) {
        const std::string path = directory + "/" + name;
        char* real = realpath(path.c_str(), nullptr);
        bool canonical = real && path == real;
        free(real);
        if (!canonical) return fail("Nonstandard guest key record path");
    }
    std::string sealedRecord;
    if (!android::base::ReadFileToString(keyDir + "/key/encrypted_key", &sealedRecord) || sealedRecord.size() > 16384)
        return fail("Cannot validate guest key identity");
    const std::string identity = assistHash(sealedRecord), publicHash = assistHash(der);
    android::vold::KeyBuffer metadata, de, aes(32), payload(128);
    if (!android::vold::retrieveKey(keyDir + "/key", android::vold::kEmptyAuthentication, &metadata) || metadata.size() != 64 ||
        !android::vold::retrieveKey(deDir, android::vold::kEmptyAuthentication, &de) || de.size() != 64)
        return fail("Guest cannot retrieve its existing supported maintenance keys");
    if (!android::base::ReadFileToString(keyDir + "/key/encrypted_key", &sealedRecord) || identity != assistHash(sealedRecord))
        return fail("Guest key identity changed during capture");
    memcpy(payload.data(), metadata.data(), 64); memcpy(payload.data() + 64, de.data(), 64);
    unsigned char nonce[12], tag[16];
    if (RAND_bytes(reinterpret_cast<unsigned char*>(aes.data()), aes.size()) != 1 || RAND_bytes(nonce, sizeof(nonce)) != 1)
        return fail("Cannot initialize encrypted handoff");
    const std::string context = "UltraDSU-assist-v1\n" + slot + "\n" + session + "\n" + identity + "\n" + publicHash;
    bssl::UniquePtr<EVP_CIPHER_CTX> cipher(EVP_CIPHER_CTX_new());
    std::vector<unsigned char> ciphertext(144);
    int ignored = 0, length = 0, final = 0;
    if (!cipher || EVP_EncryptInit_ex(cipher.get(), EVP_aes_256_gcm(), nullptr, reinterpret_cast<const unsigned char*>(aes.data()), nonce) != 1 ||
        EVP_EncryptUpdate(cipher.get(), nullptr, &ignored, reinterpret_cast<const unsigned char*>(context.data()), context.size()) != 1 ||
        EVP_EncryptUpdate(cipher.get(), ciphertext.data(), &length, reinterpret_cast<const unsigned char*>(payload.data()), payload.size()) != 1 ||
        EVP_EncryptFinal_ex(cipher.get(), ciphertext.data() + length, &final) != 1 || length + final != 128 ||
        EVP_CIPHER_CTX_ctrl(cipher.get(), EVP_CTRL_GCM_GET_TAG, sizeof(tag), tag) != 1) return fail("Cannot encrypt guest handoff");
    bssl::UniquePtr<EVP_PKEY_CTX> rsa(EVP_PKEY_CTX_new(host.get(), nullptr));
    std::vector<unsigned char> wrapped(256); size_t wrappedLength = wrapped.size();
    if (!rsa || EVP_PKEY_encrypt_init(rsa.get()) <= 0 || EVP_PKEY_CTX_set_rsa_padding(rsa.get(), RSA_PKCS1_OAEP_PADDING) <= 0 ||
        EVP_PKEY_CTX_set_rsa_oaep_md(rsa.get(), EVP_sha256()) <= 0 || EVP_PKEY_CTX_set_rsa_mgf1_md(rsa.get(), EVP_sha1()) <= 0 ||
        EVP_PKEY_encrypt(rsa.get(), wrapped.data(), &wrappedLength, reinterpret_cast<const unsigned char*>(aes.data()), aes.size()) <= 0 || wrappedLength != 256)
        return fail("Cannot wrap guest handoff for host");
    std::string patch = android::base::GetProperty("ro.build.version.security_patch", "");
    if (!std::regex_match(patch, std::regex("[0-9]{4}-[0-9]{2}-[0-9]{2}"))) patch = "unknown";
    printf("ULTRADSU_NATIVE:{\"version\":1,\"slot\":\"%s\",\"session\":\"%s\",\"keyIdentity\":\"%s\",\"publicKeyHash\":\"%s\",\"wrappedKey\":\"%s\",\"nonce\":\"%s\",\"ciphertext\":\"%s\",\"tag\":\"%s\",\"guestSdk\":%d,\"guestPatch\":\"%s\"}\n",
        slot.c_str(), session.c_str(), identity.c_str(), publicHash.c_str(), assistBase64(wrapped.data(), wrapped.size()).c_str(),
        assistBase64(nonce, sizeof(nonce)).c_str(), assistBase64(ciphertext.data(), 128).c_str(), assistBase64(tag, sizeof(tag)).c_str(),
        android::base::GetIntProperty("ro.build.version.sdk", 0), patch.c_str());
    return 0;
}

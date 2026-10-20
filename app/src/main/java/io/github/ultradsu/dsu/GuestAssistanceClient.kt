package io.github.ultradsu.dsu

import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyInfo
import android.security.keystore.KeyProperties
import io.github.ultradsu.diagnostics.DebugLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.util.Base64
import java.util.UUID

/** The wrapping private key stays in the host Android Keystore, never in /metadata. */
class GuestAssistanceClient(private val root: RootClient) {
    private fun store() = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
    private fun alias(session: String): String {
        check(AssistanceProtocol.validSession(session)) { "协作会话标识无效" }
        return "ultradsu.guest_assist.$session"
    }
    suspend fun prepare(slot: String) = withContext(Dispatchers.IO) {
        val old = root.bridge("assist-info", slot)
        val session = UUID.randomUUID().toString().replace("-", "")
        val name = alias(session)
        var published = false
        try {
            val generator = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_RSA, "AndroidKeyStore")
            generator.initialize(KeyGenParameterSpec.Builder(name, KeyProperties.PURPOSE_DECRYPT)
                .setKeySize(2048).setDigests(KeyProperties.DIGEST_SHA256)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_RSA_OAEP)
                .setUserAuthenticationRequired(false).build())
            val pair = generator.generateKeyPair()
            val info = KeyFactory.getInstance(pair.private.algorithm, "AndroidKeyStore").getKeySpec(pair.private, KeyInfo::class.java)
            check(info.isInsideSecureHardware) { "本机无法生成硬件保护的协作凭据，原槽未修改" }
            root.bridge("assist-prepare", slot, session, Base64.getEncoder().encodeToString(pair.public.encoded))
            published = true
            old.optString("session").takeIf { AssistanceProtocol.validSession(it) }?.let { store().deleteEntry(alias(it)) }
        } finally { if (!published) store().deleteEntry(name) }
    }
    suspend fun clear(slot: String) = withContext(Dispatchers.IO) {
        val info = root.bridge("assist-info", slot)
        root.bridge("assist-clear", slot)
        info.optString("session").takeIf { AssistanceProtocol.validSession(it) }?.let { store().deleteEntry(alias(it)) }
    }
    suspend fun bridge(action: String, slot: String, vararg args: String,
        progress: ((OperationProgress) -> Unit)? = null): JSONObject {
        if (Build.VERSION.SDK_INT < 36) return root.bridge(action, slot, *args, progress = progress)
        val info = root.bridge("assist-info", slot)
        if (info.optString("phase") != "CAPTURED") return root.bridge(action, slot, *args, progress = progress)
        val packet = try {
            withContext(Dispatchers.IO) {
                val request = info.getJSONObject("request")
                val name = alias(request.getString("session"))
                val keys = store()
                val key = keys.getKey(name, null) as? PrivateKey ?: error("Missing host handoff credential")
                check(keys.getCertificate(name)?.publicKey?.encoded?.contentEquals(
                    AssistanceProtocol.publicKey(request.getString("publicKey")).encoded) == true)
                AssistanceProtocol.decrypt(request, info.getJSONObject("envelope"), key)
            }
        } catch (failure: Exception) {
            DebugLog.event("guest-assist", "host credential unavailable ${failure.javaClass.simpleName}")
            // Exact raw-backup recovery does not require decrypting the filesystem.
            if (action == "grow-rollback") return root.bridge(action, slot, *args, progress = progress)
            if (action == "grow-info") return root.bridge(action, slot, *args, progress = progress).put("assistanceError",
                "主系统协作凭据无法解封装，请重新准备客体协作；已有完整恢复备份仍可回退。")
            error("主系统协作凭据无法解封装，请重新准备客体协作")
        }
        try {
            return try { root.bridge(action, slot, *args, progress = progress, secretInput = packet) }
            catch (failure: Exception) {
                if (action != "grow-info" || !failure.message.orEmpty().contains("协作信息已过期")) throw failure
                root.bridge(action, slot, *args, progress = progress).put("assistanceError",
                    "协作响应与当前槽位不再匹配，请清理协作准备后重新采集；已有完整恢复备份仍可回退。")
            }
        }
        finally { packet.fill(0) }
    }
}

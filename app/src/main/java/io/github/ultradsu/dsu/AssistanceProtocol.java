package io.github.ultradsu.dsu;

import org.json.JSONObject;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.*;
import java.util.*;
import javax.crypto.*;
import javax.crypto.spec.*;

/** Authenticated, slot/session-bound transit envelope. Contains no persistent plaintext key. */
public final class AssistanceProtocol {
    public static boolean validSession(String value) { return value.matches("[a-f0-9]{32}"); }
    public static String hex(byte[] bytes) {
        StringBuilder result = new StringBuilder();
        for (byte value : bytes) result.append(String.format(Locale.ROOT, "%02x", value & 255));
        return result.toString();
    }
    public static String digest(byte[] bytes) throws Exception { return hex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
    public static RSAPublicKey publicKey(String encoded) throws Exception {
        if (encoded.length() > 1024) throw new SecurityException("协作公钥过大");
        RSAPublicKey key = (RSAPublicKey)KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(encoded)));
        if (key.getModulus().bitLength() != 2048 || !key.getPublicExponent().equals(java.math.BigInteger.valueOf(65537)))
            throw new SecurityException("不支持的协作公钥");
        return key;
    }
    public static byte[] context(String slot, String session, String identity, String publicHash) {
        if (!slot.matches("[a-zA-Z0-9][a-zA-Z0-9_-]{0,31}") || !validSession(session) ||
                !identity.matches("[a-f0-9]{64}") || !publicHash.matches("[a-f0-9]{64}")) throw new SecurityException("协作身份无效");
        return ("UltraDSU-assist-v1\n" + slot + "\n" + session + "\n" + identity + "\n" + publicHash).getBytes(StandardCharsets.US_ASCII);
    }
    private static byte[] decode(JSONObject envelope, String name, int length) throws Exception {
        String encoded = envelope.getString(name);
        if (encoded.length() > 2048) throw new SecurityException("协作数据过大");
        byte[] result = Base64.getDecoder().decode(encoded);
        if (result.length != length) throw new SecurityException("协作数据长度无效");
        return result;
    }
    public static byte[] decrypt(JSONObject request, JSONObject envelope, PrivateKey key) throws Exception {
        String slot = request.getString("slot"), session = request.getString("session");
        String publicHash = digest(publicKey(request.getString("publicKey")).getEncoded());
        String identity = envelope.getString("keyIdentity");
        if (request.getInt("version") != 1 || envelope.getInt("version") != 1 ||
                !slot.equals(envelope.getString("slot")) || !session.equals(envelope.getString("session")) ||
                !publicHash.equals(envelope.getString("publicKeyHash"))) throw new SecurityException("协作响应不属于本槽或本次会话");
        byte[] aad = context(slot, session, identity, publicHash);
        byte[] aes = null, material = null;
        try {
            Cipher rsa = Cipher.getInstance("RSA/ECB/OAEPPadding");
            rsa.init(Cipher.DECRYPT_MODE, key, new OAEPParameterSpec("SHA-256", "MGF1", MGF1ParameterSpec.SHA1, PSource.PSpecified.DEFAULT));
            aes = rsa.doFinal(decode(envelope, "wrappedKey", 256));
            if (aes.length != 32) throw new SecurityException("协作封装无效");
            Cipher gcm = Cipher.getInstance("AES/GCM/NoPadding");
            gcm.init(Cipher.DECRYPT_MODE, new SecretKeySpec(aes, "AES"), new GCMParameterSpec(128, decode(envelope, "nonce", 12)));
            gcm.updateAAD(aad);
            byte[] sealed = new byte[144];
            System.arraycopy(decode(envelope, "ciphertext", 128), 0, sealed, 0, 128);
            System.arraycopy(decode(envelope, "tag", 16), 0, sealed, 128, 16);
            material = gcm.doFinal(sealed);
            if (material.length != 128) throw new SecurityException("协作内容无效");
            byte[] packet = new byte[196];
            System.arraycopy("UAK1".getBytes(StandardCharsets.US_ASCII), 0, packet, 0, 4);
            System.arraycopy(session.getBytes(StandardCharsets.US_ASCII), 0, packet, 4, 32);
            for (int i = 0; i < 32; ++i) packet[36+i] = (byte)Integer.parseInt(identity.substring(i*2, i*2+2), 16);
            System.arraycopy(material, 0, packet, 68, 128);
            return packet;
        } finally {
            if (aes != null) Arrays.fill(aes, (byte)0);
            if (material != null) Arrays.fill(material, (byte)0);
        }
    }
    static void validatePacket(byte[] packet, JSONObject request, String identity) throws Exception {
        if (packet.length != 196 || !Arrays.equals(Arrays.copyOfRange(packet, 0, 4), "UAK1".getBytes(StandardCharsets.US_ASCII)) ||
                !new String(packet, 4, 32, StandardCharsets.US_ASCII).equals(request.getString("session")) ||
                !hex(Arrays.copyOfRange(packet, 36, 68)).equals(identity)) throw new SecurityException("协作信息已过期或槽位密钥标识已变化，请重新采集");
    }
}

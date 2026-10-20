package io.github.ultradsu.dsu;

import org.json.JSONObject;
import org.junit.*;
import java.security.*;
import java.security.spec.MGF1ParameterSpec;
import java.util.*;
import javax.crypto.*;
import javax.crypto.spec.*;
import static org.junit.Assert.*;

public class AssistanceProtocolTest {
    private static KeyPair host, other;
    private JSONObject request, response;
    private byte[] material;
    private static final String SESSION = "0123456789abcdef0123456789abcdef", IDENTITY = "ab".repeat(32);
    @BeforeClass public static void keys() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA"); generator.initialize(2048);
        host = generator.generateKeyPair(); other = generator.generateKeyPair();
    }
    @Before public void envelope() throws Exception {
        request = new JSONObject().put("version", 1).put("slot", "fixture").put("session", SESSION)
            .put("publicKey", Base64.getEncoder().encodeToString(host.getPublic().getEncoded()));
        String publicHash = AssistanceProtocol.digest(host.getPublic().getEncoded());
        material = new byte[128]; byte[] aes = new byte[32], nonce = new byte[12];
        SecureRandom random = new SecureRandom(); random.nextBytes(material); random.nextBytes(aes); random.nextBytes(nonce);
        Cipher gcm = Cipher.getInstance("AES/GCM/NoPadding");
        gcm.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(aes, "AES"), new GCMParameterSpec(128, nonce));
        gcm.updateAAD(AssistanceProtocol.context("fixture", SESSION, IDENTITY, publicHash));
        byte[] encrypted = gcm.doFinal(material);
        Cipher rsa = Cipher.getInstance("RSA/ECB/OAEPPadding");
        rsa.init(Cipher.ENCRYPT_MODE, host.getPublic(), new OAEPParameterSpec("SHA-256", "MGF1", MGF1ParameterSpec.SHA1, PSource.PSpecified.DEFAULT));
        response = new JSONObject().put("version", 1).put("slot", "fixture").put("session", SESSION)
            .put("keyIdentity", IDENTITY).put("publicKeyHash", publicHash)
            .put("wrappedKey", Base64.getEncoder().encodeToString(rsa.doFinal(aes)))
            .put("nonce", Base64.getEncoder().encodeToString(nonce))
            .put("ciphertext", Base64.getEncoder().encodeToString(Arrays.copyOfRange(encrypted, 0, 128)))
            .put("tag", Base64.getEncoder().encodeToString(Arrays.copyOfRange(encrypted, 128, 144)));
        Arrays.fill(aes, (byte)0);
    }
    @Test public void authenticatedEnvelopeProducesOnlyBoundedPipePacket() throws Exception {
        byte[] packet = AssistanceProtocol.decrypt(request, response, host.getPrivate());
        assertEquals(196, packet.length);
        AssistanceProtocol.validatePacket(packet, request, IDENTITY);
        assertArrayEquals(material, Arrays.copyOfRange(packet, 68, 196));
        assertFalse(response.toString().contains(Base64.getEncoder().encodeToString(material)));
    }
    @Test public void damagedCiphertextAndTagCannotBeAccepted() throws Exception {
        for (String field : new String[]{"ciphertext", "tag"}) {
            JSONObject corrupt = new JSONObject(response.toString());
            byte[] bytes = Base64.getDecoder().decode(corrupt.getString(field)); bytes[0] ^= 1;
            corrupt.put(field, Base64.getEncoder().encodeToString(bytes));
            assertThrows(GeneralSecurityException.class, () -> AssistanceProtocol.decrypt(request, corrupt, host.getPrivate()));
        }
    }
    @Test public void swappedSlotOrSessionCannotReuseCapturedKeys() throws Exception {
        for (String field : new String[]{"slot", "session"}) {
            JSONObject swapped = new JSONObject(response.toString()).put(field, field.equals("slot") ? "other" : "f".repeat(32));
            assertThrows(SecurityException.class, () -> AssistanceProtocol.decrypt(request, swapped, host.getPrivate()));
        }
    }
    @Test public void changedIdentityIsAuthenticatedEvenWithValidHostAndSession() throws Exception {
        response.put("keyIdentity", "cd".repeat(32));
        assertThrows(GeneralSecurityException.class, () -> AssistanceProtocol.decrypt(request, response, host.getPrivate()));
    }
    @Test public void anotherHostPrivateKeyCannotOpenTheEnvelope() throws Exception {
        assertThrows(GeneralSecurityException.class, () -> AssistanceProtocol.decrypt(request, response, other.getPrivate()));
    }
    @Test public void oldPacketCannotTargetRecreatedSlotOrNewSession() throws Exception {
        byte[] packet = AssistanceProtocol.decrypt(request, response, host.getPrivate());
        assertThrows(SecurityException.class, () -> AssistanceProtocol.validatePacket(packet, request, "cd".repeat(32)));
        request.put("session", "e".repeat(32));
        assertThrows(SecurityException.class, () -> AssistanceProtocol.validatePacket(packet, request, IDENTITY));
    }
    @Test public void publicKeyReplacementIsRejected() throws Exception {
        request.put("publicKey", Base64.getEncoder().encodeToString(other.getPublic().getEncoded()));
        assertThrows(SecurityException.class, () -> AssistanceProtocol.decrypt(request, response, host.getPrivate()));
    }
}

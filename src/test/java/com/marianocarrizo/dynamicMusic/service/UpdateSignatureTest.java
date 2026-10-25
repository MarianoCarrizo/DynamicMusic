package com.marianocarrizo.dynamicMusic.service;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UpdateSignatureTest {

    private static final byte[] DATA = ("a".repeat(64) + "  DynamicMusic-Setup-2.0.0.exe\n").getBytes(StandardCharsets.UTF_8);

    private static KeyPair newKeyPair() throws Exception {
        return KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
    }

    static byte[] sign(byte[] data, KeyPair pair) throws Exception {
        Signature s = Signature.getInstance("Ed25519");
        s.initSign(pair.getPrivate());
        s.update(data);
        return s.sign();
    }

    @Test
    void embeddedPublicKeyParses() throws Exception {
        assertNotNull(UpdateSignature.parsePublicKey(UpdateSignature.PUBLIC_KEY_BASE64));
        assertEquals("EdDSA",UpdateSignature.embeddedKey().getAlgorithm());
    }

    @Test
    void acceptsAGoodSignature() throws Exception {
        KeyPair pair = newKeyPair();
        assertTrue(UpdateSignature.verify(DATA, sign(DATA, pair), pair.getPublic()));
    }

    @Test
    void rejectsTamperedData() throws Exception {
        KeyPair pair = newKeyPair();
        byte[] sig = sign(DATA, pair);
        byte[] tampered = DATA.clone();
        tampered[0] = 'b';
        assertFalse(UpdateSignature.verify(tampered, sig, pair.getPublic()));
    }

    @Test
    void rejectsAnotherKeysSignature() throws Exception {
        byte[] sig = sign(DATA, newKeyPair());
        assertFalse(UpdateSignature.verify(DATA, sig, newKeyPair().getPublic()));
        assertFalse(UpdateSignature.verify(DATA, sig));
    }

    @Test
    void garbageSignaturesFailWithoutThrowing() throws Exception {
        KeyPair pair = newKeyPair();
        assertDoesNotThrow(() -> {
            assertFalse(UpdateSignature.verify(DATA, new byte[0], pair.getPublic()));
            assertFalse(UpdateSignature.verify(DATA, new byte[10], pair.getPublic()));
            assertFalse(UpdateSignature.verify(DATA, new byte[64], pair.getPublic()));
            assertFalse(UpdateSignature.verify(DATA, new byte[200], pair.getPublic()));
            assertFalse(UpdateSignature.verify(DATA, null, pair.getPublic()));
            assertFalse(UpdateSignature.verify(null, new byte[64], pair.getPublic()));
        });
    }
}

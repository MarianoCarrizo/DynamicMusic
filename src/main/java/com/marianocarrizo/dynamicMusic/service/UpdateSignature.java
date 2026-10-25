package com.marianocarrizo.dynamicMusic.service;

import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;

public final class UpdateSignature {

    static final String PUBLIC_KEY_BASE64 = "MCowBQYDK2VwAyEAUUfvzJE3AYxCcHvSW7dfqbrj0XEQp2J5h8aqR3TbLiE=";
    static final int SIGNATURE_LENGTH = 64;
    private static final String ALGORITHM = "Ed25519";

    private UpdateSignature() {}

    static PublicKey parsePublicKey(String base64) throws GeneralSecurityException {
        byte[] der = Base64.getDecoder().decode(base64);
        return KeyFactory.getInstance(ALGORITHM).generatePublic(new X509EncodedKeySpec(der));
    }

    static PublicKey embeddedKey() {
        try {
            return parsePublicKey(PUBLIC_KEY_BASE64);
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            throw new IllegalStateException("embedded update key is invalid", e);
        }
    }

    static boolean verify(byte[] data, byte[] signature, PublicKey key) {
        if (data == null || signature == null || key == null || signature.length != SIGNATURE_LENGTH) return false;
        try {
            Signature s = Signature.getInstance(ALGORITHM);
            s.initVerify(key);
            s.update(data);
            return s.verify(signature);
        } catch (GeneralSecurityException | RuntimeException e) {
            return false;
        }
    }

    static boolean verify(byte[] data, byte[] signature) {
        return verify(data, signature, embeddedKey());
    }
}

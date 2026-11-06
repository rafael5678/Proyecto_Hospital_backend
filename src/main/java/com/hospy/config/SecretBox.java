package com.hospy.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;

/** Guarda la clave de ingreso cifrada para que solo el administrador pueda leerla. El login sigue usando BCrypt. */
@Component
public class SecretBox {

    private final SecretKey key;
    private final SecureRandom random = new SecureRandom();

    public SecretBox(@Value("${jwt.secret}") String secret) {
        try {
            byte[] raw = MessageDigest.getInstance("SHA-256").digest(secret.getBytes(StandardCharsets.UTF_8));
            this.key = new SecretKeySpec(raw, "AES");
        } catch (Exception ex) {
            throw new IllegalStateException("No se pudo preparar el cifrado de claves", ex);
        }
    }

    public String cifrar(String plano) {
        if (plano == null || plano.isBlank()) return null;
        try {
            byte[] iv = new byte[12];
            random.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(128, iv));
            byte[] enc = cipher.doFinal(plano.getBytes(StandardCharsets.UTF_8));
            byte[] out = new byte[iv.length + enc.length];
            System.arraycopy(iv, 0, out, 0, iv.length);
            System.arraycopy(enc, 0, out, iv.length, enc.length);
            return Base64.getEncoder().encodeToString(out);
        } catch (Exception ex) {
            throw new IllegalStateException("No se pudo cifrar la clave", ex);
        }
    }

    public String descifrar(String data) {
        if (data == null || data.isBlank()) return null;
        try {
            byte[] all = Base64.getDecoder().decode(data);
            if (all.length < 13) return null;
            byte[] iv = Arrays.copyOfRange(all, 0, 12);
            byte[] enc = Arrays.copyOfRange(all, 12, all.length);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, iv));
            return new String(cipher.doFinal(enc), StandardCharsets.UTF_8);
        } catch (Exception ex) {
            return null;
        }
    }
}

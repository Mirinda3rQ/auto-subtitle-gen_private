package com.serhat.autosub.core;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/** Stores API credentials encrypted with a non-exportable Android Keystore key. */
public final class SecretStore {
    private static final String PREFS = "encrypted_secrets";
    private static final String KEY_ALIAS = "autosub_gemini_api_key";
    private static final String KEY_VALUE = "gemini_api_key";
    private final Context context;

    public SecretStore(Context context) { this.context = context.getApplicationContext(); }

    public synchronized void saveGeminiApiKey(String value) throws Exception {
        String key = value == null ? "" : value.trim();
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        if (key.isEmpty()) { prefs.edit().remove(KEY_VALUE).apply(); return; }
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey());
        byte[] encrypted = cipher.doFinal(key.getBytes(StandardCharsets.UTF_8));
        byte[] iv = cipher.getIV();
        byte[] combined = new byte[iv.length + encrypted.length];
        System.arraycopy(iv, 0, combined, 0, iv.length);
        System.arraycopy(encrypted, 0, combined, iv.length, encrypted.length);
        prefs.edit().putString(KEY_VALUE, Base64.encodeToString(combined, Base64.NO_WRAP)).apply();
    }

    public synchronized String getGeminiApiKey() {
        String stored = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_VALUE, "");
        if (stored == null || stored.isEmpty()) return "";
        try {
            byte[] combined = Base64.decode(stored, Base64.NO_WRAP);
            int ivLength = 12;
            if (combined.length <= ivLength) return "";
            byte[] iv = new byte[ivLength], encrypted = new byte[combined.length - ivLength];
            System.arraycopy(combined, 0, iv, 0, ivLength);
            System.arraycopy(combined, ivLength, encrypted, 0, encrypted.length);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), new GCMParameterSpec(128, iv));
            return new String(cipher.doFinal(encrypted), StandardCharsets.UTF_8);
        } catch (Exception ignored) { return ""; }
    }

    private SecretKey getOrCreateKey() throws Exception {
        KeyStore store = KeyStore.getInstance("AndroidKeyStore");
        store.load(null);
        java.security.Key existing = store.getKey(KEY_ALIAS, null);
        if (existing instanceof SecretKey) return (SecretKey) existing;
        KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        generator.init(new KeyGenParameterSpec.Builder(KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build());
        return generator.generateKey();
    }
}

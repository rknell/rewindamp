package dev.rewindamp;

import android.content.Context;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import org.json.JSONObject;
import java.security.KeyStore;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/** Only an encrypted session is persisted; passwords are never stored. */
final class SessionStore {
    static synchronized SecretKey key() throws Exception {
        KeyStore store = KeyStore.getInstance("AndroidKeyStore"); store.load(null);
        if (!store.containsAlias("rewindamp-session")) {
            KeyGenerator generator = KeyGenerator.getInstance("AES", "AndroidKeyStore");
            generator.init(new KeyGenParameterSpec.Builder("rewindamp-session", KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build());
            generator.generateKey();
        }
        return (SecretKey) store.getKey("rewindamp-session", null);
    }
    static void save(Context context, JellyfinApi api) throws Exception {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding"); cipher.init(Cipher.ENCRYPT_MODE, key());
        String plain = new JSONObject().put("server", api.server).put("token", api.token).put("user", api.userId).put("device", api.deviceId).toString();
        context.getSharedPreferences("session", 0).edit()
            .putString("iv", Base64.encodeToString(cipher.getIV(), Base64.NO_WRAP))
            .putString("data", Base64.encodeToString(cipher.doFinal(plain.getBytes(java.nio.charset.StandardCharsets.UTF_8)), Base64.NO_WRAP)).commit();
    }
    static JellyfinApi load(Context context) {
        try {
            var prefs = context.getSharedPreferences("session", 0);
            if (!prefs.contains("data")) return null;
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(128, Base64.decode(prefs.getString("iv", ""), Base64.NO_WRAP)));
            JSONObject json = new JSONObject(new String(cipher.doFinal(Base64.decode(prefs.getString("data", ""), Base64.NO_WRAP)), java.nio.charset.StandardCharsets.UTF_8));
            return new JellyfinApi(json.getString("server"), json.getString("token"), json.getString("user"), json.getString("device"));
        } catch (Exception ignored) { clear(context); return null; }
    }
    static void clear(Context context) { context.getSharedPreferences("session", 0).edit().clear().commit(); }
}

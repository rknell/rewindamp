package dev.rewindamp;

import android.content.Context;
import android.util.Base64;
import org.json.JSONObject;
import java.nio.charset.StandardCharsets;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;

final class PodcastSession {
    static void save(Context context,PodcastApi api)throws Exception{
        Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.ENCRYPT_MODE,SessionStore.key());
        String plain=new JSONObject().put("server",api.server).put("token",api.token).toString();
        if(!context.getSharedPreferences("podcast-session",0).edit().putString("iv",Base64.encodeToString(cipher.getIV(),Base64.NO_WRAP)).putString("data",Base64.encodeToString(cipher.doFinal(plain.getBytes(StandardCharsets.UTF_8)),Base64.NO_WRAP)).commit())throw new java.io.IOException("Could not save podcast connection");
    }
    static PodcastApi load(Context context){try{
        var prefs=context.getSharedPreferences("podcast-session",0);if(!prefs.contains("data"))return null;
        Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.DECRYPT_MODE,SessionStore.key(),new GCMParameterSpec(128,Base64.decode(prefs.getString("iv",""),Base64.NO_WRAP)));
        JSONObject value=new JSONObject(new String(cipher.doFinal(Base64.decode(prefs.getString("data",""),Base64.NO_WRAP)),StandardCharsets.UTF_8));return new PodcastApi(value.getString("server"),value.getString("token"));
    }catch(Exception ignored){return null;}}
    static void clear(Context context){context.getSharedPreferences("podcast-session",0).edit().clear().commit();}
}

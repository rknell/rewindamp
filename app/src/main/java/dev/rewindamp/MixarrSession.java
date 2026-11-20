package dev.rewindamp;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Base64;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import org.json.JSONArray;
import org.json.JSONObject;

final class MixarrSession {
    private static SharedPreferences prefs(Context context,String scope){return context.getSharedPreferences(scope,0);}
    static void save(Context context,String scope,MixarrApi api)throws Exception {
        Cipher c=Cipher.getInstance("AES/GCM/NoPadding");c.init(Cipher.ENCRYPT_MODE,SessionStore.key());
        JSONObject value=new JSONObject().put("server",api.server).put("cookie",api.cookie()).put("csrf",api.csrf()).put("user",api.user);
        if(!prefs(context,scope).edit().putString("iv",Base64.encodeToString(c.getIV(),Base64.NO_WRAP)).putString("session",Base64.encodeToString(c.doFinal(value.toString().getBytes(StandardCharsets.UTF_8)),Base64.NO_WRAP)).commit())throw new IOException("Could not save connection");
    }
    static MixarrApi load(Context context,String scope){try{
        SharedPreferences p=prefs(context,scope);if(!p.contains("session"))return null;
        Cipher c=Cipher.getInstance("AES/GCM/NoPadding");c.init(Cipher.DECRYPT_MODE,SessionStore.key(),new GCMParameterSpec(128,Base64.decode(p.getString("iv",""),Base64.NO_WRAP)));
        JSONObject value=new JSONObject(new String(c.doFinal(Base64.decode(p.getString("session",""),Base64.NO_WRAP)),StandardCharsets.UTF_8));
        return new MixarrApi(value.getString("server"),value.getString("cookie"),value.getString("csrf"),value.getString("user"));
    }catch(Exception ignored){return null;}}
    static void clear(Context context,String scope){prefs(context,scope).edit().remove("iv").remove("session").commit();}
    static synchronized JSONArray history(Context context,String scope,MixarrApi api){try{return new JSONArray(prefs(context,scope).getString(api.historyKey(),"[]"));}catch(Exception ignored){return new JSONArray();}}
    static synchronized void remember(Context context,String scope,MixarrApi api,JSONObject row)throws Exception {
        JSONArray next=MixarrApi.record(history(context,scope,api),row);
        if(!prefs(context,scope).edit().putString(api.historyKey(),next.toString()).commit())throw new IOException("Could not save request status");
    }
}

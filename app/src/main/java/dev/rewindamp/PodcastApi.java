package dev.rewindamp;

import android.content.Context;
import android.util.Base64;
import org.json.JSONObject;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;

/** Independent server credentials: never put tokens into media URLs or music sessions. */
final class PodcastApi {
    final String server,token;
    PodcastApi(String server,String token){this.server=JellyfinApi.normalizeServer(server);this.token=token.trim();}
    String authorization(){return "Bearer "+token;}
    JSONObject request(String path,JSONObject body)throws Exception{
        HttpURLConnection c=(HttpURLConnection)new URL(server+path).openConnection();
        c.setConnectTimeout(15000);c.setReadTimeout(20000);c.setInstanceFollowRedirects(false);
        c.setRequestProperty("Authorization",authorization());c.setRequestProperty("Accept","application/json");
        try{
            if(body!=null){c.setRequestMethod("POST");c.setDoOutput(true);c.setRequestProperty("Content-Type","application/json");try(var out=c.getOutputStream()){out.write(body.toString().getBytes(StandardCharsets.UTF_8));}}
            int code=c.getResponseCode();if(code<200||code>=300)throw new java.io.IOException("Podcast server returned HTTP "+code);
            try(var in=c.getInputStream();var out=new java.io.ByteArrayOutputStream()){byte[] buffer=new byte[8192];int n;while((n=in.read(buffer))!=-1){out.write(buffer,0,n);if(out.size()>8*1024*1024)throw new java.io.IOException("Podcast response too large");}return new JSONObject(out.toString(StandardCharsets.UTF_8.name()));}
        }finally{c.disconnect();}
    }
    String audio(String id){if(!id.matches("[a-f0-9]{32}"))throw new IllegalArgumentException("Invalid episode ID");return server+"/api/episodes/"+id+"/audio";}
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

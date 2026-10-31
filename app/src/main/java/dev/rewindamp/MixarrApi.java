package dev.rewindamp;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Base64;
import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONTokener;
import java.io.IOException;
import java.net.HttpCookie;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;

/** Mixarr's own cookie + CSRF session. Jellyfin credentials never leave Jellyfin. */
final class MixarrApi {
    final String server, user;
    private String cookie, csrf;
    MixarrApi(String address,String cookie,String csrf,String user) {
        server=JellyfinApi.normalizeServer(address);
        String host=URI.create(server).getHost();
        if(!server.startsWith("https://")&&!host.equals("127.0.0.1")&&!host.equals("localhost")&&!host.equals("10.0.2.2"))
            throw new IllegalArgumentException("Use HTTPS for your Mixarr server.");
        this.cookie=cookie;this.csrf=csrf;this.user=user;
    }
    static MixarrApi login(String address,String username,String password)throws Exception {
        MixarrApi api=new MixarrApi(address,"","","");
        JSONObject response=(JSONObject)api.request("/api/v1/login",new JSONObject().put("username",username).put("password",password));
        if(api.cookie.isEmpty())throw new IOException("Mixarr did not return a session.");
        return new MixarrApi(api.server,api.cookie,response.getString("csrf_token"),response.getJSONObject("user").getString("id"));
    }
    static final class Failure extends IOException {
        final int code;
        Failure(int code){this(code,"");}
        Failure(int code,String detail){super(safeError(code,detail));this.code=code;}
        static String safeError(int code,String detail){
            if(code==409&&java.util.Set.of(
                "This track is on the household exclusion list.",
                "This track needs an exact media link in Library after a recording correction.",
                "Acquisition is disabled. An administrator can enable it in System settings.",
                "No acquisition adapter is enabled. Ask an administrator to enable a source.",
                "A media match for this track was rejected. Review its media in Library.",
                "Media acquisition and YouTube acquisition must both be enabled in System."
            ).contains(detail))return detail;
            return switch(code){
            case 401 -> "Mixarr sign-in expired or credentials are incorrect. Reconnect to Mixarr.";
            case 403 -> "Mixarr rejected this session. Reconnect and try again.";
            case 404 -> "Request status is unavailable. The Mixarr server may need updating.";
            case 409 -> "Mixarr could not add this recording. Check acquisition settings, exclusions or media review in Mixarr, then retry.";
            case 422 -> "Check the link, selected names and chapter times. The preview may have expired; preview again. Playlists must contain at most 100 tracks.";
            case 429 -> "Mixarr or YouTube is waiting between requests. Wait and retry; check Mixarr for cooldown details.";
            default -> "Mixarr is unavailable. Check your connection and retry.";
        };}
    }
    Object request(String path,JSONObject body)throws Exception {
        HttpURLConnection c=(HttpURLConnection)new URL(server+path).openConnection();
        c.setConnectTimeout(15000);c.setReadTimeout(path.startsWith("/api/v1/youtube-imports")&&body!=null?210000:25000);c.setInstanceFollowRedirects(false);
        c.setRequestProperty("Accept","application/json");
        if(!cookie.isEmpty())c.setRequestProperty("Cookie","mixarr_session="+cookie);
        if(!csrf.isEmpty())c.setRequestProperty("X-CSRF-Token",csrf);
        try {
            if(body!=null){c.setRequestMethod("POST");c.setDoOutput(true);c.setRequestProperty("Content-Type","application/json");try(var out=c.getOutputStream()){out.write(body.toString().getBytes(StandardCharsets.UTF_8));}}
            int code=c.getResponseCode();if(code<200||code>=300){
                String detail="";
                if(code==409)try(var in=c.getErrorStream()){
                    if(in!=null){var out=new java.io.ByteArrayOutputStream();byte[] buffer=new byte[1024];int n;while((n=in.read(buffer))!=-1&&out.size()<8192)out.write(buffer,0,n);detail=new JSONObject(out.toString(StandardCharsets.UTF_8.name())).optString("detail");}
                }catch(Exception ignored){}
                throw new Failure(code,detail);
            }
            if(path.equals("/api/v1/login"))for(var entry:c.getHeaderFields().entrySet())if("Set-Cookie".equalsIgnoreCase(entry.getKey()))for(String header:entry.getValue())for(HttpCookie value:HttpCookie.parse(header))if(value.getName().equals("mixarr_session"))cookie=value.getValue();
            if(code==204)return new JSONObject();
            try(var in=c.getInputStream();var out=new java.io.ByteArrayOutputStream()){
                byte[] bytes=new byte[8192];int n;
                while((n=in.read(bytes))!=-1){out.write(bytes,0,n);if(out.size()>1024*1024)throw new IOException("Response too large");}
                return new JSONTokener(out.toString(StandardCharsets.UTF_8.name())).nextValue();
            }
        }finally{c.disconnect();}
    }
    static String message(Exception e){return e instanceof Failure?e.getMessage():"Could not reach Mixarr. Check your connection and retry.";}
    static String scope(JellyfinApi jellyfin)throws Exception {
        byte[] digest=MessageDigest.getInstance("SHA-256").digest((jellyfin.server+"\n"+jellyfin.userId).getBytes(StandardCharsets.UTF_8));
        StringBuilder result=new StringBuilder();for(byte b:digest)result.append(String.format(java.util.Locale.ROOT,"%02x",b&255));return "mixarr-"+result;
    }
    private static SharedPreferences prefs(Context context,String scope){return context.getSharedPreferences(scope,0);}
    static void save(Context context,String scope,MixarrApi api)throws Exception {
        Cipher c=Cipher.getInstance("AES/GCM/NoPadding");c.init(Cipher.ENCRYPT_MODE,SessionStore.key());
        JSONObject value=new JSONObject().put("server",api.server).put("cookie",api.cookie).put("csrf",api.csrf).put("user",api.user);
        if(!prefs(context,scope).edit().putString("iv",Base64.encodeToString(c.getIV(),Base64.NO_WRAP)).putString("session",Base64.encodeToString(c.doFinal(value.toString().getBytes(StandardCharsets.UTF_8)),Base64.NO_WRAP)).commit())throw new IOException("Could not save connection");
    }
    static MixarrApi load(Context context,String scope){try{
        SharedPreferences p=prefs(context,scope);if(!p.contains("session"))return null;
        Cipher c=Cipher.getInstance("AES/GCM/NoPadding");c.init(Cipher.DECRYPT_MODE,SessionStore.key(),new GCMParameterSpec(128,Base64.decode(p.getString("iv",""),Base64.NO_WRAP)));
        JSONObject value=new JSONObject(new String(c.doFinal(Base64.decode(p.getString("session",""),Base64.NO_WRAP)),StandardCharsets.UTF_8));
        return new MixarrApi(value.getString("server"),value.getString("cookie"),value.getString("csrf"),value.getString("user"));
    }catch(Exception ignored){return null;}}
    static void clear(Context context,String scope){prefs(context,scope).edit().remove("iv").remove("session").commit();}
    private String historyKey(){return "requests:"+server+":"+user;}
    synchronized JSONArray history(Context context,String scope){try{return new JSONArray(prefs(context,scope).getString(historyKey(),"[]"));}catch(Exception ignored){return new JSONArray();}}
    private static String historyIdentity(JSONObject row){return row.has("import_id")?"import:"+row.optString("import_id"):"track:"+row.optLong("track_id");}
    static synchronized void remember(Context context,String scope,MixarrApi api,JSONObject row)throws Exception {
        JSONArray before=api.history(context,scope),next=new JSONArray();next.put(row);
        for(int i=0;i<before.length()&&next.length()<30;i++){JSONObject old=before.getJSONObject(i);if(!historyIdentity(old).equals(historyIdentity(row)))next.put(old);}
        if(!prefs(context,scope).edit().putString(api.historyKey(),next.toString()).commit())throw new IOException("Could not save request status");
    }
}

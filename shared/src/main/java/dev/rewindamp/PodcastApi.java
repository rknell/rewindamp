package dev.rewindamp;

import org.json.JSONObject;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/** Independent server credentials: never put tokens into media URLs or music sessions. */
final class PodcastApi {
    final String server,token;
    PodcastApi(String server,String token){this.server=JellyfinApi.normalizeServer(server);this.token=token.trim();}
    static PodcastApi login(String server,String password)throws Exception{
        PodcastApi anonymous=new PodcastApi(server,"");
        JSONObject result=anonymous.request("/api/auth/login",new JSONObject().put("password",password));
        return new PodcastApi(anonymous.server,result.getString("access_token"));
    }
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
}

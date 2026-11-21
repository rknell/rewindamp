package dev.rewindamp;

import org.json.JSONObject;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/** Independent server credentials: never put tokens into media URLs or music sessions. */
final class PodcastApi {
    static final class HttpError extends java.io.IOException {
        final int status;
        HttpError(int status){super("Podcast server returned HTTP "+status);this.status=status;}
    }

    final String server,token,account;
    PodcastApi(String server,String token){this(server,token,"1");}
    PodcastApi(String server,String token,String account){
        if(!account.matches("[1-9][0-9]{0,18}"))throw new IllegalArgumentException("Invalid podcast account");
        this.server=JellyfinApi.normalizeServer(server);this.token=token.trim();this.account=account;
    }
    static PodcastApi login(String server,String password)throws Exception{
        return login(server,"owner",password);
    }
    static PodcastApi login(String server,String username,String password)throws Exception{
        String user=username.trim();
        if(user.isEmpty())user="owner";
        PodcastApi anonymous=new PodcastApi(server,"");
        JSONObject result=anonymous.request("/api/auth/login",new JSONObject().put("username",user).put("password",password));
        if(!user.equalsIgnoreCase("owner")&&!user.equalsIgnoreCase(result.optString("username")))
            throw new java.io.IOException("The podcast server did not sign in this user");
        return new PodcastApi(anonymous.server,result.getString("access_token"),result.optString("account_id","1"));
    }
    String storageAccount(){return account.equals("1")?"RewindampPodcastOwner":"RewindampPodcastAccount:"+account;}
    String authorization(){return "Bearer "+token;}
    JSONObject request(String path,JSONObject body)throws Exception{
        HttpURLConnection c=(HttpURLConnection)new URL(server+path).openConnection();
        c.setConnectTimeout(15000);c.setReadTimeout(20000);c.setInstanceFollowRedirects(false);
        c.setRequestProperty("Authorization",authorization());c.setRequestProperty("Accept","application/json");
        try{
            if(body!=null){c.setRequestMethod("POST");c.setDoOutput(true);c.setRequestProperty("Content-Type","application/json");try(var out=c.getOutputStream()){out.write(body.toString().getBytes(StandardCharsets.UTF_8));}}
            int code=c.getResponseCode();if(code<200||code>=300)throw new HttpError(code);
            try(var in=c.getInputStream();var out=new java.io.ByteArrayOutputStream()){byte[] buffer=new byte[8192];int n;while((n=in.read(buffer))!=-1){out.write(buffer,0,n);if(out.size()>8*1024*1024)throw new java.io.IOException("Podcast response too large");}return new JSONObject(out.toString(StandardCharsets.UTF_8.name()));}
        }finally{c.disconnect();}
    }
    String audio(String id){if(!id.matches("[a-f0-9]{32}"))throw new IllegalArgumentException("Invalid episode ID");return server+"/api/episodes/"+id+"/audio";}
}

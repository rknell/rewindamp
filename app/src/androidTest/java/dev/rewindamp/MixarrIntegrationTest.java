package dev.rewindamp;

import android.content.Context;
import android.content.Intent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.TextView;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.Assert.*;

/** In-process HTTP fixtures only. No Jellyfin/Mixarr instance or real media is contacted. */
@RunWith(AndroidJUnit4.class)
public class MixarrIntegrationTest {
    final android.app.Instrumentation instrumentation=InstrumentationRegistry.getInstrumentation();
    final Context context=instrumentation.getTargetContext();
    final AdvancedIntegrationTest helpers=new AdvancedIntegrationTest();
    void main(Runnable action){instrumentation.runOnMainSync(action);}
    View find(View view,String label){if(view instanceof TextView text&&label.contentEquals(text.getText()))return view;if(view instanceof ViewGroup group)for(int i=0;i<group.getChildCount();i++){View result=find(group.getChildAt(i),label);if(result!=null)return result;}return null;}
    boolean has(MixarrActivity a,String label){return helpers.read(()->find(a.getWindow().getDecorView(),label)!=null);}
    EditText input(View view){if(view instanceof EditText e)return e;if(view instanceof ViewGroup group)for(int i=0;i<group.getChildCount();i++){EditText result=input(group.getChildAt(i));if(result!=null)return result;}return null;}
    final java.util.List<MixarrActivity> activityScreens=new java.util.ArrayList<>();
    @org.junit.After public void closeActivityScreens(){main(()->{for(MixarrActivity a:activityScreens)a.finish();});}
    MixarrActivity openActivity(android.app.Activity from)throws Exception{
        var monitor=instrumentation.addMonitor(MixarrActivity.class.getName(),null,false);
        main(()->new PodcastIntegrationTest().described(from.getWindow().getDecorView(),"Open library request activity").performClick());
        MixarrActivity next=(MixarrActivity)monitor.waitForActivityWithTimeout(10000);instrumentation.removeMonitor(monitor);assertNotNull(next);activityScreens.add(next);return next;
    }
    final class Fixture implements AutoCloseable {
        final ServerSocket server=new ServerSocket(0,10,java.net.InetAddress.getByName("127.0.0.1"));
        final String address="http://127.0.0.1:"+server.getLocalPort();
        final AtomicInteger searches=new AtomicInteger(),adds=new AtomicInteger(),polls=new AtomicInteger(),previews=new AtomicInteger(),imports=new AtomicInteger(),playlists=new AtomicInteger(),importPolls=new AtomicInteger();
        final AtomicReference<JSONObject> selection=new AtomicReference<>();
        final AtomicReference<Throwable> failure=new AtomicReference<>();
        volatile String state="queued";
        volatile boolean closed;
        final Thread worker;
        Fixture()throws Exception{worker=new Thread(()->{while(!closed)try(Socket socket=server.accept()){
            var reader=new java.io.BufferedReader(new java.io.InputStreamReader(socket.getInputStream(),StandardCharsets.UTF_8));
            String request=reader.readLine(),line;int length=0;String cookie="",csrf="";
            while((line=reader.readLine())!=null&&!line.isEmpty()){if(line.toLowerCase(java.util.Locale.ROOT).startsWith("content-length:"))length=Integer.parseInt(line.substring(15).trim());if(line.toLowerCase(java.util.Locale.ROOT).startsWith("cookie:"))cookie=line.substring(7).trim();if(line.toLowerCase(java.util.Locale.ROOT).startsWith("x-csrf-token:"))csrf=line.substring(13).trim();}
            char[] bytes=new char[length];int read=0;while(read<length){int n=reader.read(bytes,read,length-read);if(n<0)break;read+=n;}
            JSONObject body=length==0?new JSONObject():new JSONObject(new String(bytes));String path=request.split(" ")[1];int code=200;String extra="",result="{}";
            if(path.equals("/api/v1/login")){assertEquals("fixture-user",body.getString("username"));assertEquals("fixture-password",body.getString("password"));extra="Set-Cookie: mixarr_session=fixture-session; Path=/; HttpOnly\r\n";result="{\"csrf_token\":\"fixture-csrf\",\"user\":{\"id\":7}}";}
            else {
                assertEquals("mixarr_session=fixture-session",cookie);
                if(request.startsWith("POST"))assertEquals("fixture-csrf",csrf);
                if(path.equals("/api/v1/track-requests/search")){searches.incrementAndGet();assertTrue(body.has("query"));result="[{\"id\":\"recording-one\",\"title\":\"Fixture Song\",\"artist\":\"Fixture Artist\",\"album\":\"Fixture Album\",\"duration_ms\":210000,\"version\":\"live\",\"token\":\"opaque-selection\"}]";}
                else if(path.equals("/api/v1/track-requests")){assertEquals(1,body.length());assertEquals("opaque-selection",body.getString("token"));adds.incrementAndGet();code=202;result="{\"track_id\":123,\"job_id\":456,\"state\":\"queued\"}";}
                else if(path.equals("/api/v1/youtube-imports/preview")){previews.incrementAndGet();assertEquals("https://youtu.be/abcdefghijk",body.getString("url"));result="{\"title\":\"Fixture Album\",\"mode\":\"chapters\",\"token\":\"private-preview-token\",\"items\":[{\"source_id\":\"0\",\"title\":\"Chapter One\",\"artist\":\"Fixture Artist\",\"album\":\"Fixture Album\",\"start\":0,\"end\":100,\"duration\":200},{\"source_id\":\"1\",\"title\":\"Chapter Two\",\"artist\":\"Fixture Artist\",\"album\":\"Fixture Album\",\"start\":100,\"end\":200,\"duration\":200}]}";}
                else if(path.equals("/api/v1/youtube-imports")){assertEquals("private-preview-token",body.getString("token"));assertEquals(2,body.length());selection.set(body);imports.incrementAndGet();code=202;result=batch();}
                else if(path.equals("/api/v1/youtube-imports/playlist")){assertEquals("https://music.youtube.com/playlist?list=PLfixture12345",body.getString("url"));playlists.incrementAndGet();code=202;result=batch();}
                else if(path.equals("/api/v1/youtube-imports/fixture-batch")){importPolls.incrementAndGet();result=batch();}
                else if(path.equals("/api/v1/track-requests/jobs/456")){polls.incrementAndGet();result=new JSONObject().put("track_id",123).put("job_id",456).put("state",state).put("progress",state.equals("processing")?new JSONObject().put("label","Downloading audio").put("percent",50).put("downloaded_bytes",500).put("speed",100).put("eta",5):JSONObject.NULL).toString();}
                else {code=404;}
            }
            byte[] output=result.getBytes(StandardCharsets.UTF_8);socket.getOutputStream().write(("HTTP/1.1 "+code+" OK\r\nContent-Type: application/json\r\n"+extra+"Content-Length: "+output.length+"\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.UTF_8));socket.getOutputStream().write(output);socket.getOutputStream().flush();
        }catch(Throwable e){if(!closed)failure.set(e);}});worker.start();}
        String batch()throws Exception{return new JSONObject().put("id","fixture-batch").put("title","Fixture Import").put("items",new org.json.JSONArray().put(new JSONObject().put("id",1).put("title","Imported Chapter").put("artist","Fixture Artist").put("job_id",789).put("state",state))).toString();}
        MixarrApi login()throws Exception{return MixarrApi.login(address,"fixture-user","fixture-password");}
        @Override public void close()throws Exception{closed=true;server.close();worker.join(3000);if(failure.get()!=null)throw new AssertionError(failure.get());}
    }
    @Test public void authenticationIsSeparateEncryptedAndScoped()throws Exception {
        try(Fixture fixture=new Fixture()){
            JellyfinApi one=new JellyfinApi("http://127.0.0.1","jellyfin-secret","one","device"),two=new JellyfinApi(one.server,one.token,"two","device");String scope=MixarrApi.scope(one);
            try{
                MixarrApi api=fixture.login();MixarrApi.save(context,scope,api);assertNull(MixarrApi.load(context,MixarrApi.scope(two)));
                String stored=context.getSharedPreferences(scope,0).getAll().toString();assertFalse(stored.contains("fixture-session"));assertFalse(stored.contains("fixture-csrf"));assertFalse(stored.contains("fixture-password"));
                MixarrApi restored=MixarrApi.load(context,scope);assertNotNull(restored);restored.request("/api/v1/track-requests/search",new JSONObject().put("query","Fridy Rebeca Blac"));assertEquals(0,fixture.adds.get());
                restored.request("/api/v1/track-requests",new JSONObject().put("token","opaque-selection"));assertEquals(1,fixture.adds.get());
                try{new MixarrApi("http://example.com","","","");fail("Remote plaintext credentials must be refused");}catch(IllegalArgumentException expected){}
                try{new MixarrApi("https://user:pass@example.com","","","");fail("URL credentials must be refused");}catch(IllegalArgumentException expected){}
            }finally{context.getSharedPreferences(scope,0).edit().clear().commit();}
        }
    }
    @Test public void searchRequiresConfirmationClearsStaleResultsAndTracksProgress()throws Exception {
        try(Fixture fixture=new Fixture()){
            JellyfinApi jellyfin=new JellyfinApi("http://127.0.0.1","fixture-jellyfin","fixture-user","device");SessionStore.save(context,jellyfin);String scope=MixarrApi.scope(jellyfin);
            MixarrActivity activity=null;
            try{
                MixarrApi.save(context,scope,fixture.login());
                activity=(MixarrActivity)instrumentation.startActivitySync(new Intent(context,MixarrActivity.class).putExtra("query","Fixture Song").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));MixarrActivity screen=activity;
                helpers.await(()->has(screen,"ADD"),10);assertEquals(0,fixture.adds.get());assertTrue(has(screen,"Fixture Album · 3:30"));assertTrue(has(screen,"live"));
                main(()->{View connection=find(screen.getWindow().getDecorView(),"Connection");assertNotNull(connection);android.graphics.Rect visible=new android.graphics.Rect();assertTrue("Connection action must remain visible",connection.getGlobalVisibleRect(visible)&&visible.width()>=48);});
                screenshot("rewindamp-mixarr-search.png");
                main(()->input(screen.getWindow().getDecorView()).setText("different query"));assertFalse(has(screen,"ADD"));Thread.sleep(500);assertEquals(1,fixture.searches.get());
                main(()->find(screen.getWindow().getDecorView(),"SEARCH ONLINE").performClick());helpers.await(()->has(screen,"ADD"),10);
                main(()->{View add=find(screen.getWindow().getDecorView(),"ADD");add.performClick();add.performClick();});helpers.await(()->fixture.adds.get()==1&&has(screen,"ADD"),10);
                assertFalse("Activity must not take up the results list",has(screen,"RECENT LIBRARY REQUESTS"));
                assertTrue(has(screen,"Fixture Album · 3:30"));
                MixarrActivity activityQueue=openActivity(screen);helpers.await(()->has(activityQueue,"RECENT LIBRARY REQUESTS"),10);screenshot("rewindamp-library-activity.png");
                fixture.state="processing";helpers.await(()->helpers.read(()->{StringBuilder text=new StringBuilder();collect(activityQueue.getWindow().getDecorView(),text);return text.toString().contains("50.0% of current stream")&&text.toString().contains("ETA 5s");}),12);
                fixture.state="failed";helpers.await(()->has(activityQueue,"SEARCH AGAIN TO RETRY"),12);main(activityQueue::finish);
                main(screen::finish);activity=null;
                MixarrActivity reopened=(MixarrActivity)instrumentation.startActivitySync(new Intent(context,MixarrActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));activity=reopened;
                MixarrActivity reopenedQueue=openActivity(reopened);helpers.await(()->has(reopenedQueue,"SEARCH AGAIN TO RETRY"),10);assertEquals(1,fixture.adds.get());
                main(()->find(reopenedQueue.getWindow().getDecorView(),"SEARCH AGAIN TO RETRY").performClick());helpers.await(()->has(reopenedQueue,"ADD"),10);assertEquals(1,fixture.adds.get());
            }finally{if(activity!=null){MixarrActivity last=activity;main(last::finish);}instrumentation.waitForIdleSync();context.getSharedPreferences(scope,0).edit().clear().commit();SessionStore.clear(context);}
        }
    }
    @Test public void mainDeckShowsRequestStatusAndOpensActivity()throws Exception{
        try(Fixture fixture=new Fixture()){
            JellyfinApi jellyfin=new JellyfinApi("http://127.0.0.1","fixture-jellyfin","status-user","device");SessionStore.save(context,jellyfin);String scope=MixarrApi.scope(jellyfin);MainActivity activity=null;
            try{
                MixarrApi client=fixture.login();MixarrApi.save(context,scope,client);MixarrApi.remember(context,scope,client,new JSONObject().put("track_id",123).put("job_id",456).put("title","Fixture Song").put("artist","Fixture Artist").put("state","queued"));
                activity=(MainActivity)instrumentation.startActivitySync(new Intent(context,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));MainActivity screen=activity;
                helpers.await(()->helpers.read(()->{View strip=new PodcastIntegrationTest().described(screen.getWindow().getDecorView(),"Open library request activity");return strip instanceof TextView&&strip.isShown()&&((TextView)strip).getText().toString().contains("1 active");}),10);
                fixture.state="processing";helpers.await(()->helpers.read(()->{TextView strip=(TextView)new PodcastIntegrationTest().described(screen.getWindow().getDecorView(),"Open library request activity");return strip.getText().toString().contains("Downloading audio 50%");}),12);
                screenshot("rewindamp-main-activity-status.png");MixarrActivity queue=openActivity(screen);helpers.await(()->has(queue,"RECENT LIBRARY REQUESTS"),10);main(queue::finish);
            }finally{if(activity!=null){MainActivity last=activity;main(last::finish);}context.stopService(new Intent(context,PlaybackService.class));instrumentation.waitForIdleSync();context.getSharedPreferences(scope,0).edit().clear().commit();SessionStore.clear(context);}
        }
    }
    EditText named(View view,String name){if(view instanceof EditText text&&name.contentEquals(text.getContentDescription()))return text;if(view instanceof ViewGroup group)for(int i=0;i<group.getChildCount();i++){EditText found=named(group.getChildAt(i),name);if(found!=null)return found;}return null;}
    @Test public void youtubePreviewEditsValidationConfirmationAndPersistentStatus()throws Exception {
        try(Fixture fixture=new Fixture()){
            JellyfinApi jellyfin=new JellyfinApi("http://127.0.0.1","fixture-jellyfin","youtube-user","device");SessionStore.save(context,jellyfin);String scope=MixarrApi.scope(jellyfin);MixarrActivity activity=null;
            try{
                MixarrApi.save(context,scope,fixture.login());activity=(MixarrActivity)instrumentation.startActivitySync(new Intent(context,MixarrActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));MixarrActivity screen=activity;
                main(()->{input(screen.getWindow().getDecorView()).setText("https://youtu.be/abcdefghijk");find(screen.getWindow().getDecorView(),"PREVIEW YOUTUBE IMPORT").performClick();});
                helpers.await(()->has(screen,"IMPORT SELECTED AUDIO"),10);assertEquals(0,fixture.imports.get());assertEquals(0,fixture.searches.get());
                screenshot("rewindamp-mixarr-youtube-preview.png");
                assertFalse(context.getSharedPreferences(scope,0).getAll().toString().contains("private-preview-token"));
                main(()->{named(screen.getWindow().getDecorView(),"Track 1 end (seconds)").setText("150");find(screen.getWindow().getDecorView(),"IMPORT SELECTED AUDIO").performClick();});
                Thread.sleep(300);assertEquals(0,fixture.imports.get());
                main(()->{named(screen.getWindow().getDecorView(),"Track 1 end (seconds)").setText("100");named(screen.getWindow().getDecorView(),"Track 1 title").setText("Reviewed Chapter");find(screen.getWindow().getDecorView(),"IMPORT SELECTED AUDIO").performClick();find(screen.getWindow().getDecorView(),"IMPORT SELECTED AUDIO").performClick();});
                helpers.await(()->fixture.imports.get()==1,10);assertFalse(has(screen,"YouTube import: Fixture Import"));MixarrActivity activityQueue=openActivity(screen);helpers.await(()->has(activityQueue,"YouTube import: Fixture Import"),10);
                assertEquals("Reviewed Chapter",fixture.selection.get().getJSONArray("items").getJSONObject(0).getString("title"));assertEquals(2,fixture.selection.get().getJSONArray("items").length());
                fixture.state="processing";helpers.await(()->helpers.read(()->{StringBuilder text=new StringBuilder();collect(activityQueue.getWindow().getDecorView(),text);return text.toString().contains("Fixture Artist · processing");}),12);
                assertEquals(0,fixture.polls.get());assertTrue(fixture.importPolls.get()>0);
                MixarrApi saved=MixarrApi.load(context,scope);
                MixarrApi.remember(context,scope,saved,new JSONObject().put("track_id",123).put("state","available"));
                MixarrApi.remember(context,scope,saved,new JSONObject().put("import_id","second-batch").put("items",new org.json.JSONArray()));
                assertEquals(3,saved.history(context,scope).length());
                main(activityQueue::finish);main(screen::finish);activity=null;
                MixarrActivity reopened=(MixarrActivity)instrumentation.startActivitySync(new Intent(context,MixarrActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));activity=reopened;
                MixarrActivity reopenedQueue=openActivity(reopened);helpers.await(()->has(reopenedQueue,"YouTube import: Fixture Import"),10);assertEquals(1,fixture.imports.get());
                assertFalse(context.getSharedPreferences(scope,0).getAll().toString().contains("private-preview-token"));
            }finally{if(activity!=null){MixarrActivity last=activity;main(last::finish);}instrumentation.waitForIdleSync();context.getSharedPreferences(scope,0).edit().clear().commit();SessionStore.clear(context);}
        }
    }
    @Test public void playlistRequiresExplicitButtonAndRejectsOtherHosts()throws Exception {
        try(Fixture fixture=new Fixture()){
            JellyfinApi jellyfin=new JellyfinApi("http://127.0.0.1","fixture-jellyfin","playlist-user","device");SessionStore.save(context,jellyfin);String scope=MixarrApi.scope(jellyfin);MixarrActivity activity=null;
            try{
                String url="https://music.youtube.com/playlist?list=PLfixture12345";MixarrApi.save(context,scope,fixture.login());
                activity=(MixarrActivity)instrumentation.startActivitySync(new Intent(context,MixarrActivity.class).putExtra("query",url).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));MixarrActivity screen=activity;
                helpers.await(()->has(screen,"DOWNLOAD ENTIRE PLAYLIST"),10);Thread.sleep(300);assertEquals(0,fixture.playlists.get());
                main(()->{find(screen.getWindow().getDecorView(),"DOWNLOAD ENTIRE PLAYLIST").performClick();find(screen.getWindow().getDecorView(),"DOWNLOAD ENTIRE PLAYLIST").performClick();});
                helpers.await(()->fixture.playlists.get()==1,10);assertFalse(has(screen,"YouTube import: Fixture Import"));assertEquals(0,fixture.previews.get());assertEquals(0,fixture.searches.get());
                main(()->{input(screen.getWindow().getDecorView()).setText("https://youtube.com.evil.test/watch?v=abcdefghijk");find(screen.getWindow().getDecorView(),"PREVIEW YOUTUBE IMPORT").performClick();});
                assertTrue(has(screen,"Paste an HTTPS YouTube video or playlist link."));assertEquals(0,fixture.previews.get());
                assertTrue(MixarrYouTubeImport.validLink("https://youtu.be/abcdefghijk"));assertFalse(MixarrYouTubeImport.isPlaylist("https://www.youtube.com/watch?v=abcdefghijk&list=PLfixture12345"));
                assertFalse(MixarrYouTubeImport.validLink("https://user:pass@www.youtube.com/watch?v=abcdefghijk"));assertFalse(MixarrYouTubeImport.validLink("http://www.youtube.com/watch?v=abcdefghijk"));
            }finally{if(activity!=null){MixarrActivity last=activity;main(last::finish);}instrumentation.waitForIdleSync();context.getSharedPreferences(scope,0).edit().clear().commit();SessionStore.clear(context);}
        }
    }
    void screenshot(String name)throws Exception{instrumentation.waitForIdleSync();Thread.sleep(500);new PodcastIntegrationTest().screenshot(name.replace("rewindamp-","").replace(".png",""));}
    void collect(View view,StringBuilder value){if(view instanceof TextView text)value.append(text.getText()).append('\n');if(view instanceof ViewGroup group)for(int i=0;i<group.getChildCount();i++)collect(group.getChildAt(i),value);}
}

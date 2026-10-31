package dev.rewindamp;

import android.content.Context;
import android.content.Intent;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.media3.common.Player;
import androidx.media3.session.MediaController;
import androidx.media3.session.SessionToken;
import android.content.ComponentName;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

/** Runs only against scripts/podcast-fixture-server.py with generated audio. */
@androidx.annotation.OptIn(markerClass=androidx.media3.common.util.UnstableApi.class)
@RunWith(AndroidJUnit4.class)
public class PodcastIntegrationTest {
    interface Check{boolean ok()throws Exception;}
    void waitFor(Check check)throws Exception{long deadline=System.currentTimeMillis()+25000;while(System.currentTimeMillis()<deadline){if(check.ok())return;Thread.sleep(150);}fail("Timed out waiting for podcast fixture");}
    void screenshot(String name)throws Exception{
        try(var descriptor=InstrumentationRegistry.getInstrumentation().getUiAutomation().executeShellCommand("screencap -p /sdcard/Download/rewindamp-"+name+".png");var input=new android.os.ParcelFileDescriptor.AutoCloseInputStream(descriptor)){while(input.read()!=-1){}}
    }
    android.view.View find(android.view.View root,String value){
        if(root instanceof android.widget.TextView t&&value.contentEquals(t.getText()))return root;
        if(root instanceof android.view.ViewGroup group)for(int i=0;i<group.getChildCount();i++){var match=find(group.getChildAt(i),value);if(match!=null)return match;}return null;
    }
    @Test public void brandedDemoScreenshots()throws Exception{
        var instrumentation=InstrumentationRegistry.getInstrumentation();Context context=instrumentation.getTargetContext();
        SessionStore.clear(context);context.stopService(new Intent(context,PlaybackService.class));
        MainActivity activity=(MainActivity)instrumentation.startActivitySync(new Intent(context,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        instrumentation.runOnMainSync(()->{android.view.View demo=find(activity.getWindow().getDecorView(),"EXPLORE THE DEMO");assertNotNull(demo);demo.performClick();});
        Thread.sleep(800);screenshot("player");
        instrumentation.runOnMainSync(()->activity.openSearch("Neon"));Thread.sleep(1000);screenshot("search");
        instrumentation.runOnMainSync(activity::finish);
    }
    android.view.View described(android.view.View root,String value){
        if(value.equals(root.getContentDescription()))return root;
        if(root instanceof android.view.ViewGroup group)for(int i=0;i<group.getChildCount();i++){var match=described(group.getChildAt(i),value);if(match!=null)return match;}return null;
    }
    android.view.View hint(android.view.View root,String value){
        if(root instanceof android.widget.EditText field&&value.contentEquals(field.getHint()))return root;
        if(root instanceof android.view.ViewGroup group)for(int i=0;i<group.getChildCount();i++){var match=hint(group.getChildAt(i),value);if(match!=null)return match;}return null;
    }
    @Test public void signsInWithPasswordAndStoresOnlyEncryptedSession()throws Exception{
        var instrumentation=InstrumentationRegistry.getInstrumentation();Context context=instrumentation.getTargetContext();
        context.stopService(new Intent(context,PodcastPlaybackService.class));
        // A saved bootstrap credential must lead back to password sign-in after migration.
        PodcastApi.save(context,new PodcastApi("http://10.0.2.2:18099","isolated-podcast-fixture-token-00000001"));
        android.app.Activity activity=instrumentation.startActivitySync(new Intent(context,PodcastActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        waitFor(()->{boolean[] ready={false};instrumentation.runOnMainSync(()->ready[0]=hint(activity.getWindow().getDecorView(),"Owner password")!=null);return ready[0];});
        instrumentation.runOnMainSync(()->{((android.widget.EditText)hint(activity.getWindow().getDecorView(),"https://podcasts.example.com")).setText("http://10.0.2.2:18099");((android.widget.EditText)hint(activity.getWindow().getDecorView(),"Owner password")).setText("isolated memorable fixture password");find(activity.getWindow().getDecorView(),"CONNECT").performClick();});
        waitFor(()->{PodcastApi saved=PodcastApi.load(context);return saved!=null&&saved.server.endsWith(":18099");});
        PodcastApi saved=PodcastApi.load(context);assertNotEquals("isolated memorable fixture password",saved.token);
        assertEquals(1,saved.request("/api/feeds",null).getJSONArray("items").length());
        String encrypted=context.getSharedPreferences("podcast-session",0).getString("data","");assertFalse(encrypted.contains(saved.token));assertFalse(encrypted.contains("isolated memorable fixture password"));
        instrumentation.runOnMainSync(()->{activity.finish();context.stopService(new Intent(context,PodcastPlaybackService.class));});
    }
    @Test public void searchesCatalogueAndSubscribesWithFuturePass()throws Exception{
        var instrumentation=InstrumentationRegistry.getInstrumentation();Context context=instrumentation.getTargetContext();
        PodcastApi api=new PodcastApi("http://10.0.2.2:18097","isolated-podcast-fixture-token-00000001");PodcastApi.save(context,api);
        PodcastDiscoveryActivity activity=(PodcastDiscoveryActivity)instrumentation.startActivitySync(new Intent(context,PodcastDiscoveryActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        instrumentation.runOnMainSync(()->{((android.widget.EditText)described(activity.getWindow().getDecorView(),"Podcast search")).setText("empty fixture");find(activity.getWindow().getDecorView(),"SEARCH").performClick();});
        waitFor(()->{boolean[] found={false};instrumentation.runOnMainSync(()->found[0]=find(activity.getWindow().getDecorView(),"NO RESULTS · Try another title or publisher.")!=null);return found[0];});
        instrumentation.runOnMainSync(()->{((android.widget.EditText)described(activity.getWindow().getDecorView(),"Podcast search")).setText("unavailable fixture");find(activity.getWindow().getDecorView(),"SEARCH").performClick();});
        waitFor(()->{boolean[] found={false};instrumentation.runOnMainSync(()->found[0]=find(activity.getWindow().getDecorView(),"Search unavailable. Retry, or add an RSS feed in Podcast settings.")!=null);return found[0];});
        instrumentation.runOnMainSync(()->{((android.widget.EditText)described(activity.getWindow().getDecorView(),"Podcast search")).setText("synthetic");find(activity.getWindow().getDecorView(),"SEARCH").performClick();});
        waitFor(()->{boolean[] found={false};instrumentation.runOnMainSync(()->found[0]=find(activity.getWindow().getDecorView(),"Synthetic Discovery Show")!=null);return found[0];});
        instrumentation.runOnMainSync(()->assertFalse(find(activity.getWindow().getDecorView(),"Subscribed").isEnabled()));
        screenshot("podcast-discovery");
        instrumentation.runOnMainSync(()->described(activity.getWindow().getDecorView(),"Subscribe to Synthetic Discovery Show").performClick());
        // Selecting a result alone must not subscribe or acquire anything.
        assertEquals(1,api.request("/api/feeds",null).getJSONArray("items").length());
        waitFor(()->{var root=instrumentation.getUiAutomation().getRootInActiveWindow();if(root==null)return false;
            for(var node:root.findAccessibilityNodeInfosByText("Subscribe"))if(node.getText()!=null&&node.getText().toString().equalsIgnoreCase("Subscribe")&&node.isClickable())return node.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK);return false;});
        waitFor(()->api.request("/api/feeds",null).getJSONArray("items").length()==2);
        var feeds=api.request("/api/feeds",null).getJSONArray("items");JSONObject added=null;for(int i=0;i<feeds.length();i++)if(feeds.getJSONObject(i).getString("title").equals("Synthetic Discovery Show"))added=feeds.getJSONObject(i);
        assertNotNull(added);assertEquals("future",added.getString("mode"));assertTrue(added.isNull("baseline"));
        assertTrue(api.request("/api/podcasts/search?term=synthetic",null).getJSONArray("items").getJSONObject(0).getBoolean("subscribed"));
    }
    @Test public void podcastsStreamSeekDownloadAndKeepPlayedHistory()throws Exception{
        var instrumentation=InstrumentationRegistry.getInstrumentation();Context context=instrumentation.getTargetContext();
        PodcastApi api=new PodcastApi("http://10.0.2.2:18097","isolated-podcast-fixture-token-00000001");
        PodcastApi.save(context,api);assertEquals(api.token,PodcastApi.load(context).token);
        PodcastLibrary[] library={null};instrumentation.runOnMainSync(()->library[0]=PodcastLibrary.get(context,api));
        JSONObject episode=library[0].episodes(null,false,0).get(0);String id=episode.getString("id");
        // Explicit reset permits repeatable test runs without altering any production history.
        api.request("/api/progress/"+id,new JSONObject().put("event_id",java.util.UUID.randomUUID().toString()).put("epoch",episode.optLong("epoch")).put("action","reset"));
        episode=library[0].episodes(null,false,0).get(0);JSONObject selected=episode;
        assertFalse(library[0].media(selected).localConfiguration.uri.toString().contains(api.token));
        android.app.Activity activity=instrumentation.startActivitySync(new Intent(context,PodcastActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        var future=new MediaController.Builder(context,new SessionToken(context,new ComponentName(context,PodcastPlaybackService.class))).buildAsync();
        MediaController controller=future.get(15,java.util.concurrent.TimeUnit.SECONDS);
        instrumentation.runOnMainSync(()->{controller.setMediaItem(library[0].media(selected),40000);controller.prepare();controller.play();});
        waitFor(()->{boolean[] ready={false};instrumentation.runOnMainSync(()->ready[0]=controller.isPlaying());return ready[0];});
        long[] position={0};
        instrumentation.runOnMainSync(()->{controller.pause();controller.seekTo(40000);});
        waitFor(()->{instrumentation.runOnMainSync(()->position[0]=controller.getCurrentPosition());return Math.abs(position[0]-40000)<1000;});
        instrumentation.runOnMainSync(controller::seekBack);
        waitFor(()->{instrumentation.runOnMainSync(()->position[0]=controller.getCurrentPosition());return Math.abs(position[0]-25000)<1000;});
        instrumentation.runOnMainSync(controller::seekForward);
        waitFor(()->{instrumentation.runOnMainSync(()->position[0]=controller.getCurrentPosition());return Math.abs(position[0]-55000)<1000;});
        instrumentation.runOnMainSync(()->{assertFalse(controller.isCommandAvailable(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM));assertTrue(controller.isCommandAvailable(Player.COMMAND_SEEK_BACK));try{library[0].download(selected);}catch(Exception e){throw new RuntimeException(e);}});
        waitFor(()->library[0].complete(id));
        // Read the entire download without an upstream source: verifies genuinely offline bytes.
        var cached=new androidx.media3.datasource.cache.CacheDataSource.Factory().setCache(library[0].cache).setUpstreamDataSourceFactory(null).createDataSource();
        cached.open(new androidx.media3.datasource.DataSpec.Builder().setUri(api.audio(id)).setKey(id).build());byte[] buffer=new byte[8192];long total=0;int n;while((n=cached.read(buffer,0,buffer.length))!=-1)total+=n;cached.close();assertTrue(total>1000);
        instrumentation.runOnMainSync(()->library[0].event(id,55000,"played"));library[0].sync();
        library[0].event(id,1000,"progress");library[0].sync();assertEquals("played",library[0].episodes(null,false,0).get(0).getString("state"));assertTrue(library[0].episodes(null,true,0).isEmpty());
        instrumentation.runOnMainSync(()->{controller.pause();MediaController.releaseFuture(future);});
        screenshot("podcasts");
        instrumentation.runOnMainSync(activity::finish);
        // Exact same Activity can be opened without a Jellyfin login.
        assertTrue(library[0].store.directory.getAbsolutePath().startsWith(context.getFilesDir().getAbsolutePath()));
    }
}

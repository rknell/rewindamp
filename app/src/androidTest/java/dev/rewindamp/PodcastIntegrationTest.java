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

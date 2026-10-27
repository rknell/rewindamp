package dev.rewindamp;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.view.View;
import android.widget.Spinner;
import android.widget.TextView;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.media3.common.Player;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

/** Queue and navigation checks against a separate multi-show synthetic fixture only. */
@androidx.annotation.OptIn(markerClass=androidx.media3.common.util.UnstableApi.class)
@RunWith(AndroidJUnit4.class)
public class PodcastQueueIntegrationTest {
    final PodcastIntegrationTest helpers=new PodcastIntegrationTest();
    final android.app.Instrumentation instrumentation=InstrumentationRegistry.getInstrumentation();
    final Context context=instrumentation.getTargetContext();
    final java.util.List<PodcastActivity> activities=new java.util.ArrayList<>();
    final java.util.List<com.google.common.util.concurrent.ListenableFuture<androidx.media3.session.MediaController>> controllers=new java.util.ArrayList<>();
    @org.junit.After public void cleanup(){main(()->{for(PodcastActivity activity:activities)activity.finish();for(var future:controllers)androidx.media3.session.MediaController.releaseFuture(future);});instrumentation.waitForIdleSync();context.stopService(new Intent(context,PodcastPlaybackService.class));}
    void main(Runnable run){instrumentation.runOnMainSync(run);}
    View described(Activity activity,String label){return helpers.described(activity.getWindow().getDecorView(),label);}
    PodcastLibrary fixture()throws Exception{
        context.stopService(new Intent(context,PodcastPlaybackService.class));
        helpers.waitFor(()->((android.app.ActivityManager)context.getSystemService(Context.ACTIVITY_SERVICE)).getRunningServices(100).stream().noneMatch(info->info.service.getClassName().equals(PodcastPlaybackService.class.getName())));
        PodcastApi api=new PodcastApi("http://10.0.2.2:18100","isolated-podcast-fixture-token-00000001");PodcastApi.save(context,api);
        PodcastLibrary[] value={null};main(()->value[0]=PodcastLibrary.get(context,api));PodcastLibrary library=value[0];
        for(JSONObject e:library.episodes(null,false,0)){library.event(e.getString("id"),0,"reset");}library.sync();library.episodes(null,false,0);
        for(JSONObject e:library.episodes(null,false,0))if(e.optString("title").equals("Queue played"))library.event(e.getString("id"),0,"played");library.sync();
        library.autoDownloads(0);library.clearSleep();library.pauseDownloads(false);library.preference("show_played",false);library.store.write("podcast-browser",new JSONObject());return library;
    }
    PodcastActivity launch()throws Exception{
        PodcastActivity activity=(PodcastActivity)instrumentation.startActivitySync(new Intent(context,PodcastActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        activities.add(activity);helpers.waitFor(()->{boolean[] ready={false};main(()->ready[0]=described(activity,"Podcast show")!=null&&((Spinner)described(activity,"Podcast show")).getCount()==3&&((described(activity,"Play")!=null&&described(activity,"Play").isEnabled())||described(activity,"Pause")!=null));return ready[0];});return activity;
    }
    JSONObject episode(PodcastLibrary library,String title)throws Exception{return library.episodes(null,false,0).stream().filter(e->e.optString("title").equals(title)).findFirst().orElseThrow();}
    void waitQueue(PodcastActivity activity,String current,int count)throws Exception{
        try{helpers.waitFor(()->{boolean[] ready={false};main(()->{View q=described(activity,"View podcast queue");ready[0]=q instanceof TextView&&((TextView)q).getText().toString().startsWith("QUEUE 1/"+count)&&helpers.find(activity.getWindow().getDecorView(),current)!=null;});return ready[0];});}catch(AssertionError e){StringBuilder values=new StringBuilder();main(()->texts(activity.getWindow().getDecorView(),values));throw new AssertionError(values.toString(),e);}
    }
    void texts(View view,StringBuilder values){if(view instanceof TextView text)values.append(text.getText()).append(" | ");if(view instanceof android.view.ViewGroup group)for(int i=0;i<group.getChildCount();i++)texts(group.getChildAt(i),values);}
    @Test public void showQueueSkipsPlayedAdvancesAndKeepsTitleStable()throws Exception{
        PodcastLibrary library=fixture();String feed=episode(library,"Queue old").getString("feed_id");library.preference("oldest:"+feed,true);PodcastActivity activity=launch();
        main(()->((Spinner)described(activity,"Podcast show")).setSelection(2));
        helpers.waitFor(()->{boolean[] ready={false};main(()->ready[0]=helpers.find(activity.getWindow().getDecorView(),"OLDEST FIRST")!=null);return ready[0];});
        JSONObject selected=episode(library,"Queue old");main(()->activity.play(selected));waitQueue(activity,"Queue old",2);
        final CharSequence[] before={null};main(()->before[0]=((TextView)helpers.find(activity.getWindow().getDecorView(),"Queue old")).getText());Thread.sleep(1100);
        main(()->assertSame(before[0],((TextView)helpers.find(activity.getWindow().getDecorView(),"Queue old")).getText()));
        var future=new androidx.media3.session.MediaController.Builder(context,new androidx.media3.session.SessionToken(context,new android.content.ComponentName(context,PodcastPlaybackService.class))).buildAsync();controllers.add(future);var controller=future.get(15,java.util.concurrent.TimeUnit.SECONDS);
        main(()->{assertEquals(2,controller.getMediaItemCount());assertTrue(controller.isCommandAvailable(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM));assertEquals("Queue newest",controller.getMediaItemAt(1).mediaMetadata.title);controller.seekTo(89500);controller.play();});
        helpers.waitFor(()->{boolean[] changed={false};main(()->changed[0]=controller.getCurrentMediaItemIndex()==1);return changed[0];});
        helpers.waitFor(()->library.episode(selected.optString("id")).optString("state").equals("played"));
        main(()->{assertNotNull(described(activity,"Previous episode"));controller.pause();androidx.media3.session.MediaController.releaseFuture(future);activity.finish();});
    }
    @Test public void allShowsQueueFollowsListAndRestoresAfterServiceRestart()throws Exception{
        PodcastLibrary library=fixture();library.preference("oldest:all",false);PodcastActivity activity=launch();JSONObject selected=episode(library,"Queue newest");main(()->activity.play(selected));waitQueue(activity,"Queue newest",3);
        var future=new androidx.media3.session.MediaController.Builder(context,new androidx.media3.session.SessionToken(context,new android.content.ComponentName(context,PodcastPlaybackService.class))).buildAsync();controllers.add(future);var controller=future.get(15,java.util.concurrent.TimeUnit.SECONDS);
        helpers.waitFor(()->{boolean[] ready={false};main(()->ready[0]=controller.getPlaybackState()==Player.STATE_READY);return ready[0];});
        main(()->{assertEquals("Queue middle",controller.getMediaItemAt(1).mediaMetadata.title);assertEquals("Queue old",controller.getMediaItemAt(2).mediaMetadata.title);controller.pause();controller.setPlaybackSpeed(1.5f);controller.seekTo(20000);});helpers.waitFor(()->{boolean[] ready={false};main(()->ready[0]=controller.getCurrentPosition()>=19000);return ready[0];});Thread.sleep(600);
        main(()->{activity.finish();androidx.media3.session.MediaController.releaseFuture(future);});instrumentation.waitForIdleSync();context.stopService(new Intent(context,PodcastPlaybackService.class));Thread.sleep(600);
        var restoredFuture=new androidx.media3.session.MediaController.Builder(context,new androidx.media3.session.SessionToken(context,new android.content.ComponentName(context,PodcastPlaybackService.class))).buildAsync();controllers.add(restoredFuture);var restored=restoredFuture.get(15,java.util.concurrent.TimeUnit.SECONDS);
        main(()->{assertEquals(3,restored.getMediaItemCount());assertEquals("Queue newest",restored.getCurrentMediaItem().mediaMetadata.title);assertEquals(1.5f,restored.getPlaybackParameters().speed,.001f);assertFalse(restored.getPlayWhenReady());assertTrue("Restored position was "+restored.getCurrentPosition(),restored.getCurrentPosition()>=19000);androidx.media3.session.MediaController.releaseFuture(restoredFuture);});
    }
    @Test public void sleepTimersPauseAndUpcomingOfflineCopiesAreManaged()throws Exception{
        PodcastLibrary library=fixture();library.preference("oldest:all",false);PodcastActivity activity=launch();JSONObject selected=episode(library,"Queue newest");main(()->activity.play(selected));waitQueue(activity,"Queue newest",3);
        var future=new androidx.media3.session.MediaController.Builder(context,new androidx.media3.session.SessionToken(context,new android.content.ComponentName(context,PodcastPlaybackService.class))).buildAsync();controllers.add(future);var controller=future.get(15,java.util.concurrent.TimeUnit.SECONDS);
        helpers.waitFor(()->{boolean[] ready={false};main(()->ready[0]=controller.isPlaying());return ready[0];});
        library.sleep(System.currentTimeMillis()+1200,false,"");
        helpers.waitFor(()->{boolean[] paused={false};main(()->paused[0]=!controller.getPlayWhenReady());return paused[0];});assertEquals("off",library.sleepDescription());
        JSONObject next=episode(library,"Queue middle");library.wifiOnly(true);library.autoDownloads(1);
        helpers.waitFor(()->library.complete(next.getString("id")));assertTrue(library.offlineSummary().contains("saved"));assertTrue(library.wifiOnly());
        main(()->{library.sleep(0,true,selected.optString("id"));controller.play();});Thread.sleep(1200);
        main(()->controller.seekTo(89500));
        helpers.waitFor(()->{boolean[] paused={false};main(()->paused[0]=!controller.getPlayWhenReady());return paused[0];});assertEquals("off",library.sleepDescription());assertEquals("played",library.episode(selected.optString("id")).optString("state"));
        library.autoDownloads(0);library.remove(next.getString("id"));helpers.waitFor(()->library.download(next.getString("id"))==null);
        main(()->{activity.finish();androidx.media3.session.MediaController.releaseFuture(future);});
    }

    @Test public void bottomNavigationAndEmptyStateHaveClearActions()throws Exception{
        PodcastLibrary library=fixture();for(JSONObject e:library.episodes(null,false,0))library.event(e.getString("id"),0,"played");library.sync();PodcastActivity activity=launch();
        helpers.waitFor(()->{boolean[] ready={false};main(()->ready[0]=helpers.find(activity.getWindow().getDecorView(),"View all podcasts")!=null);return ready[0];});
        main(()->{assertNotNull(described(activity,"Music"));assertNotNull(described(activity,"Podcasts, selected"));assertNotNull(described(activity,"Settings"));helpers.find(activity.getWindow().getDecorView(),"View all podcasts").performClick();});
        helpers.waitFor(()->{boolean[] ready={false};main(()->ready[0]=helpers.find(activity.getWindow().getDecorView(),"NO EPISODES YET")!=null);return ready[0];});
        main(()->{library.preference("show_played",true);activity.load(false);});
        helpers.waitFor(()->{boolean[] ready={false};main(()->ready[0]=helpers.find(activity.getWindow().getDecorView(),"Queue old")!=null);return ready[0];});
        var musicMonitor=instrumentation.addMonitor(MainActivity.class.getName(),null,false);main(()->described(activity,"Music").performClick());
        MainActivity music=(MainActivity)musicMonitor.waitForActivityWithTimeout(10000);assertNotNull(music);instrumentation.removeMonitor(musicMonitor);main(()->assertNotNull(described(music,"Music, selected")));
        var podcastMonitor=instrumentation.addMonitor(PodcastActivity.class.getName(),null,false);main(()->described(music,"Podcasts").performClick());
        PodcastActivity returned=(PodcastActivity)podcastMonitor.waitForActivityWithTimeout(10000);assertNotNull(returned);activities.add(returned);instrumentation.removeMonitor(podcastMonitor);main(music::finish);
    }
}

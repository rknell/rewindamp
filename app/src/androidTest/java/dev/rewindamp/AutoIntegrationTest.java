package dev.rewindamp;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.media.browse.MediaBrowser;
import android.media.session.MediaController;
import androidx.media3.common.Player;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.Assert.*;

/** Legacy browser/transport used by car hosts; generated audio and isolated fixtures only. */
@androidx.annotation.OptIn(markerClass=androidx.media3.common.util.UnstableApi.class)
@RunWith(AndroidJUnit4.class)
public class AutoIntegrationTest {
    final PodcastQueueIntegrationTest fixtureHelper=new PodcastQueueIntegrationTest();
    final PodcastIntegrationTest helpers=fixtureHelper.helpers;
    final android.app.Instrumentation instrumentation=fixtureHelper.instrumentation;
    final Context context=fixtureHelper.context;
    final java.util.List<androidx.media3.session.MediaController> controllers=new java.util.ArrayList<>();
    private MediaBrowser car;
    private android.app.Activity foreground;
    @org.junit.AfterClass public static void restoreCarEntry(){
        Context context=androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().getTargetContext();
        context.getPackageManager().setComponentEnabledSetting(new ComponentName(context,AutoPlaybackService.class),PackageManager.COMPONENT_ENABLED_STATE_DEFAULT,PackageManager.DONT_KILL_APP);
    }
    void main(Runnable run){instrumentation.runOnMainSync(run);}
    void enabled(boolean enabled){context.getPackageManager().setComponentEnabledSetting(new ComponentName(context,AutoPlaybackService.class),enabled?PackageManager.COMPONENT_ENABLED_STATE_ENABLED:PackageManager.COMPONENT_ENABLED_STATE_DISABLED,PackageManager.DONT_KILL_APP);}
    @org.junit.Before public void setup()throws Exception{
        // SystemUI can reconnect media-resumption services itself. Disable that entry while
        // resetting fixtures so its binding cannot keep either private engine alive.
        enabled(false);context.stopService(new Intent(context,AutoPlaybackService.class));
        helpers.waitFor(()->!running(AutoPlaybackService.class));
        SessionStore.clear(context);
        foreground=instrumentation.startActivitySync(new Intent(context,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    }
    boolean running(Class<?> service){return ((android.app.ActivityManager)context.getSystemService(Context.ACTIVITY_SERVICE)).getRunningServices(100).stream().anyMatch(info->info.service.getClassName().equals(service.getName()));}
    @org.junit.After public void cleanup()throws Exception{
        main(()->{if(car!=null&&car.isConnected())new MediaController(context,car.getSessionToken()).getTransportControls().pause();for(var controller:controllers)controller.pause();});
        // Let remote pause commands reach the engines before releasing their transports.
        Thread.sleep(600);enabled(false);
        main(()->{if(car!=null)car.disconnect();for(var controller:controllers)controller.release();if(foreground!=null)foreground.finish();});
        context.stopService(new Intent(context,AutoPlaybackService.class));helpers.waitFor(()->!running(AutoPlaybackService.class));
        context.stopService(new Intent(context,PodcastPlaybackService.class));context.stopService(new Intent(context,PlaybackService.class));
        helpers.waitFor(()->!running(PodcastPlaybackService.class)&&!running(PlaybackService.class));
    }
    PodcastLibrary fixture()throws Exception{return fixtureHelper.fixture();}
    androidx.media3.session.MediaController controller(Class<?> service)throws Exception{
        AtomicReference<com.google.common.util.concurrent.ListenableFuture<androidx.media3.session.MediaController>> future=new AtomicReference<>();main(()->future.set(new androidx.media3.session.MediaController.Builder(context,new androidx.media3.session.SessionToken(context,new ComponentName(context,service))).buildAsync()));
        var controller=future.get().get(15,java.util.concurrent.TimeUnit.SECONDS);controllers.add(controller);return controller;
    }
    androidx.media3.session.MediaController playPodcast(PodcastLibrary library)throws Exception{
        var controller=controller(PodcastPlaybackService.class);var episodes=library.episodes(null,true,0);var selected=episodes.stream().filter(e->e.optString("title").equals("Queue newest")).findFirst().orElseThrow();
        main(()->{controller.setMediaItem(library.media(selected));controller.prepare();controller.play();});awaitPlaying(controller);return controller;
    }
    void awaitPlaying(Player player)throws Exception{helpers.waitFor(()->{boolean[] ready={false};main(()->ready[0]=player.isPlaying());return ready[0];});}
    void connectCar()throws Exception{
        enabled(true);main(()->{car=new MediaBrowser(context,new ComponentName(context,AutoPlaybackService.class),new MediaBrowser.ConnectionCallback(){},null);car.connect();});
        helpers.waitFor(()->{boolean[] ready={false};main(()->ready[0]=car.isConnected());return ready[0];});
    }
    List<MediaBrowser.MediaItem> children(String id)throws Exception{
        AtomicReference<List<MediaBrowser.MediaItem>> items=new AtomicReference<>();main(()->car.subscribe(id,new MediaBrowser.SubscriptionCallback(){@Override public void onChildrenLoaded(String parent,List<MediaBrowser.MediaItem> value){items.set(value);}}));helpers.waitFor(()->items.get()!=null);return items.get();
    }
    void carPlaying(MediaController controller)throws Exception{helpers.waitFor(()->{boolean[] playing={false};main(()->playing[0]=controller.getPlaybackState()!=null&&controller.getPlaybackState().getState()==android.media.session.PlaybackState.STATE_PLAYING);return playing[0];});}
    @Test public void oneEntryBrowsesBothDecksAndUsesRasterBranding()throws Exception{
        fixture();enabled(true);Intent discover=new Intent("android.media.browse.MediaBrowserService").setPackage(context.getPackageName());
        var entries=context.getPackageManager().queryIntentServices(discover,0);assertEquals(1,entries.size());assertEquals(AutoPlaybackService.class.getName(),entries.get(0).serviceInfo.name);
        var icon=entries.get(0).loadIcon(context.getPackageManager());assertTrue(icon instanceof android.graphics.drawable.BitmapDrawable);assertTrue(icon.getIntrinsicWidth()>0);
        connectCar();var root=children("root");assertEquals(2,root.size());assertEquals("Music",root.get(0).getDescription().getTitle());assertEquals("Podcasts",root.get(1).getDescription().getTitle());
        var podcasts=children("podcast/podcasts");assertTrue(podcasts.stream().anyMatch(item->item.getMediaId().equals("podcast/inbox")));
        assertTrue(children("podcast/inbox").stream().anyMatch(MediaBrowser.MediaItem::isPlayable));
    }
    @Test public void carReconnectResumesPodcastPositionAndSpeedAfterServicesRestart()throws Exception{
        var library=fixture();var phone=playPodcast(library);
        main(()->{phone.pause();phone.seekTo(20000);phone.setPlaybackSpeed(1.5f);});helpers.waitFor(()->fixtureHelper.positionNear(phone,20000));assertTrue(LastPlayback.podcasts(context));
        main(phone::release);controllers.remove(phone);context.stopService(new Intent(context,PodcastPlaybackService.class));helpers.waitFor(()->!running(PodcastPlaybackService.class));
        connectCar();final MediaController[] control={null};main(()->{control[0]=new MediaController(context,car.getSessionToken());control[0].getTransportControls().play();});carPlaying(control[0]);
        main(()->{assertEquals("Queue newest",control[0].getMetadata().getString(android.media.MediaMetadata.METADATA_KEY_TITLE));assertTrue(control[0].getPlaybackState().getPosition()>=19000);assertEquals(1.5f,control[0].getPlaybackState().getPlaybackSpeed(),.01f);control[0].getTransportControls().pause();control[0].getTransportControls().seekTo(20000);});Thread.sleep(300);
        main(()->control[0].getTransportControls().skipToNext());helpers.waitFor(()->{boolean[] sought={false};main(()->sought[0]=Math.abs(control[0].getPlaybackState().getPosition()-50000)<1500);return sought[0];});
        main(car::disconnect);connectCar();main(()->{control[0]=new MediaController(context,car.getSessionToken());control[0].getTransportControls().play();});carPlaying(control[0]);main(()->assertEquals("Queue newest",control[0].getMetadata().getString(android.media.MediaMetadata.METADATA_KEY_TITLE)));
    }
    @Test public void phonePlaybackSwitchesCarSourceButBrowsingDoesNot()throws Exception{
        var library=fixture();var podcast=playPodcast(library);connectCar();final MediaController[] control={null};main(()->control[0]=new MediaController(context,car.getSessionToken()));
        helpers.waitFor(()->{boolean[] ready={false};main(()->ready[0]=control[0].getMetadata()!=null&&"Queue newest".equals(control[0].getMetadata().getString(android.media.MediaMetadata.METADATA_KEY_TITLE)));return ready[0];});
        children("music/root");assertTrue(LastPlayback.podcasts(context));
        java.io.File tone=new java.io.File(context.getCacheDir(),"car-test.wav");int samples=44100*30;var wav=java.nio.ByteBuffer.allocate(44+samples*2).order(java.nio.ByteOrder.LITTLE_ENDIAN);
        wav.put("RIFF".getBytes(java.nio.charset.StandardCharsets.US_ASCII)).putInt(36+samples*2).put("WAVEfmt ".getBytes(java.nio.charset.StandardCharsets.US_ASCII)).putInt(16).putShort((short)1).putShort((short)1).putInt(44100).putInt(88200).putShort((short)2).putShort((short)16).put("data".getBytes(java.nio.charset.StandardCharsets.US_ASCII)).putInt(samples*2);
        for(int i=0;i<samples;i++)wav.putShort((short)(2000*Math.sin(i*2*Math.PI*440/44100)));java.nio.file.Files.write(tone.toPath(),wav.array());
        var music=controller(PlaybackService.class);main(()->{music.setMediaItem(new androidx.media3.common.MediaItem.Builder().setMediaId("synthetic-music").setUri(android.net.Uri.fromFile(tone)).setMediaMetadata(new androidx.media3.common.MediaMetadata.Builder().setTitle("Synthetic music").build()).build());music.prepare();music.play();});awaitPlaying(music);
        helpers.waitFor(()->!LastPlayback.podcasts(context));helpers.waitFor(()->{boolean[] ready={false};main(()->ready[0]=control[0].getMetadata()!=null&&"Synthetic music".equals(control[0].getMetadata().getString(android.media.MediaMetadata.METADATA_KEY_TITLE)));return ready[0];});
        children("podcast/podcasts");assertFalse(LastPlayback.podcasts(context));main(music::pause);tone.delete();
    }
    @Test public void selectingCarEpisodeRoutesToPodcastEngine()throws Exception{
        fixture();LastPlayback.record(context,false);connectCar();String id=children("podcast/inbox").stream().filter(MediaBrowser.MediaItem::isPlayable).findFirst().orElseThrow().getMediaId();
        final MediaController[] control={null};main(()->{control[0]=new MediaController(context,car.getSessionToken());control[0].getTransportControls().playFromMediaId(id,null);});carPlaying(control[0]);assertTrue(LastPlayback.podcasts(context));
    }
}

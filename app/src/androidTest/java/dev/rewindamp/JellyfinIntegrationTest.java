package dev.rewindamp;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MediaMetadata;
import androidx.media3.session.MediaController;
import androidx.media3.session.SessionToken;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.Assert.*;

/** Requires an isolated Jellyfin on host port 18096 with only synthetic test audio. */
@RunWith(AndroidJUnit4.class)
public class JellyfinIntegrationTest {
    JellyfinApi login() throws Exception {return JellyfinApi.login("http://10.0.2.2:18096","rewindamp-test","test-only-local-password");}
    @Test public void playlistCrudUsesEntryIdsAndPreservesVisibility() throws Exception {
        JellyfinApi api=login();List<Track> music=api.items("Audio","",null,0);assertEquals(2,music.size());
        String name="Integration "+System.nanoTime();String id=null;
        try {
            api.create(name,List.of(music.get(0),music.get(1)));
            Track playlist=api.items("Playlist",name,null,0).get(0);id=playlist.id;
            List<Track> entries=api.playlistItems(id);assertEquals(2,entries.size());assertNotEquals(entries.get(0).entryId,entries.get(1).entryId);
            api.rename(id,name+" renamed");assertEquals(name+" renamed",api.items("Playlist",name,null,0).get(0).name);
            assertEquals(2,api.playlistItems(id).size());
            assertFalse(api.request("GET","/Playlists/"+id,null).optBoolean("OpenAccess",true));
            api.move(id,entries.get(1),0);assertEquals(entries.get(1).entryId,api.playlistItems(id).get(0).entryId);
            api.remove(id,entries.get(0));entries=api.playlistItems(id);assertEquals(1,entries.size());assertEquals(music.get(1).id,entries.get(0).id);
            api.add(id,music.get(0));assertEquals(2,api.playlistItems(id).size());
            assertEquals(0,api.items("Audio","no-such-signal",null,0).size());
        } finally {if(id!=null)api.request("DELETE","/Items/"+id,null);api.request("POST","/Sessions/Logout",null);}
    }
    @Test public void encryptedSessionRoundTripAndUrlValidation() throws Exception {
        Context context=InstrumentationRegistry.getInstrumentation().getTargetContext();JellyfinApi api=login();
        SessionStore.save(context,api);JellyfinApi restored=SessionStore.load(context);assertNotNull(restored);assertEquals(api.token,restored.token);
        String data=context.getSharedPreferences("session",0).getString("data","");assertFalse(data.contains(api.token));
        assertEquals("https://example.org/jellyfin",JellyfinApi.normalizeServer(" https://example.org/jellyfin/ "));
        for(String url:List.of("file:///etc/passwd","https://user:pass@example.org","https://example.org?api_key=secret","example.org")) {
            try {JellyfinApi.normalizeServer(url);fail("Accepted invalid server URL");} catch(IllegalArgumentException expected) { }
        }
        SessionStore.clear(context);assertNull(SessionStore.load(context));api.request("POST","/Sessions/Logout",null);
    }
    @Test public void streamsFlacAndMp3ThroughBackgroundMediaService() throws Exception {
        var instrumentation=InstrumentationRegistry.getInstrumentation();Context context=instrumentation.getTargetContext();
        JellyfinApi api=login();SessionStore.save(context,api);List<Track> tracks=api.items("Audio","",null,0);
        android.app.Activity activity=instrumentation.startActivitySync(new Intent(context,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        instrumentation.waitForIdleSync();
        AtomicReference<com.google.common.util.concurrent.ListenableFuture<MediaController>> future=new AtomicReference<>();
        instrumentation.runOnMainSync(() -> future.set(new MediaController.Builder(context,new SessionToken(context,new ComponentName(context,PlaybackService.class))).buildAsync()));
        MediaController controller=future.get().get(15,TimeUnit.SECONDS);
        try {
            for(Track track:tracks) {
                instrumentation.runOnMainSync(() -> {controller.setMediaItem(new MediaItem.Builder().setMediaId(track.id).setUri(api.stream(track)).setMediaMetadata(new MediaMetadata.Builder().setTitle(track.name).setArtist(track.artist).build()).build());controller.prepare();controller.play();});
                boolean playing=false;
                for(int i=0;i<100;i++) {Thread.sleep(100);AtomicReference<Boolean> ok=new AtomicReference<>(false);instrumentation.runOnMainSync(() -> ok.set(controller.isPlaying()&&controller.getCurrentPosition()>300));if(ok.get()){playing=true;break;}}
                assertTrue("Track should stream through foreground service",playing);
                assertTrue("Spectrum processor must receive decoded playback audio",AudioDsp.sampleRate>0&&AudioDsp.channels>0);
                instrumentation.runOnMainSync(() -> controller.seekTo(10000));Thread.sleep(400);
                AtomicReference<Long> position=new AtomicReference<>(0L);instrumentation.runOnMainSync(() -> position.set(controller.getCurrentPosition()));assertTrue(position.get()>=10000);
            }
            Track flac=tracks.stream().filter(t -> t.name.contains("One")).findFirst().get();
            String transcoded=api.stream(flac).replaceAll("Container=[^&]*&AudioCodec", "Container=mp3&AudioCodec");
            instrumentation.runOnMainSync(() -> {controller.setMediaItem(new MediaItem.Builder().setMediaId(flac.id).setUri(transcoded).build());controller.prepare();controller.play();});
            boolean transcoding=false;
            for(int i=0;i<150;i++){Thread.sleep(100);AtomicReference<Boolean> ok=new AtomicReference<>(false);instrumentation.runOnMainSync(() -> ok.set(controller.isPlaying()&&controller.getCurrentPosition()>300));if(ok.get()){transcoding=true;break;}}
            assertTrue("Server transcoding fallback must play",transcoding);
            AtomicReference<Long> before=new AtomicReference<>(0L);instrumentation.runOnMainSync(() -> before.set(controller.getCurrentPosition()));
            instrumentation.runOnMainSync(() -> activity.moveTaskToBack(true));Thread.sleep(1000);
            AtomicReference<Boolean> background=new AtomicReference<>(false);instrumentation.runOnMainSync(() -> background.set(controller.isPlaying()&&controller.getCurrentPosition()>before.get()+500));
            assertTrue("Audio must continue with the activity in the background",background.get());
        } finally {
            instrumentation.runOnMainSync(() -> {controller.stop();controller.clearMediaItems();controller.release();activity.finish();context.stopService(new Intent(context,PlaybackService.class));});
            SessionStore.clear(context);api.request("POST","/Sessions/Logout",null);
        }
    }
}

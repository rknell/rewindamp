package dev.jellyamp;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import androidx.media3.common.AudioAttributes;
import androidx.media3.common.C;
import androidx.media3.common.Player;
import androidx.media3.common.audio.AudioProcessor;
import androidx.media3.exoplayer.DefaultRenderersFactory;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.audio.AudioSink;
import androidx.media3.exoplayer.audio.DefaultAudioSink;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;
import androidx.media3.session.MediaLibraryService;
import androidx.media3.session.MediaSession;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

@androidx.annotation.OptIn(markerClass=androidx.media3.common.util.UnstableApi.class)
public final class PlaybackService extends MediaLibraryService {
    private MediaLibrarySession session;
    private CarLibrary library;
    private OfflineLibrary offline;
    private JellyfinApi api;
    private final ExecutorService art=Executors.newSingleThreadExecutor();
    private PlaybackReporter reporter;
    private LibraryStore store;
    private volatile ExoPlayer player;
    private PlaybackTransitions transitions;
    private final AudioAttributes attributes=new AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build();
    private android.net.ConnectivityManager connectivity;
    private android.net.ConnectivityManager.NetworkCallback networkCallback;
    private androidx.media3.exoplayer.offline.DownloadManager.Listener downloadListener;
    private final ExecutorService disk=Executors.newSingleThreadExecutor(),network=Executors.newSingleThreadExecutor();
    private final AtomicBoolean syncing=new AtomicBoolean(false);
    private final Handler handler=new Handler(Looper.getMainLooper());
    private final Runnable ticker=new Runnable(){@Override public void run(){persist();report();handler.postDelayed(this,3000);}};
    @Override public void onCreate(){
        super.onCreate();api=SessionStore.load(this);DspSettings.load(this);
        offline=api==null?null:OfflineLibrary.get(this,api);
        player=createPlayer();
        player.setAudioAttributes(attributes,true);
        if(offline!=null){store=offline.store;reporter=new PlaybackReporter(api,store);QueueStore.Snapshot snapshot=QueueStore.load(store);
            if(!snapshot.tracks().isEmpty()){var media=new ArrayList<androidx.media3.common.MediaItem>();for(Track t:snapshot.tracks())media.add(offline.media(t));player.setMediaItems(media,snapshot.index(),snapshot.position());}
            player.setShuffleModeEnabled(snapshot.shuffle());player.setRepeatMode(snapshot.repeat());player.setVolume(snapshot.volume());
        }
        library=new CarLibrary(api,offline);
        var pending=PendingIntent.getActivity(this,0,new Intent(this,MainActivity.class),PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);
        session=new MediaLibrarySession.Builder(this,player,library).setSessionActivity(pending).build();
        transitions=new PlaybackTransitions(this,new PlaybackTransitions.Host(){
            public ExoPlayer current(){return player;}
            public ExoPlayer create(){return createPlayer();}
            public void promote(ExoPlayer next){
                if(reporter!=null){reporter.update(player);reporter.transition();}player=next;session.setPlayer(next);AudioDsp.playing=next.isPlaying();fetchArtwork(next.getCurrentMediaItem());persist();report();
            }
            public void blending(boolean active){if(session!=null){var extras=new android.os.Bundle(session.getSessionExtras());extras.putBoolean("crossfading",active);session.setSessionExtras(extras);}}
            public void volume(float value){if(session!=null){var extras=new android.os.Bundle(session.getSessionExtras());extras.putFloat("logical_volume",value);session.setSessionExtras(extras);}}
        },handler,attributes);
        library.commandListener=command->transitions.command(command);
        if(offline!=null){
            downloadListener=new androidx.media3.exoplayer.offline.DownloadManager.Listener(){
                @Override public void onDownloadChanged(androidx.media3.exoplayer.offline.DownloadManager manager,androidx.media3.exoplayer.offline.Download download,Exception error){if(download.state==androidx.media3.exoplayer.offline.Download.STATE_COMPLETED)refreshOffline(false);}
            };offline.manager.addListener(downloadListener);
            connectivity=(android.net.ConnectivityManager)getSystemService(CONNECTIVITY_SERVICE);
            networkCallback=new android.net.ConnectivityManager.NetworkCallback(){@Override public void onLost(android.net.Network network){handler.post(()->refreshOffline(true));}};
            connectivity.registerDefaultNetworkCallback(networkCallback);
        }
        handler.post(ticker);
    }
    private ExoPlayer createPlayer(){
        var owner=new java.util.concurrent.atomic.AtomicReference<ExoPlayer>();AudioDsp dsp=new AudioDsp(()->owner.get()==player);
        DefaultRenderersFactory renderers=new DefaultRenderersFactory(this){
            @Override protected AudioSink buildAudioSink(Context context,boolean floats,boolean params){return new DefaultAudioSink.Builder(context).setAudioProcessors(new AudioProcessor[]{dsp}).setEnableFloatOutput(false).build();}
        };
        var builder=new ExoPlayer.Builder(this,renderers);if(offline!=null)builder.setMediaSourceFactory(new DefaultMediaSourceFactory(this).setDataSourceFactory(offline.playbackSource()));
        ExoPlayer created=builder.build();owner.set(created);created.setAudioAttributes(attributes,false);created.setHandleAudioBecomingNoisy(true);created.setWakeMode(C.WAKE_MODE_NETWORK);
        created.addListener(new Player.Listener(){
            @Override public void onEvents(Player p,Player.Events events){
                if(p!=player)return;if(transitions!=null)transitions.onEvents(created,events);AudioDsp.playing=p.isPlaying();
                if(events.size()==1&&events.contains(Player.EVENT_VOLUME_CHANGED))return;persist();if(reporter!=null){reporter.update(p);sync();}
            }
            @Override public void onVolumeChanged(float volume){if(transitions!=null)transitions.onVolume(created,volume);}
            @Override public void onPlayWhenReadyChanged(boolean ready,int reason){if(transitions!=null)transitions.onPlayWhenReady(created,ready,reason);}
            @Override public void onMediaItemTransition(androidx.media3.common.MediaItem item,int reason){if(created!=player)return;if(reporter!=null)reporter.transition();fetchArtwork(item);}
            @Override public void onPlayerError(androidx.media3.common.PlaybackException error){
                if(created!=player||offline==null||player.getCurrentMediaItem()==null)return;var current=player.getCurrentMediaItem();boolean local=current.mediaMetadata.extras!=null&&current.mediaMetadata.extras.getBoolean("offline");
                if(!local&&offline.complete(current.mediaId)){int index=player.getCurrentMediaItemIndex();long position=player.getCurrentPosition();player.replaceMediaItem(index,offline.media(Track.fromMedia(current)));player.seekTo(index,position);player.prepare();player.play();}
            }
            @Override public void onPositionDiscontinuity(Player.PositionInfo oldPosition,Player.PositionInfo newPosition,int reason){if(created==player){if(transitions!=null)transitions.onSeek(created,reason);persist();}}
        });return created;
    }
    private void refreshOffline(boolean includeCurrent){
        if(offline==null||session==null)return;
        for(int i=0;i<player.getMediaItemCount();i++){
            if(!includeCurrent&&i==player.getCurrentMediaItemIndex())continue;var media=player.getMediaItemAt(i);
            boolean local=media.mediaMetadata.extras!=null&&media.mediaMetadata.extras.getBoolean("offline");
            if(!local&&offline.complete(media.mediaId))player.replaceMediaItem(i,offline.media(Track.fromMedia(media)));
        }
    }
    private void fetchArtwork(androidx.media3.common.MediaItem media){
        if(media==null||store==null||api==null)return;Track track=Track.fromMedia(media);if(track.imageId.isBlank())return;
        art.execute(()->{try{byte[] bytes=store.artwork(track);if(bytes.length==0){bytes=api.image(track);store.artwork(track,bytes);}byte[] image=bytes;if(image.length==0)return;
            handler.post(()->{if(session==null||player.getCurrentMediaItem()==null||!player.getCurrentMediaItem().mediaId.equals(track.id))return;
                var item=player.getCurrentMediaItem();if(item.mediaMetadata.artworkData==null)player.replaceMediaItem(player.getCurrentMediaItemIndex(),item.buildUpon().setMediaMetadata(item.mediaMetadata.buildUpon().setArtworkData(image,androidx.media3.common.MediaMetadata.PICTURE_TYPE_FRONT_COVER).build()).build());});
        }catch(Exception ignored){}});
    }
    private void persist(){if(store==null||player==null||disk.isShutdown())return;JSONObject snapshot=QueueStore.capture(player);try{if(transitions!=null)snapshot.put("volume",transitions.volume());}catch(org.json.JSONException ignored){}disk.execute(()->store.write("queue",snapshot));}
    private void report(){if(reporter!=null){reporter.update(player);sync();}}
    private void sync(){if(reporter!=null&&syncing.compareAndSet(false,true))network.execute(()->{try{reporter.sync();}finally{syncing.set(false);}});}
    @Override public MediaLibrarySession onGetSession(MediaSession.ControllerInfo controller){return session;}
    @Override public void onDestroy(){
        if(transitions!=null)transitions.close();handler.removeCallbacksAndMessages(null);if(connectivity!=null&&networkCallback!=null)connectivity.unregisterNetworkCallback(networkCallback);if(offline!=null&&downloadListener!=null)offline.manager.removeListener(downloadListener);persist();AudioDsp.playing=false;
        if(reporter!=null){reporter.stop();sync();}
        if(session!=null){session.getPlayer().release();session.release();session=null;}if(library!=null)library.close();disk.shutdown();network.shutdown();art.shutdownNow();super.onDestroy();
    }
}

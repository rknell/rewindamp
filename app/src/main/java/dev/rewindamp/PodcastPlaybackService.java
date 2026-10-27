package dev.rewindamp;

import android.os.Handler;
import android.os.Looper;
import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.Player;
import androidx.media3.common.AudioAttributes;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;
import androidx.media3.session.MediaLibraryService;
import androidx.media3.session.MediaSession;
import androidx.media3.session.LibraryResult;
import com.google.common.collect.ImmutableList;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.MoreExecutors;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;

/** Spoken audio session: independent episode history and rewind/forward commands, no crossfade. */
@androidx.annotation.OptIn(markerClass=androidx.media3.common.util.UnstableApi.class)
public final class PodcastPlaybackService extends MediaLibraryService {
    private MediaLibrarySession session;
    private ExoPlayer player;
    private PodcastLibrary library;
    private String active="";
    private long position;
    private final Handler handler=new Handler(Looper.getMainLooper());
    private final com.google.common.util.concurrent.ListeningExecutorService network=MoreExecutors.listeningDecorator(Executors.newSingleThreadExecutor());
    private final Runnable tick=new Runnable(){@Override public void run(){checkpoint();sync();handler.postDelayed(this,3000);}};
    @Override public void onCreate(){
        super.onCreate();PodcastApi api=PodcastApi.load(this);if(api!=null)library=PodcastLibrary.get(this,api);
        var builder=new ExoPlayer.Builder(this).setSeekBackIncrementMs(15000).setSeekForwardIncrementMs(30000);
        if(library!=null)builder.setMediaSourceFactory(new DefaultMediaSourceFactory(this).setDataSourceFactory(library.source()));
        player=builder.build();player.setAudioAttributes(new AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_SPEECH).build(),true);player.setHandleAudioBecomingNoisy(true);player.setWakeMode(C.WAKE_MODE_NETWORK);
        if(library!=null){JSONObject saved=library.store.read("podcast-now");String id=saved.optString("id");JSONObject episode=library.episode(id);if(episode.has("id")){player.setMediaItem(library.media(episode),(long)(episode.optDouble("position")*1000));active=id;position=player.getCurrentPosition();}}
        player.addListener(new Player.Listener(){
            @Override public void onEvents(Player p,Player.Events events){checkpoint();}
            @Override public void onPositionDiscontinuity(Player.PositionInfo old,Player.PositionInfo next,int reason){if(library!=null&&old.mediaItem!=null&&!old.mediaItem.mediaId.equals(next.mediaItem==null?"":next.mediaItem.mediaId))library.event(old.mediaItem.mediaId,old.positionMs,"progress");checkpoint();}
            @Override public void onPlaybackStateChanged(int state){if(state==Player.STATE_ENDED&&library!=null&&!active.isBlank()){position=player.getCurrentPosition();library.event(active,position,"played");sync();}}
        });
        var intent=android.app.PendingIntent.getActivity(this,3,new android.content.Intent(this,PodcastActivity.class),android.app.PendingIntent.FLAG_IMMUTABLE);
        session=new MediaLibrarySession.Builder(this,player,new Browser()).setId("rewindamp-podcasts").setSessionActivity(intent).build();handler.post(tick);
    }
    private void checkpoint(){
        if(library==null||player==null)return;
        String id=player.getCurrentMediaItem()==null?"":player.getCurrentMediaItem().mediaId;
        if(!id.equals(active)&&!active.isBlank())library.event(active,position,"progress");
        active=id;position=Math.max(0,player.getCurrentPosition());
        if(!id.isBlank()){library.event(id,position,"progress");try{library.store.write("podcast-now",new JSONObject().put("id",id));}catch(Exception ignored){}}
    }
    private void sync(){if(library!=null&&!network.isShutdown())network.execute(()->library.sync());}
    @Override public MediaLibrarySession onGetSession(MediaSession.ControllerInfo controller){return session;}
    @Override public void onDestroy(){handler.removeCallbacksAndMessages(null);checkpoint();sync();if(session!=null)session.release();if(player!=null)player.release();network.shutdown();super.onDestroy();}
    final class Browser implements MediaLibrarySession.Callback {
        @Override public MediaSession.ConnectionResult onConnect(MediaSession s,MediaSession.ControllerInfo c){
            if(c.getUid()!=android.os.Process.myUid()&&!c.isTrusted())return MediaSession.ConnectionResult.reject();
            var commands=MediaSession.ConnectionResult.DEFAULT_PLAYER_COMMANDS.buildUpon().remove(Player.COMMAND_SEEK_TO_NEXT).remove(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM).remove(Player.COMMAND_SEEK_TO_PREVIOUS).remove(Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM).remove(Player.COMMAND_SET_SHUFFLE_MODE).remove(Player.COMMAND_SET_REPEAT_MODE).add(Player.COMMAND_SEEK_BACK).add(Player.COMMAND_SEEK_FORWARD).build();
            return new MediaSession.ConnectionResult.AcceptedResultBuilder(s).setAvailablePlayerCommands(commands).setAvailableSessionCommands(MediaSession.ConnectionResult.DEFAULT_SESSION_AND_LIBRARY_COMMANDS).build();
        }
        @Override public ListenableFuture<LibraryResult<MediaItem>> onGetLibraryRoot(MediaLibrarySession s,MediaSession.ControllerInfo c,LibraryParams p){return Futures.immediateFuture(LibraryResult.ofItem(CarLibrary.folder("podcasts","Rewindamp Podcasts"),p));}
        @Override public ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> onGetChildren(MediaLibrarySession s,MediaSession.ControllerInfo c,String id,int page,int size,LibraryParams p){
            return network.submit(()->{try{if(library==null)return LibraryResult.ofItemList(List.of(CarLibrary.folder("signin","Connect podcast server on phone")),p);
                List<MediaItem> result=new ArrayList<>();if(id.equals("podcasts")){result.add(CarLibrary.folder("inbox","Unplayed episodes"));result.add(CarLibrary.folder("phone","Phone downloads"));var feeds=library.feeds();for(int i=0;i<feeds.length();i++){var f=feeds.getJSONObject(i);result.add(CarLibrary.folder("feed:"+f.getString("id"),f.getString("title")));}}
                else{List<JSONObject> episodes=id.equals("phone")?library.downloaded():library.episodes(id.startsWith("feed:")?id.substring(5):null,true,Math.max(0,page)*Math.max(1,Math.min(100,size)));for(JSONObject e:episodes)if(e.optBoolean("downloaded")||library.complete(e.optString("id")))result.add(library.media(e));}
                int start=(id.equals("podcasts")||id.equals("phone"))?Math.min(result.size(),Math.max(0,page)*Math.max(1,Math.min(100,size))):0;return LibraryResult.ofItemList(result.subList(start,Math.min(result.size(),start+Math.max(1,Math.min(100,size)))),p);
            }catch(Exception e){return LibraryResult.ofError(androidx.media3.session.SessionError.ERROR_IO);}});
        }
        @Override public ListenableFuture<List<MediaItem>> onAddMediaItems(MediaSession s,MediaSession.ControllerInfo c,List<MediaItem> items){
            if(library==null)return Futures.immediateFuture(List.of());
            List<MediaItem> safe=new ArrayList<>();for(MediaItem item:items){JSONObject episode=library.episode(item.mediaId);if(episode.has("id"))safe.add(library.media(episode));}return Futures.immediateFuture(safe);
        }
        @Override public ListenableFuture<MediaSession.MediaItemsWithStartPosition> onSetMediaItems(MediaSession s,MediaSession.ControllerInfo c,List<MediaItem> items,int index,long start){
            return Futures.transform(onAddMediaItems(s,c,items),safe->{long position=start;if(!safe.isEmpty()&&library!=null){JSONObject e=library.episode(safe.get(Math.max(0,Math.min(index,safe.size()-1))).mediaId);if(start==C.TIME_UNSET)position=(long)(e.optDouble("position")*1000);}return new MediaSession.MediaItemsWithStartPosition(safe,index,position);},com.google.common.util.concurrent.MoreExecutors.directExecutor());
        }
    }
}

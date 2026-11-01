package dev.rewindamp;

import android.app.PendingIntent;
import android.content.ComponentName;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import androidx.media3.common.ForwardingSimpleBasePlayer;
import androidx.media3.common.MediaItem;
import androidx.media3.common.Player;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.session.*;
import com.google.common.collect.ImmutableList;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;

/** One car entry, backed by the same two private playback engines used by the phone. */
@androidx.annotation.OptIn(markerClass=androidx.media3.common.util.UnstableApi.class)
public final class AutoPlaybackService extends MediaLibraryService {
    private MediaLibrarySession session;
    private ExoPlayer idle;
    private CarPlayer player;
    private MediaBrowser music,podcasts;
    private boolean spoken,destroyed;
    private ListenableFuture<MediaBrowser> musicFuture,podcastFuture;
    private ListenableFuture<List<MediaBrowser>> ready;
    private final Executor main=command->new Handler(Looper.getMainLooper()).post(command);
    private final Player.Listener musicListener=new Player.Listener(){@Override public void onIsPlayingChanged(boolean playing){if(playing)select(false);}};
    private final Player.Listener podcastListener=new Player.Listener(){@Override public void onIsPlayingChanged(boolean playing){if(playing)select(true);}};

    @Override public void onCreate(){
        super.onCreate();spoken=LastPlayback.podcasts(this);idle=new ExoPlayer.Builder(this).build();player=new CarPlayer();
        session=new MediaLibrarySession.Builder(this,player,new Browser()).setId("rewindamp-auto").setSessionActivity(activity()).build();
        musicFuture=new MediaBrowser.Builder(this,new SessionToken(this,new ComponentName(this,PlaybackService.class))).setListener(new MediaBrowser.Listener(){
            @Override public void onSearchResultChanged(MediaBrowser browser,String query,int count,LibraryParams params){
                if(session!=null)for(MediaSession.ControllerInfo controller:session.getConnectedControllers())session.notifySearchResultChanged(controller,query,count,params);
            }
        }).buildAsync();
        podcastFuture=new MediaBrowser.Builder(this,new SessionToken(this,new ComponentName(this,PodcastPlaybackService.class))).buildAsync();
        ready=Futures.allAsList(musicFuture,podcastFuture);
        ready.addListener(()->{if(destroyed)return;try{
            List<MediaBrowser> browsers=ready.get();music=browsers.get(0);podcasts=browsers.get(1);
            music.addListener(musicListener);podcasts.addListener(podcastListener);
            select(podcasts.isPlaying()||(!music.isPlaying()&&LastPlayback.podcasts(this)));
        }catch(Exception ignored){stopSelf();}},main);
    }
    private PendingIntent activity(){return PendingIntent.getActivity(this,4,new Intent(this,spoken?PodcastActivity.class:MainActivity.class),PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);}
    private void select(boolean podcast){
        if(music==null||podcasts==null)return;
        spoken=podcast;player.use(podcast?podcasts:music);session.setSessionActivity(activity());
    }
    private MediaBrowser browser(boolean podcast){return podcast?podcasts:music;}
    private static boolean podcastId(String id){return id.startsWith("podcast/");}
    private static String raw(String id){return id.startsWith("podcast/")?id.substring(8):id.startsWith("music/")?id.substring(6):id;}
    private static MediaItem tagged(MediaItem item,boolean podcast){return item.buildUpon().setMediaId((podcast?"podcast/":"music/")+item.mediaId).build();}
    private List<MediaItem> safe(List<MediaItem> items){
        List<MediaItem> result=new ArrayList<>();for(MediaItem item:items)result.add(new MediaItem.Builder().setMediaId(raw(item.mediaId)).setRequestMetadata(item.requestMetadata).build());return result;
    }
    @Override public MediaLibrarySession onGetSession(MediaSession.ControllerInfo controller){return session;}
    @Override public void onDestroy(){
        destroyed=true;if(music!=null)music.removeListener(musicListener);if(podcasts!=null)podcasts.removeListener(podcastListener);
        if(session!=null)session.release();if(musicFuture!=null)MediaController.releaseFuture(musicFuture);if(podcastFuture!=null)MediaController.releaseFuture(podcastFuture);if(idle!=null)idle.release();super.onDestroy();
    }
    private final class CarPlayer extends ForwardingSimpleBasePlayer {
        boolean resuming;
        CarPlayer(){super(idle);}
        void use(Player source){setPlayer(source);}
        @Override protected State getState(){
            State state=super.getState();
            // Remote controller timelines omit period UIDs. Give each audio window a stable
            // local identity before SimpleBasePlayer evaluates seek/discontinuity events.
            List<MediaItemData> items=new ArrayList<>();var window=new androidx.media3.common.Timeline.Window();
            for(int i=0;i<state.timeline.getWindowCount();i++){
                state.timeline.getWindow(i,window);
                items.add(new MediaItemData.Builder((spoken?"podcast/":"music/")+i+"/"+window.mediaItem.mediaId)
                    .setMediaItem(tagged(window.mediaItem,spoken)).setMediaMetadata(i==state.currentMediaItemIndex?state.currentMetadata:window.mediaItem.mediaMetadata)
                    .setDurationUs(window.durationUs).setDefaultPositionUs(Math.max(0,window.defaultPositionUs))
                    .setIsSeekable(window.isSeekable).setIsPlaceholder(window.isPlaceholder).build());
            }
            var builder=state.buildUpon().setPlaylist(items);
            if(spoken)builder.setAvailableCommands(state.availableCommands.buildUpon().addAll(Player.COMMAND_SEEK_TO_NEXT,Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,Player.COMMAND_SEEK_TO_PREVIOUS,Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM).build());
            return builder.build();
        }
        @Override protected ListenableFuture<?> handleSetMediaItems(List<MediaItem> items,int index,long position){
            if(resuming){resuming=false;if(getPlayer().getMediaItemCount()>0)getPlayer().seekTo(index,position);return Futures.immediateVoidFuture();}
            return super.handleSetMediaItems(items,index,position);
        }
        @Override protected ListenableFuture<?> handleSeek(int index,long position,int command){
            if(spoken){
                if(command==Player.COMMAND_SEEK_TO_NEXT||command==Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM){getPlayer().seekForward();return Futures.immediateVoidFuture();}
                if(command==Player.COMMAND_SEEK_TO_PREVIOUS||command==Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM){getPlayer().seekBack();return Futures.immediateVoidFuture();}
            }return super.handleSeek(index,position,command);
        }
    }
    private final class Browser implements MediaLibrarySession.Callback {
        @Override public MediaSession.ConnectionResult onConnect(MediaSession s,MediaSession.ControllerInfo c){
            if(c.getUid()!=android.os.Process.myUid()&&!c.isTrusted())return MediaSession.ConnectionResult.reject();
            return new MediaSession.ConnectionResult.AcceptedResultBuilder(s).setAvailableSessionCommands(MediaSession.ConnectionResult.DEFAULT_SESSION_AND_LIBRARY_COMMANDS).build();
        }
        @Override public boolean onMediaButtonEvent(MediaSession s,MediaSession.ControllerInfo c,Intent intent){
            if(!spoken)return false;android.view.KeyEvent event=intent.getParcelableExtra(Intent.EXTRA_KEY_EVENT);if(event==null)return false;
            boolean back=event.getKeyCode()==android.view.KeyEvent.KEYCODE_MEDIA_PREVIOUS||event.getKeyCode()==android.view.KeyEvent.KEYCODE_MEDIA_REWIND;
            boolean forward=event.getKeyCode()==android.view.KeyEvent.KEYCODE_MEDIA_NEXT||event.getKeyCode()==android.view.KeyEvent.KEYCODE_MEDIA_FAST_FORWARD;
            if(!back&&!forward)return false;if(event.getAction()==android.view.KeyEvent.ACTION_DOWN&&event.getRepeatCount()==0){if(back)player.seekBack();else player.seekForward();}return true;
        }
        @Override public ListenableFuture<LibraryResult<MediaItem>> onGetLibraryRoot(MediaLibrarySession s,MediaSession.ControllerInfo c,LibraryParams p){return Futures.immediateFuture(LibraryResult.ofItem(CarLibrary.folder("root","Rewindamp"),p));}
        @Override public ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> onGetChildren(MediaLibrarySession s,MediaSession.ControllerInfo c,String id,int page,int size,LibraryParams p){
            if(id.equals("root"))return Futures.immediateFuture(LibraryResult.ofItemList(List.of(CarLibrary.folder("music/root","Music"),CarLibrary.folder("podcast/podcasts","Podcasts")),p));
            boolean podcast=podcastId(id);
            return Futures.transformAsync(ready,ignored->Futures.transform(browser(podcast).getChildren(raw(id),page,size,p),result->{
                if(result.value==null)return LibraryResult.ofError(result.resultCode);
                List<MediaItem> items=new ArrayList<>();for(MediaItem item:result.value)items.add(tagged(item,podcast));return LibraryResult.ofItemList(items,p);
            },main),main);
        }
        @Override public ListenableFuture<LibraryResult<MediaItem>> onGetItem(MediaLibrarySession s,MediaSession.ControllerInfo c,String id){
            if(id.equals("root"))return Futures.immediateFuture(LibraryResult.ofItem(CarLibrary.folder("root","Rewindamp"),null));
            if(id.equals("music/root"))return Futures.immediateFuture(LibraryResult.ofItem(CarLibrary.folder(id,"Music"),null));
            if(id.equals("podcast/podcasts"))return Futures.immediateFuture(LibraryResult.ofItem(CarLibrary.folder(id,"Podcasts"),null));
            boolean podcast=podcastId(id);return Futures.transformAsync(ready,ignored->Futures.transform(browser(podcast).getItem(raw(id)),result->result.value==null?LibraryResult.ofError(result.resultCode):LibraryResult.ofItem(tagged(result.value,podcast),null),main),main);
        }
        @Override public ListenableFuture<List<MediaItem>> onAddMediaItems(MediaSession s,MediaSession.ControllerInfo c,List<MediaItem> items){
            return Futures.transform(ready,ignored->{if(!items.isEmpty())select(podcastId(items.get(0).mediaId));return safe(items);},main);
        }
        @Override public ListenableFuture<MediaSession.MediaItemsWithStartPosition> onSetMediaItems(MediaSession s,MediaSession.ControllerInfo c,List<MediaItem> items,int index,long position){
            return Futures.transform(onAddMediaItems(s,c,items),resolved->new MediaSession.MediaItemsWithStartPosition(resolved,index,position),main);
        }
        @Override public ListenableFuture<MediaSession.MediaItemsWithStartPosition> onPlaybackResumption(MediaSession s,MediaSession.ControllerInfo c){
            return Futures.transform(ready,ignored->{select(LastPlayback.podcasts(AutoPlaybackService.this));MediaBrowser source=browser(spoken);List<MediaItem> items=new ArrayList<>();for(int i=0;i<source.getMediaItemCount();i++)items.add(source.getMediaItemAt(i));player.resuming=!items.isEmpty();return new MediaSession.MediaItemsWithStartPosition(items,Math.max(0,source.getCurrentMediaItemIndex()),Math.max(0,source.getCurrentPosition()));},main);
        }
        @Override public ListenableFuture<LibraryResult<Void>> onSearch(MediaLibrarySession s,MediaSession.ControllerInfo c,String query,LibraryParams p){
            return Futures.transformAsync(ready,ignored->music.search(query,p),main);
        }
        @Override public ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> onGetSearchResult(MediaLibrarySession s,MediaSession.ControllerInfo c,String query,int page,int size,LibraryParams p){
            return Futures.transformAsync(ready,ignored->Futures.transform(music.getSearchResult(query,page,size,p),result->{if(result.value==null)return LibraryResult.ofError(result.resultCode);List<MediaItem> items=new ArrayList<>();for(MediaItem item:result.value)items.add(tagged(item,false));return LibraryResult.ofItemList(items,p);},main),main);
        }
    }
}

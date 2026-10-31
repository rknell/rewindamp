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
import org.json.JSONArray;
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
    private boolean skippingCompleted;
    private String offlinePlan="";
    private int ticks;
    private final Handler handler=new Handler(Looper.getMainLooper());
    private final com.google.common.util.concurrent.ListeningExecutorService network=MoreExecutors.listeningDecorator(Executors.newSingleThreadExecutor());
    private final Runnable tick=new Runnable(){@Override public void run(){checkSleep();if(++ticks%3==0){checkpoint();sync();maintainOffline();}handler.postDelayed(this,1000);}};
    @Override public void onCreate(){
        super.onCreate();PodcastApi api=PodcastApi.load(this);if(api!=null)library=PodcastLibrary.get(this,api);
        var builder=new ExoPlayer.Builder(this).setSeekBackIncrementMs(15000).setSeekForwardIncrementMs(30000);
        if(library!=null)builder.setMediaSourceFactory(new DefaultMediaSourceFactory(this).setDataSourceFactory(library.source()));
        player=builder.build();player.setAudioAttributes(new AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_SPEECH).build(),true);player.setHandleAudioBecomingNoisy(true);player.setWakeMode(C.WAKE_MODE_NETWORK);
        if(library!=null){
            JSONObject saved=library.store.read("podcast-now");String id=saved.optString("id");JSONArray ids=saved.optJSONArray("queue");List<MediaItem> restored=new ArrayList<>();int index=0;
            if(ids!=null)for(int i=0;i<ids.length();i++){JSONObject e=library.episode(ids.optString(i));if(e.has("id")){if(e.optString("id").equals(id))index=restored.size();restored.add(library.media(e));}}
            if(restored.isEmpty()){JSONObject e=library.episode(id);if(e.has("id"))restored.add(library.media(e));}
            if(!restored.isEmpty()){JSONObject e=library.episode(restored.get(index).mediaId);player.setMediaItems(restored,index,(long)(e.optDouble("position")*1000));active=restored.get(index).mediaId;position=player.getCurrentPosition();}
            player.setPlaybackSpeed((float)saved.optDouble("speed",1));
        }
        player.addListener(new Player.Listener(){
            @Override public void onEvents(Player p,Player.Events events){checkpoint();if(session!=null&&events.contains(Player.EVENT_AVAILABLE_COMMANDS_CHANGED))for(MediaSession.ControllerInfo controller:session.getConnectedControllers())session.setAvailableCommands(controller,MediaSession.ConnectionResult.DEFAULT_SESSION_AND_LIBRARY_COMMANDS,controllerCommands(controller));}
            @Override public void onPositionDiscontinuity(Player.PositionInfo old,Player.PositionInfo next,int reason){if(library!=null&&old.mediaItem!=null&&!old.mediaItem.mediaId.equals(next.mediaItem==null?"":next.mediaItem.mediaId)){
                    library.event(old.mediaItem.mediaId,old.positionMs,reason==Player.DISCONTINUITY_REASON_AUTO_TRANSITION?"played":"progress");
                    if(next.mediaItem!=null){JSONObject episode=library.episode(next.mediaItem.mediaId);long resume=episode.optString("state").equals("played")?0:(long)(episode.optDouble("position")*1000);if(resume>0&&next.positionMs==0)player.seekTo(resume);}
                }checkpoint();}
            @Override public void onMediaItemTransition(MediaItem item,int reason){
                if(library==null||item==null)return;
                if((reason==Player.MEDIA_ITEM_TRANSITION_REASON_AUTO||skippingCompleted)&&!library.showPlayed()&&List.of("played","dismissed").contains(library.episode(item.mediaId).optString("state"))){
                    skippingCompleted=true;if(player.hasNextMediaItem())player.seekToNextMediaItem();else{skippingCompleted=false;player.pause();}
                }else skippingCompleted=false;
            }
            @Override public void onPlayWhenReadyChanged(boolean ready,int reason){
                if(library!=null&&!ready&&reason==Player.PLAY_WHEN_READY_CHANGE_REASON_END_OF_MEDIA_ITEM&&library.store.read("podcast-sleep").optBoolean("end")){
                    if(player.getCurrentMediaItem()!=null)library.event(player.getCurrentMediaItem().mediaId,player.getCurrentPosition(),"played");
                    library.clearSleep();player.setPauseAtEndOfMediaItems(false);sync();
                }
            }
            @Override public void onPlaybackStateChanged(int state){if(state==Player.STATE_ENDED&&library!=null&&!active.isBlank()){position=player.getCurrentPosition();library.event(active,position,"played");sync();}}
        });
        var intent=android.app.PendingIntent.getActivity(this,3,new android.content.Intent(this,PodcastActivity.class),android.app.PendingIntent.FLAG_IMMUTABLE);
        session=new MediaLibrarySession.Builder(this,new PodcastSessionPlayer(),new Browser()).setId("rewindamp-podcasts").setSessionActivity(intent).build();handler.post(tick);
    }
    /** AVRCP transports use next/previous even for spoken audio. Keep episode skips in our UI. */
    private final class PodcastSessionPlayer extends androidx.media3.common.ForwardingSimpleBasePlayer {
        PodcastSessionPlayer(){super(player);}
        private boolean remote(){MediaSession.ControllerInfo controller=session==null?null:session.getControllerForCurrentRequest();return controller!=null&&(controller.getControllerVersion()==MediaSession.ControllerInfo.LEGACY_CONTROLLER_VERSION||controller.getUid()!=android.os.Process.myUid());}
        @Override protected State getState(){State state=super.getState();return state.buildUpon().setAvailableCommands(state.availableCommands.buildUpon().addAll(Player.COMMAND_SEEK_TO_NEXT,Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,Player.COMMAND_SEEK_TO_PREVIOUS,Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM).build()).build();}
        @Override protected ListenableFuture<?> handleSeek(int index,long position,int command){
            if(remote()){
                if(command==Player.COMMAND_SEEK_TO_NEXT||command==Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM){player.seekForward();return Futures.immediateVoidFuture();}
                if(command==Player.COMMAND_SEEK_TO_PREVIOUS||command==Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM){player.seekBack();return Futures.immediateVoidFuture();}
            }
            return super.handleSeek(index,position,command);
        }
    }
    private Player.Commands controllerCommands(MediaSession.ControllerInfo controller){
        Player.Commands.Builder commands=MediaSession.ConnectionResult.DEFAULT_PLAYER_COMMANDS.buildUpon().remove(Player.COMMAND_SET_SHUFFLE_MODE).remove(Player.COMMAND_SET_REPEAT_MODE).add(Player.COMMAND_SEEK_BACK).add(Player.COMMAND_SEEK_FORWARD);
        // The phone keeps real queue-edge availability; AVRCP needs skips even with one episode.
        if(controller.getUid()==android.os.Process.myUid()&&controller.getControllerVersion()!=MediaSession.ControllerInfo.LEGACY_CONTROLLER_VERSION)
            for(int command:new int[]{Player.COMMAND_SEEK_TO_NEXT,Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,Player.COMMAND_SEEK_TO_PREVIOUS,Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM})if(!player.isCommandAvailable(command))commands.remove(command);
        return commands.build();
    }
    private void checkSleep(){
        if(library==null)return;JSONObject timer=library.store.read("podcast-sleep");long until=timer.optLong("until");boolean end=timer.optBoolean("end");
        if(end&&player.getCurrentMediaItem()!=null&&!timer.optString("id").equals(player.getCurrentMediaItem().mediaId)){library.clearSleep();end=false;}
        player.setPauseAtEndOfMediaItems(end);
        if(until>0&&System.currentTimeMillis()>=until){player.pause();library.clearSleep();}
    }
    private void maintainOffline(){
        if(library==null)return;int count=library.autoDownloads();if(count==0){offlinePlan="";return;}
        List<JSONObject> upcoming=new ArrayList<>();StringBuilder plan=new StringBuilder().append(count).append(':').append(player.getCurrentMediaItemIndex());
        for(int i=player.getCurrentMediaItemIndex()+1;i<Math.min(player.getMediaItemCount(),player.getCurrentMediaItemIndex()+1+count);i++){
            JSONObject episode=library.episode(player.getMediaItemAt(i).mediaId);upcoming.add(episode);plan.append(':').append(episode.optString("id"));
        }
        if(plan.toString().equals(offlinePlan))return;offlinePlan=plan.toString();
        network.execute(()->{for(JSONObject e:upcoming){if(library.autoDownloads()==0)break;if(library.download(e.optString("id"))==null)try{library.download(e);}catch(Exception ignored){}}});
    }
    private void checkpoint(){
        if(library==null||player==null)return;
        String id=player.getCurrentMediaItem()==null?"":player.getCurrentMediaItem().mediaId;
        if(!id.equals(active)&&!active.isBlank())library.event(active,position,"progress");
        active=id;position=Math.max(0,player.getCurrentPosition());
        if(!id.isBlank()){library.event(id,position,"progress");try{JSONArray queue=new JSONArray();for(int i=0;i<player.getMediaItemCount();i++)queue.put(player.getMediaItemAt(i).mediaId);library.store.write("podcast-now",new JSONObject().put("id",id).put("queue",queue).put("speed",player.getPlaybackParameters().speed));}catch(Exception ignored){}}
    }
    private void sync(){if(library!=null&&!network.isShutdown())network.execute(()->library.sync());}
    @Override public MediaLibrarySession onGetSession(MediaSession.ControllerInfo controller){return session;}
    @Override public void onDestroy(){handler.removeCallbacksAndMessages(null);checkpoint();sync();if(session!=null)session.release();if(player!=null)player.release();network.shutdown();super.onDestroy();}
    final class Browser implements MediaLibrarySession.Callback {
        @Override public boolean onMediaButtonEvent(MediaSession s,MediaSession.ControllerInfo controller,android.content.Intent intent){
            android.view.KeyEvent event=intent.getParcelableExtra(android.content.Intent.EXTRA_KEY_EVENT);
            if(event==null)return false;
            int key=event.getKeyCode();boolean back=key==android.view.KeyEvent.KEYCODE_MEDIA_PREVIOUS||key==android.view.KeyEvent.KEYCODE_MEDIA_REWIND;
            boolean forward=key==android.view.KeyEvent.KEYCODE_MEDIA_NEXT||key==android.view.KeyEvent.KEYCODE_MEDIA_FAST_FORWARD;
            if(!back&&!forward)return false;
            if(event.getAction()==android.view.KeyEvent.ACTION_DOWN&&event.getRepeatCount()==0){if(back)player.seekBack();else player.seekForward();}
            return true;
        }
        @Override public MediaSession.ConnectionResult onConnect(MediaSession s,MediaSession.ControllerInfo c){
            if(c.getUid()!=android.os.Process.myUid()&&!c.isTrusted())return MediaSession.ConnectionResult.reject();
            var commands=controllerCommands(c);
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

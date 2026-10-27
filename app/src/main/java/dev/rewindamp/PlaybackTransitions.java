package dev.rewindamp;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.SystemClock;
import androidx.media3.common.AudioAttributes;
import androidx.media3.common.C;
import androidx.media3.common.Player;
import androidx.media3.common.Timeline;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.source.ShuffleOrder;
import java.util.ArrayList;

/** Two real audio players overlap; the media session always owns the audible incoming queue. */
@androidx.annotation.OptIn(markerClass=androidx.media3.common.util.UnstableApi.class)
final class PlaybackTransitions implements AutoCloseable {
    interface Host {ExoPlayer current();ExoPlayer create();void promote(ExoPlayer next);void volume(float logicalVolume);void blending(boolean active);}
    private final Context context;
    private final Host host;
    private final Handler handler;
    private final AudioAttributes attributes;
    private final SharedPreferences preferences;
    private final SharedPreferences.OnSharedPreferenceChangeListener preferenceListener;
    private TransitionSettings.Values settings;
    private ExoPlayer standby,outgoing;
    private int preparedIndex=C.INDEX_UNSET;
    private long fadeLength,fadeStart,failedUntil,gapGeneration,queueSignature;
    private float logicalVolume;
    private boolean settingVolume,switching,closed,tickScheduled;
    private final Runnable tick=new Runnable(){@Override public void run(){tickScheduled=false;if(!closed){step();schedule();}}};
    PlaybackTransitions(Context context,Host host,Handler handler,AudioAttributes attributes){
        this.context=context;this.host=host;this.handler=handler;this.attributes=attributes;logicalVolume=host.current().getVolume();settings=TransitionSettings.load(context);
        preferences=context.getSharedPreferences(TransitionSettings.PREFS,0);preferenceListener=(prefs,key)->{settings=TransitionSettings.load(context);cancel();applyMode();schedule();};preferences.registerOnSharedPreferenceChangeListener(preferenceListener);
        applyMode();host.volume(logicalVolume);schedule();
    }
    float volume(){return logicalVolume;}
    private void schedule(){if(!closed&&!tickScheduled&&settings.mode().equals(TransitionSettings.CROSSFADE)&&host.current().isPlaying()){tickScheduled=true;handler.postDelayed(tick,40);}}
    private long signature(ExoPlayer source){
        long hash=source.getMediaItemCount();for(int i=0;i<source.getMediaItemCount();i++){var item=source.getMediaItemAt(i);hash=31*hash+item.mediaId.hashCode();if(item.localConfiguration!=null)hash=31*hash+item.localConfiguration.uri.hashCode();}return hash;
    }
    private void applyMode(){host.current().setPauseAtEndOfMediaItems(settings.mode().equals(TransitionSettings.SEPARATE));}
    void onVolume(ExoPlayer source,float value){if(source!=host.current()||settingVolume||switching)return;logicalVolume=value;host.volume(value);if(outgoing!=null)applyFade();}
    void onEvents(ExoPlayer source,Player.Events events){
        if(switching||source!=host.current())return;
        if(events.contains(Player.EVENT_MEDIA_ITEM_TRANSITION))releaseStandby();
        if(!source.isPlaying()){handler.removeCallbacks(tick);tickScheduled=false;releaseStandby();if(outgoing!=null)cancelOverlap();return;}
        if((events.contains(Player.EVENT_TIMELINE_CHANGED)&&(standby!=null||outgoing!=null)&&signature(source)!=queueSignature)
            ||events.contains(Player.EVENT_SHUFFLE_MODE_ENABLED_CHANGED)||events.contains(Player.EVENT_REPEAT_MODE_CHANGED))cancel();
        schedule();
    }
    void onSeek(ExoPlayer source,int reason){if(!switching&&source==host.current()&&reason==Player.DISCONTINUITY_REASON_SEEK)cancel();}
    void onPlayWhenReady(ExoPlayer source,boolean ready,int reason){
        if(switching||source!=host.current())return;
        if(!ready&&reason==Player.PLAY_WHEN_READY_CHANGE_REASON_END_OF_MEDIA_ITEM&&settings.mode().equals(TransitionSettings.SEPARATE)){
            long generation=++gapGeneration;
            handler.postDelayed(()->{if(closed||generation!=gapGeneration||source!=host.current()||source.getPlayWhenReady())return;int next=source.getNextMediaItemIndex();if(next!=C.INDEX_UNSET){source.seekTo(next,0);source.play();}},250);
        }
    }
    void command(int command){
        if(command==Player.COMMAND_SET_VOLUME)return;
        if(command==Player.COMMAND_PLAY_PAUSE||command==Player.COMMAND_STOP||command==Player.COMMAND_SET_MEDIA_ITEM
            ||command==Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM||command==Player.COMMAND_SEEK_TO_MEDIA_ITEM||command==Player.COMMAND_SEEK_TO_NEXT||command==Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM
            ||command==Player.COMMAND_SEEK_TO_PREVIOUS||command==Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM||command==Player.COMMAND_SEEK_BACK||command==Player.COMMAND_SEEK_FORWARD)cancel();
    }
    private void step(){
        ExoPlayer current=host.current();
        if(outgoing!=null){
            if(!current.isPlaying()){cancelOverlap();return;}
            applyFade();if(current.getCurrentPosition()-fadeStart>=fadeLength||outgoing.getPlaybackState()==Player.STATE_ENDED){cancelOverlap();}return;
        }
        if(!settings.mode().equals(TransitionSettings.CROSSFADE)||!current.isPlaying()||current.getRepeatMode()==Player.REPEAT_MODE_ONE)return;
        int next=current.getNextMediaItemIndex();long duration=current.getDuration();
        if(duration==C.TIME_UNSET||duration<=0||next==C.INDEX_UNSET||next==current.getCurrentMediaItemIndex()){releaseStandby();return;}
        long length=Math.min(settings.seconds()*1000L,duration/2),remaining=duration-current.getCurrentPosition();
        if(remaining>length+4000||remaining<=0)return;
        if(standby==null&&SystemClock.elapsedRealtime()>=failedUntil)prepare(next);
        if(standby==null)return;
        if(preparedIndex!=next){releaseStandby();return;}
        if(standby.getPlayerError()!=null){releaseStandby();failedUntil=SystemClock.elapsedRealtime()+5000;return;}
        // Don't fade the outgoing song down unless a decoded incoming song is ready.
        if(remaining<=length&&remaining>200&&standby.getPlaybackState()==Player.STATE_READY)begin(Math.min(length,remaining));
    }
    private void prepare(int index){
        ExoPlayer current=host.current();queueSignature=signature(current);standby=host.create();preparedIndex=index;standby.setAudioAttributes(attributes,false);standby.setVolume(0);
        var items=new ArrayList<androidx.media3.common.MediaItem>();for(int i=0;i<current.getMediaItemCount();i++)items.add(current.getMediaItemAt(i));standby.setMediaItems(items,index,0);
        standby.setRepeatMode(current.getRepeatMode());standby.setShuffleModeEnabled(current.getShuffleModeEnabled());standby.setPlaybackParameters(current.getPlaybackParameters());
        Timeline timeline=current.getCurrentTimeline();if(!timeline.isEmpty()){
            int[] order=new int[timeline.getWindowCount()];int at=timeline.getFirstWindowIndex(true);for(int i=0;i<order.length;i++){order[i]=at;at=timeline.getNextWindowIndex(at,Player.REPEAT_MODE_OFF,true);}
            standby.setShuffleOrder(new ShuffleOrder.DefaultShuffleOrder(order,0));
        }
        standby.prepare();
    }
    private void begin(long length){
        switching=true;
        try{
            outgoing=host.current();ExoPlayer incoming=standby;standby=null;preparedIndex=C.INDEX_UNSET;fadeLength=length;fadeStart=incoming.getCurrentPosition();
            outgoing.setPauseAtEndOfMediaItems(true);outgoing.setAudioAttributes(attributes,false);incoming.play();incoming.setAudioAttributes(attributes,true);
            host.promote(incoming);host.blending(true);applyMode();applyFade();
        }finally{switching=false;}
    }
    private void setVolume(ExoPlayer player,float value){settingVolume=true;try{player.setVolume(value);}finally{settingVolume=false;}}
    private void applyFade(){if(outgoing==null)return;float fraction=Math.min(1,Math.max(0,(host.current().getCurrentPosition()-fadeStart)/(float)fadeLength));setVolume(outgoing,logicalVolume*(1-fraction));setVolume(host.current(),logicalVolume*fraction);}
    private void cancelOverlap(){if(outgoing!=null){ExoPlayer old=outgoing;outgoing=null;old.pause();old.release();host.blending(false);}setVolume(host.current(),logicalVolume);}
    private void releaseStandby(){if(standby!=null){standby.release();standby=null;}preparedIndex=C.INDEX_UNSET;}
    private void cancel(){gapGeneration++;releaseStandby();cancelOverlap();}
    @Override public void close(){closed=true;handler.removeCallbacks(tick);preferences.unregisterOnSharedPreferenceChangeListener(preferenceListener);cancel();}
}

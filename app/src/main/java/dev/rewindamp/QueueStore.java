package dev.rewindamp;

import androidx.media3.common.Player;
import androidx.media3.common.MediaItem;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.List;

final class QueueStore {
    record Snapshot(List<Track> tracks,int index,long position,boolean shuffle,int repeat,float volume){}
    static JSONObject capture(Player player){
        try{JSONArray tracks=new JSONArray();for(int i=0;i<player.getMediaItemCount();i++)tracks.put(Track.fromMedia(player.getMediaItemAt(i)).json());
            return new JSONObject().put("Items",tracks).put("index",Math.max(0,player.getCurrentMediaItemIndex())).put("position",Math.max(0,player.getCurrentPosition()))
                .put("shuffle",player.getShuffleModeEnabled()).put("repeat",player.getRepeatMode()).put("volume",player.getVolume());
        }catch(Exception ignored){return new JSONObject();}
    }
    static Snapshot load(LibraryStore store){JSONObject json=store.read("queue");try{
        List<Track> tracks=JellyfinApi.parse(json);return new Snapshot(tracks,Math.max(0,Math.min(json.optInt("index"),tracks.size()-1)),Math.max(0,json.optLong("position")),json.optBoolean("shuffle"),Math.max(0,Math.min(2,json.optInt("repeat"))),Math.max(0,Math.min(1,(float)json.optDouble("volume",1))));
    }catch(Exception ignored){return new Snapshot(List.of(),0,0,false,0,1);}}
    /** Append only. Never discard an existing queue, and never resume a deliberately paused queue. */
    static int append(Player player,List<MediaItem> media){
        if(media.isEmpty())return player.getMediaItemCount();int start=player.getMediaItemCount();boolean startPlayback=start==0||player.getPlaybackState()==Player.STATE_ENDED;
        player.addMediaItems(media);if(startPlayback){player.seekTo(start,0);player.prepare();player.play();}return start;
    }
}

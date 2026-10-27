package dev.jellyamp;

import androidx.media3.common.Player;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.UUID;

/** A durable account-scoped outbox; network failures don't interrupt listening. */
final class PlaybackReporter {
    private static final java.util.Set<String> SYNCING=java.util.concurrent.ConcurrentHashMap.newKeySet();
    private final JellyfinApi api;
    private final LibraryStore store;
    private String item="",playSession="";
    private long position=0;
    PlaybackReporter(JellyfinApi api,LibraryStore store){this.api=api;this.store=store;}
    synchronized void update(Player player){
        String next=player.getCurrentMediaItem()==null?"":player.getCurrentMediaItem().mediaId;
        if(!next.equals(item)&&!item.isBlank())stop();
        if(player.isPlaying()&&item.isBlank()){
            item=next;playSession=UUID.randomUUID().toString().replace("-","");position=player.getCurrentPosition();
            enqueue("/Sessions/Playing",body(player));
        }else if(!item.isBlank()){
            position=Math.max(0,player.getCurrentPosition());enqueue("/Sessions/Playing/Progress",body(player));
        }
    }
    synchronized void transition(){if(!item.isBlank())stop();}
    synchronized void stop(){
        try{enqueue("/Sessions/Playing/Stopped",new JSONObject().put("ItemId",item).put("PositionTicks",position*10000).put("PlaySessionId",playSession));}catch(Exception ignored){}
        item="";playSession="";position=0;
    }
    private JSONObject body(Player player){
        try{return new JSONObject().put("ItemId",item).put("PlaySessionId",playSession).put("PositionTicks",Math.max(0,player.getCurrentPosition())*10000)
            .put("IsPaused",!player.isPlaying()).put("CanSeek",player.isCurrentMediaItemSeekable()).put("PlayMethod","DirectStream").put("VolumeLevel",Math.round(player.getVolume()*100));
        }catch(Exception e){return new JSONObject();}
    }
    private synchronized void enqueue(String path,JSONObject body){
        if(body.optString("ItemId").isBlank())return;
        synchronized(store){try{
            JSONArray old=store.read("playback-outbox").optJSONArray("events"),events=new JSONArray();
            if(old!=null)for(int i=0;i<old.length();i++){
                JSONObject event=old.getJSONObject(i);
                if(path.endsWith("Progress")&&event.optString("path").endsWith("Progress")&&event.getJSONObject("body").optString("PlaySessionId").equals(playSession))continue;
                events.put(event);
            }
            events.put(new JSONObject().put("id",UUID.randomUUID().toString()).put("path",path).put("body",body));
            store.write("playback-outbox",new JSONObject().put("events",events));
        }catch(Exception ignored){} }
    }
    void sync(){
        if(!SYNCING.add(store.scope))return;
        try{
            JSONObject favorites; synchronized(store){favorites=store.read("pending-favourites");}
            java.util.Iterator<String> keys=favorites.keys();
            while(keys.hasNext()){
                String id=keys.next();boolean value=favorites.getBoolean(id);api.favorite(new Track(id,"","","","Audio","",0),value);
                synchronized(store){JSONObject pending=store.read("pending-favourites");if(pending.has(id)&&pending.getBoolean(id)==value){pending.remove(id);store.write("pending-favourites",pending);}}
            }
            while(true){
                JSONObject event;synchronized(store){JSONArray events=store.read("playback-outbox").optJSONArray("events");if(events==null||events.length()==0)return;event=events.getJSONObject(0);}
                api.request("POST",event.getString("path"),event.getJSONObject("body"));
                synchronized(store){JSONArray current=store.read("playback-outbox").optJSONArray("events"),remaining=new JSONArray();
                    if(current!=null)for(int i=0;i<current.length();i++)if(!current.getJSONObject(i).optString("id").equals(event.optString("id")))remaining.put(current.get(i));
                    store.write("playback-outbox",new JSONObject().put("events",remaining));
                }
            }
        }catch(Exception ignored){/* Retain the outbox; retry on the next tick or reconnection. */}finally{SYNCING.remove(store.scope);}
    }
}

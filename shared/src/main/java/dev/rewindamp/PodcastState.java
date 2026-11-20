package dev.rewindamp;

import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

class PodcastState {
    final PodcastApi api;
    final LibraryData store;
    private boolean syncing;
    PodcastState(PodcastApi api, LibraryData store){this.api=api;this.store=store;}
    synchronized JSONArray feeds()throws Exception{
        try{JSONObject value=api.request("/api/feeds",null);store.write("podcast-feeds",value);return value.getJSONArray("items");}
        catch(Exception e){if(e.getMessage()!=null&&e.getMessage().contains("HTTP 401"))throw e;JSONArray cached=store.read("podcast-feeds").optJSONArray("items");if(cached!=null)return cached;throw e;}
    }
    boolean oldest(String feed){return store.read("podcast-preferences").optBoolean("oldest:"+(feed==null?"all":feed));}
    boolean showPlayed(){return store.read("podcast-preferences").optBoolean("show_played");}
    void preference(String key,boolean value){try{JSONObject prefs=store.read("podcast-preferences");prefs.put(key,value);store.writeChecked("podcast-preferences",prefs);}catch(Exception e){throw new IllegalStateException(e);}}
    List<JSONObject> episodes(String feed,boolean inbox,int offset)throws Exception{return episodes(feed,inbox,offset,false,false);}
    List<JSONObject> episodes(String feed,boolean inbox,int offset,boolean oldest,boolean hidePlayed)throws Exception{
        String path="/api/episodes?limit=100&offset="+offset+"&unplayed="+inbox+(feed==null?"":"&feed_id="+UrlEncoding.encode(feed))+"&order="+(oldest?"oldest":"newest")+"&hide_played="+hidePlayed;
        JSONObject value;boolean fresh;
        try{value=api.request(path,null);fresh=true;store.write(path,value);}
        catch(Exception e){fresh=false;value=store.read(path);if(!value.has("items"))throw e;}
        List<JSONObject> result=new ArrayList<>();JSONArray rows=value.getJSONArray("items");
        synchronized(this){for(int i=0;i<rows.length();i++){
            JSONObject episode=rows.getJSONObject(i);String id=episode.getString("id");JSONObject local=episode(id);
            if(hasPending(id)||!fresh){for(String key:List.of("position","state","epoch"))if(local.has(key))episode.put(key,local.get(key));}
            else{JSONObject journal=journal();JSONObject states=journal.getJSONObject("states");states.remove(id);store.writeChecked("podcast-journal",journal);}
            store.write("episode:"+id,episode);
            if(!inbox||!List.of("played","dismissed").contains(episode.optString("state")))result.add(episode);
        }}return result;
    }
    synchronized JSONObject journal(){
        JSONObject value=store.read("podcast-journal");try{if(!value.has("events"))value.put("events",new JSONArray());if(!value.has("states"))value.put("states",new JSONObject());}catch(Exception e){throw new IllegalStateException(e);}return value;
    }
    static boolean pending(JSONArray events,String id){for(int i=0;i<events.length();i++)if(id.equals(events.optJSONObject(i).optString("episode_id")))return true;return false;}
    synchronized boolean hasPending(String id){return pending(journal().optJSONArray("events"),id);}
    synchronized JSONObject episode(String id){
        JSONObject value=store.read("episode:"+id),state=journal().optJSONObject("states").optJSONObject(id);
        if(state!=null)try{for(String key:List.of("position","state","epoch"))value.put(key,state.get(key));}catch(Exception e){throw new IllegalStateException(e);}return value;
    }
    synchronized void event(String id,long millis,String action){
        try{
            JSONObject episode=episode(id);if(!episode.has("id"))return;long epoch=episode.optLong("epoch");
            JSONObject body=new JSONObject().put("event_id",UUID.randomUUID().toString()).put("epoch",epoch).put("position",Math.max(0,millis)/1000.0).put("observed",System.currentTimeMillis()/1000.0).put("action",action);
            JSONObject journal=journal();JSONArray old=journal.getJSONArray("events"),events=new JSONArray();
            for(int i=0;i<old.length();i++){JSONObject entry=old.getJSONObject(i);JSONObject b=entry.getJSONObject("body");if(action.equals("progress")&&entry.getString("episode_id").equals(id)&&b.optString("action").equals("progress")&&b.optLong("epoch")==epoch)continue;events.put(entry);}
            events.put(new JSONObject().put("episode_id",id).put("body",body));
            String state=episode.optString("state","unplayed");
            if(action.equals("reset")){episode.put("epoch",epoch+1).put("position",0).put("state","unplayed");}
            else{episode.put("position",Math.max(0,millis)/1000.0);if(action.equals("played")||action.equals("dismissed"))episode.put("state",action);else if(!state.equals("played")&&!state.equals("dismissed")&&millis>0)episode.put("state","in_progress");}
            JSONObject local=new JSONObject();for(String key:List.of("position","state","epoch"))local.put(key,episode.get(key));
            journal.getJSONObject("states").put(id,local);journal.put("events",events);
            // Local state and its outbound event commit in the same AtomicFile transaction.
            store.writeChecked("podcast-journal",journal);
        }catch(Exception e){throw new IllegalStateException("Could not save podcast progress",e);}
    }
    void sync(){
        synchronized(this){if(syncing)return;syncing=true;}
        try{while(true){
            JSONObject entry;synchronized(this){JSONArray events=journal().getJSONArray("events");if(events.length()==0)return;entry=events.getJSONObject(0);}
            String id=entry.getString("episode_id"),eventId=entry.getJSONObject("body").getString("event_id");
            JSONObject response=api.request("/api/progress/"+id,entry.getJSONObject("body"));
            synchronized(this){JSONObject journal=journal();JSONArray events=journal.getJSONArray("events"),remaining=new JSONArray();for(int i=0;i<events.length();i++){JSONObject candidate=events.getJSONObject(i);if(!candidate.getJSONObject("body").getString("event_id").equals(eventId))remaining.put(candidate);}
                if(!pending(remaining,id)){JSONObject state=new JSONObject();for(String key:List.of("position","state","epoch"))state.put(key,response.get(key));journal.getJSONObject("states").put(id,state);}
                journal.put("events",remaining);store.writeChecked("podcast-journal",journal);
            }
        }}catch(Exception ignored){/* Durable journal retries idempotently on the service tick or manual sync. */}finally{synchronized(this){syncing=false;}}
    }
    void sleep(long until,boolean end,String id){try{store.writeChecked("podcast-sleep",new JSONObject().put("until",until).put("end",end).put("id",id));}catch(Exception e){throw new IllegalStateException(e);}}
    void clearSleep(){sleep(0,false,"");}
    String sleepDescription(){JSONObject timer=store.read("podcast-sleep");if(timer.optBoolean("end"))return "end of episode";long remaining=timer.optLong("until")-System.currentTimeMillis();return remaining>0?Math.max(1,(remaining+59999)/60000)+" min":"off";}
}

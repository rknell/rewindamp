package dev.rewindamp;

import android.content.Context;
import android.net.Uri;
import android.os.Bundle;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MediaMetadata;
import androidx.media3.database.StandaloneDatabaseProvider;
import androidx.media3.datasource.DefaultDataSource;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.datasource.cache.CacheDataSource;
import androidx.media3.datasource.cache.NoOpCacheEvictor;
import androidx.media3.datasource.cache.SimpleCache;
import androidx.media3.exoplayer.offline.Download;
import androidx.media3.exoplayer.offline.DownloadManager;
import androidx.media3.exoplayer.offline.DownloadRequest;
import androidx.media3.exoplayer.offline.DownloadService;
import androidx.media3.exoplayer.scheduler.Requirements;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** App-private podcast metadata, per-episode positions, download cache and durable sync outbox. */
@androidx.annotation.OptIn(markerClass=androidx.media3.common.util.UnstableApi.class)
final class PodcastLibrary {
    private static PodcastLibrary instance;
    final Context context;
    final PodcastApi api;
    final LibraryStore store;
    final SimpleCache cache;
    final DownloadManager manager;
    private final StandaloneDatabaseProvider database;
    private boolean syncing;
    static synchronized PodcastLibrary get(Context context,PodcastApi api){
        String scope=LibraryStore.hash(api.server+"\nRewindampPodcastOwner");
        if(instance!=null&&(!instance.store.scope.equals(scope)||!instance.api.token.equals(api.token))){
            instance.manager.release();instance.cache.release();instance.database.close();DownloadService.clearDownloadManagerHelpers();instance=null;
        }
        if(instance==null)instance=new PodcastLibrary(context.getApplicationContext(),api);return instance;
    }
    private PodcastLibrary(Context context,PodcastApi api){
        this.context=context;this.api=api;
        // Distinct server/owner namespace; never share a Jellyfin cache or playback outbox.
        store=new LibraryStore(context,new JellyfinApi(api.server,"","RewindampPodcastOwner",""));database=new StandaloneDatabaseProvider(context);
        cache=new SimpleCache(new File(store.directory,"podcast-audio"),new NoOpCacheEvictor(),database);
        manager=new DownloadManager(context,new androidx.media3.exoplayer.offline.DefaultDownloadIndex(database,"podcast_"+store.scope),
            new androidx.media3.exoplayer.offline.DefaultDownloaderFactory(new CacheDataSource.Factory().setCache(cache).setUpstreamDataSourceFactory(http()),java.util.concurrent.Executors.newFixedThreadPool(2)));
        manager.setMaxParallelDownloads(2);manager.setRequirements(new Requirements(Requirements.NETWORK));manager.setMinRetryCount(3);manager.resumeDownloads();
    }
    DefaultHttpDataSource.Factory http(){return new DefaultHttpDataSource.Factory().setUserAgent("Rewindamp/0.4").setDefaultRequestProperties(Map.of("Authorization",api.authorization()));}
    androidx.media3.datasource.DataSource.Factory source(){return new CacheDataSource.Factory().setCache(cache).setCacheWriteDataSinkFactory(null).setUpstreamDataSourceFactory(new DefaultDataSource.Factory(context,http())).setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR);}
    synchronized JSONArray feeds()throws Exception{
        try{JSONObject value=api.request("/api/feeds",null);store.write("podcast-feeds",value);return value.getJSONArray("items");}
        catch(Exception e){if(e.getMessage()!=null&&e.getMessage().contains("HTTP 401"))throw e;JSONArray cached=store.read("podcast-feeds").optJSONArray("items");if(cached!=null)return cached;throw e;}
    }
    List<JSONObject> episodes(String feed,boolean inbox,int offset)throws Exception{
        String path="/api/episodes?limit=100&offset="+offset+"&unplayed="+inbox+(feed==null?"":"&feed_id="+Uri.encode(feed));
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
    Download download(String id){try{return manager.getDownloadIndex().getDownload(id);}catch(Exception e){return null;}}
    boolean complete(String id){Download d=download(id);return d!=null&&d.state==Download.STATE_COMPLETED;}
    List<JSONObject> downloaded(){List<JSONObject> result=new ArrayList<>();try(var cursor=manager.getDownloadIndex().getDownloads()){while(cursor.moveToNext()){Download d=cursor.getDownload();JSONObject e=episode(d.request.id);if(e.has("id"))result.add(e);}}catch(Exception ignored){}return result;}
    String label(String id){Download d=download(id);if(d==null)return "";return switch(d.state){case Download.STATE_COMPLETED->"PHONE OFFLINE";case Download.STATE_FAILED->"DOWNLOAD FAILED";case Download.STATE_STOPPED->"PAUSED";default->d.getPercentDownloaded()<0?"QUEUED":(int)d.getPercentDownloaded()+"%";};}
    void download(JSONObject episode)throws Exception{
        String id=episode.getString("id");if(!episode.optBoolean("downloaded"))throw new IllegalArgumentException("Wait for the server download first");
        DownloadRequest request=new DownloadRequest.Builder(id,Uri.parse(api.audio(id))).setMimeType("audio/mpeg").setCustomCacheKey(id).setData(episode.toString().getBytes(StandardCharsets.UTF_8)).build();
        DownloadService.sendAddDownload(context,PodcastDownloadService.class,request,true);
    }
    void remove(String id){DownloadService.sendRemoveDownload(context,PodcastDownloadService.class,id,true);}
    MediaItem media(JSONObject episode){try{
        String id=episode.getString("id");Bundle extras=new Bundle();extras.putString("podcast",id);
        return new MediaItem.Builder().setMediaId(id).setUri(api.audio(id)).setCustomCacheKey(id)
            .setMediaMetadata(new MediaMetadata.Builder().setTitle(episode.optString("title")).setArtist(episode.optString("show_title")).setIsPlayable(true).setIsBrowsable(false).setMediaType(MediaMetadata.MEDIA_TYPE_PODCAST_EPISODE).setExtras(extras).build()).build();
    }catch(Exception e){throw new IllegalArgumentException("Invalid episode",e);}}
    static synchronized void pause(Context context){if(instance!=null)instance.manager.pauseDownloads();context.stopService(new android.content.Intent(context,PodcastDownloadService.class));}
}

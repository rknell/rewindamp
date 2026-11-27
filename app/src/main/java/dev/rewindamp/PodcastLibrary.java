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
import org.json.JSONObject;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** App-private podcast metadata, per-episode positions, download cache and durable sync outbox. */
@androidx.annotation.OptIn(markerClass=androidx.media3.common.util.UnstableApi.class)
final class PodcastLibrary extends PodcastState {
    private static PodcastLibrary instance;
    final Context context;
    final SimpleCache cache;
    final DownloadManager manager;
    private final StandaloneDatabaseProvider database;
    static synchronized PodcastLibrary get(Context context,PodcastApi api){
        String scope=LibraryStore.hash(api.server+"\n"+api.storageAccount());
        if(instance!=null&&(!instance.store.scope.equals(scope)||!instance.api.token.equals(api.token))){
            instance.manager.release();instance.cache.release();instance.database.close();DownloadService.clearDownloadManagerHelpers();instance=null;
        }
        if(instance==null)instance=new PodcastLibrary(context.getApplicationContext(),api);return instance;
    }
    private PodcastLibrary(Context context,PodcastApi api){
        super(api,new LibraryStore(context,new JellyfinApi(api.server,"",api.storageAccount(),"")));
        this.context=context;database=new StandaloneDatabaseProvider(context);
        cache=new SimpleCache(new File(store.directory,"podcast-audio"),new NoOpCacheEvictor(),database);
        manager=new DownloadManager(context,new androidx.media3.exoplayer.offline.DefaultDownloadIndex(database,"podcast_"+store.scope),
            new androidx.media3.exoplayer.offline.DefaultDownloaderFactory(new CacheDataSource.Factory().setCache(cache).setUpstreamDataSourceFactory(http()),java.util.concurrent.Executors.newFixedThreadPool(2)));
        manager.setMaxParallelDownloads(2);applyDownloadRequirements();manager.setMinRetryCount(3);if(store.read("podcast-preferences").optBoolean("downloads_paused"))manager.pauseDownloads();else manager.resumeDownloads();
    }
    DefaultHttpDataSource.Factory http(){return new DefaultHttpDataSource.Factory().setUserAgent("Rewindamp/0.4").setDefaultRequestProperties(Map.of("Authorization",api.authorization()));}
    androidx.media3.datasource.DataSource.Factory source(){return new CacheDataSource.Factory().setCache(cache).setCacheWriteDataSinkFactory(null).setUpstreamDataSourceFactory(new DefaultDataSource.Factory(context,http())).setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR);}
    boolean wifiOnly(){return store.read("podcast-preferences").optBoolean("wifi_only",true);}
    int autoDownloads(){return Math.max(0,Math.min(5,store.read("podcast-preferences").optInt("auto_downloads")));}
    void applyDownloadRequirements(){manager.setRequirements(new Requirements(Requirements.NETWORK|(wifiOnly()?Requirements.NETWORK_UNMETERED:0)));}
    void wifiOnly(boolean value){preference("wifi_only",value);applyDownloadRequirements();}
    void autoDownloads(int count){try{JSONObject prefs=store.read("podcast-preferences");prefs.put("auto_downloads",count);store.writeChecked("podcast-preferences",prefs);}catch(Exception e){throw new IllegalStateException(e);}}
    void pauseDownloads(boolean paused){preference("downloads_paused",paused);if(paused)manager.pauseDownloads();else manager.resumeDownloads();}
    String offlineSummary(){long bytes=0;int complete=0,pending=0,failed=0;try(var cursor=manager.getDownloadIndex().getDownloads()){while(cursor.moveToNext()){Download d=cursor.getDownload();bytes+=d.getBytesDownloaded();if(d.state==Download.STATE_COMPLETED)complete++;else if(d.state==Download.STATE_FAILED)failed++;else pending++;}}catch(Exception ignored){}
        return complete+" saved · "+pending+" pending · "+failed+" failed\n"+String.format(java.util.Locale.ROOT,"%.1f MB on phone · %.1f GB free",bytes/1048576.0,store.directory.getUsableSpace()/1073741824.0);}
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
        long durationMs=(long)(episode.optDouble("duration",0)*1000);
        return new MediaItem.Builder().setMediaId(id).setUri(api.audio(id)).setCustomCacheKey(id)
            .setMediaMetadata(new MediaMetadata.Builder().setTitle(episode.optString("title")).setArtist(episode.optString("show_title")).setDurationMs(durationMs>0?durationMs:null).setIsPlayable(true).setIsBrowsable(false).setMediaType(MediaMetadata.MEDIA_TYPE_PODCAST_EPISODE).setExtras(extras).build()).build();
    }catch(Exception e){throw new IllegalArgumentException("Invalid episode",e);}}
    static synchronized void pause(Context context){if(instance!=null)instance.manager.pauseDownloads();context.stopService(new android.content.Intent(context,PodcastDownloadService.class));}
}

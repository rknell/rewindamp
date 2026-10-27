package dev.rewindamp;

import android.content.Context;
import android.net.Uri;
import android.os.Bundle;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MediaMetadata;
import androidx.media3.database.StandaloneDatabaseProvider;
import androidx.media3.datasource.DataSource;
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
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@androidx.annotation.OptIn(markerClass=androidx.media3.common.util.UnstableApi.class)
final class OfflineLibrary {
    private static OfflineLibrary instance;
    final Context context;
    JellyfinApi api;
    final LibraryStore store;
    final DownloadManager manager;
    final SimpleCache cache;
    private final StandaloneDatabaseProvider database;
    static synchronized OfflineLibrary get(Context context,JellyfinApi api){
        String scope=LibraryStore.hash(api.server+"\n"+api.userId);
        if(instance!=null&&!instance.store.scope.equals(scope)){
            // A sign-out stops both services before another account can connect.
            instance.manager.release();instance.cache.release();instance.database.close();DownloadService.clearDownloadManagerHelpers();instance=null;
        }
        if(instance==null)instance=new OfflineLibrary(context.getApplicationContext(),api);
        instance.api=api;instance.apiToken=api.authorization();return instance;
    }
    private volatile String apiToken;
    private OfflineLibrary(Context context,JellyfinApi api){
        this.context=context;this.api=api;this.store=new LibraryStore(context,api);this.apiToken=api.authorization();
        database=new StandaloneDatabaseProvider(context);
        cache=new SimpleCache(new File(store.directory,"audio"),new NoOpCacheEvictor(),database);
        manager=new DownloadManager(context,new androidx.media3.exoplayer.offline.DefaultDownloadIndex(database,store.scope),
            new androidx.media3.exoplayer.offline.DefaultDownloaderFactory(new CacheDataSource.Factory().setCache(cache).setUpstreamDataSourceFactory(http()),java.util.concurrent.Executors.newFixedThreadPool(2)));
        manager.setMaxParallelDownloads(2);manager.setMinRetryCount(3);manager.setRequirements(new Requirements(Requirements.NETWORK));
        if(!store.read("download-settings").optBoolean("paused"))manager.resumeDownloads();
    }
    DataSource.Factory http(){
        return ()->new DefaultHttpDataSource.Factory().setUserAgent("Rewindamp/0.2.0").setDefaultRequestProperties(Map.of("Authorization",apiToken)).createDataSource();
    }
    DataSource.Factory playbackSource(){return new CacheDataSource.Factory().setCache(cache).setCacheWriteDataSinkFactory(null)
        .setUpstreamDataSourceFactory(new DefaultDataSource.Factory(context,http())).setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR);}
    List<Download> downloads(){
        List<Download> all=new ArrayList<>();try(var cursor=manager.getDownloadIndex().getDownloads()){while(cursor.moveToNext())all.add(cursor.getDownload());}catch(Exception ignored){}return all;
    }
    Download download(String id){try{return manager.getDownloadIndex().getDownload(id);}catch(Exception ignored){return null;}}
    boolean complete(String id){Download download=download(id);return download!=null&&download.state==Download.STATE_COMPLETED;}
    List<Track> tracks(boolean onlyComplete){List<Track> tracks=new ArrayList<>();for(Download d:downloads())if(!onlyComplete||d.state==Download.STATE_COMPLETED)try{tracks.add(Track.from(new org.json.JSONObject(new String(d.request.data,StandardCharsets.UTF_8))));}catch(Exception ignored){}return tracks;}
    MediaItem media(Track track){
        try{
            Download download=download(track.id);boolean local=download!=null&&download.state==Download.STATE_COMPLETED;
            Bundle extras=new Bundle();extras.putString("track",track.json().toString());extras.putBoolean("offline",local);
            MediaMetadata.Builder meta=new MediaMetadata.Builder().setTitle(track.name).setArtist(track.artist).setAlbumTitle(track.album)
                .setIsPlayable(true).setIsBrowsable(false).setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC).setExtras(extras);
            byte[] art=store.artwork(track);if(art.length>0)meta.setArtworkData(art,MediaMetadata.PICTURE_TYPE_FRONT_COVER);
            return new MediaItem.Builder().setMediaId(track.id).setUri(local?download.request.uri:Uri.parse(api.stream(track)))
                .setCustomCacheKey(local?track.id:null).setMediaMetadata(meta.build()).build();
        }catch(Exception e){throw new IllegalStateException("Could not build music item",e);}
    }
    void enqueue(List<Track> tracks)throws Exception{
        paused(false);
        for(Track track:tracks){
            Download existing=download(track.id);if(existing!=null&&(existing.state==Download.STATE_COMPLETED||existing.state==Download.STATE_DOWNLOADING||existing.state==Download.STATE_QUEUED))continue;
            DownloadRequest request=new DownloadRequest.Builder(track.id,Uri.parse(api.downloadUrl(track))).setMimeType("audio/unknown").setCustomCacheKey(track.id).setData(track.json().toString().getBytes(StandardCharsets.UTF_8)).build();
            DownloadService.sendAddDownload(context,MusicDownloadService.class,request,true);
        }
    }
    void paused(boolean value){
        try{store.write("download-settings",new org.json.JSONObject().put("paused",value));}catch(Exception ignored){}
        if(value)manager.pauseDownloads();else DownloadService.sendResumeDownloads(context,MusicDownloadService.class,true);
    }
    void remove(String id){DownloadService.sendRemoveDownload(context,MusicDownloadService.class,id,true);}
    String label(String id){Download d=download(id);if(d==null)return "";return switch(d.state){
        case Download.STATE_COMPLETED->"OFFLINE";case Download.STATE_FAILED->"FAILED — TAP TO RETRY";case Download.STATE_STOPPED->"PAUSED";
        case Download.STATE_REMOVING->"REMOVING";default->d.getPercentDownloaded()<0?"WAITING FOR NETWORK":String.format(java.util.Locale.US,"DOWNLOADING %d%%",(int)d.getPercentDownloaded());};}
    static synchronized void pause(Context context){if(instance!=null){instance.manager.pauseDownloads();}context.stopService(new android.content.Intent(context,MusicDownloadService.class));}
}

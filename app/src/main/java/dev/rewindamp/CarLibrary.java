package dev.rewindamp;

import android.os.Bundle;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MediaMetadata;
import androidx.media3.common.Player;
import androidx.media3.session.LibraryResult;
import androidx.media3.session.MediaLibraryService;
import androidx.media3.session.MediaLibraryService.MediaLibrarySession;
import androidx.media3.session.MediaLibraryService.LibraryParams;
import androidx.media3.session.MediaSession;
import com.google.common.collect.ImmutableList;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.ListeningExecutorService;
import com.google.common.util.concurrent.MoreExecutors;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;

@androidx.annotation.OptIn(markerClass=androidx.media3.common.util.UnstableApi.class)
final class CarLibrary implements MediaLibrarySession.Callback {
    java.util.function.IntConsumer commandListener=command->{};
    private final JellyfinApi api;
    private final OfflineLibrary offline;
    private final ListeningExecutorService io=MoreExecutors.listeningDecorator(Executors.newSingleThreadExecutor());
    CarLibrary(JellyfinApi api,OfflineLibrary offline){this.api=api;this.offline=offline;}
    void close(){io.shutdown();}
    static MediaItem folder(String id,String title){return new MediaItem.Builder().setMediaId(id).setMediaMetadata(new MediaMetadata.Builder().setTitle(title)
        .setIsBrowsable(true).setIsPlayable(false).setMediaType(MediaMetadata.MEDIA_TYPE_FOLDER_MIXED).build()).build();}
    @Override public MediaSession.ConnectionResult onConnect(MediaSession session,MediaSession.ControllerInfo controller){
        if(controller.getUid()==android.os.Process.myUid())return MediaLibrarySession.Callback.super.onConnect(session,controller);
        if(!controller.isTrusted())return MediaSession.ConnectionResult.reject();
        return new MediaSession.ConnectionResult.AcceptedResultBuilder(session)
            .setAvailableSessionCommands(MediaSession.ConnectionResult.DEFAULT_SESSION_AND_LIBRARY_COMMANDS).build();
    }
    @Override public int onPlayerCommandRequest(MediaSession session,MediaSession.ControllerInfo controller,int command){commandListener.accept(command);return androidx.media3.session.SessionResult.RESULT_SUCCESS;}
    @Override public ListenableFuture<LibraryResult<MediaItem>> onGetLibraryRoot(MediaLibrarySession session,MediaSession.ControllerInfo controller,LibraryParams params){
        return Futures.immediateFuture(LibraryResult.ofItem(folder("root","Rewindamp"),params));
    }
    List<MediaItem> children(String id,int page,int pageSize)throws Exception{
        if(api==null||offline==null)return List.of(folder("signin","Sign in on your phone"));
        if(id.equals("root"))return List.of(folder("Tracks","Tracks"),folder("Albums","Albums"),folder("Artists","Artists"),folder("Playlists","Playlists"),folder("Favourites","Favourites"),folder("Offline","Downloaded music"));
        if(id.equals("signin"))return List.of();
        List<Track> tracks;int offset=Math.max(0,page)*Math.max(1,Math.min(100,pageSize));boolean localPage=id.equals("Offline")||id.startsWith("Playlist:");
        if(id.equals("Offline"))tracks=offline.tracks(true);
        else{
            String[] parts=id.split(":",2);String section=parts.length==1?id:"Tracks",parent=parts.length==1?null:parts[1],type=parts.length==1?"":parts[0];
            String cacheKey="car:"+id+":"+offset+(id.equals("Artists")||id.equals("Albums")?":artist-order-v1":"");
            try{tracks=api.browse(section,"",parent,type,localPage?0:offset);offline.store.tracks(cacheKey,tracks);}catch(Exception e){tracks=offline.store.tracks(cacheKey);if(tracks.isEmpty())throw e;}
        }
        List<MediaItem> result=new ArrayList<>();for(Track t:tracks){if(t.type.equals("Audio"))result.add(offline.media(t));else result.add(folder(t.type+":"+t.id,t.name));}
        int start=localPage?Math.min(result.size(),offset):0;return result.subList(start,Math.min(result.size(),start+Math.min(100,pageSize)));
    }
    @Override public ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> onGetChildren(MediaLibrarySession s,MediaSession.ControllerInfo c,String id,int page,int size,LibraryParams params){
        return io.submit(()->{try{return LibraryResult.ofItemList(children(id,page,size),params);}catch(Exception e){return LibraryResult.ofError(androidx.media3.session.SessionError.ERROR_IO);}});
    }
    @Override public ListenableFuture<LibraryResult<MediaItem>> onGetItem(MediaLibrarySession s,MediaSession.ControllerInfo c,String id){
        return io.submit(()->{try{if(id.equals("root"))return LibraryResult.ofItem(folder("root","Rewindamp"),null);return LibraryResult.ofItem(resolve(id),null);}catch(Exception e){return LibraryResult.ofError(androidx.media3.session.SessionError.ERROR_BAD_VALUE);}});
    }
    private MediaItem resolve(String id)throws Exception{
        if(offline==null)throw new IllegalStateException();
        for(Track t:offline.tracks(true))if(t.id.equals(id))return offline.media(t);
        return offline.media(api.item(id));
    }
    @Override public ListenableFuture<List<MediaItem>> onAddMediaItems(MediaSession session,MediaSession.ControllerInfo controller,List<MediaItem> items){
        if(controller.getUid()==android.os.Process.myUid()&&items.stream().allMatch(item->item.localConfiguration!=null))return Futures.immediateFuture(items);
        // Never accept another app's URI: resolve an authorized Jellyfin ID ourselves.
        return io.submit(()->{List<MediaItem> result=new ArrayList<>();for(MediaItem item:items){String query=item.requestMetadata.searchQuery;if(query!=null&&!query.isBlank()){List<Track> matches=api.browse("Tracks",query,null,"",0);if(!matches.isEmpty())result.add(offline.media(matches.get(0)));}else result.add(resolve(item.mediaId));}return result;});
    }
    @Override public ListenableFuture<MediaSession.MediaItemsWithStartPosition> onSetMediaItems(MediaSession session,MediaSession.ControllerInfo controller,List<MediaItem> items,int index,long position){
        if(controller.getUid()==android.os.Process.myUid()&&items.stream().allMatch(item->item.localConfiguration!=null))return MediaLibrarySession.Callback.super.onSetMediaItems(session,controller,items,index,position);
        // Car selection builds on the household queue, matching the phone's append-first behavior.
        return Futures.transformAsync(onAddMediaItems(session,controller,items),resolved->{
            Player player=session.getPlayer();int at=player.getMediaItemCount();List<MediaItem> combined=new ArrayList<>();for(int i=0;i<at;i++)combined.add(player.getMediaItemAt(i));combined.addAll(resolved);
            return Futures.immediateFuture(new MediaSession.MediaItemsWithStartPosition(combined,at+Math.max(0,index),Math.max(0,position)));
        },command->new android.os.Handler(session.getPlayer().getApplicationLooper()).post(command));
    }
    @Override public ListenableFuture<LibraryResult<Void>> onSearch(MediaLibrarySession s,MediaSession.ControllerInfo c,String query,LibraryParams params){
        io.execute(()->{try{List<Track> result=api.browse("Tracks",query,null,"",0);offline.store.tracks("search:"+query,result);s.notifySearchResultChanged(c,query,result.size(),params);}catch(Exception ignored){s.notifySearchResultChanged(c,query,0,params);}});
        return Futures.immediateFuture(LibraryResult.ofVoid());
    }
    @Override public ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> onGetSearchResult(MediaLibrarySession s,MediaSession.ControllerInfo c,String query,int page,int size,LibraryParams params){
        List<MediaItem> result=new ArrayList<>();if(offline!=null)for(Track t:offline.store.tracks("search:"+query))result.add(offline.media(t));int start=Math.min(result.size(),Math.max(0,page)*Math.min(100,size));return Futures.immediateFuture(LibraryResult.ofItemList(result.subList(start,Math.min(result.size(),start+Math.min(100,size))),params));
    }
}

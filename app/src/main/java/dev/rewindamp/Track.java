package dev.rewindamp;

import android.os.Bundle;
import androidx.media3.common.MediaItem;
import org.json.JSONArray;
import org.json.JSONObject;

final class Track {
    final String id, name, artist, album, type, entryId, imageId, imageTag;
    final long duration;
    final boolean favorite;
    Track(String id, String name, String artist, String album, String type, String entryId, long duration) {
        this(id,name,artist,album,type,entryId,duration,"","",false);
    }
    Track(String id,String name,String artist,String album,String type,String entryId,long duration,String imageId,String imageTag,boolean favorite) {
        this.id=id;this.name=name;this.artist=artist;this.album=album;this.type=type;this.entryId=entryId;
        this.duration=duration;this.imageId=imageId;this.imageTag=imageTag;this.favorite=favorite;
    }
    static Track from(JSONObject json) throws org.json.JSONException {
        JSONArray artists=json.optJSONArray("Artists"); StringBuilder names=new StringBuilder();
        if(artists!=null) for(int i=0;i<artists.length();i++){if(i>0)names.append(", ");names.append(artists.optString(i));}
        JSONObject tags=json.optJSONObject("ImageTags"),data=json.optJSONObject("UserData");
        String tag=tags==null?"":tags.optString("Primary"),image=tag.isBlank()?json.optString("AlbumId"):json.optString("Id");
        if(tag.isBlank())tag=json.optString("AlbumPrimaryImageTag");
        return new Track(json.optString("Id"),json.optString("Name",json.optString("Type").equals("MusicAlbum")?"":"Untitled"),names.length()==0?json.optString("AlbumArtist",""):names.toString(),
            json.optString("Album",""),json.optString("Type","Audio"),json.optString("PlaylistItemId",""),json.optLong("RunTimeTicks")/10000,
            image,tag,data!=null&&data.optBoolean("IsFavorite"));
    }
    JSONObject json() throws org.json.JSONException {
        return new JSONObject().put("Id",id).put("Name",name).put("Artists",new JSONArray().put(artist)).put("Album",album)
            .put("Type",type).put("PlaylistItemId",entryId).put("RunTimeTicks",duration*10000)
            .put("AlbumId",imageId).put("AlbumPrimaryImageTag",imageTag).put("UserData",new JSONObject().put("IsFavorite",favorite));
    }
    static Track fromMedia(MediaItem item) {
        try {Bundle extra=item.mediaMetadata.extras;if(extra!=null&&extra.containsKey("track"))return from(new JSONObject(extra.getString("track")));}catch(Exception ignored){}
        return new Track(item.mediaId,String.valueOf(item.mediaMetadata.title),item.mediaMetadata.artist==null?"":item.mediaMetadata.artist.toString(),"","Audio","",0);
    }
    Track withFavorite(boolean value){return new Track(id,name,artist,album,type,entryId,duration,imageId,imageTag,value);}
    /** Hide unfiled/single releases only in album lists; individual tracks remain available. */
    boolean hiddenAlbum(){
        if(!type.equals("MusicAlbum"))return false;
        String label=name.strip().toLowerCase(java.util.Locale.ROOT);
        String placeholder=label.replaceAll("[\\s_\\-\\[\\]()]+","");
        return placeholder.isEmpty()||java.util.Set.of("single","singles","noalbum","unknownalbum").contains(placeholder)
            ||label.matches(".*(?:\\s[-–—]\\s*singles?|\\s*\\(singles?\\)|\\s*\\[singles?\\])");
    }
    String subtitle(){return artist.isBlank()?(type.equals("Playlist")?"JELLYFIN PLAYLIST":"MUSIC LIBRARY"):artist+(album.isBlank()?"":" · "+album);}
    static String time(long millis){long seconds=Math.max(0,millis/1000);return String.format(java.util.Locale.US,"%d:%02d",seconds/60,seconds%60);}
}

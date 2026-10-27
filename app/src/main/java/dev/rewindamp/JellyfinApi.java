package dev.rewindamp;

import android.net.Uri;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

final class JellyfinApi {
    final String server, token, userId, deviceId;
    private final java.util.LinkedHashMap<String,TrackSearch> searches=new java.util.LinkedHashMap<>(4,.75f,true);
    JellyfinApi(String server, String token, String userId, String deviceId) {
        this.server = server; this.token = token; this.userId = userId; this.deviceId = deviceId;
    }
    static String normalizeServer(String input) {
        try {
            URI uri = new URI(input.trim());
            if (!("https".equals(uri.getScheme()) || "http".equals(uri.getScheme())) || uri.getHost() == null
                || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null) throw new Exception();
            return uri.toString().replaceAll("/+$", "");
        } catch (Exception ignored) { throw new IllegalArgumentException("Enter a full server URL, e.g. https://music.example.com"); }
    }
    static JellyfinApi login(String server, String username, String password) throws Exception {
        JellyfinApi anonymous = new JellyfinApi(normalizeServer(server), "", "", UUID.randomUUID().toString());
        JSONObject result = anonymous.request("POST", "/Users/AuthenticateByName", new JSONObject().put("Username", username).put("Pw", password));
        return new JellyfinApi(anonymous.server, result.getString("AccessToken"), result.getJSONObject("User").getString("Id"), anonymous.deviceId);
    }
    String authorization() {
        return "MediaBrowser Client=\"Rewindamp\", Device=\"Android\", DeviceId=\"" + deviceId + "\", Version=\"0.3.6\"" + (token.isEmpty() ? "" : ", Token=\"" + token + "\"");
    }
    JSONObject request(String method, String path, JSONObject body) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(server + path).openConnection();
        connection.setConnectTimeout(15000); connection.setReadTimeout(20000);
        connection.setInstanceFollowRedirects(false); // Never forward credentials to a redirected host.
        connection.setRequestMethod(method); connection.setRequestProperty("Authorization", authorization());
        connection.setRequestProperty("Accept", "application/json");
        try {
            if (body != null) {
                connection.setDoOutput(true); connection.setRequestProperty("Content-Type", "application/json");
                try (var output = connection.getOutputStream()) { output.write(body.toString().getBytes(StandardCharsets.UTF_8)); }
            } else if (method.equals("POST")) {
                connection.setDoOutput(true); connection.setFixedLengthStreamingMode(0); connection.getOutputStream().close();
            }
            int code = connection.getResponseCode();
            if (code < 200 || code >= 300) {
                if (code == 401) throw new IOException("Sign-in expired or credentials incorrect. Reconnect in Settings.");
                if (code == 403) throw new IOException("Your Jellyfin account does not have permission for this action.");
                throw new IOException("Jellyfin returned HTTP " + code + ". Check the server address and connection.");
            }
            if (code == 204) return new JSONObject();
            try (var input = connection.getInputStream()) {
                var bytes = new java.io.ByteArrayOutputStream(); byte[] buffer = new byte[8192]; int length;
                while ((length = input.read(buffer)) != -1) bytes.write(buffer, 0, length);
                String result = new String(bytes.toByteArray(), StandardCharsets.UTF_8);
                return result.isBlank() ? new JSONObject() : new JSONObject(result);
            }
        } finally { connection.disconnect(); }
    }
    List<Track> items(String type, String search, String parent, int start) throws Exception {
        return items(type,search,parent,start,parent!=null?"ParentIndexNumber,IndexNumber,SortName":type.equals("MusicAlbum")?"AlbumArtist,SortName":"SortName");
    }
    private List<Track> items(String type,String search,String parent,int start,String sort) throws Exception {
        String path = "/Users/" + userId + "/Items?Recursive=true&IncludeItemTypes=" + type
            + "&Fields=Artists,Album,RunTimeTicks,PrimaryImageAspectRatio&SortBy="+sort+"&SortOrder=Ascending&Limit=100&StartIndex=" + start;
        if (!search.isBlank()) path += "&SearchTerm=" + Uri.encode(search);
        if (parent != null) path += "&ParentId=" + Uri.encode(parent);
        return parse(request("GET", path, null));
    }
    /** Keep independent cursors so an older in-flight query cannot mix with a newer one. */
    private List<Track> searchTracks(String term,int start)throws Exception {
        TrackSearch cursor;
        synchronized(searches){
            cursor=searches.get(term);
            if(start==0||cursor==null){cursor=new TrackSearch(term);searches.put(term,cursor);}
            while(searches.size()>4)searches.remove(searches.keySet().iterator().next());
        }
        synchronized(cursor){return cursor.page(start);}
    }
    private final class TrackSearch {
        final String term;
        final List<Track> matches=new ArrayList<>();
        final java.util.Set<String> seen=new java.util.HashSet<>();
        List<Track> artists=List.of();
        int titleOffset=0,artistOffset=0,artistIndex=0,trackOffset=0;
        boolean titlesDone=false,artistsDone=false;
        TrackSearch(String term){this.term=term;}
        void append(List<Track> page){for(Track track:page)if(seen.add(track.id))matches.add(track);}
        List<Track> page(int start)throws Exception {
            int target=start+100;
            while(matches.size()<target){
                if(Thread.currentThread().isInterrupted())throw new java.io.InterruptedIOException("Search superseded");
                if(!titlesDone){
                    List<Track> page=items("Audio",term,null,titleOffset);append(page);titleOffset+=page.size();titlesDone=page.size()<100;continue;
                }
                if(artistIndex>=artists.size()){
                    if(artistsDone)break;
                    List<Track> page=parse(request("GET","/Artists?UserId="+userId+"&Recursive=true&IncludeItemTypes=Audio&SortBy=SortName&Limit=100&StartIndex="+artistOffset+"&SearchTerm="+Uri.encode(term),null));
                    artists=page;artistIndex=0;artistOffset+=page.size();artistsDone=page.size()<100;
                    if(artists.isEmpty())break;
                }
                String artist=artists.get(artistIndex).id;
                List<Track> page=parse(request("GET","/Users/"+userId+"/Items?Recursive=true&IncludeItemTypes=Audio&ArtistIds="+Uri.encode(artist)+"&Fields=Artists,Album,RunTimeTicks,PrimaryImageAspectRatio&SortBy=SortName&Limit=100&StartIndex="+trackOffset,null));
                append(page);trackOffset+=page.size();
                if(page.size()<100){artistIndex++;trackOffset=0;}
            }
            int from=Math.min(start,matches.size());return new ArrayList<>(matches.subList(from,Math.min(target,matches.size())));
        }
    }
    List<Track> playlistItems(String id) throws Exception {
        List<Track> all = new ArrayList<>();
        for (int start = 0; ; start += 100) {
            JSONObject result = request("GET", "/Playlists/" + id + "/Items?UserId=" + userId + "&Fields=Artists,Album,RunTimeTicks,PrimaryImageAspectRatio&Limit=100&StartIndex=" + start, null);
            List<Track> page = parse(result); all.addAll(page);
            if (page.size() < 100) return all;
        }
    }
    void create(String name, List<Track> tracks) throws Exception {
        JSONArray ids = new JSONArray(); for (Track track : tracks) ids.put(track.id);
        request("POST", "/Playlists", new JSONObject().put("Name", name).put("Ids", ids).put("UserId", userId).put("MediaType", "Audio").put("IsPublic", false));
    }
    void rename(String id, String name) throws Exception { request("POST", "/Playlists/" + id, new JSONObject().put("Name", name)); }
    void add(String id, Track track) throws Exception { request("POST", "/Playlists/" + id + "/Items?UserId=" + userId + "&Ids=" + Uri.encode(track.id), null); }
    void remove(String id, Track track) throws Exception {
        requireEntry(track); request("DELETE", "/Playlists/" + id + "/Items?EntryIds=" + Uri.encode(track.entryId), null);
    }
    void move(String id, Track track, int index) throws Exception {
        requireEntry(track); request("POST", "/Playlists/" + id + "/Items/" + Uri.encode(track.entryId) + "/Move/" + index, null);
    }
    private static void requireEntry(Track track) throws IOException { if (track.entryId.isBlank()) throw new IOException("This server did not provide a playlist entry ID. Refresh and try again."); }
    String stream(Track track) {
        return server + "/Audio/" + track.id + "/universal?UserId=" + userId + "&DeviceId=" + deviceId
            + "&Container=" + Uri.encode("mp3|mp3,aac|aac,m4a|aac,flac|flac,ogg|vorbis|opus,wav|pcm_s16le|pcm_s24le")
            + "&AudioCodec=mp3&TranscodingContainer=mp3&TranscodingProtocol=http&AudioBitRate=320000&MaxStreamingBitrate=140000000&EnableRedirection=false";
    }
    List<Track> browse(String section,String search,String parent,String parentType,int start) throws Exception {
        if(parent==null&&section.equals("Tracks")&&!search.isBlank())return searchTracks(search.trim(),start);
        if(parent!=null && parentType.equals("Playlist"))return playlistItems(parent);
        if(parent!=null && parentType.equals("MusicArtist"))return parse(request("GET","/Users/"+userId+"/Items?Recursive=true&IncludeItemTypes=MusicAlbum&ArtistIds="+Uri.encode(parent)+"&Fields=Artists,Album&SortBy=SortName&Limit=100&StartIndex="+start,null));
        if(section.equals("Artists")&&parent==null)return items("Audio",search,null,start,"Artist,SortName");
        if((section.equals("Favourites")||section.equals("History"))&&parent==null){
            String extra=section.equals("Favourites")?"&Filters=IsFavorite&SortBy=SortName":"&Filters=IsPlayed&SortBy=DatePlayed&SortOrder=Descending";
            return parse(request("GET","/Users/"+userId+"/Items?Recursive=true&IncludeItemTypes=Audio&Fields=Artists,Album,RunTimeTicks&EnableUserData=true&Limit=100&StartIndex="+start+"&SearchTerm="+Uri.encode(search)+extra,null));
        }
        return items(parent!=null?"Audio":switch(section){case "Albums"->"MusicAlbum";case "Playlists"->"Playlist";default->"Audio";},search,parent,start);
    }
    Track item(String id)throws Exception{return Track.from(request("GET","/Users/"+userId+"/Items/"+Uri.encode(id),null));}
    List<Track> allTracks(String id,String type)throws Exception{
        if(type.equals("Playlist"))return playlistItems(id);
        List<Track> all=new ArrayList<>();for(int start=0;;start+=100){List<Track> page=items("Audio","",id,start);all.addAll(page);if(page.size()<100)return all;}
    }
    void addMany(String id,List<Track> tracks)throws Exception{
        if(tracks.isEmpty())return;StringBuilder ids=new StringBuilder();for(Track track:tracks){if(ids.length()>0)ids.append(',');ids.append(track.id);}
        request("POST","/Playlists/"+id+"/Items?UserId="+userId+"&Ids="+Uri.encode(ids.toString()),null);
    }
    void removeMany(String id,List<Track> tracks)throws Exception{
        StringBuilder ids=new StringBuilder();for(Track track:tracks){requireEntry(track);if(ids.length()>0)ids.append(',');ids.append(track.entryId);}
        if(ids.length()>0)request("DELETE","/Playlists/"+id+"/Items?EntryIds="+Uri.encode(ids.toString()),null);
    }
    void restoreEntry(String id,Track track,int index)throws Exception{
        List<Track> before=playlistItems(id);add(id,track);List<Track> after=playlistItems(id);
        java.util.Set<String> existing=new java.util.HashSet<>();for(Track t:before)existing.add(t.entryId);
        for(Track t:after)if(!existing.contains(t.entryId)&&t.id.equals(track.id)){move(id,t,Math.min(index,after.size()-1));return;}
        throw new IOException("Jellyfin did not restore the entry (it may already be present).");
    }
    void favorite(Track track,boolean value)throws Exception{request(value?"POST":"DELETE","/Users/"+userId+"/FavoriteItems/"+track.id,null);}
    String downloadUrl(Track track){return server+"/Items/"+track.id+"/Download";}
    String imageUrl(Track track){return track.imageId.isBlank()?"":server+"/Items/"+track.imageId+"/Images/Primary?MaxWidth=400&Quality=85&Tag="+Uri.encode(track.imageTag);}
    byte[] image(Track track)throws Exception{
        String url=imageUrl(track);if(url.isBlank())return new byte[0];
        HttpURLConnection c=(HttpURLConnection)new URL(url).openConnection();c.setInstanceFollowRedirects(false);c.setConnectTimeout(10000);c.setReadTimeout(15000);c.setRequestProperty("Authorization",authorization());
        try{if(c.getResponseCode()!=200)return new byte[0];try(var input=c.getInputStream();var out=new java.io.ByteArrayOutputStream()){
            byte[] buffer=new byte[8192];int n;while((n=input.read(buffer))!=-1){if(out.size()+n>3000000)throw new IOException("Artwork too large");out.write(buffer,0,n);}return out.toByteArray();
        }}finally{c.disconnect();}
    }
    static List<Track> parse(JSONObject result) throws Exception {
        List<Track> tracks = new ArrayList<>(); JSONArray items = result.optJSONArray("Items");
        if (items != null) for (int i = 0; i < items.length(); i++) tracks.add(Track.from(items.getJSONObject(i)));
        return tracks;
    }
}

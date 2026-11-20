package dev.rewindamp;

import org.json.JSONArray;
import org.json.JSONObject;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;

/** Account-scoped, app-private metadata. No authentication headers or stream URLs. */
abstract class LibraryData {
    final File directory;
    final String scope;
    LibraryData(File parent,String address,String account){
        scope=hash(address+"\n"+account);
        directory=new File(parent,"library-"+scope);
    }
    abstract JSONObject read(String key);
    void write(String key,JSONObject json){try{writeChecked(key,json);}catch(IllegalStateException ignored){}}
    abstract void writeChecked(String key,JSONObject json);
    static String hash(String text){try{byte[] value=MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));StringBuilder out=new StringBuilder();for(byte b:value)out.append(String.format(java.util.Locale.ROOT,"%02x",b));return out.substring(0,24);}catch(Exception e){throw new IllegalStateException(e);}}
    void tracks(String key,List<Track> tracks){try{JSONArray array=new JSONArray();for(Track t:tracks)array.put(t.json());write(key,new JSONObject().put("Items",array));}catch(Exception ignored){}}
    List<Track> tracks(String key){try{return JellyfinApi.parse(read(key));}catch(Exception ignored){return new ArrayList<>();}}
    List<Track> cachedTracks(String key){JSONObject saved=read(key);if(saved.optJSONArray("Items")==null)return null;try{return JellyfinApi.parse(saved);}catch(Exception ignored){return null;}}
    File artFile(Track track){return new File(directory,"art-"+hash(track.imageId+":"+track.imageTag)+".jpg");}
    byte[] artwork(Track track){try{return java.nio.file.Files.readAllBytes(artFile(track).toPath());}catch(Exception ignored){return new byte[0];}}
    void artwork(Track track,byte[] bytes){if(bytes.length==0)return;try{java.nio.file.Files.write(artFile(track).toPath(),bytes);}catch(Exception ignored){}}
    synchronized void editFavorite(Track track,boolean value){
        try{JSONObject pending=read("pending-favourites");pending.put(track.id,value);write("pending-favourites",pending);
            List<Track> favorites=tracks("favourites-local");favorites.removeIf(t->t.id.equals(track.id));if(value)favorites.add(track.withFavorite(true));tracks("favourites-local",favorites);
        }catch(Exception ignored){}
    }
    synchronized Boolean favoriteOverride(String id){JSONObject pending=read("pending-favourites");return pending.has(id)?pending.optBoolean(id):null;}
}

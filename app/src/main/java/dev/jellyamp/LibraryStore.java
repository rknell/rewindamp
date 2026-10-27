package dev.jellyamp;

import android.content.Context;
import android.util.AtomicFile;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;

/** Account-scoped, app-private metadata. No authentication headers or stream URLs. */
final class LibraryStore {
    final File directory;
    final String scope;
    LibraryStore(Context context,JellyfinApi api){
        scope=hash(api.server+"\n"+api.userId);directory=new File(context.getFilesDir(),"library-"+scope);directory.mkdirs();
    }
    static String hash(String text){try{byte[] value=MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));StringBuilder out=new StringBuilder();for(byte b:value)out.append(String.format(java.util.Locale.ROOT,"%02x",b));return out.substring(0,24);}catch(Exception e){throw new IllegalStateException(e);}}
    synchronized JSONObject read(String key){
        try(var input=new AtomicFile(new File(directory,hash(key)+".json")).openRead();var out=new java.io.ByteArrayOutputStream()){
            byte[] buffer=new byte[8192];int n;while((n=input.read(buffer))!=-1)out.write(buffer,0,n);return new JSONObject(out.toString(StandardCharsets.UTF_8.name()));
        }catch(Exception ignored){return new JSONObject();}
    }
    synchronized void write(String key,JSONObject json){
        AtomicFile file=new AtomicFile(new File(directory,hash(key)+".json"));java.io.FileOutputStream output=null;
        try{output=file.startWrite();output.write(json.toString().getBytes(StandardCharsets.UTF_8));file.finishWrite(output);}catch(Exception e){if(output!=null)file.failWrite(output);}
    }
    void tracks(String key,List<Track> tracks){try{JSONArray array=new JSONArray();for(Track t:tracks)array.put(t.json());write(key,new JSONObject().put("Items",array));}catch(Exception ignored){}}
    List<Track> tracks(String key){try{return JellyfinApi.parse(read(key));}catch(Exception ignored){return new ArrayList<>();}}
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

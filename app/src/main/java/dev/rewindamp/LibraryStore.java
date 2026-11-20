package dev.rewindamp;

import android.content.Context;
import android.util.AtomicFile;
import java.io.File;
import java.nio.charset.StandardCharsets;
import org.json.JSONObject;

final class LibraryStore extends LibraryData {
    LibraryStore(Context context,JellyfinApi api){
        super(context.getFilesDir(),api.server,api.userId);
        directory.mkdirs();
    }
    synchronized JSONObject read(String key){
        try(var input=new AtomicFile(new File(directory,hash(key)+".json")).openRead();var out=new java.io.ByteArrayOutputStream()){
            byte[] buffer=new byte[8192];int n;while((n=input.read(buffer))!=-1)out.write(buffer,0,n);return new JSONObject(out.toString(StandardCharsets.UTF_8.name()));
        }catch(Exception ignored){return new JSONObject();}
    }
    synchronized void write(String key,JSONObject json){
        AtomicFile file=new AtomicFile(new File(directory,hash(key)+".json"));java.io.FileOutputStream output=null;
        try{output=file.startWrite();output.write(json.toString().getBytes(StandardCharsets.UTF_8));file.finishWrite(output);}catch(Exception e){if(output!=null)file.failWrite(output);}
    }
    synchronized void writeChecked(String key,JSONObject json){
        AtomicFile file=new AtomicFile(new File(directory,hash(key)+".json"));java.io.FileOutputStream output=null;
        try{output=file.startWrite();output.write(json.toString().getBytes(StandardCharsets.UTF_8));file.finishWrite(output);}
        catch(Exception e){if(output!=null)file.failWrite(output);throw new IllegalStateException("Could not persist private listening history",e);}
    }
}

package dev.jellyamp;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.widget.ImageView;
import android.os.Handler;
import android.os.Looper;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

final class ArtworkLoader {
    private final ExecutorService io=Executors.newFixedThreadPool(2);
    private final Handler main=new Handler(Looper.getMainLooper());
    private final android.util.LruCache<String,Bitmap> memory=new android.util.LruCache<>(40);
    void show(ImageView view,Track track,JellyfinApi api,LibraryStore store){
        String key=track.imageId+":"+track.imageTag;view.setTag(key);view.setImageResource(R.drawable.ic_jellyamp);
        if(api==null||store==null||track.imageId.isBlank())return;
        Bitmap bitmap=memory.get(key);if(bitmap!=null){view.setImageBitmap(bitmap);return;}
        io.execute(()->{try{byte[] data=store.artwork(track);if(data.length==0){data=api.image(track);store.artwork(track,data);}Bitmap result=BitmapFactory.decodeByteArray(data,0,data.length);if(result!=null){memory.put(key,result);main.post(()->{if(key.equals(view.getTag()))view.setImageBitmap(result);});}}catch(Exception ignored){}});
    }
    void close(){io.shutdownNow();main.removeCallbacksAndMessages(null);}
}

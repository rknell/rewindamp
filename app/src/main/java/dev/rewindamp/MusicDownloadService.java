package dev.rewindamp;

import android.app.Notification;
import androidx.media3.exoplayer.offline.Download;
import androidx.media3.exoplayer.offline.DownloadManager;
import androidx.media3.exoplayer.offline.DownloadNotificationHelper;
import androidx.media3.exoplayer.offline.DownloadService;
import androidx.media3.exoplayer.scheduler.PlatformScheduler;
import androidx.media3.exoplayer.scheduler.Scheduler;
import java.util.List;

@androidx.annotation.OptIn(markerClass=androidx.media3.common.util.UnstableApi.class)
public final class MusicDownloadService extends DownloadService {
    public MusicDownloadService(){super(20,1000,"rewindamp-downloads",R.string.download_channel,0);}
    @Override protected DownloadManager getDownloadManager(){
        JellyfinApi api=SessionStore.load(this);if(api==null){
            // A delayed scheduler restart after sign-out must not access the previous account.
            var idle=OfflineLibrary.get(this,new JellyfinApi("https://signed-out.invalid","","signed-out","none")).manager;idle.pauseDownloads();return idle;
        }
        return OfflineLibrary.get(this,api).manager;
    }
    @Override protected Scheduler getScheduler(){return new PlatformScheduler(this,42);}
    @Override protected Notification getForegroundNotification(List<Download> downloads,int unmet){
        var intent=android.app.PendingIntent.getActivity(this,1,new android.content.Intent(this,MainActivity.class),android.app.PendingIntent.FLAG_IMMUTABLE);
        return new DownloadNotificationHelper(this,"rewindamp-downloads").buildProgressNotification(this,R.drawable.ic_rewindamp,intent,"Saving your music for offline listening",downloads,unmet);
    }
    @Override public void onTimeout(int startId,int type){pauseAll();stopSelf();}
    private void pauseAll(){getDownloadManager().pauseDownloads();}
}

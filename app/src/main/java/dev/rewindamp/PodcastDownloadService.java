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
public final class PodcastDownloadService extends DownloadService {
    public PodcastDownloadService(){super(21,1000,"rewindamp-podcasts",R.string.download_channel,0);}
    @Override protected DownloadManager getDownloadManager(){
        PodcastApi api=PodcastApi.load(this);
        if(api==null){var manager=PodcastLibrary.get(this,new PodcastApi("https://signed-out.invalid","signed-out")).manager;manager.pauseDownloads();return manager;}
        return PodcastLibrary.get(this,api).manager;
    }
    @Override protected Scheduler getScheduler(){return new PlatformScheduler(this,43);}
    @Override protected Notification getForegroundNotification(List<Download> downloads,int unmet){
        var intent=android.app.PendingIntent.getActivity(this,2,new android.content.Intent(this,PodcastActivity.class),android.app.PendingIntent.FLAG_IMMUTABLE);
        return new DownloadNotificationHelper(this,"rewindamp-podcasts").buildProgressNotification(this,R.drawable.ic_rewindamp,intent,"Saving podcast copies on this phone",downloads,unmet);
    }
    @Override public void onTimeout(int startId,int type){getDownloadManager().pauseDownloads();stopSelf();}
}

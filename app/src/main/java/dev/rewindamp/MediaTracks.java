package dev.rewindamp;

import android.os.Bundle;
import androidx.media3.common.MediaItem;
import org.json.JSONObject;

final class MediaTracks {
    static Track fromMedia(MediaItem item) {
        try {Bundle extra=item.mediaMetadata.extras;if(extra!=null&&extra.containsKey("track"))return Track.from(new JSONObject(extra.getString("track")));}catch(Exception ignored){}
        return new Track(item.mediaId,String.valueOf(item.mediaMetadata.title),item.mediaMetadata.artist==null?"":item.mediaMetadata.artist.toString(),"","Audio","",0);
    }
}

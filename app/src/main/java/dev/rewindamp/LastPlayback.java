package dev.rewindamp;

import android.content.Context;

/** Updated only by actual playback, never by browsing or restoring a paused queue. */
final class LastPlayback {
    static boolean podcasts(Context context){return context.getSharedPreferences("last-playback",Context.MODE_PRIVATE).getBoolean("podcasts",false);}
    static void record(Context context,boolean podcasts){
        if(podcasts(context)!=podcasts)context.getSharedPreferences("last-playback",Context.MODE_PRIVATE).edit().putBoolean("podcasts",podcasts).commit();
    }
}

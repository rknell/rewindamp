package dev.rewindamp;

import android.content.Context;
import java.util.Arrays;

final class DspSettings {
    private static volatile Equalizer.Values values=new Equalizer.Values(false,0,new float[10],0);
    static Equalizer.Values get(){return values;}
    static void load(Context context){var prefs=context.getSharedPreferences("equalizer",0);float[] gains=new float[10];for(int i=0;i<10;i++)gains[i]=prefs.getFloat("band"+i,0);values=new Equalizer.Values(prefs.getBoolean("enabled",false),prefs.getFloat("preamp",0),gains,values.version()+1);}
    static synchronized void set(Context context,boolean enabled,float preamp,float[] gains){
        float[] copy=Arrays.copyOf(gains,10);for(int i=0;i<10;i++)copy[i]=Math.max(-12,Math.min(12,copy[i]));
        values=new Equalizer.Values(enabled,Math.max(-12,Math.min(12,preamp)),copy,values.version()+1);
        var edit=context.getSharedPreferences("equalizer",0).edit().putBoolean("enabled",enabled).putFloat("preamp",values.preamp());
        for(int i=0;i<10;i++)edit.putFloat("band"+i,copy[i]);edit.apply();
    }
    static void preset(Context context,String name){
        set(context,true,0,Equalizer.preset(name));
    }
}

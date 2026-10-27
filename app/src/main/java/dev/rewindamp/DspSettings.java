package dev.rewindamp;

import android.content.Context;
import java.util.Arrays;

final class DspSettings {
    static final int[] FREQUENCIES={70,180,320,600,1000,3000,6000,12000,14000,16000};
    static final String[] LABELS={"70","180","320","600","1K","3K","6K","12K","14K","16K"};
    record Values(boolean enabled,float preamp,float[] gains,long version){}
    private static volatile Values values=new Values(false,0,new float[10],0);
    static Values get(){return values;}
    static void load(Context context){var prefs=context.getSharedPreferences("equalizer",0);float[] gains=new float[10];for(int i=0;i<10;i++)gains[i]=prefs.getFloat("band"+i,0);values=new Values(prefs.getBoolean("enabled",false),prefs.getFloat("preamp",0),gains,values.version+1);}
    static synchronized void set(Context context,boolean enabled,float preamp,float[] gains){
        float[] copy=Arrays.copyOf(gains,10);for(int i=0;i<10;i++)copy[i]=Math.max(-12,Math.min(12,copy[i]));
        values=new Values(enabled,Math.max(-12,Math.min(12,preamp)),copy,values.version+1);
        var edit=context.getSharedPreferences("equalizer",0).edit().putBoolean("enabled",enabled).putFloat("preamp",values.preamp);
        for(int i=0;i<10;i++)edit.putFloat("band"+i,copy[i]);edit.apply();
    }
    static void preset(Context context,String name){
        float[] gains=switch(name){case "Rock"->new float[]{5,3,-2,-3,1,3,5,6,5,4};case "Bass"->new float[]{8,6,3,0,-1,-1,0,1,1,1};case "Vocal"->new float[]{-4,-3,-1,2,4,5,3,0,-2,-3};case "Electronic"->new float[]{6,4,1,-2,0,3,2,4,5,6};default->new float[10];};
        set(context,true,0,gains);
    }
}

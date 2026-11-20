package dev.rewindamp;

final class Equalizer {
    static final int[] FREQUENCIES={70,180,320,600,1000,3000,6000,12000,14000,16000};
    static final String[] LABELS={"70","180","320","600","1K","3K","6K","12K","14K","16K"};
    record Values(boolean enabled,float preamp,float[] gains,long version){}
    static float[] preset(String name){
        return switch(name){case "Rock"->new float[]{5,3,-2,-3,1,3,5,6,5,4};case "Bass"->new float[]{8,6,3,0,-1,-1,0,1,1,1};case "Vocal"->new float[]{-4,-3,-1,2,4,5,3,0,-2,-3};case "Electronic"->new float[]{6,4,1,-2,0,3,2,4,5,6};default->new float[10];};
    }
}

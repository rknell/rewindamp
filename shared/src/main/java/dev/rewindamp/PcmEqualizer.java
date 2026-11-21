package dev.rewindamp;

import java.nio.ByteBuffer;

/** Channel-local ten-band equalization and optional post-EQ spectrum. */
final class PcmEqualizer {
    private final int rate, channels;
    private final double[][] coefficients=new double[10][5];
    private final double[][] z1,z2;
    private final double[] samples=new double[1024];
    private final double[] window=new double[1024],goertzel=new double[28];
    private int sampleCount=0;
    private long version=-1;
    private float multiplier=1;
    private boolean enabled;
    PcmEqualizer(int rate,int channels){
        this.rate=rate;this.channels=channels;
        z1=new double[10][channels];z2=new double[10][channels];
        for(int i=0;i<1024;i++)window[i]=0.5-0.5*Math.cos(2*Math.PI*i/1023);
        for(int i=0;i<28;i++){double frequency=Math.min(50*Math.pow(320,i/27.0),rate*.45);int k=(int)Math.round(1024*frequency/rate);goertzel[i]=2*Math.cos(2*Math.PI*k/1024);}
    }
    private void configureBands(Equalizer.Values settings){
        if(settings.version()==version)return;version=settings.version();enabled=settings.enabled();
        float maximum=0;for(float g:settings.gains())maximum=Math.max(maximum,g);
        multiplier=(float)Math.pow(10,(settings.preamp()-maximum)/20f); // Automatic boost headroom prevents accidental clipping.
        for(int i=0;i<10;i++){
            double frequency=Math.min(Equalizer.FREQUENCIES[i],rate*0.45);
            double omega=2*Math.PI*frequency/rate,alpha=Math.sin(omega)/(2*1.4),a=Math.pow(10,settings.gains()[i]/40.0),a0=1+alpha/a;
            coefficients[i][0]=(1+alpha*a)/a0;coefficients[i][1]=-2*Math.cos(omega)/a0;coefficients[i][2]=(1-alpha*a)/a0;
            coefficients[i][3]=-2*Math.cos(omega)/a0;coefficients[i][4]=(1-alpha/a)/a0;
        }
    }
    float[] process(ByteBuffer input,ByteBuffer output,Equalizer.Values settings,boolean visualize){
        configureBands(settings);
        float[] latest=null;
        if(!visualize){
            sampleCount=0;
            if(!enabled){output.put(input);return null;}
        }
        while(input.remaining()>=channels*2){
            double mono=0;
            for(int channel=0;channel<channels;channel++){
                double value=input.getShort()/32768.0;
                if(enabled){value*=multiplier;for(int band=0;band<10;band++){double[] b=coefficients[band];double result=b[0]*value+z1[band][channel];z1[band][channel]=b[1]*value-b[3]*result+z2[band][channel];z2[band][channel]=b[2]*value-b[4]*result;value=result;}}
                value=Math.max(-1,Math.min(0.999969,value));mono+=value;output.putShort((short)Math.round(value*32768));
            }
            if(visualize){samples[sampleCount++]=mono/channels;if(sampleCount==samples.length){latest=analyse();sampleCount=0;}}
        }
        return latest;
    }
    private float[] analyse(){
        float[] bars=new float[28];int n=samples.length;
        for(int i=0;i<bars.length;i++){
            double coeff=goertzel[i],q1=0,q2=0;
            for(int j=0;j<n;j++){double sample=samples[j]*window[j];double q=sample+coeff*q1-q2;q2=q1;q1=q;}
            double amplitude=4*Math.sqrt(Math.max(0,q1*q1+q2*q2-coeff*q1*q2))/n;
            bars[i]=(float)Math.max(0,Math.min(1,(20*Math.log10(Math.max(amplitude,0.000001))+60)/60));
        }
        return bars;
    }
}

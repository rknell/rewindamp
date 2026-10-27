package dev.rewindamp;

import androidx.media3.common.C;
import androidx.media3.common.audio.BaseAudioProcessor;
import androidx.media3.common.audio.AudioProcessor;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/** Ten peaking biquads per channel plus post-EQ spectrum analysis; never records a microphone. */
@androidx.annotation.OptIn(markerClass=androidx.media3.common.util.UnstableApi.class)
final class AudioDsp extends BaseAudioProcessor {
    static volatile float[] spectrum=new float[28];
    static volatile boolean playing=false;
    static volatile int sampleRate=0,channels=0;
    private final java.util.function.BooleanSupplier publish;
    AudioDsp(){this(()->true);}
    AudioDsp(java.util.function.BooleanSupplier publish){this.publish=publish;}
    private final double[][] coefficients=new double[10][5];
    private double[][] z1,z2;
    private final double[] samples=new double[1024];
    private final double[] window=new double[1024],goertzel=new double[28];
    private int sampleCount=0;
    private long version=-1;
    private float multiplier=1;
    private boolean enabled;
    @Override protected AudioFormat onConfigure(AudioFormat format)throws UnhandledAudioFormatException{
        if(format.encoding!=C.ENCODING_PCM_16BIT)throw new UnhandledAudioFormatException(format);
        if(publish.getAsBoolean()){sampleRate=format.sampleRate;channels=format.channelCount;}
        for(int i=0;i<1024;i++)window[i]=0.5-0.5*Math.cos(2*Math.PI*i/1023);
        for(int i=0;i<28;i++){double frequency=Math.min(50*Math.pow(320,i/27.0),format.sampleRate*.45);int k=(int)Math.round(1024*frequency/format.sampleRate);goertzel[i]=2*Math.cos(2*Math.PI*k/1024);}
        return format;
    }
    @Override protected void onFlush(){z1=new double[10][inputAudioFormat.channelCount];z2=new double[10][inputAudioFormat.channelCount];sampleCount=0;version=-1;}
    @Override protected void onReset(){if(publish.getAsBoolean())spectrum=new float[28];}
    private void configureBands(){
        DspSettings.Values settings=DspSettings.get();if(settings.version()==version)return;version=settings.version();enabled=settings.enabled();
        float maximum=0;for(float g:settings.gains())maximum=Math.max(maximum,g);
        multiplier=(float)Math.pow(10,(settings.preamp()-maximum)/20f); // Automatic boost headroom prevents accidental clipping.
        for(int i=0;i<10;i++){
            double frequency=Math.min(DspSettings.FREQUENCIES[i],inputAudioFormat.sampleRate*0.45);
            double omega=2*Math.PI*frequency/inputAudioFormat.sampleRate,alpha=Math.sin(omega)/(2*1.4),a=Math.pow(10,settings.gains()[i]/40.0),a0=1+alpha/a;
            coefficients[i][0]=(1+alpha*a)/a0;coefficients[i][1]=-2*Math.cos(omega)/a0;coefficients[i][2]=(1-alpha*a)/a0;
            coefficients[i][3]=-2*Math.cos(omega)/a0;coefficients[i][4]=(1-alpha/a)/a0;
        }
    }
    @Override public void queueInput(ByteBuffer input){
        configureBands();input.order(ByteOrder.nativeOrder());ByteBuffer output=replaceOutputBuffer(input.remaining()).order(ByteOrder.nativeOrder());int channels=inputAudioFormat.channelCount;
        while(input.remaining()>=channels*2){
            double mono=0;
            for(int channel=0;channel<channels;channel++){
                double value=input.getShort()/32768.0;
                if(enabled){value*=multiplier;for(int band=0;band<10;band++){double[] b=coefficients[band];double result=b[0]*value+z1[band][channel];z1[band][channel]=b[1]*value-b[3]*result+z2[band][channel];z2[band][channel]=b[2]*value-b[4]*result;value=result;}}
                value=Math.max(-1,Math.min(0.999969,value));mono+=value;output.putShort((short)Math.round(value*32768));
            }
            samples[sampleCount++]=mono/channels;if(sampleCount==samples.length){analyse();sampleCount=0;}
        }
        // Decoders supply whole PCM frames; don't leave a partial tail to stall the sink.
        input.position(input.limit());output.flip();
    }
    private void analyse(){
        if(!publish.getAsBoolean())return;sampleRate=inputAudioFormat.sampleRate;channels=inputAudioFormat.channelCount;
        float[] bars=new float[28];int n=samples.length;
        for(int i=0;i<bars.length;i++){
            double coeff=goertzel[i],q1=0,q2=0;
            for(int j=0;j<n;j++){double sample=samples[j]*window[j];double q=sample+coeff*q1-q2;q2=q1;q1=q;}
            double amplitude=4*Math.sqrt(Math.max(0,q1*q1+q2*q2-coeff*q1*q2))/n;
            bars[i]=(float)Math.max(0,Math.min(1,(20*Math.log10(Math.max(amplitude,0.000001))+60)/60));
        }
        spectrum=bars;
    }
}

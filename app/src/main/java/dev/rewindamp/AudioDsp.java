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
    static volatile Object spectrumOwner;
    private final java.util.function.BooleanSupplier publish;
    private final boolean standalone;
    private long nextSpectrum;
    AudioDsp(){this(()->true,true);}
    AudioDsp(java.util.function.BooleanSupplier publish){this(publish,false);}
    private AudioDsp(java.util.function.BooleanSupplier publish,boolean standalone){this.publish=publish;this.standalone=standalone;}
    private PcmEqualizer equalizer;
    @Override protected AudioFormat onConfigure(AudioFormat format)throws UnhandledAudioFormatException{
        if(format.encoding!=C.ENCODING_PCM_16BIT)throw new UnhandledAudioFormatException(format);
        if(publish.getAsBoolean()){sampleRate=format.sampleRate;channels=format.channelCount;}
        return format;
    }
    @Override protected void onFlush(){equalizer=new PcmEqualizer(inputAudioFormat.sampleRate,inputAudioFormat.channelCount);}
    @Override protected void onReset(){if(publish.getAsBoolean())spectrum=new float[28];}
    @Override public void queueInput(ByteBuffer input){
        input.order(ByteOrder.nativeOrder());
        ByteBuffer output=replaceOutputBuffer(input.remaining()).order(ByteOrder.nativeOrder());
        long now=System.nanoTime();
        boolean visible=publish.getAsBoolean()&&(standalone||(spectrumOwner!=null&&now>=nextSpectrum));
        float[] bars=equalizer.process(input,output,DspSettings.get(),visible);
        if(bars!=null){nextSpectrum=now+33_000_000L;sampleRate=inputAudioFormat.sampleRate;channels=inputAudioFormat.channelCount;spectrum=bars;}
        input.position(input.limit());output.flip();
    }
}

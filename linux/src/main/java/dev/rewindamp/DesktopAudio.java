package dev.rewindamp;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

final class DesktopAudio implements AutoCloseable {
    static final int RATE = 48000, CHANNELS = 2, BLOCK = RATE / 50 * CHANNELS * 2;
    private static final byte[] END = new byte[0];
    private static final String AUDIO_FORMATS = "aac,ac3,aiff,amr,ape,asf,au,caf,dsf,eac3,flac,matroska,mov,mp3,ogg,tta,wav,wv";
    record Item(String key, MediaSource source, long duration, long resume) {}
    record Boundary(Item item, long finalPosition, Item successor, boolean complete, String error) {}
    volatile Equalizer.Values equalizer = new Equalizer.Values(false, 0, new float[10], 0);
    volatile boolean visible;
    volatile float volume = 1;
    volatile float[] spectrum = new float[28];
    private final MediaSource.Relay relay;
    private final Consumer<Boundary> callback;
    private volatile Run active;

    DesktopAudio(Consumer<Boundary> callback) throws IOException {
        this.callback = callback;
        relay = new MediaSource.Relay();
    }

    void start(Item item, long position, double speed) {
        stop();
        Run run = new Run(item, position, speed);
        active = run;
        run.thread.start();
    }

    void successor(Item item, String mode, int seconds) {
        Run run = active;
        if (run != null) run.plan = new Plan(item, mode, seconds);
    }

    void stop() {
        Run run = active;
        active = null;
        spectrum = new float[28];
        if (run != null) {
            run.thread.interrupt();
            Process sink = run.sink;
            if (sink != null) sink.destroyForcibly();
        }
    }

    long position() {
        Run run = active;
        return run == null ? 0 : run.position;
    }

    @Override public void close() {
        stop();
        relay.close();
    }

    private record Plan(Item item, String mode, int seconds) {}

    private final class Run implements Runnable {
        final Thread thread = new Thread(this, "rewindamp-audio");
        final Item first;
        final double rate;
        volatile long position;
        volatile Plan plan = new Plan(null, "gapless", 0);
        volatile Process sink;

        Run(Item first, long position, double rate) {
            this.first = first;
            this.position = position;
            this.rate = rate;
            thread.setDaemon(true);
        }

        @Override public void run() {
            Decoder decoder = null, prepared = null, outgoing = null;
            Item item = first;
            long fadeStart = 0, fadeDuration = 0, nextSpectrum = 0, offlineCheck = 0;
            boolean fallback = false, retried = false;
            PcmEqualizer dsp = new PcmEqualizer(RATE, CHANNELS);
            ByteBuffer processed = ByteBuffer.allocate(BLOCK).order(ByteOrder.LITTLE_ENDIAN);
            try {
                if (active != this) return;
                sink = new ProcessBuilder("pacat", "--playback", "--raw", "--format=s16le", "--rate=" + RATE,
                    "--channels=" + CHANNELS, "--latency-msec=70", "--client-name=Rewindamp", "--stream-name=Rewindamp")
                    .redirectError(ProcessBuilder.Redirect.DISCARD).start();
                decoder = new Decoder(item, position, rate);
                try (OutputStream output = sink.getOutputStream()) {
                    while (active == this) {
                        Plan upcoming = plan;
                        long remaining = item.duration - position;
                        long fade = upcoming.mode.equals("crossfade") && upcoming.item != null && !item.key.equals(upcoming.item.key)
                            ? Math.min(upcoming.seconds * 1000L, item.duration / 2) : 0;
                        if (prepared != null && !prepared.item.equals(upcoming.item)) {
                            prepared.close();
                            prepared = null;
                        }
                        if (prepared == null && outgoing == null && upcoming.item != null && remaining > 0 && remaining <= Math.max(1800, fade + 1500))
                            prepared = new Decoder(upcoming.item, upcoming.item.resume, rate);
                        if (fade > 0 && remaining > 0 && remaining <= fade && prepared != null && prepared.ready()) {
                            outgoing = decoder;
                            decoder = prepared;
                            prepared = null;
                            fadeStart = decoder.start;
                            fadeDuration = remaining;
                            if (plan == upcoming) plan = new Plan(null, upcoming.mode, upcoming.seconds);
                            changed(item, position, decoder.item, true, null);
                            item = decoder.item;
                            position = decoder.start;
                            fallback = false;
                            retried = false;
                            offlineCheck = position;
                        }
                        if (!decoder.local && position >= offlineCheck && outgoing == null && item.source.offline() != null) {
                            offlineCheck = position + 1500;
                            if (Files.isRegularFile(item.source.offline())) {
                                decoder.close();
                                decoder = new Decoder(item, position, rate);
                            }
                        }
                        byte[] block = decoder.take();
                        if (block == null || block == END) {
                            if (outgoing != null) {
                                outgoing.close();
                                outgoing = null;
                            }
                            boolean interrupted = !decoder.local && (block == null || decoder.stream.interrupted);
                            if (interrupted || block == null || decoder.failed) {
                                if (!fallback && !decoder.local && item.source.offline() != null && Files.isRegularFile(item.source.offline())) {
                                    decoder.close();
                                    decoder = new Decoder(item, position, rate);
                                    fallback = true;
                                    continue;
                                }
                                if (interrupted && !retried) {
                                    retried = true;
                                    decoder.close();
                                    Thread.sleep(350);
                                    decoder = new Decoder(item, position, rate);
                                    continue;
                                }
                                throw new IOException(interrupted ? "The audio stream was interrupted. Press Play to retry."
                                    : block == null ? "Audio buffering timed out. Press Play to retry."
                                    : "Could not decode this audio. Check the server or offline copy.");
                            }
                            upcoming = plan;
                            if (prepared != null && !prepared.item.equals(upcoming.item)) {
                                prepared.close();
                                prepared = null;
                            }
                            if (upcoming.item == null) {
                                output.close();
                                if (sink.waitFor(3, TimeUnit.SECONDS) && sink.exitValue() != 0)
                                    throw new IOException("The desktop audio output stopped. Press Play to retry.");
                                changed(item, position, null, true, null);
                                return;
                            }
                            decoder.close();
                            if (upcoming.mode.equals("separate")) {
                                output.write(new byte[BLOCK * 12 + BLOCK / 2]);
                            }
                            decoder = prepared != null ? prepared : new Decoder(upcoming.item, upcoming.item.resume, rate);
                            prepared = null;
                            if (plan == upcoming) plan = new Plan(null, upcoming.mode, upcoming.seconds);
                            changed(item, position, decoder.item, true, null);
                            item = decoder.item;
                            position = decoder.start;
                            fallback = false;
                            retried = false;
                            offlineCheck = position;
                            continue;
                        }
                        if (outgoing != null) {
                            double incoming = Math.min(1, (double)(position - fadeStart) / fadeDuration);
                            mix(block, outgoing.poll(), incoming);
                            if (incoming >= 1) {
                                outgoing.close();
                                outgoing = null;
                            }
                        }
                        if (active != this) return;
                        boolean analyze = visible && System.nanoTime() >= nextSpectrum;
                        Equalizer.Values settings = equalizer;
                        int length = block.length;
                        if (settings.enabled() || analyze) {
                            processed.clear();
                            float[] bars = dsp.process(ByteBuffer.wrap(block).order(ByteOrder.LITTLE_ENDIAN), processed, settings, analyze);
                            if (bars != null) {
                                spectrum = bars;
                                nextSpectrum = System.nanoTime() + 65_000_000;
                            }
                            block = processed.array();
                        }
                        if (volume != 1) scale(block, length, volume);
                        output.write(block, 0, length);
                        position = decoder.start + Math.round((decoder.frames += decoder.lastBytes / (CHANNELS * 2)) * 1000.0 * rate / RATE);
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (IOException | RuntimeException e) {
                changed(item, position, null, false,
                    sink != null && !sink.isAlive() ? "The desktop audio output stopped. Press Play to retry." : e.getMessage());
            } finally {
                if (decoder != null) decoder.close();
                if (prepared != null) prepared.close();
                if (outgoing != null) outgoing.close();
                if (sink != null) sink.destroyForcibly();
            }
        }

        private void changed(Item from, long at, Item next, boolean complete, String error) {
            if (active == this) callback.accept(new Boundary(from, at, next, complete, error));
        }
    }

    private final class Decoder implements AutoCloseable {
        final Item item;
        final long start;
        final Process process;
        final MediaSource.Relay.Route stream;
        final boolean local;
        final ArrayBlockingQueue<byte[]> buffer = new ArrayBlockingQueue<>(16);
        final Thread reader;
        volatile boolean failed;
        long frames;
        int lastBytes;

        Decoder(Item item, long start, double speed) throws IOException {
            this.item = item;
            this.start = start;
            local = item.source.offline() != null && Files.isRegularFile(item.source.offline());
            stream = local ? null : relay.add(item.source);
            List<String> command = new ArrayList<>(List.of("ffmpeg", "-nostdin", "-hide_banner", "-loglevel", "error", "-xerror",
                "-threads", "1", "-filter_threads", "1", "-protocol_whitelist", local ? "file" : "pipe,http,tcp",
                "-format_whitelist", local ? AUDIO_FORMATS : "concat," + AUDIO_FORMATS));
            if (!local) command.addAll(List.of("-f", "concat", "-safe", "0"));
            if (local || item.duration > 0) command.addAll(List.of("-ss", Double.toString(start / 1000.0)));
            command.addAll(List.of("-i", local ? item.source.offline().toString() : "pipe:0", "-vn", "-sn", "-dn"));
            if (!local && item.duration <= 0 && start > 0) command.addAll(List.of("-ss", Double.toString(start / 1000.0)));
            if (speed != 1) command.addAll(List.of("-af", "atempo=" + speed));
            command.addAll(List.of("-ar", Integer.toString(RATE), "-ac", Integer.toString(CHANNELS), "-f", "s16le", "pipe:1"));
            Process started = null;
            try {
                ProcessBuilder decoder = new ProcessBuilder(command).redirectError(ProcessBuilder.Redirect.DISCARD);
                decoder.environment().remove("http_proxy");
                decoder.environment().remove("HTTP_PROXY");
                started = decoder.start();
                try (var input = started.getOutputStream()) {
                    if (!local) {
                        // Keep the temporary relay route out of process arguments and logs.
                        String manifest = "ffconcat version 1.0\nfile '" + stream.url + "'\n";
                        if (item.duration > 0) manifest += "duration " + item.duration / 1000.0 + "\n";
                        input.write(manifest.getBytes(java.nio.charset.StandardCharsets.US_ASCII));
                    }
                }
                process = started;
            } catch (IOException e) {
                if (started != null) started.destroyForcibly();
                if (stream != null) relay.remove(stream);
                throw e;
            }
            reader = new Thread(this::decode, "rewindamp-decoder");
            reader.setDaemon(true);
            reader.start();
        }

        private void decode() {
            try (var pcm = process.getInputStream()) {
                byte[] data;
                while ((data = pcm.readNBytes(BLOCK)).length > 0) buffer.put(data);
                failed = process.waitFor() != 0;
                buffer.put(END);
            } catch (IOException e) {
                failed = true;
                if (!Thread.currentThread().isInterrupted()) try { buffer.put(END); } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        boolean ready() { byte[] next = buffer.peek(); return next != null && next != END; }
        byte[] poll() { return buffer.poll(); }
        byte[] take() throws InterruptedException {
            byte[] next = buffer.poll(25, TimeUnit.SECONDS);
            if (next != null) lastBytes = next.length;
            return next;
        }
        @Override public void close() {
            reader.interrupt();
            process.destroyForcibly();
            if (stream != null) relay.remove(stream);
        }
    }

    static void mix(byte[] incoming, byte[] outgoing, double gain) {
        if (outgoing == null || outgoing.length == 0) return;
        ByteBuffer a = ByteBuffer.wrap(incoming).order(ByteOrder.LITTLE_ENDIAN);
        ByteBuffer b = ByteBuffer.wrap(outgoing).order(ByteOrder.LITTLE_ENDIAN);
        for (int offset = 0; offset + 1 < Math.min(a.limit(), b.limit()); offset += 2)
            a.putShort(offset, (short)Math.round(a.getShort(offset) * gain + b.getShort(offset) * (1 - gain)));
    }

    private static void scale(byte[] data, int length, float gain) {
        ByteBuffer pcm = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
        for (int offset = 0; offset + 1 < length; offset += 2)
            pcm.putShort(offset, (short)Math.round(pcm.getShort(offset) * gain));
    }
}

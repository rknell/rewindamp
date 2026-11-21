package dev.rewindamp;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.function.BiConsumer;
import javax.swing.SwingUtilities;
import org.json.JSONObject;

final class DesktopMediaKeys implements AutoCloseable {
    private Process bridge;
    private Thread reader, writer;
    private final ArrayBlockingQueue<JSONObject> updates = new ArrayBlockingQueue<>(1);
    private String key = "";

    DesktopMediaKeys(BiConsumer<String, Long> receive) {
        try {
            Path code = Path.of(DesktopMediaKeys.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            Path script = code.resolveSibling("mpris.py");
            if (!Files.isRegularFile(script)) return;
            bridge = new ProcessBuilder("/usr/bin/python3", script.toString())
                .redirectError(ProcessBuilder.Redirect.DISCARD).start();
            reader = new Thread(() -> {
                try (var input = new BufferedReader(new InputStreamReader(bridge.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = input.readLine()) != null) {
                        JSONObject command = new JSONObject(line);
                        SwingUtilities.invokeLater(() -> receive.accept(command.getString("action"), command.optLong("position")));
                    }
                } catch (IOException | org.json.JSONException ignored) {}
            }, "rewindamp-media-keys");
            writer = new Thread(() -> {
                try (var output = bridge.getOutputStream()) {
                    while (!Thread.currentThread().isInterrupted()) {
                        output.write((updates.take().toString() + "\n").getBytes(StandardCharsets.UTF_8));
                        output.flush();
                    }
                } catch (IOException ignored) {}
                catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            }, "rewindamp-media-status");
            reader.setDaemon(true);
            writer.setDaemon(true);
            reader.start();
            writer.start();
        } catch (Exception ignored) {
            // Local transport remains usable on desktops without a session bus.
        }
    }

    String trackKey() { return key; }

    void update(Track track, String key, boolean playing, long position, double rate, float volume, boolean podcast, boolean previous, boolean next) {
        this.key = key;
        if (bridge == null || !bridge.isAlive()) return;
        JSONObject value = new JSONObject().put("key", key).put("playing", playing).put("position", position * 1000)
            .put("rate", rate).put("volume", (double)volume).put("podcast", podcast).put("previous", previous).put("next", next);
        if (track != null) value.put("title", track.name).put("artist", track.artist).put("album", track.album).put("duration", track.duration * 1000);
        updates.clear();
        updates.offer(value);
    }

    @Override public void close() {
        if (writer != null) writer.interrupt();
        if (reader != null) reader.interrupt();
        if (bridge != null) bridge.destroy();
    }
}

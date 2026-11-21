package dev.rewindamp;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Function;
import org.json.JSONObject;

final class Downloads implements AutoCloseable {
    private final DesktopStore store;
    private final Function<String, MediaSource> source;
    private final JSONObject entries;
    private final ExecutorService workers = Executors.newFixedThreadPool(2);
    private final Map<String, Transfer> running = new HashMap<>();
    private final Map<String, Integer> progress = new HashMap<>();
    private boolean closed;
    private boolean paused;
    private boolean networkAllowed = true;

    Downloads(DesktopStore store, Function<String, MediaSource> source) {
        this.store = store;
        this.source = source;
        entries = store.read("downloads");
        paused = store.read("download-settings").optBoolean("paused");
    }

    synchronized int pending() {
        int count = 0;
        for (String id : entries.keySet()) if (entries.optString(id).equals("pending")) count++;
        return count;
    }

    synchronized void start() {
        if (closed || paused() || !networkAllowed) return;
        for (String id : entries.keySet())
            if ("pending".equals(entries.optString(id)) && !running.containsKey(id)) schedule(id);
    }

    synchronized void networkAllowed(boolean value) {
        if (networkAllowed == value) return;
        networkAllowed = value;
        if (value) start(); else cancelAll();
    }

    synchronized void enqueue(String id) {
        if (closed || complete(id)) return;
        entries.put(id, "pending");
        save();
        if (!paused() && networkAllowed && !running.containsKey(id)) schedule(id);
    }

    synchronized void enqueueNew(String id) {
        if (!entries.has(id)) enqueue(id);
    }

    private void schedule(String id) {
        Transfer transfer = new Transfer(id);
        running.put(id, transfer);
        transfer.task = workers.submit(transfer);
    }

    Path file(String id) {
        return store.directory.toPath().resolve("audio-" + LibraryData.hash(id));
    }

    boolean complete(String id) { return Files.isRegularFile(file(id)); }

    synchronized String[] ids() { return entries.keySet().toArray(String[]::new); }

    synchronized String label(String id) {
        if (complete(id)) return "Saved offline";
        return switch (entries.optString(id)) {
            case "failed" -> "Download failed";
            case "pending" -> progress.containsKey(id) ? progress.get(id) + "%" : paused() ? "Paused" : !networkAllowed ? "Waiting for unmetered network" : "Queued";
            default -> "";
        };
    }

    synchronized boolean paused() { return paused; }

    synchronized void pause(boolean value) {
        store.writeChecked("download-settings", new JSONObject().put("paused", value));
        paused = value;
        if (value) cancelAll(); else start();
    }

    synchronized void remove(String id) throws IOException {
        Transfer transfer = running.remove(id);
        if (transfer != null) transfer.cancel();
        progress.remove(id);
        Files.deleteIfExists(file(id));
        entries.remove(id);
        save();
    }

    synchronized String summary() {
        int saved = 0, pending = 0, failed = 0;
        long bytes = 0;
        for (String id : ids()) {
            if (complete(id)) {
                saved++;
                try { bytes += Files.size(file(id)); } catch (IOException ignored) {}
            } else if (entries.optString(id).equals("failed")) failed++;
            else pending++;
        }
        return String.format(java.util.Locale.ROOT, "%d saved · %d pending · %d failed · %.1f MB saved · %.1f GB free",
            saved, pending, failed, bytes / 1048576.0, store.directory.getUsableSpace() / 1073741824.0);
    }

    private void save() { store.writeChecked("downloads", entries); }

    private void cancelAll() {
        for (Transfer transfer : running.values()) transfer.cancel();
        running.clear();
        progress.clear();
    }

    @Override public synchronized void close() {
        closed = true;
        cancelAll();
        workers.shutdownNow();
    }

    private final class Transfer implements Runnable {
        final String id;
        volatile HttpURLConnection connection;
        volatile boolean cancelled;
        Future<?> task;

        Transfer(String id) { this.id = id; }

        void cancel() {
            cancelled = true;
            task.cancel(true);
        }

        @Override public void run() {
            Path temp = null;
            try {
                temp = Files.createTempFile(store.directory.toPath(), ".download-", ".part",
                    PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
                connection = source.apply(id).open(null);
                if (cancelled || connection.getResponseCode() != 200) throw new IOException("Download unavailable");
                long total = connection.getContentLengthLong();
                long copied = 0;
                try (var input = connection.getInputStream(); var output = Files.newOutputStream(temp)) {
                    byte[] buffer = new byte[65536];
                    int length;
                    while (!cancelled && (length = input.read(buffer)) != -1) {
                        output.write(buffer, 0, length);
                        copied += length;
                        if (total > 0) synchronized (Downloads.this) {
                            if (running.get(id) == this) progress.put(id, (int)(100.0 * copied / total));
                        }
                    }
                }
                synchronized (Downloads.this) {
                    if (running.get(id) != this) return;
                    if (copied == 0 || (total >= 0 && copied != total)) throw new IOException("Incomplete download");
                    Files.move(temp, file(id), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                    entries.put(id, "complete");
                    save();
                }
            } catch (Exception error) {
                synchronized (Downloads.this) {
                    if (running.get(id) == this && !complete(id)) {
                        entries.put(id, "failed");
                        save();
                    }
                }
            } finally {
                if (connection != null) connection.disconnect();
                if (temp != null) try { Files.deleteIfExists(temp); } catch (IOException ignored) {}
                synchronized (Downloads.this) {
                    if (running.remove(id, this)) progress.remove(id);
                }
            }
        }
    }
}

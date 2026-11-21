package dev.rewindamp;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import javax.swing.SwingUtilities;
import org.json.JSONArray;
import org.json.JSONObject;

final class DesktopPlayer implements AutoCloseable {
    record Entry(String key, Track track) {
        static Entry of(Track track) { return new Entry(UUID.randomUUID().toString(), track); }
    }

    static final class Deck {
        final DesktopStore store;
        final MediaSourceFactory source;
        final PodcastState podcasts;
        final PlaybackReporter reporter;
        final Downloads downloads;
        final List<Entry> queue = new ArrayList<>();
        final List<String> shuffled = new ArrayList<>();
        String snapshot;
        int index = -1;
        long position;
        double speed = 1;
        boolean shuffle;
        int repeat;

        Deck(DesktopStore store, MediaSourceFactory source, PodcastState podcasts, PlaybackReporter reporter, Downloads downloads) {
            this.store = store;
            this.source = source;
            this.podcasts = podcasts;
            this.reporter = reporter;
            this.downloads = downloads;
            JSONObject state = store.read("queue");
            snapshot = state.optString("snapshot", UUID.randomUUID().toString());
            JSONObject checkpoint = store.read("queue-position");
            JSONObject positionState = snapshot.equals(checkpoint.optString("snapshot")) ? checkpoint : state;
            JSONArray tracks = state.optJSONArray("tracks");
            if (tracks != null) for (int i = 0; i < tracks.length(); i++) {
                try { queue.add(Entry.of(Track.from(tracks.getJSONObject(i)))); }
                catch (org.json.JSONException ignored) {}
            }
            index = queue.isEmpty() ? -1 : Math.max(0, Math.min(positionState.optInt("index"), queue.size() - 1));
            position = Math.max(0, positionState.optLong("position"));
            speed = Math.max(0.5, Math.min(3, state.optDouble("speed", 1)));
            shuffle = state.optBoolean("shuffle");
            repeat = Math.max(0, Math.min(2, state.optInt("repeat")));
            rebuildShuffle();
        }

        Entry current() { return index >= 0 && index < queue.size() ? queue.get(index) : null; }

        void rebuildShuffle() {
            shuffled.clear();
            for (Entry entry : queue) shuffled.add(entry.key);
            Collections.shuffle(shuffled);
            if (current() != null) {
                shuffled.remove(current().key);
                shuffled.add(0, current().key);
            }
        }

        void reconcileShuffle() {
            var keys = new java.util.HashSet<String>();
            for (Entry entry : queue) keys.add(entry.key);
            shuffled.removeIf(key -> !keys.contains(key));
            var existing = new java.util.HashSet<>(shuffled);
            for (Entry entry : queue) if (existing.add(entry.key)) {
                int currentOrder = current() == null ? -1 : shuffled.indexOf(current().key);
                int from = Math.max(0, currentOrder + 1);
                shuffled.add(java.util.concurrent.ThreadLocalRandom.current().nextInt(from, shuffled.size() + 1), entry.key);
            }
        }

        int find(String key) {
            for (int i = 0; i < queue.size(); i++) if (queue.get(i).key.equals(key)) return i;
            return -1;
        }

        int adjacent(int direction, boolean automatic) {
            Entry playing = current();
            if (playing == null) return -1;
            if (automatic && repeat == 1) return index;
            int order = shuffle ? shuffled.indexOf(playing.key) : index;
            for (int i = 0; i < queue.size(); i++) {
                order += direction;
                if (order < 0 || order >= queue.size()) {
                    if (repeat != 2) return -1;
                    order = Math.floorMod(order, queue.size());
                }
                int candidate = shuffle ? find(shuffled.get(order)) : order;
                if (candidate < 0) continue;
                if (automatic && podcasts != null && !podcasts.showPlayed() && List.of("played", "dismissed")
                    .contains(podcasts.episode(queue.get(candidate).track.id).optString("state"))) continue;
                return candidate;
            }
            return -1;
        }

        long resume(Track track) {
            if (podcasts == null) return 0;
            JSONObject episode = podcasts.episode(track.id);
            return episode.optString("state").equals("played") ? 0 : Math.max(0, (long)(episode.optDouble("position", 0) * 1000));
        }

        DesktopAudio.Item item(int i, long resume) {
            Entry entry = queue.get(i);
            return new DesktopAudio.Item(entry.key, source.get(entry.track), entry.track.duration, resume);
        }

        void save() {
            JSONArray tracks = new JSONArray();
            for (Entry entry : queue) tracks.put(entry.track.json());
            String nextSnapshot = UUID.randomUUID().toString();
            store.writeChecked("queue", new JSONObject().put("tracks", tracks).put("index", index).put("position", position)
                .put("speed", speed).put("shuffle", shuffle).put("repeat", repeat).put("snapshot", nextSnapshot));
            snapshot = nextSnapshot;
        }

        void checkpoint() {
            store.writeChecked("queue-position", new JSONObject().put("index", index).put("position", position).put("snapshot", snapshot));
        }
    }

    interface MediaSourceFactory { MediaSource get(Track track); }

    private static final float[] SILENCE = new float[28];
    private final DesktopAudio engine;
    private final Runnable changed;
    private final Consumer<String> status;
    private final DesktopStore settings;
    Deck active;
    boolean playing;
    float volume;
    String transition;
    int crossfadeSeconds;
    int autoDownloads;

    DesktopPlayer(DesktopStore settings, Runnable changed, Consumer<String> status) throws IOException {
        this.settings = settings;
        this.changed = changed;
        this.status = status;
        engine = new DesktopAudio(boundary -> SwingUtilities.invokeLater(() -> boundary(boundary)));
        JSONObject saved = settings.read("audio");
        volume = Math.max(0, Math.min(1, (float)saved.optDouble("volume", 0.8)));
        engine.volume = volume;
        transition = saved.optString("transition", "gapless");
        crossfadeSeconds = saved.optInt("crossfade", 5);
        Equalizer.Values eq = engine.equalizer;
        JSONArray gains = saved.optJSONArray("bands");
        float[] bands = eq.gains().clone();
        if (gains != null) for (int i = 0; i < bands.length; i++) bands[i] = (float)gains.optDouble(i, 0);
        equalizer(saved.optBoolean("equalizer"), (float)saved.optDouble("preamp", 0), bands);
    }

    Equalizer.Values equalizer() { return engine.equalizer; }
    float[] spectrum() { return playing && active != null && active.podcasts == null ? engine.spectrum : SILENCE; }
    void visible(boolean value) { engine.visible = value; }

    void equalizer(boolean enabled, float preamp, float[] gains) {
        engine.equalizer = new Equalizer.Values(enabled, preamp, gains.clone(), engine.equalizer.version() + 1);
    }

    void volume(float value) { volume = value; engine.volume = value; }

    void saveSettings() {
        Equalizer.Values eq = equalizer();
        JSONArray bands = new JSONArray();
        for (float gain : eq.gains()) bands.put(gain);
        settings.writeChecked("audio", new JSONObject().put("volume", volume).put("transition", transition)
            .put("crossfade", crossfadeSeconds).put("equalizer", eq.enabled()).put("preamp", eq.preamp()).put("bands", bands));
    }

    void activate(Deck deck) {
        if (active == deck) return;
        pause();
        if (active != null && active.reporter != null) active.reporter.stop();
        active = deck;
        changed.run();
    }

    void start(Deck deck, int index, long position) {
        activate(deck);
        boolean moved = index != deck.index;
        pause();
        if (moved) clearEndSleep(deck);
        if (deck.reporter != null && moved) deck.reporter.stop();
        deck.index = index;
        deck.position = position;
        resume();
    }

    void resume() {
        if (active == null || active.current() == null || playing) return;
        if (active.current().track.duration > 0 && active.position >= active.current().track.duration) active.position = 0;
        engine.start(active.item(active.index, active.position), active.position, active.speed);
        playing = true;
        plan();
        checkpoint();
        changed.run();
    }

    void pause() {
        if (active == null || !playing) return;
        active.position = engine.position();
        engine.stop();
        playing = false;
        checkpoint();
        changed.run();
    }

    void toggle() { if (playing) pause(); else resume(); }

    void seek(long position) {
        if (active == null || active.current() == null) return;
        boolean resumed = playing;
        pause();
        long duration = active.current().track.duration;
        active.position = Math.max(0, duration > 0 ? Math.min(position, duration) : position);
        if (resumed) resume(); else { checkpoint(); changed.run(); }
    }

    void skip(int direction) {
        if (active == null) return;
        int i = active.adjacent(direction, false);
        if (i >= 0) start(active, i, active.resume(active.queue.get(i).track));
    }

    long position() { return playing ? engine.position() : active == null ? 0 : active.position; }

    void plan() {
        if (active == null || active.current() == null) return;
        JSONObject sleep = active.podcasts == null ? new JSONObject() : active.store.read("podcast-sleep");
        int i = sleep.optBoolean("end") ? -1 : active.adjacent(1, true);
        DesktopAudio.Item next = i < 0 ? null : active.item(i, active.resume(active.queue.get(i).track));
        engine.successor(next, active.podcasts == null ? transition : "gapless", crossfadeSeconds);
        if (active.podcasts != null && active.downloads != null && autoDownloads > 0) {
            int end = Math.min(active.queue.size(), active.index + autoDownloads + 1);
            for (int j = active.index + 1; j < end; j++) {
                String id = active.queue.get(j).track.id;
                if (active.podcasts.episode(id).optBoolean("downloaded")) active.downloads.enqueueNew(id);
            }
        }
    }

    private void boundary(DesktopAudio.Boundary result) {
        if (!playing || active == null || active.current() == null || !active.current().key.equals(result.item().key())) return;
        Track track = active.current().track;
        if (active.podcasts != null && result.complete()) {
            active.podcasts.event(track.id, result.finalPosition(), "played");
            clearEndSleep(active);
        }
        if (active.reporter != null) {
            active.reporter.update(track.id, false, result.finalPosition(), true, volume);
            active.reporter.stop();
        }
        int successor = result.successor() == null ? -1 : active.find(result.successor().key());
        if (successor < 0) {
            engine.stop();
            playing = false;
            active.position = result.complete() ? 0 : result.finalPosition();
            active.checkpoint();
            if (!result.complete()) status.accept(result.error() == null ? "Playback stopped" : result.error());
        } else {
            active.index = successor;
            active.position = result.successor().resume();
            plan();
            checkpoint();
        }
        changed.run();
    }

    private void clearEndSleep(Deck deck) {
        if (deck.podcasts != null && deck.store.read("podcast-sleep").optBoolean("end")) deck.podcasts.clearSleep();
    }

    void checkpoint() {
        if (active == null) return;
        active.position = position();
        Entry entry = active.current();
        if (entry != null && active.podcasts != null)
            active.podcasts.event(entry.track.id, active.position, "progress");
        if (active.reporter != null)
            active.reporter.update(entry == null ? "" : entry.track.id, playing, active.position, true, volume);
        active.checkpoint();
    }

    @Override public void close() {
        pause();
        if (active != null && active.reporter != null) active.reporter.stop();
        engine.close();
    }
}

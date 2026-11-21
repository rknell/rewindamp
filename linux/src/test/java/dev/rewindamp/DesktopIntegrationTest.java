package dev.rewindamp;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import org.json.JSONArray;
import org.json.JSONObject;

public final class DesktopIntegrationTest {
    private static final String ID = "00112233445566778899aabbccddeeff";

    public static void main(String[] args) throws Exception {
        Path temp = Files.createTempDirectory("rewindamp-desktop-test");
        try {
            testDsp();
            testSkin();
            testChapters();
            testFixture(temp);
            testAudio(temp);
            if (List.of(args).contains("--shared-fixture")) testSharedFixture(temp);
            System.out.println("Linux integration checks passed");
        } finally {
            try (var files = Files.walk(temp)) {
                for (Path file : files.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(file);
            }
        }
    }

    private static void testFixture(Path temp) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        AtomicInteger redirects = new AtomicInteger(), reports = new AtomicInteger(), acquisition = new AtomicInteger();
        try {
            server.createContext("/", exchange -> {
                try (exchange) { fixture(exchange, redirects, reports, acquisition); }
            });
            server.start();
            String base = "http://127.0.0.1:" + server.getAddress().getPort();
            JellyfinApi jellyfin = JellyfinApi.login(base + "/base/", "synthetic", "login-only", "Linux");
            assert jellyfin.userId.equals("synthetic-user");
            assert jellyfin.authorization().contains("Device=\"Linux\"");
            List<Track> rows = jellyfin.browseAll("Tracks", "Signal", null, "");
            assert rows.size() == 2 && rows.get(0).id.equals("first") && rows.get(1).id.equals("artist-match");
            assert jellyfin.playlistItems("playlist").get(0).entryId.equals("entry-1");
            jellyfin.create("Synthetic playlist", rows);

            DesktopStore music = new DesktopStore(temp, jellyfin.server, jellyfin.userId);
            DesktopStore other = new DesktopStore(temp, jellyfin.server, "other-account");
            music.tracks("catalog", rows);
            assert music.cachedTracks("catalog").size() == 2 && other.cachedTracks("catalog") == null;
            DesktopPlayer.Deck deck = new DesktopPlayer.Deck(music, track -> null, null, null, null);
            deck.queue.addAll(rows.stream().map(DesktopPlayer.Entry::of).toList());
            deck.index = 0;
            deck.position = 456;
            deck.rebuildShuffle();
            deck.save();
            assert deck.adjacent(-1, false) == -1 && deck.adjacent(1, false) == 1;
            deck.repeat = 2;
            assert deck.adjacent(-1, false) == 1;
            DesktopPlayer.Deck restored = new DesktopPlayer.Deck(music, track -> null, null, null, null);
            assert restored.position == 456 && restored.queue.get(0).track().id.equals("first");

            PlaybackReporter reporter = new PlaybackReporter(jellyfin, music);
            reporter.update(rows.get(0).id, true, 500, true, 0.5f);
            reporter.update(rows.get(0).id, true, 1000, true, 0.5f);
            reporter.update(rows.get(0).id, false, 1500, true, 0.5f);
            reporter.stop();
            reporter.sync();
            assert reports.get() == 3;
            assert music.read("playback-outbox").getJSONArray("events").isEmpty();

            String authorization = jellyfin.authorization();
            try (Downloads downloads = new Downloads(music, id -> new MediaSource(base + "/download", authorization, null))) {
                downloads.pause(true);
                downloads.enqueue("first");
                assert downloads.label("first").equals("Paused") && !downloads.complete("first");
                downloads.pause(false);
                waitFor(() -> downloads.complete("first"));
                assert Files.readString(downloads.file("first")).equals("synthetic bytes");
                downloads.remove("first");
                assert !downloads.complete("first") && downloads.ids().length == 0;
            }
            try (MediaSource.Relay relay = new MediaSource.Relay()) {
                String capability = relay.add(new MediaSource(base + "/download", authorization, null));
                HttpURLConnection range = (HttpURLConnection)URI.create(capability).toURL().openConnection();
                range.setRequestProperty("Range", "bytes=10-");
                assert range.getResponseCode() == 206 && range.getHeaderField("Content-Range").equals("bytes 10-14/15");
                range.disconnect();
                relay.remove(capability);
                assert response(capability) == 404;
                assert response(relay.add(new MediaSource(base + "/redirect", authorization, null))) == 502;
                assert redirects.get() == 0;
            }

            PodcastApi api = PodcastApi.login(base, "fixture-owner");
            DesktopStore podcastStore = new DesktopStore(temp, api.server, "RewindampPodcastOwner");
            PodcastState podcast = new PodcastState(api, podcastStore);
            assert podcast.feeds().length() == 1;
            assert podcast.episodes(null, false, 0).size() == 1;
            podcast.event(ID, 12_000, "progress");
            assert podcast.episode(ID).optString("state").equals("in_progress");
            podcast.sync();
            assert podcast.journal().getJSONArray("events").isEmpty() && podcast.episode(ID).getInt("position") == 12;
            podcast.event(ID, 0, "reset");
            assert podcast.episode(ID).getInt("epoch") == 1;
            assert PodcastPass.body("Fixture", "https://example.test/rss", 2, true).getInt("years") == 2;

            MixarrApi mixarr = MixarrApi.login(base, "fixture", "password");
            JSONArray recordings = (JSONArray)mixarr.request("/api/v1/track-requests/search", new JSONObject().put("query", "Signal"));
            assert recordings.length() == 1 && acquisition.get() == 0;
            JSONObject queued = (JSONObject)mixarr.request("/api/v1/track-requests",
                new JSONObject().put("token", recordings.getJSONObject(0).getString("token")));
            assert acquisition.get() == 1;
            assert MixarrApi.record(new JSONArray().put(queued), queued).length() == 1;
        } finally {
            server.stop(0);
        }
    }

    private static void fixture(HttpExchange x, AtomicInteger redirects, AtomicInteger reports, AtomicInteger acquisition) throws IOException {
        String path = x.getRequestURI().getPath();
        String query = x.getRequestURI().getQuery();
        String auth = x.getRequestHeaders().getFirst("Authorization");
        JSONObject item = new JSONObject().put("Id", "first").put("Name", "Signal")
            .put("Artists", new JSONArray().put("Synthetic Artist")).put("RunTimeTicks", 60_000_000);
        JSONObject artist = new JSONObject().put("Id", "artist").put("Name", "Synthetic Artist");
        switch (path) {
            case "/base/Users/AuthenticateByName" -> send(x, new JSONObject().put("AccessToken", "fixture-jellyfin")
                .put("User", new JSONObject().put("Id", "synthetic-user")));
            case "/base/Users/synthetic-user/Items" -> {
                assert auth.contains("fixture-jellyfin");
                JSONArray result = query.contains("ArtistIds=") ? new JSONArray().put(item).put(new JSONObject().put("Id", "artist-match").put("Name", "Different Title"))
                    : new JSONArray().put(item);
                send(x, new JSONObject().put("Items", result));
            }
            case "/base/Artists" -> send(x, new JSONObject().put("Items", new JSONArray().put(artist)));
            case "/base/Playlists/playlist/Items" -> send(x, new JSONObject().put("Items", new JSONArray().put(item.put("PlaylistItemId", "entry-1"))));
            case "/base/Playlists" -> {
                JSONObject body = body(x);
                assert !body.getBoolean("IsPublic") && body.getJSONArray("Ids").length() == 2;
                send(x, new JSONObject());
            }
            case "/base/Sessions/Playing", "/base/Sessions/Playing/Progress", "/base/Sessions/Playing/Stopped" -> {
                reports.incrementAndGet();
                send(x, new JSONObject());
            }
            case "/download" -> {
                assert auth.contains("fixture-jellyfin");
                if ("bytes=10-".equals(x.getRequestHeaders().getFirst("Range"))) {
                    x.getResponseHeaders().set("Content-Range", "bytes 10-14/15");
                    send(x, 206, "bytes");
                } else send(x, 200, "synthetic bytes");
            }
            case "/redirect" -> {
                x.getResponseHeaders().set("Location", "/redirect-target");
                x.sendResponseHeaders(302, -1);
            }
            case "/redirect-target" -> {
                redirects.incrementAndGet();
                x.sendResponseHeaders(500, -1);
            }
            case "/api/auth/login" -> send(x, new JSONObject().put("access_token", "fixture-podcast"));
            case "/api/feeds" -> {
                assert auth.equals("Bearer fixture-podcast");
                send(x, new JSONObject().put("items", new JSONArray().put(new JSONObject().put("title", "Fixture Podcast"))));
            }
            case "/api/episodes" -> send(x, new JSONObject().put("items", new JSONArray().put(new JSONObject().put("id", ID)
                .put("downloaded", true).put("title", "Fixture Episode").put("epoch", 0).put("state", "unplayed"))));
            case "/api/progress/" + ID -> send(x, new JSONObject().put("position", body(x).getInt("position")).put("state", "in_progress").put("epoch", 0));
            case "/api/v1/login" -> {
                x.getResponseHeaders().set("Set-Cookie", "mixarr_session=fixture-mixarr; Path=/; HttpOnly");
                send(x, new JSONObject().put("csrf_token", "fixture-csrf").put("user", new JSONObject().put("id", "fixture-user")));
            }
            case "/api/v1/track-requests/search" -> {
                assert x.getRequestHeaders().getFirst("Cookie").equals("mixarr_session=fixture-mixarr");
                assert x.getRequestHeaders().getFirst("X-CSRF-Token").equals("fixture-csrf");
                send(x, 200, new JSONArray().put(new JSONObject().put("token", "fixture-selection")).toString());
            }
            case "/api/v1/track-requests" -> {
                assert body(x).getString("token").equals("fixture-selection");
                acquisition.incrementAndGet();
                send(x, new JSONObject().put("track_id", 2).put("job_id", 3).put("state", "queued"));
            }
            default -> throw new IOException("Unexpected fixture path " + path);
        }
    }

    private static void testSharedFixture(Path directory) throws Exception {
        JellyfinApi jellyfin = JellyfinApi.login("http://127.0.0.1:18096", "rewindamp-test", "test-only-local-password", "Linux");
        try {
            List<Track> tracks = jellyfin.browseAll("Tracks", "", null, "");
            assert tracks.size() == 2 && tracks.get(0).name.equals("Test Signal One");
            assert jellyfin.browseAll("Tracks", "Rewindamp Test", null, "").size() == 2;
            jellyfin.create("Linux parity fixture", tracks);
            Track playlist = jellyfin.browseAll("Playlists", "", null, "").stream()
                .filter(track -> track.name.equals("Linux parity fixture")).findFirst().orElseThrow();
            List<Track> contents = jellyfin.playlistItems(playlist.id);
            assert contents.size() == 2 && contents.get(0).id.equals(tracks.get(0).id);
            jellyfin.move(playlist.id, contents.get(0), 1);
            assert jellyfin.playlistItems(playlist.id).get(0).id.equals(tracks.get(1).id);

            Track track = tracks.get(0);
            MediaSource source = new MediaSource(jellyfin.stream(track), jellyfin.authorization(), null);
            assertResumes(source, track.duration);

            PodcastApi api = PodcastApi.login("http://127.0.0.1:18099", "isolated memorable fixture password");
            PodcastState podcasts = new PodcastState(api, new DesktopStore(directory, api.server, "fixture"));
            assert podcasts.feeds().length() == 1;
            assert api.request("/api/podcasts/search?term=synthetic", null).getJSONArray("items").length() == 2;
            JSONObject episode = podcasts.episodes(null, false, 0).get(0);
            String id = episode.getString("id");
            assertResumes(new MediaSource(api.audio(id), api.authorization(), null), 90_000);
            podcasts.event(id, 12_500, "progress");
            podcasts.sync();
            assert podcasts.journal().getJSONArray("events").isEmpty();
            assert podcasts.episode(id).getDouble("position") == 12.5;
        } finally {
            jellyfin.request("POST", "/Sessions/Logout", null);
        }
    }

    private static void assertResumes(MediaSource source, long duration) throws Exception {
        LinkedBlockingQueue<DesktopAudio.Boundary> events = new LinkedBlockingQueue<>();
        try (DesktopAudio audio = new DesktopAudio(events::add)) {
            audio.start(new DesktopAudio.Item("fixture", source, duration, 12_000), 12_000, 1);
            waitFor(() -> audio.position() > 12_500 || !events.isEmpty());
            assert events.isEmpty() && audio.position() > 12_500 : events.poll();
        }
    }

    private static void testSkin() throws Exception {
        assert Skin.digit('0') == 0x3f && Skin.digit('8') == 0x7f;
        assert Skin.digit('−') == Skin.digit('-') && Skin.digit(' ') == 0;
        javax.swing.SwingUtilities.invokeAndWait(() -> {
            var letters = new DesktopWheels.Letters();
            letters.entries(List.of("A", "B", "S"), "B");
            assert letters.letter().equals("B");
            letters.getActionMap().get("letter-1").actionPerformed(null);
            assert letters.letter().equals("S");
            letters.entries(List.of("A"), "S");
            assert letters.letter().equals("A");
            letters.entries(List.of(), "");
            assert letters.letter().isEmpty();

            var tabs = new javax.swing.JTabbedPane();
            for (String title : List.of("Music", "Podcasts", "Queue")) tabs.addTab(title, new javax.swing.JPanel());
            var wheel = DesktopWheels.destinations(tabs);
            assert ((javax.swing.JToggleButton)wheel.getComponent(1)).getText().equals("MUSIC");
            ((javax.swing.JToggleButton)wheel.getComponent(2)).doClick(0);
            assert tabs.getSelectedIndex() == 1;
            assert ((javax.swing.JToggleButton)wheel.getComponent(1)).isSelected();
        });
    }

    private static void testDsp() {
        PcmEqualizer dsp = new PcmEqualizer(48000, 2);
        ByteBuffer input = ByteBuffer.allocate(1024 * 4).order(ByteOrder.LITTLE_ENDIAN);
        ByteBuffer output = ByteBuffer.allocate(input.capacity()).order(ByteOrder.LITTLE_ENDIAN);
        for (int frame = 0; frame < 1024; frame++) {
            short sample = (short)(12_000 * Math.sin(2 * Math.PI * 440 * frame / 48000));
            input.putShort(sample).putShort(sample);
        }
        Equalizer.Values disabled = new Equalizer.Values(false, 0, new float[10], 1);
        ByteBuffer empty = ByteBuffer.allocate(0);
        assert dsp.process(empty, empty, disabled, false) == null;
        assert dsp.process(input.flip(), output, disabled, false) == null;
        assert java.util.Arrays.equals(input.array(), output.array());
        assert dsp.process(input.rewind(), output.clear(), disabled, true) != null;
        byte[] a = ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN).putShort((short)1000).array();
        byte[] b = ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN).putShort((short)3000).array();
        DesktopAudio.mix(a, b, 0.25);
        assert ByteBuffer.wrap(a).order(ByteOrder.LITTLE_ENDIAN).getShort() == 2500;
    }

    private static void testChapters() throws Exception {
        assert YouTubeSelection.validLink("https://youtu.be/synthetic");
        assert !YouTubeSelection.validLink("https://youtu.be@example.test/synthetic");
        assert !YouTubeSelection.isPlaylist("https://youtube.com/watch?v=test&list=test");
        JSONObject source = new JSONObject().put("source_id", "fixture").put("duration", 30);
        JSONObject preview = new JSONObject().put("token", "synthetic-preview");
        var first = new YouTubeSelection.Chapter(source, "Signal 1", "Artist", "", "0", "10");
        var second = new YouTubeSelection.Chapter(source, "Signal 2", "Artist", "", "10", "20");
        assert YouTubeSelection.body(preview, List.of(first, second)).getJSONArray("items").length() == 2;
        try {
            YouTubeSelection.body(preview, List.of(first, first));
            throw new AssertionError("Overlapping chapters were accepted");
        } catch (IllegalArgumentException expected) {}
    }

    private static void testAudio(Path temp) throws Exception {
        Path tone = temp.resolve("synthetic.flac");
        Process ffmpeg = new ProcessBuilder("ffmpeg", "-nostdin", "-loglevel", "error", "-f", "lavfi", "-i",
            "sine=frequency=440:duration=3", tone.toString()).inheritIO().start();
        assert ffmpeg.waitFor(12, TimeUnit.SECONDS) && ffmpeg.exitValue() == 0;
        HttpServer streaming = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        byte[] flac = Files.readAllBytes(tone);
        streaming.createContext("/track", x -> {
            try (x) {
                assert "Bearer synthetic-audio".equals(x.getRequestHeaders().getFirst("Authorization"));
                x.getResponseHeaders().set("Content-Type", "audio/flac");
                x.getResponseHeaders().set("Accept-Ranges", "bytes");
                String range = x.getRequestHeaders().getFirst("Range");
                int from = range == null ? 0 : Integer.parseInt(range.split("[-=]")[1]);
                if (range != null) x.getResponseHeaders().set("Content-Range", "bytes " + from + "-" + (flac.length - 1) + "/" + flac.length);
                x.sendResponseHeaders(range == null ? 200 : 206, flac.length - from);
                x.getResponseBody().write(flac, from, flac.length - from);
            }
        });
        streaming.start();
        LinkedBlockingQueue<DesktopAudio.Boundary> events = new LinkedBlockingQueue<>();
        MediaSource audio = new MediaSource("", "", tone);
        MediaSource remote = new MediaSource("http://127.0.0.1:" + streaming.getAddress().getPort() + "/track", "Bearer synthetic-audio", null);
        var first = new DesktopAudio.Item("first", remote, 3000, 0);
        var second = new DesktopAudio.Item("second", audio, 3000, 0);
        try (DesktopAudio engine = new DesktopAudio(events::add)) {
            engine.start(first, 0, 1);
            engine.successor(second, "crossfade", 1);
            waitFor(() -> engine.position() > 300);
            assert java.util.Arrays.equals(engine.spectrum, new float[28]);
            engine.visible = true;
            waitFor(() -> { for (float bar : engine.spectrum) if (bar > 0.01) return true; return false; });
            DesktopAudio.Boundary transition = events.poll(8, TimeUnit.SECONDS);
            assert transition != null && transition.error() == null : transition;
            assert transition.successor().equals(second) && transition.finalPosition() >= 1950 && transition.finalPosition() < 3000 : transition;
            engine.stop();
            assert engine.position() == 0 && java.util.Arrays.equals(engine.spectrum, new float[28]);
            engine.visible = false;
            events.clear();
            engine.start(second, 1500, 1.25);
            DesktopAudio.Boundary end = events.poll(8, TimeUnit.SECONDS);
            assert end != null && end.complete() && end.finalPosition() >= 2800 && end.successor() == null : end;
        } finally { streaming.stop(0); }
    }

    private static int response(String url) throws IOException {
        HttpURLConnection connection = (HttpURLConnection)URI.create(url).toURL().openConnection();
        try { return connection.getResponseCode(); } finally { connection.disconnect(); }
    }

    private static JSONObject body(HttpExchange x) throws IOException {
        return new JSONObject(new String(x.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
    }

    private static void send(HttpExchange x, JSONObject body) throws IOException { send(x, 200, body.toString()); }

    private static void send(HttpExchange x, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        x.sendResponseHeaders(status, bytes.length);
        x.getResponseBody().write(bytes);
    }

    private static void waitFor(BooleanSupplier ready) throws InterruptedException {
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
        while (!ready.getAsBoolean() && System.nanoTime() < until) Thread.sleep(25);
        assert ready.getAsBoolean() : "Condition timed out";
    }
}

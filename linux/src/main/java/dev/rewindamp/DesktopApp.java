package dev.rewindamp;

import static dev.rewindamp.DesktopTheme.*;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.GraphicsEnvironment;
import java.awt.Rectangle;
import java.awt.event.*;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Consumer;
import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import org.json.JSONArray;
import org.json.JSONObject;

final class DesktopApp {
    private static final String[] SECTIONS = {"Tracks", "Albums", "Artists", "Playlists", "Favourites", "Offline", "History"};
    private final Path data;
    private final DesktopStore preferences;
    private final JFrame frame = new JFrame("Rewindamp");
    private final ExecutorService io = Executors.newFixedThreadPool(5);
    private final JTabbedPane tabs = new JTabbedPane();
    private final Title title = new Title();
    private final JLabel subtitle = lcd(" ", 13), status = lcd(" ", 12);
    private final JButton clock = button("0:00", this::toggleTime);
    private final Spectrum spectrum = new Spectrum();
    private final Artwork artwork = new Artwork();
    private final JSlider seek = new JSlider(0, 1000);
    private final JSlider volume = new JSlider(0, 100);
    private final JPanel transport = row();
    private final JButton upNext = button("Up next", this::openActiveQueue);
    private final DesktopPlayer player;
    private final DesktopMediaKeys mediaKeys;
    private final Timer positionTimer = new Timer(75, event -> position());
    private final Timer checkpointTimer = new Timer(3000, event -> tick());
    private final Timer networkTimer = new Timer(15000, event -> background());
    private final Timer downloadsTimer = new Timer(1500, event -> refreshDownloadLabels());
    private final Timer searchTimer = new Timer(300, event -> search());
    private final Rows<Track> music = new Rows<>();
    private final Rows<JSONObject> episodes = new Rows<>();
    private final Rows<DesktopPlayer.Entry> queue = new Rows<>();
    private final JComboBox<String> section = new JComboBox<>(SECTIONS);
    private final JComboBox<String> shows = new JComboBox<>();
    private final JComboBox<String> filter = new JComboBox<>(new String[]{"Unplayed inbox", "All available episodes", "Desktop downloads"});
    private final JComboBox<String> order = new JComboBox<>(new String[]{"Newest first", "Oldest first"});
    private final JComboBox<String> queueDeck = new JComboBox<>(new String[]{"Music queue", "Podcast queue"});
    private final JComboBox<String> letters = new JComboBox<>();
    private final JTextField search = new JTextField(25);
    private final JButton back = button("‹ Back", this::goBack);
    private final JButton online = button("Search online", this::openMixarr);
    private final JButton musicConnection = button("Connect Jellyfin", this::musicConnection);
    private final JButton podcastConnection = button("Connect podcasts", this::podcastConnection);
    private final JCheckBox select = new JCheckBox("Select multiple");
    private final List<JSONObject> feeds = new ArrayList<>();
    private final ArrayDeque<MusicLocation> trail = new ArrayDeque<>();
    private final DesktopMixarr mixarr;
    private JellyfinApi jellyfin;
    private PodcastState podcasts;
    private DesktopPlayer.Deck musicDeck, podcastDeck;
    private Track parent;
    private List<JSONObject> catalogue = List.of();
    private String feed, term = "";
    private Runnable undo;
    private String artworkID = "";
    private String currentKey = "";
    private boolean seeking, updating, changingShows, demo, networkBusy, connectingMusic, connectingPodcasts;
    private Future<?> libraryTask, podcastTask;
    private long libraryGeneration, podcastGeneration;
    private DesktopPlayer.Deck displayedQueue;
    private int lastDownloadCount = -1;
    private final java.util.Set<String> artworkPending = new HashSet<>();
    private final java.util.Map<String, ImageIcon> rowArtwork = new java.util.LinkedHashMap<>(128, .75f, true) {
        @Override protected boolean removeEldestEntry(java.util.Map.Entry<String, ImageIcon> eldest) { return size() > 200; }
    };

    DesktopApp(Path data) throws Exception {
        this.data = data;
        preferences = new DesktopStore(data, "desktop", "settings");
        player = new DesktopPlayer(preferences, this::playerChanged, this::say);
        mediaKeys = new DesktopMediaKeys(this::mediaCommand);
        mixarr = new DesktopMixarr(frame, io, this::say);
        frame.setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
        frame.addWindowListener(new WindowAdapter() {
            @Override public void windowClosing(WindowEvent event) { close(); }
            @Override public void windowIconified(WindowEvent event) { visibility(); }
            @Override public void windowDeiconified(WindowEvent event) { visibility(); }
            @Override public void windowActivated(WindowEvent event) { visibility(); }
            @Override public void windowDeactivated(WindowEvent event) { visibility(); }
        });
        frame.setMinimumSize(new Dimension(760, 540));
        frame.setSize(1020, 710);
        frame.setLocationByPlatform(true);
        JPanel content = new JPanel(new BorderLayout(0, 8));
        content.setBorder(BorderFactory.createEmptyBorder(10, 12, 10, 12));
        content.add(header(), BorderLayout.NORTH);
        content.add(tabs, BorderLayout.CENTER);
        content.add(status, BorderLayout.SOUTH);
        tabs.addTab("Music", musicPanel());
        tabs.addTab("Podcasts", podcastPanel());
        tabs.addTab("Queue", queuePanel());
        tabs.setSelectedIndex(Math.max(0, Math.min(2, preferences.read("window").optInt("tab"))));
        tabs.addChangeListener(event -> {
            preferences.write("window", new JSONObject().put("tab", tabs.getSelectedIndex()));
            if (tabs.getSelectedIndex() == 2) loadQueue();
            if (tabs.getSelectedIndex() == 1) redrawEpisodes();
        });
        searchTimer.setRepeats(false);
        frame.setContentPane(content);
        frame.setVisible(true);
        if (Boolean.getBoolean("rewindamp.demo")) demo();
        playerChanged();
        restore();
        networkTimer.setRepeats(false);
    }

    private JPanel header() {
        JPanel readout = new JPanel(new BorderLayout(14, 0));
        readout.setBackground(LCD);
        readout.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createLoweredBevelBorder(),
            BorderFactory.createEmptyBorder(9, 10, 9, 10)));
        readout.add(artwork, BorderLayout.WEST);
        JPanel details = column(lcd("REWINDAMP", 12), title, subtitle);
        details.setOpaque(false);
        readout.add(details, BorderLayout.CENTER);
        JPanel meters = column(clock, spectrum, upNext);
        meters.setOpaque(false);
        clock.setForeground(GREEN);
        clock.setToolTipText("Toggle elapsed / remaining time");
        readout.add(meters, BorderLayout.EAST);
        seek.setValue(0);
        seek.getAccessibleContext().setAccessibleName("Playback position");
        seek.addMouseListener(new MouseAdapter() {
            @Override public void mousePressed(MouseEvent event) { seeking = true; }
            @Override public void mouseReleased(MouseEvent event) { seeking = false; seekFromSlider(); }
        });
        seek.addChangeListener(event -> { if (!updating && !seeking && !seek.getValueIsAdjusting()) seekFromSlider(); });
        JPanel controls = new JPanel(new BorderLayout());
        controls.add(transport, BorderLayout.CENTER);
        volume.setValue(Math.round(player.volume * 100));
        volume.setPreferredSize(new Dimension(105, 30));
        volume.getAccessibleContext().setAccessibleName("Volume");
        volume.addChangeListener(event -> {
            player.volume(volume.getValue() / 100f);
            if (!volume.getValueIsAdjusting()) { player.saveSettings(); updateMpris(); }
        });
        controls.add(row(new JLabel("Volume"), volume, button("EQ", this::equalizer), button("Settings", this::settings)), BorderLayout.EAST);
        JPanel header = new JPanel(new BorderLayout());
        header.add(readout, BorderLayout.NORTH);
        header.add(seek, BorderLayout.CENTER);
        header.add(controls, BorderLayout.SOUTH);
        return header;
    }

    private JPanel musicPanel() {
        section.getAccessibleContext().setAccessibleName("Music library view");
        section.addActionListener(event -> {
            if (updating) return;
            search.setText("");
            searchTimer.stop();
            term = "";
            parent = null;
            trail.clear();
            loadMusic(null);
        });
        search.getAccessibleContext().setAccessibleName("Search track titles and artists");
        search.setToolTipText("Search track titles and artists");
        listen(search, () -> { if (!updating) searchTimer.restart(); });
        search.addActionListener(event -> { searchTimer.stop(); search(); music.list.requestFocusInWindow(); });
        letters.getAccessibleContext().setAccessibleName("Jump to letter");
        letters.addActionListener(event -> {
            if (updating || letters.getSelectedItem() == null) return;
            String target = letters.getSelectedItem().toString();
            for (int i = 0; i < music.model.size(); i++) {
                if (initial(music.model.get(i)).equals(target)) {
                    music.scroll.getViewport().setViewPosition(new java.awt.Point(0, i * music.list.getFixedCellHeight()));
                    break;
                }
            }
        });
        letters.addMouseWheelListener(event -> {
            int next = letters.getSelectedIndex() + Integer.signum(event.getWheelRotation());
            if (next >= 0 && next < letters.getItemCount()) letters.setSelectedIndex(next);
            event.consume();
        });
        music.scroll.getViewport().addChangeListener(event -> {
            int first = music.list.getFirstVisibleIndex();
            if (letters.isVisible() && first >= 0 && first < music.model.size()) {
                updating = true;
                letters.setSelectedItem(initial(music.model.get(first)));
                updating = false;
            }
        });
        setRenderer(music, track -> track.name, track -> track.subtitle() + trackFlags(track), this::trackIcon);
        music.activate(this::openTrack, () -> select.isSelected());
        music.reorder(() -> parent != null && parent.type.equals("Playlist"),
            (from, to) -> movePlaylist(music.model.get(from), to - from));
        music.menu(this::musicMenu);
        JPanel panel = new JPanel(new BorderLayout());
        panel.add(column(row(musicConnection, button("Demo", this::demo), button("Refresh", () -> loadMusic(music.anchor())), online, mixarr.summary()),
            row(back, section, new JLabel("Search"), search, letters, select, button("Actions", () -> musicMenu(null)))), BorderLayout.NORTH);
        panel.add(music.scroll);
        return panel;
    }

    private JPanel podcastPanel() {
        shows.setPrototypeDisplayValue("All shows                       ");
        shows.getAccessibleContext().setAccessibleName("Podcast show");
        filter.getAccessibleContext().setAccessibleName("Episode filter");
        order.getAccessibleContext().setAccessibleName("Episode sort order");
        shows.addActionListener(event -> {
            if (changingShows || podcasts == null) return;
            int selected = shows.getSelectedIndex();
            feed = selected <= 0 ? null : feeds.get(selected - 1).optString("id");
            podcasts.store.write("podcast-browser", new JSONObject().put("feed", feed == null ? "" : feed));
            changingShows = true;
            order.setSelectedIndex(podcasts.oldest(feed) ? 1 : 0);
            changingShows = false;
            loadEpisodes();
        });
        filter.addActionListener(event -> { if (!changingShows) loadEpisodes(); });
        order.addActionListener(event -> {
            if (changingShows || podcasts == null) return;
            podcasts.preference("oldest:" + (feed == null ? "all" : feed), order.getSelectedIndex() == 1);
            loadEpisodes();
        });
        setRenderer(episodes, episode -> episode.optString("title"), this::episodeDetails, unused -> null);
        episodes.activate(this::playEpisode, () -> false);
        episodes.menu(this::episodeMenu);
        JPanel panel = new JPanel(new BorderLayout());
        panel.add(column(row(podcastConnection, button("Find shows", this::discovery), button("Refresh", this::loadFeeds),
                button("Podcast settings", this::podcastSettings)),
            row(shows, filter, order, button("Edit subscription", this::editPass))), BorderLayout.NORTH);
        panel.add(episodes.scroll);
        return panel;
    }

    private JPanel queuePanel() {
        queueDeck.addActionListener(event -> loadQueue());
        setRenderer(queue, entry -> entry.track().name, entry -> entry.track().subtitle(), entry -> trackIcon(entry.track()));
        queue.activate(entry -> {
            DesktopPlayer.Deck deck = selectedQueue();
            if (deck != null) player.start(deck, deck.find(entry.key()), deck.resume(entry.track()));
        }, () -> false);
        queue.menu(this::queueMenu);
        queue.reorder(() -> selectedQueue() != null, this::moveEntry);
        JPanel panel = new JPanel(new BorderLayout());
        panel.add(row(queueDeck, button("Save as playlist", this::saveQueue), button("Clear queue", this::clearQueue),
            button("Undo", () -> { Runnable action = undo; undo = null; if (action != null) action.run(); }),
            button("Actions", () -> queueMenu(null))), BorderLayout.NORTH);
        panel.add(queue.scroll);
        return panel;
    }

    private void restore() {
        async(() -> DesktopSecrets.load("jellyfin"), state -> {
            if (!state.has("token") || jellyfin != null || demo || connectingMusic || musicDeck != null) return;
            installMusic(new JellyfinApi(JellyfinApi.normalizeServer(state.getString("server")), state.getString("token"),
                state.getString("user"), state.getString("device"), "Linux"));
        });
        async(() -> DesktopSecrets.load("podcasts"), state -> {
            if (state.has("token") && podcasts == null && !connectingPodcasts)
                installPodcasts(new PodcastApi(state.getString("server"), state.getString("token")));
        });
    }

    private void musicConnection() {
        if (connectingMusic) return;
        if (jellyfin != null || demo) {
            if (!confirm("Disconnect music?", "Playback and desktop downloads for this connection will pause. Saved data stays in its account.")) return;
            JellyfinApi old = jellyfin;
            clearMusic();
            if (old != null) {
                connectingMusic = true;
                async(() -> {
                    boolean cleared = DesktopSecrets.clear("jellyfin");
                    try { old.request("POST", "/Sessions/Logout", null); } catch (Exception ignored) {}
                    return cleared;
                }, cleared -> {
                    connectingMusic = false;
                    if (!cleared) say("Could not clear the desktop keyring entry. Remove it in your keyring if still present.");
                });
            }
            return;
        }
        Login login = login(frame, "Connect Jellyfin", preferences.read("addresses").optString("jellyfin", "https://"), true);
        if (login == null) return;
        connectingMusic = true;
        say("Connecting to Jellyfin…");
        async(() -> {
            JellyfinApi api = JellyfinApi.login(login.address, login.user, login.password, "Linux");
            boolean saved = DesktopSecrets.save("jellyfin", new JSONObject().put("server", api.server).put("token", api.token)
                .put("user", api.userId).put("device", api.deviceId));
            return new Connection<>(api, saved);
        }, result -> {
            connectingMusic = false;
            installMusic(result.api);
            keyring(result.saved);
        }, error -> { connectingMusic = false; say(error); });
    }

    private void clearMusic() {
        if (musicDeck != null) {
            if (player.active == musicDeck) player.activate(null);
            musicDeck.downloads.close();
            if (musicDeck.reporter != null) io.submit(musicDeck.reporter::sync);
        }
        mixarr.disconnectAccount();
        musicDeck = null;
        jellyfin = null;
        demo = false;
        rowArtwork.clear();
        artworkPending.clear();
        musicConnection.setText("Connect Jellyfin");
        trail.clear();
        parent = null;
        loadMusic(null);
        loadQueue();
        playerChanged();
    }

    private void installMusic(JellyfinApi api) {
        jellyfin = api;
        demo = false;
        DesktopStore store = new DesktopStore(data, api.server, api.userId);
        Downloads downloads = new Downloads(store, id -> new MediaSource(api.downloadUrl(blankTrack(id)), api.authorization(), null));
        musicDeck = new DesktopPlayer.Deck(store, track -> new MediaSource(api.stream(track), api.authorization(), downloads.file(track.id)),
            null, new PlaybackReporter(api, store), downloads);
        musicConnection.setText("Disconnect Jellyfin");
        rememberAddress("jellyfin", api.server);
        activateLast(musicDeck, "music");
        downloads.start();
        loadMusic(null);
        loadQueue();
        mixarr.account(api, store);
        scheduleNetwork();
    }

    private void podcastConnection() {
        if (connectingPodcasts) return;
        if (podcasts != null) {
            if (!confirm("Disconnect podcasts?", "Playback and desktop downloads for this server will pause.")) return;
            if (player.active == podcastDeck) player.activate(null);
            podcastDeck.downloads.close();
            podcasts = null;
            podcastDeck = null;
            feed = null;
            feeds.clear();
            shows.removeAllItems();
            podcastConnection.setText("Connect podcasts");
            loadEpisodes();
            loadQueue();
            connectingPodcasts = true;
            async(() -> DesktopSecrets.clear("podcasts"), cleared -> {
                connectingPodcasts = false;
                if (!cleared) say("Could not clear the desktop keyring entry. Remove it in your keyring if still present.");
            });
            return;
        }
        Login login = login(frame, "Connect Rewindamp Podcasts", preferences.read("addresses").optString("podcasts", "https://"), false);
        if (login == null) return;
        connectingPodcasts = true;
        say("Connecting to the podcast archive…");
        async(() -> {
            PodcastApi api = PodcastApi.login(login.address, login.password);
            api.request("/api/feeds", null);
            return new Connection<>(api, DesktopSecrets.save("podcasts", new JSONObject().put("server", api.server).put("token", api.token)));
        }, result -> {
            connectingPodcasts = false;
            installPodcasts(result.api);
            keyring(result.saved);
        }, error -> { connectingPodcasts = false; say(error); });
    }

    private void installPodcasts(PodcastApi api) {
        DesktopStore store = new DesktopStore(data, api.server, "RewindampPodcastOwner");
        podcasts = new PodcastState(api, store);
        Downloads downloads = new Downloads(store, id -> new MediaSource(api.audio(id), api.authorization(), null));
        downloads.networkAllowed(false);
        podcastDeck = new DesktopPlayer.Deck(store, track -> new MediaSource(api.audio(track.id), api.authorization(), downloads.file(track.id)),
            podcasts, null, downloads);
        player.autoDownloads = Math.max(0, Math.min(5, store.read("podcast-preferences").optInt("auto_downloads")));
        podcastConnection.setText("Disconnect podcasts");
        String saved = store.read("podcast-browser").optString("feed");
        feed = saved.isBlank() ? null : saved;
        if (podcasts.showPlayed()) filter.setSelectedIndex(1);
        rememberAddress("podcasts", api.server);
        activateLast(podcastDeck, "podcasts");
        checkMetering();
        scheduleNetwork();
        loadFeeds();
        loadQueue();
    }

    private void activateLast(DesktopPlayer.Deck deck, String name) {
        String last = preferences.read("last-playback").optString("deck");
        if (last.equals(name) || player.active == null) player.activate(deck);
    }

    private void rememberAddress(String key, String server) {
        preferences.write("addresses", preferences.read("addresses").put(key, server));
    }

    private void keyring(boolean saved) {
        if (!saved) JOptionPane.showMessageDialog(frame,
            "Your desktop keyring is unavailable. This connection will be kept for this session only.",
            "Rewindamp keyring", JOptionPane.INFORMATION_MESSAGE);
    }

    private record Connection<T>(T api, boolean saved) {}
    record Login(String address, String user, String password) {}

    static Login login(JFrame parent, String title, String address, boolean username) {
        JTextField server = new JTextField(address, 33), user = new JTextField(33);
        JPasswordField pass = new JPasswordField(33);
        JPanel input = column(new JLabel("Server address"), server);
        if (username) { input.add(new JLabel("Username")); input.add(user); }
        input.add(new JLabel(username ? "Password" : "Owner password"));
        input.add(pass);
        if (JOptionPane.showConfirmDialog(parent, input, title, JOptionPane.OK_CANCEL_OPTION) != JOptionPane.OK_OPTION) return null;
        char[] chars = pass.getPassword();
        try { return new Login(server.getText().trim(), user.getText().trim(), new String(chars)); }
        finally { Arrays.fill(chars, '\0'); pass.setText(""); }
    }

    private void demo() {
        if (musicDeck != null || connectingMusic) return;
        connectingMusic = true;
        DesktopStore store = new DesktopStore(data, "demo", "demo");
        async(() -> {
            List<Track> tracks = List.of(new Track("signal1", "After Hours", "The Test Tones", "Synthetic Signals", "Audio", "", 36_000),
                new Track("signal2", "Blue Dial", "The Test Tones", "Synthetic Signals", "Audio", "", 36_000),
                new Track("signal3", "Late Transmission", "Night Shift", "Bench Sessions", "Audio", "", 36_000),
                new Track("signal4", "Stereo Test", "Night Shift", "Bench Sessions", "Audio", "", 36_000));
            for (int i = 0; i < tracks.size(); i++) {
                Path file = store.directory.toPath().resolve(tracks.get(i).id + ".flac");
                if (Files.isRegularFile(file)) continue;
                Path temporary = Files.createTempFile(store.directory.toPath(), ".demo-", ".flac");
                try {
                    Process command = new ProcessBuilder("ffmpeg", "-nostdin", "-y", "-loglevel", "error", "-f", "lavfi", "-i",
                        "sine=frequency=" + (220 + i * 110) + ":duration=36", "-c:a", "flac", temporary.toString())
                        .redirectError(ProcessBuilder.Redirect.DISCARD).start();
                    if (command.waitFor() != 0) throw new IOException("Could not generate demo audio");
                    Files.move(temporary, file, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
                } finally { Files.deleteIfExists(temporary); }
            }
            store.tracks("demo", tracks);
            return tracks;
        }, tracks -> {
            connectingMusic = false;
            demo = true;
            musicDeck = new DesktopPlayer.Deck(store, track -> new MediaSource("", "", store.directory.toPath().resolve(track.id + ".flac")),
                null, null, new Downloads(store, id -> { throw new IllegalStateException("Demo is already saved locally"); }));
            musicConnection.setText("Exit demo");
            activateLast(musicDeck, "music");
            loadMusic(null);
        }, error -> { connectingMusic = false; say(error); });
    }

    private record Anchor(String id, int row, int offset) {}
    private record MusicLocation(String section, String term, Track parent, Anchor anchor) {}

    private void search() {
        String query = search.getText().strip();
        if (query.equals(term)) return;
        if (term.isBlank() && !query.isBlank()) trail.push(location());
        if (query.isBlank() && !term.isBlank() && !trail.isEmpty()) { goBack(); return; }
        term = query;
        parent = null;
        loadMusic(null);
    }

    private MusicLocation location() { return new MusicLocation(section(), term, parent, music.anchor()); }
    private String section() { return String.valueOf(section.getSelectedItem()); }

    private void goBack() {
        if (trail.isEmpty()) return;
        MusicLocation previous = trail.pop();
        updating = true;
        section.setSelectedItem(previous.section);
        search.setText(previous.term);
        updating = false;
        term = previous.term;
        parent = previous.parent;
        loadMusic(previous.anchor);
    }

    private void loadMusic(Anchor anchor) {
        long generation = ++libraryGeneration;
        if (libraryTask != null) libraryTask.cancel(true);
        back.setEnabled(!trail.isEmpty());
        back.setToolTipText(parent == null ? "Return to the previous view" : parent.name);
        online.setEnabled(jellyfin != null);
        DesktopPlayer.Deck deck = musicDeck;
        JellyfinApi api = jellyfin;
        if (deck == null) { showMusic(List.of(), anchor); say("Connect Jellyfin or open Demo to browse music."); return; }
        String view = term.isBlank() ? section() : "Tracks";
        String query = term;
        Track root = parent;
        if (demo) {
            List<Track> rows = deck.store.tracks("demo").stream()
                .filter(t -> (t.name + " " + t.artist).toLowerCase(Locale.ROOT).contains(query.toLowerCase(Locale.ROOT))).toList();
            showMusic(rows, anchor);
            return;
        }
        if (view.equals("Offline") && root == null) {
            List<Track> saved = new ArrayList<>();
            for (String id : deck.downloads.ids()) {
                JSONObject data = deck.store.read("download:" + id);
                if (data.has("Id")) saved.add(Track.from(data));
            }
            saved.sort(Comparator.comparing(t -> t.name, String.CASE_INSENSITIVE_ORDER));
            showMusic(saved, anchor);
            return;
        }
        String key = root == null && query.isBlank() && view.equals("Artists") ? "catalog:Tracks::"
            : "catalog:" + view + ":" + (root == null ? "" : root.id) + ":" + query;
        List<Track> cache = deck.store.cachedTracks(key);
        showMusic(cache == null ? List.of() : prepared(deck, view, query, root, cache), anchor);
        libraryTask = async(() -> {
            List<Track> rows = api.browseAll(view, query, root == null ? null : root.id, root == null ? "" : root.type);
            if (Thread.currentThread().isInterrupted()) throw new java.io.InterruptedIOException();
            deck.store.tracks(key, rows);
            return prepared(deck, view, query, root, rows);
        }, rows -> {
            if (generation != libraryGeneration) return;
            Anchor current = music.anchor();
            showMusic(rows, current == null ? anchor : current);
        }, error -> {
            if (generation != libraryGeneration) return;
            say(cache == null ? error : "Offline · showing the saved music library.");
        });
    }

    private static List<Track> prepared(DesktopPlayer.Deck deck, String view, String search, Track parent, List<Track> rows) {
        if (parent != null || !search.isBlank() || !List.of("Tracks","Artists","Albums","Favourites").contains(view)) return rows;
        List<Track> result = new ArrayList<>(rows);
        if (view.equals("Albums")) result.removeIf(Track::hiddenAlbum);
        if (view.equals("Favourites")) {
            JSONObject pending = deck.store.read("pending-favourites");
            result.removeIf(track -> pending.has(track.id) && !pending.optBoolean(track.id));
            var ids = new HashSet<String>();
            for (Track track : result) ids.add(track.id);
            for (Track local : deck.store.tracks("favourites-local"))
                if (ids.add(local.id)) result.add(local);
        }
        java.text.Collator compare = java.text.Collator.getInstance();
        Comparator<Track> title = Comparator.comparing(t -> t.name, compare);
        result.sort(view.equals("Artists") || view.equals("Albums") ? Comparator.<Track,String>comparing(t -> t.artist, compare).thenComparing(title) : title);
        return result;
    }

    private void showMusic(List<Track> rows, Anchor anchor) {
        favoriteOverrides = musicDeck == null ? new JSONObject() : musicDeck.store.read("pending-favourites");
        music.set(rows, anchor);
        boolean alphabetical = parent == null && term.isBlank() && List.of("Tracks", "Artists", "Albums", "Favourites").contains(section());
        updating = true;
        letters.setVisible(alphabetical && !rows.isEmpty());
        letters.removeAllItems();
        if (alphabetical) rows.stream().map(this::initial).distinct().forEach(letters::addItem);
        updating = false;
        if (musicDeck != null && tabs.getSelectedIndex() == 0) say(rows.size() + (rows.size() == 1 ? " item" : " items"));
    }

    private String initial(Track track) {
        String text = term.isBlank() && List.of("Artists", "Albums").contains(section()) ? track.artist : track.name;
        if (text.isBlank() || !Character.isLetter(text.codePointAt(0))) return "#";
        return text.substring(0, Character.charCount(text.codePointAt(0))).toUpperCase(Locale.ROOT);
    }

    private JSONObject favoriteOverrides = new JSONObject();

    private String trackFlags(Track track) {
        if (musicDeck == null) return "";
        String download = musicDeck.downloads.label(track.id);
        Boolean pending = favoriteOverrides.has(track.id) ? favoriteOverrides.optBoolean(track.id) : null;
        return ((pending == null ? track.favorite : pending) ? "  ★" : "") + (download.isBlank() ? "" : "  ·  " + download);
    }

    private void openTrack(Track track) {
        if (track.type.equals("Audio")) append(List.of(track), false, false);
        else {
            trail.push(location());
            parent = track;
            loadMusic(null);
        }
    }

    private void append(List<Track> tracks, boolean next, boolean now) {
        DesktopPlayer.Deck deck = musicDeck;
        if (deck == null || tracks.isEmpty()) return;
        int start = next && deck.current() != null ? deck.index + 1 : deck.queue.size();
        boolean first = deck.queue.isEmpty();
        deck.queue.addAll(start, tracks.stream().map(DesktopPlayer.Entry::of).toList());
        if (deck.index < 0) deck.index = 0;
        deck.reconcileShuffle();
        if (next && deck.shuffle && deck.current() != null) {
            int current = deck.shuffled.indexOf(deck.current().key());
            List<String> insert = deck.queue.subList(start, start + tracks.size()).stream().map(DesktopPlayer.Entry::key).toList();
            deck.shuffled.removeAll(insert);
            deck.shuffled.addAll(current + 1, insert);
        }
        deck.save();
        if (now || first) player.start(deck, start, 0);
        else {
            if (player.active == deck) player.plan();
            playerChanged();
        }
        say(tracks.size() == 1 ? "Queued · " + tracks.get(0).name : "Queued " + tracks.size() + " tracks");
    }

    private void musicMenu(MouseEvent event) {
        if (musicDeck == null) return;
        List<Track> selected = music.list.getSelectedValuesList();
        List<Track> tracks = selected.stream().filter(t -> t.type.equals("Audio")).toList();
        List<Track> all = music.values().stream().filter(t -> t.type.equals("Audio")).toList();
        JPopupMenu menu = new JPopupMenu();
        if (!tracks.isEmpty()) {
            item(menu, "Add selected to queue", () -> append(tracks, false, false));
            item(menu, "Play next", () -> append(tracks, true, false));
            item(menu, "Play now", () -> append(tracks, true, true));
            if (!demo) {
                item(menu, "Add to playlist", () -> addToPlaylist(tracks));
                item(menu, "Download / retry selected", () -> downloadTracks(tracks));
                if (tracks.stream().anyMatch(t -> !musicDeck.downloads.label(t.id).isBlank()))
                    item(menu, "Remove selected desktop copies", () -> removeCopies(musicDeck, tracks.stream().map(t -> t.id).toList()));
                if (tracks.size() == 1) item(menu, "Toggle favourite", () -> favorite(tracks.get(0)));
            }
        }
        if (!all.isEmpty()) {
            item(menu, "Add all to queue", () -> append(all, false, false));
            if (!demo) item(menu, "Download this view", () -> downloadTracks(all));
        }
        if (!demo) {
            item(menu, "Create playlist", () -> createPlaylist(tracks));
            if (parent != null && parent.type.equals("Playlist")) {
                item(menu, "Rename playlist", this::renamePlaylist);
                item(menu, "Duplicate playlist", () -> createPlaylist(all));
                if (!tracks.isEmpty()) item(menu, "Remove playlist entries", () -> removePlaylist(tracks));
                if (tracks.size() == 1) {
                    item(menu, "Move playlist entry up", () -> movePlaylist(tracks.get(0), -1));
                    item(menu, "Move playlist entry down", () -> movePlaylist(tracks.get(0), 1));
                }
                item(menu, "Undo last removal", () -> { Runnable action = undo; undo = null; if (action != null) action.run(); });
            }
        }
        show(menu, event, music.list);
    }

    private void favorite(Track track) {
        DesktopPlayer.Deck deck = musicDeck;
        Boolean old = deck.store.favoriteOverride(track.id);
        boolean value = !(old == null ? favoriteOverrides.optBoolean(track.id, track.favorite) : old);
        deck.store.editFavorite(track, value);
        favoriteOverrides.put(track.id, value);
        music.list.repaint();
        background();
    }

    private void downloadTracks(List<Track> tracks) {
        for (Track track : tracks) {
            musicDeck.store.writeChecked("download:" + track.id, track.json());
            musicDeck.downloads.enqueue(track.id);
        }
        say("Desktop downloads queued");
        refreshDownloadLabels();
    }

    private void removeCopies(DesktopPlayer.Deck deck, List<String> ids) {
        if (!confirm("Remove desktop copies?", "Server files and listening history are kept.")) return;
        async(() -> { for (String id : ids) deck.downloads.remove(id); return null; }, result -> {
            say("Desktop copies removed");
            if (deck == musicDeck && section().equals("Offline")) loadMusic(music.anchor());
            if (deck == podcastDeck) loadEpisodes();
        });
    }

    private void createPlaylist(List<Track> tracks) {
        JellyfinApi api = jellyfin;
        if (api == null) return;
        String name = prompt("Name for the private Jellyfin playlist", "");
        if (name == null || name.isBlank()) return;
        musicMutation(api, () -> api.create(name.strip(), tracks), "Playlist saved");
    }

    private void addToPlaylist(List<Track> tracks) {
        JellyfinApi api = jellyfin;
        if (api == null) return;
        async(() -> api.browseAll("Playlists", "", null, ""), playlists -> {
            if (api != jellyfin) return;
            Track target = chooseTrack("Add to playlist", playlists);
            if (target != null) musicMutation(api, () -> api.addMany(target.id, tracks), "Tracks added to " + target.name);
        });
    }

    private void renamePlaylist() {
        Track root = parent;
        JellyfinApi api = jellyfin;
        String name = prompt("Rename playlist", root.name);
        if (name != null && !name.isBlank()) musicMutation(api, () -> api.rename(root.id, name.strip()), "Playlist renamed");
    }

    private void removePlaylist(List<Track> tracks) {
        JellyfinApi api = jellyfin;
        Track root = parent;
        List<Integer> indexes = tracks.stream().map(t -> music.values().indexOf(t)).toList();
        async(() -> { api.removeMany(root.id, tracks); return null; }, ignored -> {
            if (api != jellyfin) return;
            undo = () -> musicMutation(api, () -> {
                for (int i = 0; i < tracks.size(); i++) api.restoreEntry(root.id, tracks.get(i), indexes.get(i));
            }, "Playlist entries restored");
            say("Playlist entries removed · Undo is in Actions");
            loadMusic(music.anchor());
        });
    }

    private void movePlaylist(Track track, int direction) {
        JellyfinApi api = jellyfin;
        Track root = parent;
        int target = music.values().indexOf(track) + direction;
        if (target >= 0 && target < music.model.size())
            musicMutation(api, () -> api.move(root.id, track, target), "Playlist entry moved");
    }

    private void musicMutation(JellyfinApi api, Work work, String message) {
        async(() -> { work.run(); return null; }, ignored -> {
            if (api != jellyfin) return;
            loadMusic(music.anchor());
            say(message);
        });
    }

    private DesktopPlayer.Deck selectedQueue() { return queueDeck.getSelectedIndex() == 0 ? musicDeck : podcastDeck; }

    private void loadQueue() {
        DesktopPlayer.Deck deck = selectedQueue();
        Anchor anchor = deck == displayedQueue ? queue.anchor() : null;
        displayedQueue = deck;
        queue.set(deck == null ? List.of() : deck.queue, anchor);
    }

    private void queueMenu(MouseEvent event) {
        DesktopPlayer.Deck deck = selectedQueue();
        List<DesktopPlayer.Entry> entries = queue.list.getSelectedValuesList();
        if (deck == null || entries.isEmpty()) return;
        DesktopPlayer.Entry selected = entries.get(0);
        JPopupMenu menu = new JPopupMenu();
        item(menu, "Play now", () -> player.start(deck, deck.find(selected.key()), deck.resume(selected.track())));
        item(menu, "Move up", () -> moveEntry(deck.find(selected.key()), deck.find(selected.key()) - 1));
        item(menu, "Move down", () -> moveEntry(deck.find(selected.key()), deck.find(selected.key()) + 1));
        if (deck == musicDeck && !demo) {
            List<Track> tracks = entries.stream().map(DesktopPlayer.Entry::track).toList();
            item(menu, "Download selected", () -> downloadTracks(tracks));
            item(menu, "Add selected to playlist", () -> addToPlaylist(tracks));
        }
        item(menu, "Remove selected", () -> removeQueue(deck, entries));
        show(menu, event, queue.list);
    }

    private void moveEntry(int from, int to) {
        DesktopPlayer.Deck deck = selectedQueue();
        if (deck == null || from < 0 || to < 0 || from >= deck.queue.size() || to >= deck.queue.size()) return;
        DesktopPlayer.Entry current = deck.current();
        deck.queue.add(to, deck.queue.remove(from));
        deck.index = current == null ? -1 : deck.find(current.key());
        queueEdited(deck);
        queue.list.setSelectedIndex(to);
    }

    private void clearQueue() {
        DesktopPlayer.Deck deck = selectedQueue();
        if (deck != null && !deck.queue.isEmpty() && confirm("Clear queue?", "You can undo this from the Queue view."))
            removeQueue(deck, List.copyOf(deck.queue));
    }

    private void removeQueue(DesktopPlayer.Deck deck, List<DesktopPlayer.Entry> entries) {
        DesktopPlayer.Entry current = deck.current();
        int previous = deck.index;
        boolean removedCurrent = entries.contains(current);
        boolean wasPlaying = player.active == deck && player.playing;
        if (removedCurrent && wasPlaying) player.pause();
        long savedPosition = deck.position;
        List<Integer> indexes = entries.stream().map(deck.queue::indexOf).toList();
        deck.queue.removeAll(entries);
        deck.index = deck.queue.isEmpty() ? -1 : removedCurrent ? Math.min(previous, deck.queue.size() - 1) : deck.find(current.key());
        if (removedCurrent) {
            if (deck.reporter != null) deck.reporter.stop();
            deck.position = deck.current() == null ? 0 : deck.resume(deck.current().track());
            if (wasPlaying && deck.current() != null) player.resume();
        }
        undo = () -> {
            if (deck != musicDeck && deck != podcastDeck) return;
            DesktopPlayer.Entry playing = deck.current();
            for (int i = 0; i < entries.size(); i++)
                deck.queue.add(Math.max(0, Math.min(indexes.get(i), deck.queue.size())), entries.get(i));
            deck.index = playing == null ? Math.max(0, Math.min(previous, deck.queue.size() - 1)) : deck.find(playing.key());
            if (playing == null) deck.position = savedPosition;
            queueEdited(deck);
        };
        queueEdited(deck);
        say("Queue updated · Undo is available");
    }

    private void queueEdited(DesktopPlayer.Deck deck) {
        deck.reconcileShuffle();
        deck.save();
        if (player.active == deck) player.plan();
        playerChanged();
    }

    private void saveQueue() {
        if (selectedQueue() == musicDeck && musicDeck != null) createPlaylist(musicDeck.queue.stream().map(DesktopPlayer.Entry::track).toList());
    }

    private void loadFeeds() {
        PodcastState connection = podcasts;
        if (connection == null) { say("Connect your Rewindamp podcast archive."); return; }
        async(connection::feeds, result -> {
            if (connection != podcasts) return;
            changingShows = true;
            feeds.clear();
            shows.removeAllItems();
            shows.addItem("All shows");
            int index = 0;
            for (int i = 0; i < result.length(); i++) {
                JSONObject show = result.getJSONObject(i);
                feeds.add(show);
                shows.addItem(show.optString("title"));
                if (show.optString("id").equals(feed)) index = i + 1;
            }
            if (index == 0) feed = null;
            shows.setSelectedIndex(index);
            order.setSelectedIndex(connection.oldest(feed) ? 1 : 0);
            changingShows = false;
            loadEpisodes();
        });
    }

    private void loadEpisodes() {
        long generation = ++podcastGeneration;
        if (podcastTask != null) podcastTask.cancel(true);
        PodcastState connection = podcasts;
        if (connection == null) {
            catalogue = List.of();
            redrawEpisodes();
            return;
        }
        if (filter.getSelectedIndex() == 2) {
            List<JSONObject> downloaded = new ArrayList<>();
            for (String id : podcastDeck.downloads.ids()) {
                JSONObject episode = connection.episode(id);
                if (episode.has("id") && (feed == null || episode.optString("feed_id").equals(feed))) downloaded.add(episode);
            }
            Comparator<JSONObject> byDate = Comparator.comparingDouble(e -> e.optDouble("published", 0));
            downloaded.sort(order.getSelectedIndex() == 1 ? byDate : byDate.reversed());
            catalogue = downloaded;
            redrawEpisodes();
            return;
        }
        String selectedFeed = feed;
        boolean oldest = order.getSelectedIndex() == 1;
        podcastTask = async(() -> {
            List<JSONObject> result = new ArrayList<>();
            for (int offset = 0; ; offset += 100) {
                List<JSONObject> page = connection.episodes(selectedFeed, false, offset, oldest, false);
                result.addAll(page);
                if (page.size() < 100) return result;
                if (Thread.currentThread().isInterrupted()) throw new java.io.InterruptedIOException();
            }
        }, result -> {
            if (generation != podcastGeneration) return;
            catalogue = result;
            redrawEpisodes();
            if (tabs.getSelectedIndex() == 1) say(episodes.model.size() + " episodes");
        }, error -> {
            if (generation != podcastGeneration) return;
            say(error);
        });
    }

    private void redrawEpisodes() {
        Anchor anchor = episodes.anchor();
        if (podcasts == null) { episodes.set(List.of(), null); return; }
        boolean inbox = filter.getSelectedIndex() == 0;
        List<JSONObject> result = new ArrayList<>();
        JSONObject journal = podcasts.journal().getJSONObject("states");
        for (JSONObject original : catalogue) {
            JSONObject update = journal.optJSONObject(original.optString("id"));
            JSONObject episode = update == null ? original : new JSONObject(original.toString());
            if (update != null) for (String key : List.of("position", "state", "epoch")) episode.put(key, update.get(key));
            if (!inbox || !finished(episode)) result.add(episode);
        }
        episodes.set(result, anchor);
    }

    private static boolean finished(JSONObject episode) { return List.of("played", "dismissed").contains(episode.optString("state")); }

    private String episodeDetails(JSONObject episode) {
        if (podcastDeck == null) return "";
        String label = podcastDeck.downloads.label(episode.optString("id"));
        boolean available = episode.optBoolean("downloaded");
        String state = episode.optString("state", "unplayed").replace('_', ' ');
        long position = (long)(episode.optDouble("position", 0) * 1000);
        long duration = (long)(episode.optDouble("duration", 0) * 1000);
        return episode.optString("show_title") + "  ·  " + state
            + (position > 0 && duration > position ? "  ·  " + Track.time(duration - position) + " left" : "")
            + (label.isBlank() ? available ? "" : "  ·  Awaiting server audio" : "  ·  " + label);
    }

    private void playEpisode(JSONObject chosen) {
        DesktopPlayer.Deck deck = podcastDeck;
        if (deck == null) return;
        String id = chosen.optString("id");
        if (!playable(chosen)) {
            if (confirm("Download episode on server?", chosen.optString("title"))) archiveEpisode(chosen);
            return;
        }
        List<Track> snapshot = episodes.values().stream().filter(this::playable)
            .filter(row -> podcasts.showPlayed() || !finished(row) || row.optString("id").equals(id))
            .map(DesktopApp::episodeTrack).toList();
        player.activate(deck);
        player.pause();
        if (deck.current() != null && !deck.current().track().id.equals(id) && deck.store.read("podcast-sleep").optBoolean("end"))
            podcasts.clearSleep();
        deck.queue.clear();
        for (Track episode : snapshot) deck.queue.add(DesktopPlayer.Entry.of(episode));
        deck.index = -1;
        for (int i = 0; i < deck.queue.size(); i++) if (deck.queue.get(i).track().id.equals(id)) deck.index = i;
        if (deck.index < 0) return;
        deck.rebuildShuffle();
        deck.save();
        player.start(deck, deck.index, deck.resume(deck.current().track()));
        loadQueue();
    }

    private boolean playable(JSONObject episode) {
        return episode.optBoolean("downloaded") || podcastDeck.downloads.complete(episode.optString("id"));
    }

    private static Track episodeTrack(JSONObject episode) {
        return new Track(episode.getString("id"), episode.optString("title"), episode.optString("show_title"),
            "", "Podcast", "", (long)(episode.optDouble("duration", 0) * 1000));
    }

    private void episodeMenu(MouseEvent event) {
        JSONObject episode = episodes.list.getSelectedValue();
        if (podcasts == null || episode == null) return;
        JPopupMenu menu = new JPopupMenu();
        item(menu, "Play / resume", () -> playEpisode(episode));
        item(menu, "Mark played", () -> mark(episode, "played"));
        item(menu, "Dismiss from inbox", () -> mark(episode, "dismissed"));
        item(menu, "Mark unplayed / reset position", () -> mark(episode, "reset"));
        String id = episode.optString("id");
        if (episode.optBoolean("downloaded"))
            item(menu, "Download / retry desktop copy", () -> { podcastDeck.downloads.enqueue(id); refreshDownloadLabels(); });
        if (!podcastDeck.downloads.label(id).isBlank())
            item(menu, "Remove desktop copy", () -> removeCopies(podcastDeck, List.of(id)));
        item(menu, "Download on server", () -> { if (confirm("Download episode on server?", episode.optString("title"))) archiveEpisode(episode); });
        show(menu, event, episodes.list);
    }

    private void mark(JSONObject episode, String action) {
        PodcastState connection = podcasts;
        String id = episode.optString("id");
        boolean current = player.active == podcastDeck && podcastDeck.current() != null && podcastDeck.current().track().id.equals(id);
        long at = current ? player.position() : (long)(episode.optDouble("position", 0) * 1000);
        if (current && action.equals("reset")) player.pause();
        connection.event(id, at, action);
        if (current && action.equals("reset")) { podcastDeck.position = 0; podcastDeck.save(); playerChanged(); }
        background();
        if (player.active == podcastDeck) player.plan();
        redrawEpisodes();
    }

    private void archiveEpisode(JSONObject episode) {
        PodcastApi api = podcasts.api;
        String id = episode.getString("id");
        async(() -> api.request("/api/episodes/" + UrlEncoding.encode(id) + "/download", new JSONObject()), ignored -> say("Server download queued"));
    }

    private void discovery() {
        if (podcasts == null) { podcastConnection(); return; }
        PodcastApi api = podcasts.api;
        String term = prompt("Find shows by title or publisher", "");
        if (term == null || term.isBlank()) return;
        async(() -> api.request("/api/podcasts/search?term=" + UrlEncoding.encode(term.strip()), null), result -> {
            if (podcasts == null || podcasts.api != api) return;
            JSONArray items = result.optJSONArray("items");
            if (items == null || items.isEmpty()) { say("No podcasts found"); return; }
            List<JSONObject> available = objects(items);
            List<String> labels = available.stream().map(item -> item.optString("title") + " — " + item.optString("author")
                + (item.optBoolean("subscribed") ? " · Subscribed" : "")).toList();
            int chosen = choose("Find shows", labels);
            if (chosen < 0 || available.get(chosen).optBoolean("subscribed")) return;
            JSONObject show = available.get(chosen);
            pass(show.optString("title"), show.getString("feed_url"), true);
        });
    }

    private void editPass() {
        if (podcasts == null || feed == null) { say("Choose a show to edit its subscription."); return; }
        for (JSONObject show : feeds) if (show.optString("id").equals(feed)) {
            pass(show.optString("title"), show.getString("url"), false);
            return;
        }
    }

    private void pass(String title, String url, boolean newFeed) {
        int choice = choose(newFeed ? "Subscribe to " + title : "Automatic downloads for " + title, List.of(PodcastPass.LABELS));
        if (choice >= 0) savePass(PodcastPass.body(title, url, choice, newFeed));
    }

    private void savePass(JSONObject body) {
        PodcastState connection = podcasts;
        async(() -> connection.api.request("/api/feeds", body), result -> {
            if (connection != podcasts) return;
            feed = result.optString("id");
            loadFeeds();
            say("Subscription saved");
        });
    }

    private void podcastSettings() {
        if (podcasts == null) { podcastConnection(); return; }
        String[] actions = {"Offline downloads", "Sleep timer · " + podcasts.sleepDescription(),
            podcasts.showPlayed() ? "Exclude played and dismissed episodes from new queues" : "Include played and dismissed episodes in new queues",
            "Refresh feeds on server", "Subscribe by RSS URL", "Sync listening history"};
        switch (choose("Podcast settings", List.of(actions))) {
            case 0 -> downloadSettings(podcastDeck);
            case 1 -> sleep();
            case 2 -> {
                podcasts.preference("show_played", !podcasts.showPlayed());
                if (player.active == podcastDeck) player.plan();
            }
            case 3 -> {
                PodcastApi api = podcasts.api;
                String chosen = feed;
                List<JSONObject> subscribed = List.copyOf(feeds);
                async(() -> {
                    for (JSONObject show : subscribed) {
                        String id = show.getString("id");
                        if (chosen == null || chosen.equals(id)) api.request("/api/feeds/" + UrlEncoding.encode(id) + "/refresh", new JSONObject());
                    }
                    return null;
                }, ignored -> say("Server feed refresh queued"));
            }
            case 4 -> {
                String url = prompt("Podcast RSS feed URL", "https://");
                if (url != null && !url.isBlank()) savePass(new JSONObject().put("url", url.strip()).put("mode", "future").put("only_if_new", true));
            }
            case 5 -> async(() -> { PodcastState connection = podcasts; if (connection != null) connection.sync(); return null; },
                ignored -> { loadEpisodes(); say("Podcast sync attempted"); });
            default -> {}
        }
    }

    private void sleep() {
        if (podcasts == null) return;
        int choice = choose("Podcast sleep timer", List.of("Off", "15 minutes", "30 minutes", "60 minutes", "90 minutes",
            "120 minutes", "End of current episode"));
        if (choice < 0) return;
        if (choice == 6 && podcastDeck.current() == null) { say("Start an episode before setting this timer."); return; }
        int[] minutes = {0, 15, 30, 60, 90, 120, 0};
        podcasts.sleep(minutes[choice] == 0 ? 0 : System.currentTimeMillis() + minutes[choice] * 60_000L, choice == 6,
            podcastDeck.current() == null ? "" : podcastDeck.current().track().id);
        if (player.active == podcastDeck) player.plan();
        say("Sleep timer · " + podcasts.sleepDescription());
    }

    private void downloadSettings(DesktopPlayer.Deck deck) {
        if (deck == null) return;
        List<String> actions = new ArrayList<>(List.of(deck.downloads.paused() ? "Resume transfers" : "Pause transfers",
            "Show desktop copies", "Remove all completed desktop copies"));
        if (deck == podcastDeck) actions.addAll(List.of("Download next three unplayed episodes", "Automatic upcoming downloads",
            podcasts.store.read("podcast-preferences").optBoolean("unmetered", true) ? "Allow metered downloads" : "Restrict to unmetered networks"));
        switch (choose("Downloads · " + deck.downloads.summary(), actions)) {
            case 0 -> deck.downloads.pause(!deck.downloads.paused());
            case 1 -> {
                if (deck == podcastDeck) { tabs.setSelectedIndex(1); filter.setSelectedIndex(2); }
                else { tabs.setSelectedIndex(0); section.setSelectedItem("Offline"); }
            }
            case 2 -> {
                if (deck == podcastDeck && !confirm("Remove completed desktop copies?", "Automatic upcoming downloads will also be turned off.")) return;
                List<String> ids = Arrays.stream(deck.downloads.ids()).filter(deck.downloads::complete).toList();
                if (deck == podcastDeck) {
                    autoDownloads(0);
                    async(() -> { for (String id : ids) deck.downloads.remove(id); return null; }, ignored -> loadEpisodes());
                } else removeCopies(deck, ids);
            }
            case 3 -> {
                for (JSONObject episode : catalogue.stream().map(row -> podcasts.episode(row.optString("id")))
                    .filter(row -> row.optBoolean("downloaded") && !finished(row) && !deck.downloads.complete(row.optString("id"))).limit(3).toList())
                    deck.downloads.enqueue(episode.getString("id"));
            }
            case 4 -> {
                int choice = choose("Automatic upcoming downloads", List.of("Off", "Next episode", "Next 3 episodes", "Next 5 episodes"));
                if (choice >= 0) autoDownloads(new int[]{0, 1, 3, 5}[choice]);
            }
            case 5 -> {
                podcasts.preference("unmetered", !podcasts.store.read("podcast-preferences").optBoolean("unmetered", true));
                checkMetering();
            }
            default -> {}
        }
        refreshDownloadLabels();
    }

    private void autoDownloads(int count) {
        player.autoDownloads = count;
        podcasts.store.writeChecked("podcast-preferences", podcasts.store.read("podcast-preferences").put("auto_downloads", count));
        if (player.active == podcastDeck) player.plan();
    }

    private void checkMetering() {
        DesktopPlayer.Deck deck = podcastDeck;
        if (deck == null) return;
        boolean unmetered = deck.store.read("podcast-preferences").optBoolean("unmetered", true);
        if (!unmetered || URIHost.loopback(deck.podcasts.api.server)) { deck.downloads.networkAllowed(true); deck.downloads.start(); return; }
        async(() -> {
            Process query;
            try {
                query = new ProcessBuilder("busctl", "--system", "get-property", "org.freedesktop.NetworkManager",
                    "/org/freedesktop/NetworkManager", "org.freedesktop.NetworkManager", "Metered")
                    .redirectError(ProcessBuilder.Redirect.DISCARD).start();
            } catch (IOException e) { return false; }
            try {
                if (!query.waitFor(3, java.util.concurrent.TimeUnit.SECONDS) || query.exitValue() != 0) return false;
                String value = new String(query.getInputStream().readNBytes(100), java.nio.charset.StandardCharsets.UTF_8).strip();
                return value.equals("u 2") || value.equals("u 4");
            } finally { query.destroyForcibly(); }
        }, allowed -> {
            if (podcastDeck != deck) return;
            deck.downloads.networkAllowed(allowed || !deck.store.read("podcast-preferences").optBoolean("unmetered", true));
            deck.downloads.start();
        });
    }

    private static final class URIHost {
        static boolean loopback(String server) {
            return List.of("127.0.0.1", "localhost", "[::1]").contains(java.net.URI.create(server).getHost());
        }
    }

    private void openMixarr() {
        if (jellyfin == null) return;
        mixarr.open(term);
    }

    private void settings() {
        switch (choose("Rewindamp settings", List.of("Playback transitions", "Music downloads", "Sync favourites and listening history",
            "Podcast settings", "Close to system tray"))) {
            case 0 -> transitions();
            case 1 -> downloadSettings(musicDeck);
            case 2 -> {
                background();
                say("Sync scheduled");
            }
            case 3 -> podcastSettings();
            case 4 -> minimizeToTray();
            default -> {}
        }
    }

    private void transitions() {
        JComboBox<String> mode = new JComboBox<>(new String[]{"Gapless · continuous albums", "Crossfade · overlap songs", "Separate · short pause"});
        mode.setSelectedIndex(switch (player.transition) { case "crossfade" -> 1; case "separate" -> 2; default -> 0; });
        JSpinner duration = new JSpinner(new SpinnerNumberModel(player.crossfadeSeconds, 1, 12, 1));
        if (JOptionPane.showConfirmDialog(frame, column(new JLabel("Track boundaries"), mode, new JLabel("Crossfade seconds"), duration),
            "Playback transitions", JOptionPane.OK_CANCEL_OPTION) != JOptionPane.OK_OPTION) return;
        player.transition = List.of("gapless", "crossfade", "separate").get(mode.getSelectedIndex());
        player.crossfadeSeconds = (Integer)duration.getValue();
        player.saveSettings();
        player.plan();
    }

    private void equalizer() {
        Equalizer.Values values = player.equalizer();
        JCheckBox enabled = new JCheckBox("Enable ten-band equalizer", values.enabled());
        JPanel sliders = new JPanel(new java.awt.GridLayout(1, 11, 4, 0));
        JSlider[] bands = new JSlider[11];
        for (int i = 0; i < bands.length; i++) {
            String label = i == 0 ? "Preamp" : Equalizer.LABELS[i - 1];
            JSlider slider = new JSlider(JSlider.VERTICAL, -12, 12, Math.round(i == 0 ? values.preamp() : values.gains()[i - 1]));
            bands[i] = slider;
            slider.setMajorTickSpacing(6);
            slider.setPaintTicks(true);
            slider.setPreferredSize(new Dimension(38, 155));
            slider.setToolTipText(label + " gain");
            slider.getAccessibleContext().setAccessibleName(label + " gain in dB");
            sliders.add(column(new JLabel(label), slider));
        }
        JComboBox<String> presets = new JComboBox<>(new String[]{"Flat", "Rock", "Bass", "Vocal", "Electronic"});
        Runnable apply = () -> {
            float[] gains = new float[10];
            for (int i = 0; i < gains.length; i++) gains[i] = bands[i + 1].getValue();
            player.equalizer(enabled.isSelected(), bands[0].getValue(), gains);
        };
        enabled.addActionListener(event -> apply.run());
        for (JSlider band : bands) band.addChangeListener(event -> apply.run());
        presets.addActionListener(event -> {
            float[] gains = Equalizer.preset(String.valueOf(presets.getSelectedItem()));
            enabled.setSelected(true);
            bands[0].setValue(0);
            for (int i = 0; i < gains.length; i++) bands[i + 1].setValue(Math.round(gains[i]));
            apply.run();
        });
        if (JOptionPane.showConfirmDialog(frame, column(row(enabled, new JLabel("Preset"), presets), sliders),
            "Rewindamp equalizer", JOptionPane.OK_CANCEL_OPTION) == JOptionPane.OK_OPTION) player.saveSettings();
        else player.equalizer(values.enabled(), values.preamp(), values.gains());
    }

    private java.awt.TrayIcon tray;

    private void minimizeToTray() {
        if (!java.awt.SystemTray.isSupported()) { frame.setState(JFrame.ICONIFIED); return; }
        if (tray == null) try {
            java.awt.image.BufferedImage icon = new java.awt.image.BufferedImage(32, 32, java.awt.image.BufferedImage.TYPE_INT_ARGB);
            var g = icon.createGraphics();
            g.setColor(SHELL); g.fillRect(0, 0, 32, 32);
            g.setColor(GREEN); g.fillPolygon(new int[]{9, 24, 9}, new int[]{6, 16, 26}, 3);
            g.dispose();
            java.awt.PopupMenu menu = new java.awt.PopupMenu();
            java.awt.MenuItem show = new java.awt.MenuItem("Show Rewindamp"), quit = new java.awt.MenuItem("Quit");
            show.addActionListener(event -> SwingUtilities.invokeLater(this::showWindow));
            quit.addActionListener(event -> SwingUtilities.invokeLater(this::close));
            menu.add(show); menu.add(quit);
            tray = new java.awt.TrayIcon(icon, "Rewindamp", menu);
            tray.addActionListener(event -> SwingUtilities.invokeLater(this::showWindow));
            java.awt.SystemTray.getSystemTray().add(tray);
        } catch (java.awt.AWTException e) { say("System tray unavailable"); return; }
        frame.setVisible(false);
        visibility();
    }

    private void showWindow() {
        frame.setVisible(true);
        frame.setState(JFrame.NORMAL);
        frame.toFront();
        visibility();
    }

    private void toggleTime() {
        String key = player.active == podcastDeck ? "podcast-remaining" : "music-remaining";
        JSONObject state = preferences.read("time");
        preferences.write("time", state.put(key, !state.optBoolean(key)));
        updateTimeMode();
        position();
    }

    private boolean remainingTime;
    private void updateTimeMode() {
        remainingTime = preferences.read("time").optBoolean(player.active == podcastDeck ? "podcast-remaining" : "music-remaining");
    }

    private void seekFromSlider() {
        Track current = current();
        if (current != null && current.duration > 0) player.seek(current.duration * seek.getValue() / 1000);
    }

    private Track current() { return player.active == null || player.active.current() == null ? null : player.active.current().track(); }

    private void openActiveQueue() {
        queueDeck.setSelectedIndex(player.active != null && player.active == podcastDeck ? 1 : 0);
        tabs.setSelectedIndex(2);
    }

    private void playerChanged() {
        Track current = current();
        DesktopPlayer.Deck deck = player.active;
        String key = deck == null || deck.current() == null ? "" : deck.current().key();
        title.setText(current == null ? "No audio queued" : current.name);
        subtitle.setText(current == null ? " " : current.subtitle());
        if (!key.equals(currentKey)) {
            currentKey = key;
            loadArtwork(deck, current);
        }
        if (player.playing) {
            preferences.write("last-playback", new JSONObject().put("deck", deck == podcastDeck ? "podcasts" : "music"));
            if (!checkpointTimer.isRunning()) checkpointTimer.start();
        } else checkpointTimer.stop();
        transport.removeAll();
        boolean podcast = deck != null && deck == podcastDeck;
        int next = deck == null ? -1 : deck.adjacent(1, true);
        upNext.setVisible(podcast && next >= 0);
        if (podcast && next >= 0) upNext.setToolTipText("Up next · " + deck.queue.get(next).track().name);
        JButton previous = button(podcast ? "Previous episode" : "Previous", () -> player.skip(-1));
        JButton following = button(podcast ? "Next episode" : "Next", () -> player.skip(1));
        previous.setEnabled(deck != null && deck.adjacent(-1, false) >= 0);
        following.setEnabled(deck != null && deck.adjacent(1, false) >= 0);
        transport.add(previous);
        if (podcast) transport.add(button("−15 s", () -> player.seek(player.position() - 15_000)));
        JButton play = button(player.playing ? "Pause" : "Play", player::toggle);
        play.setEnabled(current != null);
        transport.add(play);
        if (podcast) transport.add(button("+30 s", () -> player.seek(player.position() + 30_000)));
        transport.add(following);
        if (podcast) transport.add(button(deck.speed + "×", this::speed));
        else if (deck != null) {
            transport.add(button(deck.shuffle ? "Shuffle on" : "Shuffle off", () -> {
                deck.shuffle = !deck.shuffle; deck.rebuildShuffle(); queueEdited(deck);
            }));
            transport.add(button(List.of("Repeat off", "Repeat one", "Repeat all").get(deck.repeat), () -> {
                deck.repeat = (deck.repeat + 1) % 3; queueEdited(deck);
            }));
        }
        transport.revalidate();
        transport.repaint();
        updateTimeMode();
        visibility();
        position();
        if (deck != null) {
            scheduleNetwork();
            if (deck == podcastDeck && tabs.getSelectedIndex() == 1) redrawEpisodes();
        }
        loadQueue();
        refreshDownloadLabels();
    }

    private void speed() {
        DesktopPlayer.Deck deck = podcastDeck;
        if (deck == null || deck != player.active) return;
        List<String> labels = List.of("0.75×", "1×", "1.25×", "1.5×", "1.75×", "2×", "2.5×", "3×");
        int picked = choose("Podcast playback speed", labels);
        if (picked < 0) return;
        changeSpeed(Double.parseDouble(labels.get(picked).replace("×", "")));
    }

    private void changeSpeed(double rate) {
        DesktopPlayer.Deck deck = podcastDeck;
        if (deck == null || deck != player.active || deck.speed == rate) return;
        boolean resume = player.playing;
        player.pause();
        deck.speed = rate;
        deck.save();
        if (resume) player.resume(); else playerChanged();
    }

    private void visibility() {
        boolean visible = frame.isActive() && frame.isShowing() && (frame.getExtendedState() & JFrame.ICONIFIED) == 0;
        player.visible(visible && player.active != podcastDeck);
        mixarr.visibility();
        if (visible) refreshDownloadLabels(); else downloadsTimer.stop();
        if (visible && player.playing) {
            if (!positionTimer.isRunning()) positionTimer.start();
        } else positionTimer.stop();
    }

    private void position() {
        Track current = current();
        long duration = current == null ? 0 : current.duration;
        long at = player.position();
        long elapsed = Math.max(0, at);
        String time = remainingTime ? duration <= 0 ? "--:--" : "−" + Track.time(duration - elapsed) : Track.time(elapsed);
        if (!clock.getText().equals(time)) clock.setText(time);
        seek.setEnabled(duration > 0);
        if (!seeking && duration > 0) {
            boolean wasUpdating = updating;
            updating = true;
            seek.setValue((int)Math.max(0, Math.min(1000, at * 1000 / duration)));
            updating = wasUpdating;
        }
        title.advance(player.playing);
        float[] levels = player.spectrum();
        if (spectrum.levels != levels) {
            spectrum.levels = levels;
            spectrum.repaint();
        }
        updateMpris();
    }

    private void tick() {
        if (!player.playing) return;
        player.checkpoint();
        scheduleNetwork();
        updateMpris();
        if (player.active == podcastDeck && podcasts != null) {
            long until = podcasts.store.read("podcast-sleep").optLong("until");
            if (until > 0 && until <= System.currentTimeMillis()) {
                podcasts.clearSleep();
                player.pause();
                say("Podcast sleep timer paused playback");
            }
        }
    }

    private void scheduleNetwork() {
        if (!networkTimer.isRunning()) networkTimer.start();
    }

    private void refreshDownloadLabels() {
        boolean visible = frame.isActive() && frame.isShowing() && (frame.getExtendedState() & JFrame.ICONIFIED) == 0;
        int count = (musicDeck == null ? 0 : musicDeck.downloads.pending()) + (podcastDeck == null ? 0 : podcastDeck.downloads.pending());
        if (podcastDeck != null && podcastDeck.downloads.pending() > 0 && !podcastDeck.downloads.paused()) scheduleNetwork();
        if (visible && count > 0) {
            if (!downloadsTimer.isRunning()) downloadsTimer.start();
        } else downloadsTimer.stop();
        if (count != lastDownloadCount || count > 0) {
            music.list.repaint();
            episodes.list.repaint();
            lastDownloadCount = count;
        }
    }

    private void background() {
        if (podcastDeck != null && podcastDeck.downloads.pending() > 0 && !podcastDeck.downloads.paused()) {
            checkMetering();
            scheduleNetwork();
        }
        if (networkBusy) { scheduleNetwork(); return; }
        DesktopPlayer.Deck deck = musicDeck;
        PodcastState connection = podcasts;
        JSONArray musicEvents = deck == null ? null : deck.store.read("playback-outbox").optJSONArray("events");
        boolean musicPending = deck != null && deck.reporter != null && (!deck.store.read("pending-favourites").isEmpty()
            || musicEvents != null && !musicEvents.isEmpty());
        JSONArray events = connection == null ? null : connection.store.read("podcast-journal").optJSONArray("events");
        boolean podcastPending = events != null && !events.isEmpty();
        if (!musicPending && !podcastPending) return;
        scheduleNetwork();
        networkBusy = true;
        async(() -> {
            if (musicPending) deck.reporter.sync();
            if (podcastPending) connection.sync();
            return null;
        }, ignored -> networkBusy = false, error -> networkBusy = false);
    }

    private void loadArtwork(DesktopPlayer.Deck deck, Track current) {
        artwork.picture = null;
        artwork.repaint();
        if (current == null || deck != musicDeck || jellyfin == null || current.imageId.isBlank()) return;
        JellyfinApi api = jellyfin;
        String key = artworkKey(current);
        artworkID = key;
        async(() -> image(deck, api, current, 80), icon -> {
            if (deck != musicDeck || !key.equals(artworkID) || current() != current) return;
            artwork.picture = icon.getImage();
            artwork.repaint();
        }, ignored -> {});
    }

    private static String artworkKey(Track track) { return track.imageId + ":" + track.imageTag; }

    private ImageIcon trackIcon(Track track) {
        if (track.imageId.isBlank() || jellyfin == null || track.type.equals("Podcast")) return null;
        DesktopPlayer.Deck deck = musicDeck;
        JellyfinApi api = jellyfin;
        String key = artworkKey(track);
        if (rowArtwork.containsKey(key)) return rowArtwork.get(key);
        if (!frame.isShowing() || artworkPending.size() >= 4 || !artworkPending.add(key)) return null;
        async(() -> image(deck, api, track, 34), icon -> {
            if (deck != musicDeck) return;
            artworkPending.remove(key);
            rowArtwork.put(key, icon);
            music.list.repaint();
            queue.list.repaint();
        }, error -> {
            artworkPending.remove(key);
            if (deck == musicDeck) rowArtwork.put(key, null);
        });
        return null;
    }

    private static ImageIcon image(DesktopPlayer.Deck deck, JellyfinApi api, Track track, int size) throws Exception {
        byte[] bytes = deck.store.artwork(track);
        if (bytes.length == 0) {
            bytes = api.image(track);
            deck.store.artwork(track, bytes);
        }
        if (bytes.length == 0) return new ImageIcon();
        ImageIcon original = new ImageIcon(bytes);
        return original.getIconWidth() > 0 ? new ImageIcon(original.getImage().getScaledInstance(size, size, java.awt.Image.SCALE_SMOOTH)) : new ImageIcon();
    }

    private void mediaCommand(String action, long position) {
        boolean podcast = player.active != null && player.active == podcastDeck;
        switch (action) {
            case "Raise" -> showWindow();
            case "Quit" -> close();
            case "Play" -> player.resume();
            case "PlayPause" -> player.toggle();
            case "Pause" -> player.pause();
            case "Stop" -> { player.pause(); player.seek(0); }
            case "Next" -> { if (podcast) player.seek(player.position() + 30_000); else player.skip(1); }
            case "Previous" -> { if (podcast) player.seek(player.position() - 15_000); else player.skip(-1); }
            case "Seek" -> player.seek(player.position() + position / 1000);
            case "SetPosition" -> player.seek(position / 1000);
            case "Volume" -> volume.setValue((int)Math.round(position / 10.0));
            case "Rate" -> { if (position == 0) player.pause(); else if (podcast) changeSpeed(position / 1000.0); }
            default -> {}
        }
    }

    private long mprisUpdated;
    private void updateMpris() {
        long now = System.nanoTime();
        if (player.playing && now - mprisUpdated < 1_000_000_000L && currentKey.equals(mediaKeys.trackKey())) return;
        mprisUpdated = now;
        Track track = current();
        DesktopPlayer.Deck deck = player.active;
        mediaKeys.update(track, currentKey, player.playing, player.position(), deck == null ? 1 : deck.speed, player.volume, deck != null && deck == podcastDeck,
            deck != null && (deck == podcastDeck ? track != null : deck.adjacent(-1, false) >= 0),
            deck != null && (deck == podcastDeck ? track != null : deck.adjacent(1, false) >= 0));
    }

    private void close() {
        positionTimer.stop(); checkpointTimer.stop(); networkTimer.stop(); downloadsTimer.stop(); searchTimer.stop();
        player.close();
        if (musicDeck != null) {
            musicDeck.downloads.close();
            if (musicDeck.reporter != null) io.submit(musicDeck.reporter::sync);
        }
        if (podcastDeck != null) {
            podcastDeck.downloads.close();
            io.submit(podcasts::sync);
        }
        mixarr.close();
        mediaKeys.close();
        if (tray != null) java.awt.SystemTray.getSystemTray().remove(tray);
        frame.dispose();
        io.shutdown();
        System.exit(0);
    }

    private void say(String message) { status.setText(message == null || message.isBlank() ? " " : message); }
    private String prompt(String message, String value) { return (String)JOptionPane.showInputDialog(frame, message, "Rewindamp", JOptionPane.PLAIN_MESSAGE, null, null, value); }
    private boolean confirm(String title, String message) { return JOptionPane.showConfirmDialog(frame, message, title, JOptionPane.OK_CANCEL_OPTION) == JOptionPane.OK_OPTION; }
    private int choose(String title, List<String> choices) {
        if (choices.isEmpty()) return -1;
        JList<String> picker = new JList<>(choices.toArray(String[]::new));
        picker.setSelectedIndex(0);
        picker.setVisibleRowCount(Math.min(10, choices.size()));
        picker.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        return JOptionPane.showConfirmDialog(frame, new JScrollPane(picker), title, JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE) == JOptionPane.OK_OPTION
            ? picker.getSelectedIndex() : -1;
    }

    private Track chooseTrack(String title, List<Track> tracks) {
        int selected = choose(title, tracks.stream().map(t -> t.name).toList());
        return selected < 0 ? null : tracks.get(selected);
    }

    static List<JSONObject> objects(JSONArray array) {
        List<JSONObject> items = new ArrayList<>();
        if (array != null) for (int i = 0; i < array.length(); i++) {
            JSONObject row = array.optJSONObject(i);
            if (row != null) items.add(row);
        }
        return items;
    }

    private <T> Future<?> async(Callable<T> work, Consumer<T> result) { return async(work, result, this::say); }
    private <T> Future<?> async(Callable<T> work, Consumer<T> result, Consumer<String> failure) {
        return io.submit(() -> {
            try {
                T value = work.call();
                SwingUtilities.invokeLater(() -> result.accept(value));
            } catch (Exception e) {
                if (!Thread.currentThread().isInterrupted()) SwingUtilities.invokeLater(() -> failure.accept(error(e)));
            }
        });
    }

    private static String error(Exception e) {
        if (e instanceof java.net.ConnectException || e instanceof java.net.SocketTimeoutException) return "Could not reach the server. Check the connection and retry.";
        if (e instanceof IOException || e instanceof IllegalArgumentException) return e.getMessage() == null ? "Request failed" : e.getMessage();
        return "Request failed. Check your connection or permissions.";
    }

    @FunctionalInterface private interface Work { void run() throws Exception; }
    private static Track blankTrack(String id) { return new Track(id, "", "", "", "Audio", "", 0); }

    static void item(JPopupMenu menu, String label, Runnable run) {
        JMenuItem item = new JMenuItem(label);
        item.addActionListener(event -> run.run());
        menu.add(item);
    }

    private static void show(JPopupMenu menu, MouseEvent event, JList<?> list) {
        if (event == null) {
            Rectangle area = list.getVisibleRect();
            menu.show(list, area.x + 12, area.y + 12);
        } else menu.show(list, event.getX(), event.getY());
    }

    static void listen(JTextField field, Runnable run) {
        field.getDocument().addDocumentListener(new DocumentListener() {
            public void insertUpdate(DocumentEvent event) { run.run(); }
            public void removeUpdate(DocumentEvent event) { run.run(); }
            public void changedUpdate(DocumentEvent event) { run.run(); }
        });
    }

    private static <T> void setRenderer(Rows<T> rows, java.util.function.Function<T, String> name,
        java.util.function.Function<T, String> subtitle, java.util.function.Function<T, ImageIcon> image) {
        rows.list.setCellRenderer(new ListCellRenderer<>() {
            double progress;
            final JPanel cell = new JPanel(new BorderLayout(9, 0)) {
                @Override protected void paintComponent(java.awt.Graphics graphics) {
                    super.paintComponent(graphics);
                    if (progress > 0) {
                        graphics.setColor(GOLD);
                        graphics.fillRect(53, getHeight() - 3, (int)((getWidth() - 65) * progress), 2);
                    }
                }
            };
            final JLabel art = new JLabel();
            final JLabel title = lcd("", 14);
            final JLabel second = new JLabel(), handle = lcd("≡", 15);
            {
                JPanel lines = column(title, second);
                lines.setOpaque(false);
                cell.setBorder(BorderFactory.createEmptyBorder(4, 9, 4, 9));
                art.setPreferredSize(new Dimension(35, 35));
                cell.add(art, BorderLayout.WEST);
                cell.add(lines, BorderLayout.CENTER);
                handle.setHorizontalAlignment(SwingConstants.CENTER);
                handle.setPreferredSize(new Dimension(20, 24));
                cell.add(handle, BorderLayout.EAST);
            }
            @Override public java.awt.Component getListCellRendererComponent(JList<? extends T> list, T value, int index, boolean selected, boolean focused) {
                title.putClientProperty("html.disable", true);
                second.putClientProperty("html.disable", true);
                title.setText(name.apply(value));
                second.setText(subtitle.apply(value));
                art.setIcon(image.apply(value));
                handle.setVisible(rows.canReorder.getAsBoolean());
                if (value instanceof JSONObject episode && episode.optDouble("duration", 0) > 0)
                    progress = Math.max(0, Math.min(1, episode.optDouble("position", 0) / episode.getDouble("duration")));
                else progress = 0;
                title.setForeground(selected ? GREEN : INK);
                second.setForeground(MUTED);
                cell.setBackground(selected ? list.getSelectionBackground() : index % 2 == 0 ? LCD : new java.awt.Color(23, 32, 33));
                return cell;
            }
        });
    }

    private static final class Rows<T> {
        final DefaultListModel<T> model = new DefaultListModel<>();
        final JList<T> list = new JList<>(model);
        final JScrollPane scroll = new JScrollPane(list);
        java.util.function.BooleanSupplier canReorder = () -> false;

        Rows() {
            list.setFixedCellHeight(48);
            // The viewport supplies the width. A fixed cell size keeps Swing from measuring every item.
            list.setFixedCellWidth(1);
            list.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
            scroll.getVerticalScrollBar().setUnitIncrement(48);
        }

        String key(T item) {
            if (item instanceof Track track) return track.entryId.isBlank() ? track.id : track.entryId;
            if (item instanceof DesktopPlayer.Entry entry) return entry.key();
            return ((JSONObject)item).optString("id");
        }

        List<T> values() { return java.util.Collections.list(model.elements()); }

        Anchor anchor() {
            int row = list.getFirstVisibleIndex();
            return row < 0 || row >= model.size() ? null : new Anchor(key(model.get(row)), row,
                scroll.getViewport().getViewPosition().y - row * list.getFixedCellHeight());
        }

        void set(List<T> values, Anchor anchor) {
            List<String> selected = list.getSelectedValuesList().stream().map(this::key).toList();
            model.clear();
            model.addAll(values);
            if (!selected.isEmpty()) {
                int[] indexes = java.util.stream.IntStream.range(0, model.size()).filter(i -> selected.contains(key(model.get(i)))).toArray();
                list.setSelectedIndices(indexes);
            }
            int row = 0;
            if (anchor != null && !values.isEmpty()) {
                row = Math.min(anchor.row, values.size() - 1);
                for (int i = 0; i < values.size(); i++)
                    if (key(values.get(i)).equals(anchor.id)) { row = i; break; }
            }
            int y = row * list.getFixedCellHeight() + (anchor == null ? 0 : anchor.offset);
            scroll.getViewport().setViewPosition(new java.awt.Point(0, Math.max(0, y)));
        }

        void activate(Consumer<T> action, java.util.function.BooleanSupplier selecting) {
            list.addMouseListener(new MouseAdapter() {
                @Override public void mouseClicked(MouseEvent event) {
                    if (!SwingUtilities.isLeftMouseButton(event) || event.isControlDown() || event.isShiftDown() || event.isAltDown() || selecting.getAsBoolean() || dragHandle(event)) return;
                    int index = list.locationToIndex(event.getPoint());
                    if (index >= 0 && list.getCellBounds(index, index).contains(event.getPoint())) action.accept(model.get(index));
                }
            });
            list.getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), "activate");
            list.getActionMap().put("activate", new AbstractAction() {
                @Override public void actionPerformed(ActionEvent event) { T value = list.getSelectedValue(); if (value != null) action.accept(value); }
            });
        }

        boolean dragHandle(MouseEvent event) {
            return canReorder.getAsBoolean() && event.getX() >= list.getWidth() - 32;
        }

        void reorder(java.util.function.BooleanSupplier enabled, java.util.function.BiConsumer<Integer, Integer> move) {
            canReorder = enabled;
            list.addMouseListener(new MouseAdapter() {
                int from = -1;
                @Override public void mousePressed(MouseEvent event) {
                    if (SwingUtilities.isLeftMouseButton(event) && dragHandle(event)) from = list.locationToIndex(event.getPoint());
                }
                @Override public void mouseReleased(MouseEvent event) {
                    int start = from;
                    from = -1;
                    if (start < 0 || start >= model.size()) return;
                    int target = list.locationToIndex(event.getPoint());
                    if (target >= 0 && target != start) move.accept(start, target);
                }
            });
        }

        void menu(Consumer<MouseEvent> menu) {
            list.getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_F10, InputEvent.SHIFT_DOWN_MASK), "menu");
            list.getActionMap().put("menu", new AbstractAction() {
                @Override public void actionPerformed(ActionEvent event) { menu.accept(null); }
            });
            list.addMouseListener(new MouseAdapter() {
                private void popup(MouseEvent event) {
                    if (!event.isPopupTrigger()) return;
                    int index = list.locationToIndex(event.getPoint());
                    if (index >= 0 && !list.isSelectedIndex(index)) list.setSelectedIndex(index);
                    menu.accept(event);
                }
                @Override public void mousePressed(MouseEvent event) { popup(event); }
                @Override public void mouseReleased(MouseEvent event) { popup(event); }
            });
        }
    }

    private static FileChannel instance;

    public static void main(String[] args) {
        if (GraphicsEnvironment.isHeadless()) {
            System.err.println("Rewindamp needs a graphical Linux desktop.");
            return;
        }
        String xdg = System.getenv("XDG_DATA_HOME");
        Path root = xdg == null || xdg.isBlank() || !Path.of(xdg).isAbsolute()
            ? Path.of(System.getProperty("user.home"), ".local", "share") : Path.of(xdg);
        Path data = root.resolve("rewindamp");
        SwingUtilities.invokeLater(() -> {
            try {
                Files.createDirectories(data, java.nio.file.attribute.PosixFilePermissions.asFileAttribute(
                    java.nio.file.attribute.PosixFilePermissions.fromString("rwx------")));
                instance = FileChannel.open(data.resolve(".lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                if (instance.tryLock() == null) {
                    JOptionPane.showMessageDialog(null, "Rewindamp is already running.");
                    System.exit(0);
                }
                DesktopTheme.apply();
                new DesktopApp(data);
            } catch (Exception e) {
                JOptionPane.showMessageDialog(null, "Could not open Rewindamp's private storage or media relay.",
                    "Rewindamp", JOptionPane.ERROR_MESSAGE);
                System.exit(1);
            }
        });
    }
}

package dev.rewindamp;

import static dev.rewindamp.DesktopTheme.*;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.GridLayout;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.function.Consumer;
import javax.swing.*;
import org.json.JSONArray;
import org.json.JSONObject;

final class DesktopMixarr implements AutoCloseable {
    private final JFrame parent;
    private final ExecutorService io;
    private final Consumer<String> appStatus;
    private final Timer timer = new Timer(5000, event -> poll());
    private JDialog window;
    private JLabel status;
    private JTextField search;
    private final JLabel activitySummary = lcd("", 12);
    private JButton connection, submit;
    private JTabbedPane tabs;
    private JPanel activityRows, searchRows;
    private DesktopStore store;
    private String scope;
    private MixarrApi api;
    private boolean busy, polling, restoring;
    private long generation;

    DesktopMixarr(JFrame parent, ExecutorService io, Consumer<String> appStatus) {
        this.parent = parent;
        this.io = io;
        this.appStatus = appStatus;
    }

    JLabel summary() { return activitySummary; }

    void account(JellyfinApi jellyfin, DesktopStore store) {
        disconnectAccount();
        this.store = store;
        try { scope = MixarrApi.scope(jellyfin); }
        catch (Exception e) { throw new IllegalStateException(e); }
        String key = scope;
        restoring = true;
        io.submit(() -> {
            JSONObject saved = DesktopSecrets.load(key);
            SwingUtilities.invokeLater(() -> {
                if (!key.equals(scope)) return;
                restoring = false;
                if (!saved.has("cookie")) return;
                try {
                    api = new MixarrApi(saved.getString("server"), saved.getString("cookie"), saved.getString("csrf"), saved.getString("user"));
                    refreshActivity();
                    poll();
                } catch (IllegalArgumentException | org.json.JSONException ignored) {}
            });
        });
    }

    void disconnectAccount() {
        generation++;
        scope = null;
        store = null;
        api = null;
        busy = false;
        polling = false;
        restoring = false;
        activitySummary.setText("");
        close();
    }

    void open(String initial) {
        if (scope == null) return;
        if (window == null) createWindow();
        window.setVisible(true);
        window.toFront();
        boolean initialSearch = !initial.isBlank() && search.getText().isBlank();
        if (initialSearch) search.setText(initial);
        if (api == null && !restoring) connect();
        else if (initialSearch && api != null && !YouTubeSelection.isLink(initial)) runSearch();
        else poll();
    }

    private void createWindow() {
        window = new JDialog(parent, "Rewindamp · Mixarr", false);
        window.setDefaultCloseOperation(WindowConstants.HIDE_ON_CLOSE);
        window.setSize(770, 580);
        window.setLocationRelativeTo(parent);
        window.addWindowListener(new java.awt.event.WindowAdapter() {
            @Override public void windowActivated(java.awt.event.WindowEvent event) { visibility(); poll(); }
            @Override public void windowDeactivated(java.awt.event.WindowEvent event) { visibility(); }
        });
        search = new JTextField(32);
        search.getAccessibleContext().setAccessibleName("Search Mixarr or enter a YouTube URL");
        connection = button(api == null ? "Connect Mixarr" : "Disconnect Mixarr", this::connect);
        submit = button("Search", this::runSearch);
        search.addActionListener(event -> runSearch());
        DesktopApp.listen(search, () -> submit.setText(!YouTubeSelection.isLink(search.getText().strip()) ? "Search"
            : YouTubeSelection.validLink(search.getText().strip()) && YouTubeSelection.isPlaylist(search.getText().strip())
                ? "Download entire playlist" : "Preview YouTube import"));
        status = lcd("Search recordings or paste a YouTube URL", 12);
        tabs = new JTabbedPane();
        searchRows = column();
        activityRows = column();
        tabs.addTab("Search results", scroll(searchRows));
        tabs.addTab("Library activity", scroll(activityRows));
        JPanel content = new JPanel(new BorderLayout(5, 5));
        content.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
        content.add(row(connection, search, submit), BorderLayout.NORTH);
        content.add(tabs, BorderLayout.CENTER);
        content.add(status, BorderLayout.SOUTH);
        window.setContentPane(content);
        refreshActivity();
    }

    private static JScrollPane scroll(JPanel body) {
        JPanel layout = new JPanel(new BorderLayout());
        layout.add(body, BorderLayout.NORTH);
        JScrollPane scroll = new JScrollPane(layout);
        scroll.getVerticalScrollBar().setUnitIncrement(22);
        return scroll;
    }

    private void connect() {
        if (busy || restoring || scope == null) return;
        if (api != null) {
            if (JOptionPane.showConfirmDialog(window, "Disconnect this Mixarr account?", "Mixarr", JOptionPane.OK_CANCEL_OPTION)
                != JOptionPane.OK_OPTION) return;
            MixarrApi old = api;
            String key = scope;
            api = null;
            generation++;
            polling = false;
            timer.stop();
            connection.setText("Connect Mixarr");
            searchRows.removeAll();
            refreshActivity();
            work(() -> {
                boolean cleared = DesktopSecrets.clear(key);
                try { old.request("/api/v1/logout", new JSONObject()); } catch (Exception ignored) {}
                return cleared;
            }, cleared -> say(cleared ? "Mixarr disconnected" : "Could not clear the desktop keyring entry."));
            return;
        }
        DesktopApp.Login login = DesktopApp.login(parent, "Connect Mixarr", store.read("mixarr-address").optString("value",
            "https://mixarr.snappyapps.com.au"), true);
        if (login == null) return;
        String key = scope;
        DesktopStore account = store;
        work(() -> {
            MixarrApi connected = MixarrApi.login(login.address(), login.user(), login.password());
            boolean saved = DesktopSecrets.save(key, new JSONObject().put("server", connected.server)
                .put("cookie", connected.cookie()).put("csrf", connected.csrf()).put("user", connected.user));
            account.write("mixarr-address", new JSONObject().put("value", connected.server));
            return new Connected(connected, saved);
        }, result -> {
            api = result.api;
            connection.setText("Disconnect Mixarr");
            say(result.saved ? "Connected to Mixarr" : "Desktop keyring unavailable · connected for this session");
            refreshActivity();
            if (!search.getText().isBlank() && !YouTubeSelection.isLink(search.getText().strip())) runSearch();
            else poll();
        });
    }

    private record Connected(MixarrApi api, boolean saved) {}

    private void runSearch() {
        if (busy) return;
        if (api == null) { connect(); return; }
        String term = search.getText().strip();
        if (term.isBlank()) return;
        tabs.setSelectedIndex(0);
        MixarrApi client = api;
        DesktopStore account = store;
        if (!YouTubeSelection.isLink(term)) {
            say("Searching Mixarr…");
            work(() -> (JSONArray)client.request("/api/v1/track-requests/search", new JSONObject().put("query", term)),
                this::recordings);
            return;
        }
        if (!YouTubeSelection.validLink(term)) { say("Enter a valid HTTPS YouTube video or playlist URL."); return; }
        boolean playlist = YouTubeSelection.isPlaylist(term);
        if (playlist && JOptionPane.showConfirmDialog(window, "Queue every available unique video from this playlist?",
            "Download entire playlist", JOptionPane.OK_CANCEL_OPTION) != JOptionPane.OK_OPTION) return;
        say(playlist ? "Queuing playlist…" : "Loading YouTube preview…");
        work(() -> {
            JSONObject result = (JSONObject)client.request(playlist ? "/api/v1/youtube-imports/playlist" : "/api/v1/youtube-imports/preview",
                new JSONObject().put("url", term));
            if (playlist) remember(account, client, result.put("import_id", result.getString("id")));
            return result;
        }, result -> {
            if (playlist) { say("Playlist import queued"); refreshActivity(); poll(); }
            else editor(result);
        });
    }

    private void recordings(JSONArray recordings) {
        searchRows.removeAll();
        for (JSONObject recording : DesktopApp.objects(recordings)) {
            String title = recording.optString("title"), artist = recording.optString("artist");
            String album = recording.isNull("album") ? "Album unknown" : recording.optString("album");
            JLabel info = lcd(title + " — " + artist, 14);
            long duration = recording.optLong("duration_ms");
            JLabel detail = new JLabel(album + (duration > 0 ? " · " + Track.time(duration) : "") +
                (recording.optString("version").isBlank() ? "" : " · " + recording.optString("version")));
            detail.putClientProperty("html.disable", true);
            searchRows.add(row(column(info, detail), button("Add", () -> add(recording))));
        }
        searchRows.revalidate();
        searchRows.repaint();
        say(recordings.isEmpty() ? "No recordings found" : recordings.length() + " recordings");
    }

    private void add(JSONObject recording) {
        if (api == null || busy) return;
        String title = recording.optString("title"), artist = recording.optString("artist");
        MixarrApi client = api;
        DesktopStore account = store;
        work(() -> {
            JSONObject response = (JSONObject)client.request("/api/v1/track-requests", new JSONObject().put("token", recording.getString("token")));
            remember(account, client, response.put("title", title).put("artist", artist));
            return response;
        }, result -> {
            say(result.optString("state").equals("available") ? "Already in your server library" : "Request queued");
            refreshActivity();
            poll();
        });
    }

    private record Chapter(JCheckBox selected, JSONObject source, JTextField title, JTextField artist, JTextField album, JTextField start, JTextField end) {}

    private void editor(JSONObject preview) {
        searchRows.removeAll();
        List<Chapter> chapters = new ArrayList<>();
        JPanel entries = column();
        JTextField artist = new JTextField(15), album = new JTextField(preview.optString("title"), 15);
        searchRows.add(row(lcd(preview.optString("title"), 15)));
        searchRows.add(row(artist, button("Apply artist to all", () -> chapters.forEach(chapter -> chapter.artist.setText(artist.getText()))),
            album, button("Apply album to all", () -> chapters.forEach(chapter -> chapter.album.setText(album.getText())))));
        searchRows.add(entries);
        boolean playlist = "playlist".equals(preview.optString("mode"));
        DesktopApp.objects(preview.optJSONArray("items")).forEach(source -> chapter(entries, chapters, source, playlist));
        if (!playlist) searchRows.add(row(button("Add another chapter", () -> {
            if (chapters.isEmpty() || chapters.size() >= 100) return;
            Chapter previous = chapters.get(chapters.size() - 1);
            JSONObject source = new JSONObject(chapters.get(0).source.toString()).put("title", "")
                .put("start", previous.end.getText()).put("end", previous.source.opt("duration"));
            chapter(entries, chapters, source, false);
            entries.revalidate(); entries.repaint();
        })));
        searchRows.add(row(button("Import selected audio", () -> {
            if (busy || api == null) return;
            try {
                JSONObject body = YouTubeSelection.body(preview, chapters.stream().filter(ch -> ch.selected.isSelected())
                    .map(ch -> new YouTubeSelection.Chapter(ch.source, ch.title.getText(), ch.artist.getText(), ch.album.getText(),
                        ch.start.getText(), ch.end.getText())).toList());
                MixarrApi client = api;
                DesktopStore account = store;
                work(() -> {
                    JSONObject batch = (JSONObject)client.request("/api/v1/youtube-imports", body);
                    remember(account, client, batch.put("import_id", batch.getString("id")));
                    return batch;
                }, ignored -> {
                    searchRows.removeAll();
                    searchRows.revalidate();
                    searchRows.repaint();
                    say("Import queued · open Library activity for status");
                    refreshActivity();
                    poll();
                });
            } catch (NumberFormatException e) { say("Enter numeric chapter times in seconds."); }
            catch (IllegalArgumentException | org.json.JSONException e) { say(e.getMessage()); }
        })));
        searchRows.revalidate(); searchRows.repaint();
        say("Review names and chapter times, then confirm the import.");
    }

    private static void chapter(JPanel container, List<Chapter> chapters, JSONObject source, boolean playlist) {
        JCheckBox selected = new JCheckBox("Import track " + (chapters.size() + 1), true);
        JTextField title = new JTextField(source.optString("title"), 15);
        JTextField artist = new JTextField(source.optString("artist"), 15);
        JTextField album = new JTextField(source.isNull("album") ? "" : source.optString("album"), 15);
        JTextField start = new JTextField(source.optString("start", "0"), 8);
        JTextField end = new JTextField(source.isNull("end") ? "" : source.optString("end"), 8);
        JPanel fields = new JPanel(new GridLayout(0, 2, 7, 3));
        addField(fields, "Title", title); addField(fields, "Artist", artist); addField(fields, "Album", album);
        if (!playlist) { addField(fields, "Starts at (seconds)", start); addField(fields, "Ends at (seconds)", end); }
        JPanel panel = column(selected, fields);
        panel.setBorder(BorderFactory.createEmptyBorder(5, 9, 10, 9));
        container.add(panel);
        chapters.add(new Chapter(selected, source, title, artist, album, start, end));
    }

    private static void addField(JPanel fields, String text, JTextField input) {
        JLabel label = new JLabel(text);
        label.setLabelFor(input);
        fields.add(label); fields.add(input);
    }

    private void poll() {
        MixarrApi client = api;
        DesktopStore account = store;
        if (polling || client == null || account == null) return;
        if (!visible()) return;
        List<JSONObject> pending = DesktopApp.objects(history(account, client)).stream().filter(DesktopMixarr::pending).toList();
        if (pending.isEmpty()) return;
        polling = true;
        long current = generation;
        io.submit(() -> {
            for (JSONObject row : pending) {
                try {
                    if (row.has("import_id")) {
                        String id = row.getString("import_id");
                        JSONObject result = (JSONObject)client.request("/api/v1/youtube-imports/" + UrlEncoding.encode(id), null);
                        remember(account, client, result.put("import_id", id));
                    } else if (!row.isNull("job_id")) {
                        JSONObject result = (JSONObject)client.request("/api/v1/track-requests/jobs/" + row.getLong("job_id"), null);
                        remember(account, client, result.put("title", row.optString("title")).put("artist", row.optString("artist")));
                    }
                } catch (Exception ignored) {}
            }
            SwingUtilities.invokeLater(() -> {
                if (current != generation) return;
                polling = false;
                refreshActivity();
            });
        });
    }

    private static boolean pending(JSONObject row) {
        if (row.has("import_id")) return DesktopApp.objects(row.optJSONArray("items")).stream().anyMatch(DesktopMixarr::pending);
        return List.of("queued", "processing", "paused", "pending", "downloading").contains(row.optString("state"));
    }

    private static void remember(DesktopStore account, MixarrApi api, JSONObject row) throws Exception {
        synchronized (account) {
            account.writeChecked(api.historyKey(), new JSONObject().put("items", MixarrApi.record(history(account, api), row)));
        }
    }

    private static JSONArray history(DesktopStore account, MixarrApi api) {
        JSONArray rows = account.read(api.historyKey()).optJSONArray("items");
        return rows == null ? new JSONArray() : rows;
    }

    private boolean visible() {
        return parent.isActive() && parent.isShowing() && (parent.getExtendedState() & JFrame.ICONIFIED) == 0
            || window != null && window.isActive();
    }

    void visibility() {
        if (visible() && api != null && summaryHasPending) {
            if (!timer.isRunning()) timer.start();
        } else timer.stop();
    }

    private boolean summaryHasPending;

    private void refreshActivity() {
        List<JSONObject> rows = api == null ? List.of() : DesktopApp.objects(history(store, api));
        long pending = rows.stream().filter(DesktopMixarr::pending).count();
        summaryHasPending = pending > 0;
        visibility();
        activitySummary.setText(api == null ? "" : pending == 0 ? "Mixarr connected" : pending + " Mixarr requests active");
        if (window == null) return;
        if (connection != null) connection.setText(api == null ? "Connect Mixarr" : "Disconnect Mixarr");
        activityRows.removeAll();
        if (rows.isEmpty()) activityRows.add(row(new JLabel("No recent requests")));
        for (JSONObject row : rows) {
            if (row.has("import_id")) {
                activityRows.add(row(lcd("YouTube import · " + row.optString("title"), 14)));
                for (JSONObject track : DesktopApp.objects(row.optJSONArray("items")))
                    activityRows.add(row(lcd(track.optString("title") + " — " + track.optString("artist") + " · " + track.optString("state"), 12)));
                continue;
            }
            String state = row.optString("state");
            JSONObject progress = row.optJSONObject("progress");
            String details = state.replace('_', ' ');
            if (progress != null) {
                details += " · " + progress.optString("label", "");
                double percent = progress.optDouble("percent");
                if (Double.isFinite(percent) && percent >= 0 && percent <= 100)
                    details += String.format(java.util.Locale.ROOT, " · %.1f%%", percent);
                if (progress.has("downloaded_bytes")) details += " · " + dataSize(progress.optLong("downloaded_bytes"));
                if (progress.has("speed")) details += " · " + dataSize(progress.optLong("speed")) + "/s";
                if (progress.has("eta")) details += " · ETA " + progress.optLong("eta") + " s";
            }
            JPanel card = row(column(lcd(row.optString("title") + " — " + row.optString("artist"), 14), lcd(details, 12)));
            if (List.of("failed", "cancelled").contains(state)) card.add(button("Search again to retry", () -> {
                search.setText(row.optString("title") + " " + row.optString("artist")); runSearch();
            }));
            activityRows.add(card);
        }
        activityRows.revalidate();
        activityRows.repaint();
    }

    private static String dataSize(long bytes) {
        return bytes >= 1_048_576 ? String.format(java.util.Locale.ROOT, "%.1f MiB", bytes / 1_048_576.0)
            : String.format(java.util.Locale.ROOT, "%.0f KiB", bytes / 1024.0);
    }

    private <T> void work(Callable<T> task, Consumer<T> success) {
        long current = generation;
        busy = true;
        submit.setEnabled(false);
        io.submit(() -> {
            try {
                T result = task.call();
                SwingUtilities.invokeLater(() -> {
                    if (current != generation) return;
                    busy = false;
                    submit.setEnabled(true);
                    success.accept(result);
                });
            } catch (Exception e) {
                SwingUtilities.invokeLater(() -> {
                    if (current != generation) return;
                    busy = false;
                    submit.setEnabled(true);
                    say(MixarrApi.message(e));
                });
            }
        });
    }

    private void say(String message) {
        if (status != null) status.setText(message); else appStatus.accept(message);
    }

    @Override public void close() {
        timer.stop();
        if (window != null) { window.dispose(); window = null; }
    }
}

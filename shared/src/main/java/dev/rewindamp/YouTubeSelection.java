package dev.rewindamp;

import java.net.URI;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;

final class YouTubeSelection {
    record Chapter(JSONObject source, String title, String artist, String album, String start, String end) {}

    static boolean isLink(String value) {
        String lower = value.toLowerCase(Locale.ROOT);
        return lower.startsWith("http:") || lower.startsWith("https:");
    }

    static boolean isPlaylist(String value) {
        return "/playlist".equals(URI.create(value).getPath());
    }

    static boolean validLink(String value) {
        try {
            URI link = URI.create(value);
            String host = link.getHost();
            return "https".equalsIgnoreCase(link.getScheme()) && host != null && Set.of(
                "youtube.com", "www.youtube.com", "music.youtube.com", "m.youtube.com", "youtu.be", "www.youtu.be")
                .contains(host.toLowerCase(Locale.ROOT)) && link.getUserInfo() == null && link.getPort() == -1;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    static JSONObject body(JSONObject preview, List<Chapter> chapters) throws org.json.JSONException {
        if (chapters.isEmpty() || chapters.size() > 100) throw new IllegalArgumentException("Select between 1 and 100 tracks.");
        JSONArray selected = new JSONArray();
        List<double[]> ranges = new ArrayList<>();
        boolean playlist = "playlist".equals(preview.optString("mode"));
        for (Chapter chapter : chapters) {
            String title = chapter.title.trim(), artist = chapter.artist.trim();
            if (title.isEmpty() || artist.isEmpty()) throw new IllegalArgumentException("Enter a title and artist for each selected track.");
            JSONObject item = new JSONObject().put("source_id", chapter.source.getString("source_id"))
                .put("title", title).put("artist", artist).put("album", chapter.album.trim());
            if (playlist) item.put("start", chapter.source.get("start")).put("end", chapter.source.opt("end"));
            else {
                double start = Double.parseDouble(chapter.start), end = Double.parseDouble(chapter.end);
                if (!Double.isFinite(start) || !Double.isFinite(end) || start < 0 || end <= start || end > chapter.source.getDouble("duration"))
                    throw new IllegalArgumentException("Chapter times must be inside the video.");
                ranges.add(new double[] {start, end});
                item.put("start", start).put("end", end);
            }
            selected.put(item);
        }
        ranges.sort(Comparator.comparingDouble(value -> value[0]));
        for (int i = 1; i < ranges.size(); i++)
            if (ranges.get(i - 1)[1] > ranges.get(i)[0]) throw new IllegalArgumentException("Chapter times overlap.");
        return new JSONObject().put("token", preview.getString("token")).put("items", selected);
    }
}

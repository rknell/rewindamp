package dev.rewindamp;

import java.util.Calendar;
import org.json.JSONObject;

final class PodcastPass {
    static final String[] LABELS = {"Future episodes only", "Last 12 months", "Last two years",
        "This year (from January 1)", "All available episodes", "Manual downloads only"};

    static JSONObject body(String title, String url, int choice, boolean newFeed) throws org.json.JSONException {
        String mode = switch (choice) {
            case 1, 2 -> "years";
            case 3 -> "since";
            case 4 -> "all";
            case 5 -> "manual";
            default -> "future";
        };
        JSONObject body = new JSONObject().put("title", title).put("url", url)
            .put("mode", mode).put("years", choice == 2 ? 2 : 1);
        if (newFeed) body.put("only_if_new", true);
        if (choice == 3) {
            Calendar start = Calendar.getInstance();
            start.set(Calendar.DAY_OF_YEAR, 1);
            start.set(Calendar.HOUR_OF_DAY, 0);
            start.set(Calendar.MINUTE, 0);
            start.set(Calendar.SECOND, 0);
            start.set(Calendar.MILLISECOND, 0);
            body.put("since", start.getTimeInMillis() / 1000);
        }
        return body;
    }
}

package dev.rewindamp;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.json.JSONObject;

/** Secret Service credentials are never stored in the cache or passed as process arguments. */
final class DesktopSecrets {
    static JSONObject load(String key) {
        try {
            return new JSONObject(call("lookup", key, null));
        } catch (Exception e) {
            return new JSONObject();
        }
    }

    static boolean save(String key, JSONObject value) {
        try {
            call("store", key, value.toString());
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    static boolean clear(String key) {
        try {
            call("clear", key, null);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private static String call(String command, String key, String secret) throws Exception {
        List<String> arguments = new ArrayList<>(List.of("secret-tool", command));
        if (secret != null) arguments.add("--label=Rewindamp " + key);
        arguments.addAll(List.of("application", "dev.rewindamp", "connection", key));
        Process process = new ProcessBuilder(arguments).redirectError(ProcessBuilder.Redirect.DISCARD).start();
        try {
            try (var input = process.getOutputStream()) {
                if (secret != null) input.write(secret.getBytes(StandardCharsets.UTF_8));
            }
            if (!process.waitFor(12, TimeUnit.SECONDS) || process.exitValue() != 0)
                throw new IOException("Desktop keyring is unavailable");
            return new String(process.getInputStream().readNBytes(32768), StandardCharsets.UTF_8);
        } finally {
            process.destroyForcibly();
        }
    }
}

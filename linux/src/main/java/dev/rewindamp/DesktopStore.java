package dev.rewindamp;

import org.json.JSONObject;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;

final class DesktopStore extends LibraryData {
    private static final java.nio.file.attribute.FileAttribute<?> PRIVATE_DIRECTORY = PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------"));
    private static final java.nio.file.attribute.FileAttribute<?> PRIVATE_FILE = PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------"));

    DesktopStore(Path parent, String server, String account) {
        super(parent.toFile(), server, account);
        try {
            Files.createDirectories(directory.toPath(), PRIVATE_DIRECTORY);
        } catch (IOException e) {
            throw new IllegalStateException("Could not open private Rewindamp storage", e);
        }
    }

    @Override synchronized JSONObject read(String key) {
        try {
            return new JSONObject(Files.readString(file(key)));
        } catch (IOException | org.json.JSONException e) {
            return new JSONObject();
        }
    }

    @Override synchronized void writeChecked(String key, JSONObject data) {
        Path temporary = null;
        try {
            temporary = Files.createTempFile(directory.toPath(), ".write-", ".tmp", PRIVATE_FILE);
            Files.writeString(temporary, data.toString());
            Files.move(temporary, file(key), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new IllegalStateException("Could not save private Rewindamp data", e);
        } finally {
            if (temporary != null) try { Files.deleteIfExists(temporary); } catch (IOException ignored) {}
        }
    }

    private Path file(String key) {
        return directory.toPath().resolve(hash(key) + ".json");
    }

}

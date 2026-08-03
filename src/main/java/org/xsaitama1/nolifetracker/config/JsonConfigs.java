package org.xsaitama1.nolifetracker.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.Reader;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.function.Supplier;

/**
 * Shared JSON loading and saving for every NoLifeTracker config and data file.
 *
 * <p>Reads are UTF-8, null-safe and total: a missing, empty or malformed file never
 * throws at the caller and never yields null. Anything unreadable is moved aside with
 * a timestamped suffix so the admin can recover it by hand, and defaults are used for
 * the rest of the run. This matters because these loads happen inside the server-start
 * callback -- an escaping exception there used to abort the remaining initialisation.
 *
 * <p>Writes are atomic: content goes to a sibling temp file which is then moved over
 * the target, so a crash or a concurrent reader can never observe a truncated file.
 */
public final class JsonConfigs {

    private static final Logger LOGGER = LoggerFactory.getLogger("nolifetracker");
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    public static final Gson GSON = new GsonBuilder()
            .setPrettyPrinting()
            .disableHtmlEscaping()
            .create();

    private JsonConfigs() {
    }

    /**
     * Reads {@code path} as {@code type}, falling back to {@code defaults} for anything
     * that is missing, empty or unparseable.
     */
    public static <T> T load(Path path, Type type, Supplier<T> defaults, String label) {
        if (!Files.exists(path)) {
            return defaults.get();
        }

        try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            T parsed = GSON.fromJson(reader, type);
            if (parsed == null) {
                // Gson yields null for an empty file rather than throwing.
                LOGGER.warn("{} ({}) was empty - regenerating from defaults.", label, path.getFileName());
                quarantine(path, label);
                return defaults.get();
            }
            return parsed;
        } catch (IOException | JsonParseException e) {
            LOGGER.error("Could not read {} ({}): {} - falling back to defaults.",
                    label, path.getFileName(), e.toString());
            quarantine(path, label);
            return defaults.get();
        }
    }

    /** Serialises and writes atomically, logging rather than throwing on failure. */
    public static void save(Path path, Object value, String label) {
        try {
            writeAtomically(path, GSON.toJson(value));
        } catch (IOException e) {
            LOGGER.error("Could not write {} to {}", label, path, e);
        }
    }

    /**
     * Writes {@code content} to {@code path} via a temp file and an atomic move.
     * Safe to call from a background thread as long as the content string was built
     * on the thread that owns the underlying data.
     */
    public static void writeAtomically(Path path, String content) throws IOException {
        Path parent = path.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }

        Path tmp = path.resolveSibling(path.getFileName() + ".tmp");
        Files.writeString(tmp, content, StandardCharsets.UTF_8);
        try {
            Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            // Some network filesystems cannot move atomically; a plain replace is the best available.
            Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /**
     * Moves a file that already exists at {@code from} to {@code to}, used to migrate
     * older NoLifeTracker layouts. Returns true when a migration actually happened.
     */
    public static boolean migrateIfPresent(Path from, Path to, String label) {
        if (!Files.exists(from) || Files.exists(to)) {
            return false;
        }
        try {
            Path parent = to.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.move(from, to, StandardCopyOption.REPLACE_EXISTING);
            LOGGER.info("Migrated {} from {} to {}", label, from, to);
            return true;
        } catch (IOException e) {
            LOGGER.error("Could not migrate {} from {} to {}", label, from, to, e);
            return false;
        }
    }

    private static void quarantine(Path path, String label) {
        Path backup = path.resolveSibling(
                path.getFileName() + ".corrupt-" + LocalDateTime.now().format(STAMP));
        try {
            Files.move(path, backup, StandardCopyOption.REPLACE_EXISTING);
            LOGGER.warn("Moved unreadable {} aside to {} so it can be recovered by hand.",
                    label, backup.getFileName());
        } catch (IOException e) {
            LOGGER.error("Could not move unreadable {} aside", label, e);
        }
    }
}

package org.xsaitama1.nolifetracker.data;

import com.google.gson.reflect.TypeToken;
import net.minecraft.server.MinecraftServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.xsaitama1.nolifetracker.config.JsonConfigs;
import org.xsaitama1.nolifetracker.config.NoLifeTrackerPaths;

import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Player progress, stored inside the world save.
 *
 * <p>Saving is split deliberately: the map is serialised on the server thread, where it is
 * the only thing touching it, and only the finished string is handed to a single background
 * writer. Previously the whole serialisation ran on a pool thread while the server thread
 * kept mutating the same {@link HashMap}, which risks a {@link java.util.ConcurrentModificationException}
 * mid-write -- and because the old code wrote straight to the destination file, a throw at
 * that point left a truncated file that then failed to load at all.
 */
public final class StatsRepository {

    private static final Logger LOGGER = LoggerFactory.getLogger("nolifetracker");
    private static final Type ON_DISK = new TypeToken<HashMap<UUID, PlayerStats>>() {
    }.getType();

    private final Path path;
    private final Map<UUID, PlayerStats> players = new HashMap<>();
    private final ExecutorService writer = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "NoLifeTracker-IO");
        thread.setDaemon(true);
        return thread;
    });

    public StatsRepository(MinecraftServer server) {
        this.path = NoLifeTrackerPaths.playerStats(server);
    }

    public void load() {
        Map<UUID, PlayerStats> loaded = JsonConfigs.load(path, ON_DISK, HashMap::new, "player stats");

        players.clear();
        int migratedKeys = 0;

        for (Map.Entry<UUID, PlayerStats> entry : loaded.entrySet()) {
            if (entry.getKey() == null || entry.getValue() == null) {
                continue;
            }
            PlayerStats stats = entry.getValue().sanitised();
            migratedKeys += normaliseMobKeys(stats);
            players.put(entry.getKey(), stats);
        }

        if (migratedKeys > 0) {
            LOGGER.info("Upgraded {} mob key(s) to fully-qualified ids across {} player(s).",
                    migratedKeys, players.size());
        }
        LOGGER.info("Loaded stats for {} player(s) from {}", players.size(), path);
    }

    public PlayerStats get(UUID playerUuid) {
        return players.computeIfAbsent(playerUuid, key -> new PlayerStats());
    }

    /** Live view; only touch it from the server thread. */
    public Map<UUID, PlayerStats> all() {
        return players;
    }

    public PlayerStats findByName(String playerName) {
        for (PlayerStats stats : players.values()) {
            if (stats.lastKnownName.equalsIgnoreCase(playerName)) {
                return stats;
            }
        }
        return null;
    }

    public UUID findUuidByName(String playerName) {
        for (Map.Entry<UUID, PlayerStats> entry : players.entrySet()) {
            if (entry.getValue().lastKnownName.equalsIgnoreCase(playerName)) {
                return entry.getKey();
            }
        }
        return null;
    }

    /** Call from the server thread. Serialises here, writes on the IO thread. */
    public void saveAsync() {
        String json = JsonConfigs.GSON.toJson(players);
        writer.execute(() -> writeNow(json));
    }

    /** Call from the server thread during shutdown, when the write must complete. */
    public void saveBlocking() {
        writeNow(JsonConfigs.GSON.toJson(players));
    }

    public void shutdown() {
        saveBlocking();
        writer.shutdown();
        try {
            if (!writer.awaitTermination(10, TimeUnit.SECONDS)) {
                writer.shutdownNow();
            }
        } catch (InterruptedException e) {
            writer.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    private void writeNow(String json) {
        try {
            JsonConfigs.writeAtomically(path, json);
        } catch (IOException e) {
            LOGGER.error("Could not save player stats to {}", path, e);
        }
    }

    /**
     * Kill counts used to be keyed by the bare id path, which collides as soon as two mods
     * ship a mob with the same name. Rewrite them to {@code namespace:path} once, on load.
     */
    private static int normaliseMobKeys(PlayerStats stats) {
        Map<String, Integer> upgraded = new LinkedHashMap<>();
        int changed = 0;

        for (Map.Entry<String, Integer> entry : stats.killedMobs.entrySet()) {
            String key = entry.getKey();
            if (key == null || key.isBlank()) {
                continue;
            }
            if (key.indexOf(':') < 0) {
                key = "minecraft:" + key;
                changed++;
            }
            upgraded.merge(key, entry.getValue() == null ? 0 : entry.getValue(), Integer::sum);
        }

        stats.killedMobs = upgraded;
        return changed;
    }
}

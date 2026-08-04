package org.xsaitama1.nolifetracker.config;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;

import java.nio.file.Path;

/**
 * Every path NoLifeTracker reads or writes, in one place.
 *
 * <p>Config lives under {@code config/nolifetracker/}. Player progress lives inside the
 * world save rather than the process working directory, so each world keeps its own
 * challenge and a world backup carries the stats with it. Older layouts are migrated
 * on first run.
 */
public final class NoLifeTrackerPaths {

    private NoLifeTrackerPaths() {
    }

    public static Path configDir() {
        return FabricLoader.getInstance().getConfigDir().resolve("nolifetracker");
    }

    public static Path config() {
        return configDir().resolve("config.json");
    }

    public static Path dimensionOverrides() {
        return configDir().resolve("dimension_overrides.json");
    }

    public static Path excludedMobs() {
        return configDir().resolve("excluded_mobs.json");
    }

    public static Path challengeMobs() {
        return configDir().resolve("challenge_mobs.json");
    }

    public static Path playerStats(MinecraftServer server) {
        return server.getWorldPath(LevelResource.ROOT).resolve("nolifetracker").resolve("player_stats.json");
    }

    /**
     * Two older layouts, oldest first.
     *
     * <p>Pre-1.0 config files sat loose in {@code config/} under different names. The mod was
     * then called ServerStats and kept them in {@code config/serverstats/}, so a server
     * upgrading across the rename would otherwise come up with default settings and an empty
     * exclusion list.
     *
     * <p>Both hops run in order, so a pre-1.0 install that never saw a ServerStats release
     * still lands in the right place: the loose files move into {@code config/serverstats/}
     * and are picked up by the second hop in the same pass.
     */
    public static void migrateLegacyConfigs() {
        Path legacyRoot = FabricLoader.getInstance().getConfigDir();
        Path serverStatsDir = legacyRoot.resolve("serverstats");

        JsonConfigs.migrateIfPresent(
                legacyRoot.resolve("ServerStatsConfig.json"),
                serverStatsDir.resolve("config.json"), "config");
        JsonConfigs.migrateIfPresent(
                legacyRoot.resolve("mob_dimensions_override.json"),
                serverStatsDir.resolve("dimension_overrides.json"), "dimension overrides");
        JsonConfigs.migrateIfPresent(
                legacyRoot.resolve("ExecludedMobs.json"),
                serverStatsDir.resolve("excluded_mobs.json"), "excluded mobs");

        JsonConfigs.migrateIfPresent(
                serverStatsDir.resolve("config.json"), config(), "config");
        JsonConfigs.migrateIfPresent(
                serverStatsDir.resolve("dimension_overrides.json"), dimensionOverrides(), "dimension overrides");
        JsonConfigs.migrateIfPresent(
                serverStatsDir.resolve("excluded_mobs.json"), excludedMobs(), "excluded mobs");

        // Rewritten from the registry on every start, so its contents do not matter -- moved
        // anyway rather than left behind, where a stale copy under the old name would look
        // authoritative to anyone reading the config folder.
        JsonConfigs.migrateIfPresent(
                serverStatsDir.resolve("challenge_mobs.json"), challengeMobs(), "challenge mob list");
    }

    /**
     * Player progress used to be written to {@code server_stats.json} relative to whatever
     * directory the server JVM was launched from, and later to {@code <world>/serverstats/}
     * under the mod's old name. Pull either into the current location if it is there.
     */
    public static void migrateLegacyStats(MinecraftServer server) {
        Path serverStatsStats = server.getWorldPath(LevelResource.ROOT)
                .resolve("serverstats").resolve("player_stats.json");

        JsonConfigs.migrateIfPresent(
                FabricLoader.getInstance().getGameDir().resolve("server_stats.json"),
                serverStatsStats,
                "player stats");
        JsonConfigs.migrateIfPresent(serverStatsStats, playerStats(server), "player stats");
    }
}

package org.xsaitama1.nolifetracker.challenge;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.entity.EntityType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.xsaitama1.nolifetracker.config.ConfigManager;
import org.xsaitama1.nolifetracker.config.ExcludedMobsConfig;
import org.xsaitama1.nolifetracker.config.JsonConfigs;
import org.xsaitama1.nolifetracker.config.NoLifeTrackerPaths;
import org.xsaitama1.nolifetracker.data.PlayerStats;

import java.nio.file.Files;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;

/**
 * The authoritative answer to "which mobs is this challenge about, and how far along is
 * each player".
 *
 * <p>The set is derived from the entity registry every start (see {@link MobClassifier}),
 * then written to {@code config/nolifetracker/challenge_mobs.json} so it is visible rather
 * than implied, and diffed against the previous run so a mob appearing or disappearing
 * after a game or mod update is reported instead of silently changing everyone's target.
 *
 * <p>Per-player progress is cached. It used to be recounted on every call -- including
 * twice per comparison inside a leaderboard sort, and once per player per tab list
 * packet -- which was the single biggest cost in the mod.
 */
public final class ChallengeRegistry {

    private static final Logger LOGGER = LoggerFactory.getLogger("nolifetracker");
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private static final Set<EntityType<?>> CHALLENGE_TYPES = new LinkedHashSet<>();
    private static final Set<String> CHALLENGE_IDS = new LinkedHashSet<>();
    private static final Map<String, Identifier> DIMENSION_BY_ID = new HashMap<>();

    /** Player uuid to unique challenge kills; invalidated on kill and on any config change. */
    private static final Map<UUID, Integer> UNIQUE_KILLS = new HashMap<>();

    private ChallengeRegistry() {
    }

    /** Rescans dimensions and rebuilds the challenge set. Safe to call again on {@code /nolifetracker reload}. */
    public static void rebuild(MinecraftServer server) {
        DimensionResolver.scan(server);
        refresh();
    }

    /**
     * Rebuilds the set from the registry and current config without rescanning biome spawn
     * data, which does not change while the server is running. Used after an admin edits
     * the exclusions or a dimension override.
     */
    public static void refresh() {
        CHALLENGE_TYPES.clear();
        CHALLENGE_IDS.clear();
        DIMENSION_BY_ID.clear();
        UNIQUE_KILLS.clear();

        Set<String> forceInclude = Set.copyOf(ConfigManager.get().forceIncludeMobs);

        for (EntityType<?> type : BuiltInRegistries.ENTITY_TYPE) {
            Identifier id = BuiltInRegistries.ENTITY_TYPE.getKey(type);
            if (id == null) {
                continue;
            }

            String key = id.toString();
            if (!MobClassifier.isMob(type, forceInclude) || ExcludedMobsConfig.contains(key)) {
                continue;
            }

            CHALLENGE_TYPES.add(type);
            CHALLENGE_IDS.add(key);

            Identifier dimension = DimensionResolver.resolve(type);
            if (dimension != null) {
                DIMENSION_BY_ID.put(key, dimension);
            }
        }

        writeManifest();
        reportUnclassified(forceInclude);
    }

    /**
     * Names MISC entities that were not classified as mobs. Logged at startup rather than
     * left to {@code /nolifetracker audit}, because the failure mode this guards against -- a game
     * update adding a mob nobody notices is missing -- is one nobody goes looking for.
     */
    private static void reportUnclassified(Set<String> forceInclude) {
        Set<String> candidates = MobClassifier.unclassifiedCandidates(forceInclude);
        if (!candidates.isEmpty()) {
            LOGGER.info("Not counted (non-spawning entities). Add any real mob here to forceIncludeMobs: {}",
                    String.join(", ", candidates));
        }
    }

    public static int total() {
        return CHALLENGE_IDS.size();
    }

    public static boolean isChallengeMob(EntityType<?> type) {
        return CHALLENGE_TYPES.contains(type);
    }

    public static Set<String> challengeIds() {
        return Set.copyOf(CHALLENGE_IDS);
    }

    /** Null means the mob has no known dimension and belongs in the "Other" group. */
    public static Identifier dimensionOf(String mobId) {
        return DIMENSION_BY_ID.get(mobId);
    }

    /**
     * How many distinct challenge mobs this player has killed. Only mobs that are actually
     * part of the challenge count, so the figure can never exceed {@link #total()} -- the
     * previous implementation counted every entry in the kill map and could report 75/72.
     */
    public static int uniqueKills(UUID playerUuid, PlayerStats stats) {
        Integer cached = UNIQUE_KILLS.get(playerUuid);
        if (cached != null) {
            return cached;
        }

        int count = 0;
        for (String killed : stats.killedMobs.keySet()) {
            if (CHALLENGE_IDS.contains(killed)) {
                count++;
            }
        }

        UNIQUE_KILLS.put(playerUuid, count);
        return count;
    }

    public static void invalidate(UUID playerUuid) {
        UNIQUE_KILLS.remove(playerUuid);
    }

    public static void invalidateAll() {
        UNIQUE_KILLS.clear();
    }

    /**
     * Writes the resolved set out and reports what changed since the previous start.
     * The file is generated, not read back as configuration: to drop a mob use
     * {@code /nolifetracker exclude}, and to move one between groups use {@code /nolifetracker editMob}.
     */
    private static void writeManifest() {
        Manifest previous = null;
        if (Files.exists(NoLifeTrackerPaths.challengeMobs())) {
            previous = JsonConfigs.load(
                    NoLifeTrackerPaths.challengeMobs(), Manifest.class, Manifest::new, "challenge mob list");
        }

        Manifest current = new Manifest();
        current.generated = LocalDateTime.now().format(STAMP);
        current.count = CHALLENGE_IDS.size();
        for (String id : CHALLENGE_IDS) {
            Identifier dimension = DIMENSION_BY_ID.get(id);
            current.mobs.put(id, dimension == null ? "other" : dimension.toString());
        }

        if (previous == null || previous.mobs == null || previous.mobs.isEmpty()) {
            LOGGER.info("Challenge set: {} mobs (first run - written to {})",
                    current.count, NoLifeTrackerPaths.challengeMobs().getFileName());
        } else {
            Set<String> added = new TreeSet<>(current.mobs.keySet());
            added.removeAll(previous.mobs.keySet());

            Set<String> removed = new TreeSet<>(previous.mobs.keySet());
            removed.removeAll(current.mobs.keySet());

            if (added.isEmpty() && removed.isEmpty()) {
                LOGGER.info("Challenge set: {} mobs (unchanged).", current.count);
            } else {
                LOGGER.info("Challenge set: {} mobs ({} added, {} removed since last start).",
                        current.count, added.size(), removed.size());
                if (!added.isEmpty()) {
                    LOGGER.info("  now counted: {}", String.join(", ", added));
                }
                if (!removed.isEmpty()) {
                    LOGGER.info("  no longer counted: {}", String.join(", ", removed));
                }
            }
        }

        JsonConfigs.save(NoLifeTrackerPaths.challengeMobs(), current, "challenge mob list");
    }

    /** On-disk shape of {@code challenge_mobs.json}. */
    public static class Manifest {
        public List<String> _readme = List.of(
                "Generated by NoLife Tracker on every server start - edits here are overwritten.",
                "This is a report of exactly which mobs the challenge is tracking.",
                "To stop counting a mob:      /nolifetracker exclude <mob> true",
                "To change a mob's group:     /nolifetracker editMob <mob> <dimension>",
                "To count a mob listed as unclassified by /nolifetracker audit: add it to",
                "forceIncludeMobs in config.json."
        );
        public String generated = "";
        public int count = 0;
        public Map<String, String> mobs = new TreeMap<>();
    }
}

package org.xsaitama1.nolifetracker.config;

import com.google.gson.JsonElement;
import com.google.gson.reflect.TypeToken;
import net.minecraft.entity.EntityType;
import net.minecraft.registry.Registries;
import net.minecraft.util.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/**
 * Manual "this mob belongs to that dimension" hints, used purely to group mobs in
 * {@code /nolifetracker missing}.
 *
 * <p>Dimension no longer decides whether a mob is part of the challenge -- that is
 * {@link org.xsaitama1.nolifetracker.challenge.MobClassifier}'s job now. A mob with no
 * usable dimension simply lands in the "Other" group instead of disappearing.
 */
public final class DimensionOverridesConfig {

    private static final Logger LOGGER = LoggerFactory.getLogger("nolifetracker");

    /** Read leniently: the pre-1.0 format stored a list of dimensions per mob. */
    private static final Type ON_DISK = new TypeToken<Map<String, JsonElement>>() {
    }.getType();

    private static Map<String, String> overrides = new TreeMap<>();
    private static final Map<EntityType<?>, Identifier> CACHE = new HashMap<>();
    private static final List<String> UNKNOWN_ENTITY_IDS = new ArrayList<>();

    private DimensionOverridesConfig() {
    }

    public static void load() {
        Map<String, JsonElement> raw = JsonConfigs.load(
                NoLifeTrackerPaths.dimensionOverrides(), ON_DISK, LinkedHashMap::new, "dimension overrides");

        overrides = new TreeMap<>();
        for (Map.Entry<String, JsonElement> entry : raw.entrySet()) {
            String dimension = readDimension(entry.getValue());
            if (dimension == null) {
                continue;
            }
            overrides.put(normaliseId(entry.getKey()), normaliseId(dimension));
        }

        defaults().forEach(overrides::putIfAbsent);

        rebuildCache();
        // Persist so the leniently-parsed old format is rewritten in the current one.
        save();
    }

    public static void save() {
        JsonConfigs.save(NoLifeTrackerPaths.dimensionOverrides(), overrides, "dimension overrides");
    }

    /** The configured dimension for a mob, or null if there is no override. */
    public static Identifier get(EntityType<?> type) {
        return CACHE.get(type);
    }

    public static void put(String entityId, String dimensionId) {
        // Replaces rather than appends: repeating the command used to stack entries and
        // leave the resulting "primary" dimension down to hash order.
        overrides.put(normaliseId(entityId), normaliseId(dimensionId));
        save();
        rebuildCache();
    }

    public static boolean remove(String entityId) {
        if (overrides.remove(normaliseId(entityId)) == null) {
            return false;
        }
        save();
        rebuildCache();
        return true;
    }

    /** Entries naming an entity that does not exist in the registry; reported by {@code /nolifetracker audit}. */
    public static List<String> unknownEntityIds() {
        return List.copyOf(UNKNOWN_ENTITY_IDS);
    }

    private static void rebuildCache() {
        CACHE.clear();
        UNKNOWN_ENTITY_IDS.clear();

        for (Map.Entry<String, String> entry : overrides.entrySet()) {
            Identifier entityId = Identifier.tryParse(entry.getKey());
            Identifier dimensionId = Identifier.tryParse(entry.getValue());

            if (entityId == null || dimensionId == null) {
                UNKNOWN_ENTITY_IDS.add(entry.getKey());
                continue;
            }

            // getOptionalValue, not get: ENTITY_TYPE is a DefaultedRegistry, so a typo would
            // otherwise silently resolve to minecraft:pig and rewrite the pig's dimension.
            Optional<EntityType<?>> type = Registries.ENTITY_TYPE.getOptionalValue(entityId);
            if (type.isEmpty()) {
                UNKNOWN_ENTITY_IDS.add(entry.getKey());
                continue;
            }

            CACHE.put(type.get(), dimensionId);
        }

        if (!UNKNOWN_ENTITY_IDS.isEmpty()) {
            LOGGER.warn("{} dimension override(s) name unknown entities and were ignored: {}",
                    UNKNOWN_ENTITY_IDS.size(), UNKNOWN_ENTITY_IDS);
        }
    }

    private static String readDimension(JsonElement element) {
        if (element == null || element.isJsonNull()) {
            return null;
        }
        if (element.isJsonPrimitive()) {
            return element.getAsString();
        }
        if (element.isJsonArray() && !element.getAsJsonArray().isEmpty()) {
            JsonElement first = element.getAsJsonArray().get(0);
            return first.isJsonPrimitive() ? first.getAsString() : null;
        }
        return null;
    }

    private static String normaliseId(String id) {
        String trimmed = id.trim();
        return trimmed.indexOf(':') >= 0 ? trimmed : "minecraft:" + trimmed;
    }

    /**
     * Mobs that never appear in a biome spawn table, so grouping them has to be told
     * where they belong. Applied only where the admin has not already said otherwise.
     */
    private static Map<String, String> defaults() {
        Map<String, String> defaults = new TreeMap<>();

        defaults.put("minecraft:blaze", "minecraft:the_nether");
        defaults.put("minecraft:happy_ghast", "minecraft:the_nether");
        defaults.put("minecraft:piglin_brute", "minecraft:the_nether");
        defaults.put("minecraft:wither", "minecraft:the_nether");
        defaults.put("minecraft:wither_skeleton", "minecraft:the_nether");
        defaults.put("minecraft:zoglin", "minecraft:the_nether");

        defaults.put("minecraft:ender_dragon", "minecraft:the_end");
        defaults.put("minecraft:shulker", "minecraft:the_end");

        // Mobs that spawn in more than one dimension, where the automatic pick is not the
        // one a player would go looking in. Skeletons and endermen both appear in Nether
        // and End biome lists, so without these they get filed away from the Overworld.
        defaults.put("minecraft:enderman", "minecraft:overworld");
        defaults.put("minecraft:skeleton", "minecraft:overworld");

        // Structure-spawned or specially-spawned, so absent from biome spawn tables.
        defaults.put("minecraft:bee", "minecraft:overworld");
        defaults.put("minecraft:breeze", "minecraft:overworld");
        defaults.put("minecraft:camel_husk", "minecraft:overworld");
        defaults.put("minecraft:cat", "minecraft:overworld");
        defaults.put("minecraft:guardian", "minecraft:overworld");
        defaults.put("minecraft:phantom", "minecraft:overworld");
        defaults.put("minecraft:villager", "minecraft:overworld");
        defaults.put("minecraft:zombie_nautilus", "minecraft:overworld");

        defaults.put("minecraft:allay", "minecraft:overworld");
        defaults.put("minecraft:cave_spider", "minecraft:overworld");
        defaults.put("minecraft:copper_golem", "minecraft:overworld");
        defaults.put("minecraft:creaking", "minecraft:overworld");
        defaults.put("minecraft:elder_guardian", "minecraft:overworld");
        defaults.put("minecraft:endermite", "minecraft:overworld");
        defaults.put("minecraft:evoker", "minecraft:overworld");
        defaults.put("minecraft:giant", "minecraft:overworld");
        defaults.put("minecraft:illusioner", "minecraft:overworld");
        defaults.put("minecraft:iron_golem", "minecraft:overworld");
        defaults.put("minecraft:mule", "minecraft:overworld");
        defaults.put("minecraft:pillager", "minecraft:overworld");
        defaults.put("minecraft:ravager", "minecraft:overworld");
        defaults.put("minecraft:silverfish", "minecraft:overworld");
        defaults.put("minecraft:skeleton_horse", "minecraft:overworld");
        defaults.put("minecraft:sniffer", "minecraft:overworld");
        defaults.put("minecraft:snow_golem", "minecraft:overworld");
        defaults.put("minecraft:tadpole", "minecraft:overworld");
        defaults.put("minecraft:trader_llama", "minecraft:overworld");
        defaults.put("minecraft:vex", "minecraft:overworld");
        defaults.put("minecraft:villager", "minecraft:overworld");
        defaults.put("minecraft:vindicator", "minecraft:overworld");
        defaults.put("minecraft:wandering_trader", "minecraft:overworld");
        defaults.put("minecraft:warden", "minecraft:overworld");
        defaults.put("minecraft:zombie_horse", "minecraft:overworld");

        return defaults;
    }
}

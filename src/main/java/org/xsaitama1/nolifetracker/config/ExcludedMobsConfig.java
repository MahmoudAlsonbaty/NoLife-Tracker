package org.xsaitama1.nolifetracker.config;

import com.google.gson.reflect.TypeToken;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Type;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * Mobs deliberately left out of the challenge.
 *
 * <p>Ids are normalised to {@code namespace:path} on load, so entries written by older
 * versions as bare paths ({@code ender_dragon}) keep working and are rewritten in the
 * canonical form. This was previously the one config that used a different key format
 * from the others.
 */
public final class ExcludedMobsConfig {

    private static final Logger LOGGER = LoggerFactory.getLogger("nolifetracker");
    private static final Type ON_DISK = new TypeToken<List<String>>() {
    }.getType();

    private static Set<String> excluded = new TreeSet<>();

    private ExcludedMobsConfig() {
    }

    public static void load() {
        boolean fresh = !Files.exists(NoLifeTrackerPaths.excludedMobs());

        List<String> raw = JsonConfigs.load(
                NoLifeTrackerPaths.excludedMobs(), ON_DISK, ArrayList::new, "excluded mobs");

        excluded = new TreeSet<>();
        for (String entry : raw) {
            if (entry != null && !entry.isBlank()) {
                excluded.add(normaliseId(entry));
            }
        }

        if (fresh) {
            // Same starting set as previous versions. Change any of them at runtime with
            // /nolifetracker exclude <mob> <true|false>.
            excluded.add("minecraft:ender_dragon");
            excluded.add("minecraft:giant");
            excluded.add("minecraft:illusioner");
        }

        save();
    }

    public static void save() {
        JsonConfigs.save(NoLifeTrackerPaths.excludedMobs(), new ArrayList<>(excluded), "excluded mobs");
    }

    public static Set<String> all() {
        return Set.copyOf(excluded);
    }

    public static boolean contains(String mobId) {
        return excluded.contains(normaliseId(mobId));
    }

    /** @return true when this actually changed the set. */
    public static boolean add(String mobId) {
        if (!excluded.add(normaliseId(mobId))) {
            return false;
        }
        save();
        return true;
    }

    /** @return true when this actually changed the set. */
    public static boolean remove(String mobId) {
        if (!excluded.remove(normaliseId(mobId))) {
            return false;
        }
        save();
        return true;
    }

    /** Entries naming an entity that does not exist in the registry; reported by {@code /nolifetracker audit}. */
    public static List<String> unknownEntityIds() {
        List<String> unknown = new ArrayList<>();
        for (String id : excluded) {
            Identifier parsed = Identifier.tryParse(id);
            if (parsed == null || BuiltInRegistries.ENTITY_TYPE.getOptional(parsed).isEmpty()) {
                unknown.add(id);
            }
        }
        if (!unknown.isEmpty()) {
            LOGGER.warn("{} excluded mob(s) name unknown entities: {}", unknown.size(), unknown);
        }
        return unknown;
    }

    private static String normaliseId(String id) {
        String trimmed = id.trim();
        return trimmed.indexOf(':') >= 0 ? trimmed : "minecraft:" + trimmed;
    }
}

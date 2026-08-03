package org.xsaitama1.nolifetracker.challenge;

import net.minecraft.entity.EntityType;
import net.minecraft.entity.SpawnGroup;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Identifier;
import net.minecraft.world.biome.Biome;
import net.minecraft.world.biome.SpawnSettings;
import org.xsaitama1.nolifetracker.config.DimensionOverridesConfig;

import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Works out which dimension to file a mob under in {@code /nolifetracker missing}.
 *
 * <p>Purely cosmetic grouping. A mob this cannot place still counts toward the
 * challenge -- it just lands in the "Other" group. That separation is deliberate:
 * dimension data used to double as the membership test, so an unrecognised mob
 * silently dropped out of the challenge entirely.
 */
public final class DimensionResolver {

    public static final Identifier OVERWORLD = Identifier.of("minecraft", "overworld");
    public static final Identifier NETHER = Identifier.of("minecraft", "the_nether");
    public static final Identifier END = Identifier.of("minecraft", "the_end");

    private static final Map<EntityType<?>, Set<Identifier>> NATIVE_DIMENSIONS = new HashMap<>();

    private DimensionResolver() {
    }

    /** Walks every loaded dimension's biome sources once, at server start. */
    public static void scan(MinecraftServer server) {
        NATIVE_DIMENSIONS.clear();

        for (ServerWorld world : server.getWorlds()) {
            Identifier dimensionId = world.getRegistryKey().getValue();

            Set<RegistryEntry<Biome>> biomes = world.getChunkManager()
                    .getChunkGenerator()
                    .getBiomeSource()
                    .getBiomes();

            for (RegistryEntry<Biome> biomeEntry : biomes) {
                SpawnSettings spawnSettings = biomeEntry.value().getSpawnSettings();

                for (SpawnGroup group : SpawnGroup.values()) {
                    spawnSettings.getSpawnEntries(group).getEntries().forEach(weighted ->
                            NATIVE_DIMENSIONS
                                    .computeIfAbsent(weighted.value().type(), key -> new HashSet<>())
                                    .add(dimensionId));
                }
            }
        }
    }

    /**
     * @return the dimension to group this mob under, or null when nothing can be
     * determined and it should fall into the "Other" group.
     */
    public static Identifier resolve(EntityType<?> type) {
        Identifier override = DimensionOverridesConfig.get(type);
        if (override != null) {
            return override;
        }

        Set<Identifier> natural = NATIVE_DIMENSIONS.get(type);
        if (natural == null || natural.isEmpty()) {
            return null;
        }

        // Mobs that spawn in several dimensions are filed under the most distinctive one.
        if (natural.contains(END)) {
            return END;
        }
        if (natural.contains(NETHER)) {
            return NETHER;
        }
        if (natural.contains(OVERWORLD)) {
            return OVERWORLD;
        }

        // A datapack or mod dimension: pick deterministically rather than by hash order.
        return natural.stream().min(Comparator.comparing(Identifier::toString)).orElse(null);
    }
}

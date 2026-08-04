package org.xsaitama1.nolifetracker.challenge;

import net.minecraft.core.Holder;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.MobSpawnSettings;
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

    public static final Identifier OVERWORLD = Identifier.fromNamespaceAndPath("minecraft", "overworld");
    public static final Identifier NETHER = Identifier.fromNamespaceAndPath("minecraft", "the_nether");
    public static final Identifier END = Identifier.fromNamespaceAndPath("minecraft", "the_end");

    private static final Map<EntityType<?>, Set<Identifier>> NATIVE_DIMENSIONS = new HashMap<>();

    private DimensionResolver() {
    }

    /** Walks every loaded dimension's biome sources once, at server start. */
    public static void scan(MinecraftServer server) {
        NATIVE_DIMENSIONS.clear();

        for (ServerLevel level : server.getAllLevels()) {
            Identifier dimensionId = level.dimension().identifier();

            Set<Holder<Biome>> biomes = level.getChunkSource()
                    .getGenerator()
                    .getBiomeSource()
                    .possibleBiomes();

            for (Holder<Biome> biomeEntry : biomes) {
                MobSpawnSettings spawnSettings = biomeEntry.value().getMobSettings();

                for (MobCategory category : MobCategory.values()) {
                    spawnSettings.getMobs(category).unwrap().forEach(weighted ->
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

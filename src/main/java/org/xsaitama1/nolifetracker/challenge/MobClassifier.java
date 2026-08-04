package org.xsaitama1.nolifetracker.challenge;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Decides which entity types count as "a mob" for the challenge.
 *
 * <p>The primary signal is {@link MobCategory}. Vanilla files every entity that does not
 * support natural spawning -- arrows, boats, minecarts, item frames, TNT, displays,
 * experience orbs -- under {@link MobCategory#MISC}, and everything else under one of the
 * living categories. That is a stable registry property, so mobs added by a game update
 * or by another mod are picked up automatically with no list to maintain.
 *
 * <p>This deliberately replaces the previous approach, which decided membership from
 * biome spawn tables. That coupling meant any mob which never spawns naturally silently
 * vanished from the challenge unless someone remembered to add a dimension override for
 * it, which is why the override file had grown a long list of entries whose real purpose
 * was "please count this mob at all".
 *
 * <p>A small number of genuinely living mobs are also MISC, because they are only ever
 * built or summoned. Those are named explicitly below rather than guessed at.
 */
public final class MobClassifier {

    /**
     * Living mobs that vanilla files under MISC because they never spawn as part of a
     * spawn group: the golems, the two bosses, the summon-only horses, and the two
     * unused mobs that are still reachable via commands.
     */
    public static final Set<String> DEFAULT_FORCE_INCLUDE = Set.of(
            "minecraft:iron_golem",
            "minecraft:snow_golem",
            "minecraft:copper_golem",
            "minecraft:wither",
            "minecraft:ender_dragon",
            "minecraft:giant",
            "minecraft:illusioner",
            "minecraft:zombie_horse",
            "minecraft:skeleton_horse",
            // Villagers only spawn as part of a village, so vanilla files them under MISC
            // alongside the boats. Confirmed against the 26.2 registry, not assumed.
            "minecraft:villager"
    );

    /**
     * Vehicle families, kept as suffixes because a new wood type adds several entries at
     * once and pale oak was added only recently.
     */
    private static final Set<String> NON_MOB_SUFFIXES = Set.of(
            "_boat", "_chest_boat", "_raft", "_chest_raft", "_minecart"
    );

    /**
     * Every remaining MISC entity in 26.2 that is not a mob: projectiles, dropped items,
     * displays and decorations.
     *
     * <p>The point of naming them all is that anything MISC and <em>not</em> listed here gets
     * reported at startup and by {@code /nolifetracker audit}. So when a game update introduces a mob
     * that happens to be MISC, it shows up as a single unfamiliar name instead of being lost
     * in sixty boats.
     */
    private static final Set<String> KNOWN_NON_MOBS = Set.of(
            "minecraft:player",
            "minecraft:armor_stand",
            "minecraft:mannequin",
            "minecraft:marker",
            "minecraft:interaction",
            "minecraft:text_display",
            "minecraft:item_display",
            "minecraft:block_display",
            "minecraft:area_effect_cloud",
            "minecraft:arrow",
            "minecraft:spectral_arrow",
            "minecraft:trident",
            "minecraft:egg",
            "minecraft:snowball",
            "minecraft:ender_pearl",
            "minecraft:eye_of_ender",
            "minecraft:experience_bottle",
            "minecraft:experience_orb",
            "minecraft:splash_potion",
            "minecraft:lingering_potion",
            "minecraft:fireball",
            "minecraft:small_fireball",
            "minecraft:dragon_fireball",
            "minecraft:wither_skull",
            "minecraft:shulker_bullet",
            "minecraft:llama_spit",
            "minecraft:wind_charge",
            "minecraft:breeze_wind_charge",
            "minecraft:firework_rocket",
            "minecraft:fishing_bobber",
            "minecraft:evoker_fangs",
            "minecraft:end_crystal",
            "minecraft:falling_block",
            "minecraft:item",
            "minecraft:item_frame",
            "minecraft:glow_item_frame",
            "minecraft:painting",
            "minecraft:leash_knot",
            "minecraft:lightning_bolt",
            "minecraft:ominous_item_spawner",
            "minecraft:tnt"
    );

    private MobClassifier() {
    }

    public static boolean isMob(EntityType<?> type, Set<String> forceInclude) {
        String id = idOf(type);
        if (id == null || isKnownNonMob(id)) {
            return false;
        }
        if (forceInclude.contains(id)) {
            return true;
        }
        return type.getCategory() != MobCategory.MISC;
    }

    private static boolean isKnownNonMob(String id) {
        if (KNOWN_NON_MOBS.contains(id)) {
            return true;
        }
        for (String suffix : NON_MOB_SUFFIXES) {
            if (id.endsWith(suffix)) {
                return true;
            }
        }
        return "minecraft:minecart".equals(id);
    }

    /**
     * MISC types that are neither force-included nor known non-mobs. Surfaced by
     * {@code /nolifetracker audit} so a mob added by a future update that happens to be MISC
     * can be spotted and force-included rather than quietly missed.
     */
    public static Set<String> unclassifiedCandidates(Set<String> forceInclude) {
        Set<String> candidates = new LinkedHashSet<>();
        for (EntityType<?> type : BuiltInRegistries.ENTITY_TYPE) {
            if (type.getCategory() != MobCategory.MISC) {
                continue;
            }
            String id = idOf(type);
            if (id == null || forceInclude.contains(id) || isKnownNonMob(id)) {
                continue;
            }
            candidates.add(id);
        }
        return candidates;
    }

    private static String idOf(EntityType<?> type) {
        Identifier id = BuiltInRegistries.ENTITY_TYPE.getKey(type);
        return id == null ? null : id.toString();
    }
}

package org.xsaitama1.nolifetracker.listener;

import net.fabricmc.fabric.api.entity.event.v1.ServerEntityCombatEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import org.xsaitama1.nolifetracker.NoLifeTrackerService;
import org.xsaitama1.nolifetracker.challenge.ChallengeRegistry;
import org.xsaitama1.nolifetracker.config.ConfigManager;
import org.xsaitama1.nolifetracker.config.PluginConfig;
import org.xsaitama1.nolifetracker.data.PlayerStats;
import org.xsaitama1.nolifetracker.util.TextUtil;

/**
 * Records kills.
 *
 * <p>Fabric fires this for the entity that dealt the killing blow, so a mob finished off by
 * a tamed wolf, an iron golem, or fall damage after a knockback is not credited to the
 * player. That is a deliberate property of the challenge rather than an oversight.
 */
public final class CombatListener {

    private CombatListener() {
    }

    public static void register() {
        ServerEntityCombatEvents.AFTER_KILLED_OTHER_ENTITY.register((level, entity, killedEntity, damageSource) -> {
            if (!(entity instanceof ServerPlayer player)) {
                return;
            }

            NoLifeTrackerService service = NoLifeTrackerService.get();
            if (service == null) {
                return;
            }

            PlayerStats stats = service.stats().get(player.getUUID());

            if (killedEntity instanceof ServerPlayer victim) {
                stats.totalPlayerKills++;
                stats.killedPlayers.merge(victim.getName().getString(), 1, Integer::sum);
                service.tabList().markDirty();
                return;
            }

            if (!(killedEntity instanceof Mob)) {
                return;
            }

            Identifier mobId = BuiltInRegistries.ENTITY_TYPE.getKey(killedEntity.getType());
            if (mobId == null) {
                return;
            }

            stats.totalMobKills++;

            String key = mobId.toString();
            boolean firstOfItsKind = !stats.killedMobs.containsKey(key);
            stats.killedMobs.merge(key, 1, Integer::sum);

            if (firstOfItsKind) {
                // Invalidate before reading the score back, so the announcement reflects the
                // kill that just happened rather than guessing with a +1.
                ChallengeRegistry.invalidate(player.getUUID());
                stats.lastKillTime = System.currentTimeMillis();

                announceFirstKill(service, player, killedEntity.getType(), key, stats);
                service.stats().saveAsync();
            }

            service.tabList().markDirty();
        });
    }

    private static void announceFirstKill(NoLifeTrackerService service, ServerPlayer player,
                                          EntityType<?> type, String mobId, PlayerStats stats) {
        PluginConfig config = ConfigManager.get();
        if (!config.announceFirstKills) {
            return;
        }

        boolean challengeMob = ChallengeRegistry.isChallengeMob(type);
        if (!challengeMob && !config.announceNonChallengeKills) {
            return;
        }

        boolean global = config.globalMobKillAnnouncement;
        String prettyName = TextUtil.prettify(mobId);
        String subject = global ? player.getName().getString() + " hunted their first " : "You hunted your first ";

        MutableComponent message;
        if (challengeMob) {
            int score = ChallengeRegistry.uniqueKills(player.getUUID(), stats);
            int target = ChallengeRegistry.total();

            message = Component.literal(subject).withStyle(ChatFormatting.GOLD)
                    .append(Component.literal(prettyName).withStyle(ChatFormatting.WHITE, ChatFormatting.BOLD))
                    .append(Component.literal("! [" + score + "/" + target + "]").withStyle(ChatFormatting.GREEN));
        } else {
            message = Component.literal(subject).withStyle(ChatFormatting.GRAY)
                    .append(Component.literal(prettyName).withStyle(ChatFormatting.WHITE))
                    .append(Component.literal("! (not part of the challenge)").withStyle(ChatFormatting.DARK_GRAY));
        }

        if (global) {
            // Reaches every online player and the server log.
            service.server().getPlayerList().broadcastSystemMessage(message, false);
        } else {
            player.sendSystemMessage(message);
        }
    }
}

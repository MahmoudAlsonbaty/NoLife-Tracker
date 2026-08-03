package org.xsaitama1.nolifetracker.listener;

import net.fabricmc.fabric.api.entity.event.v1.ServerEntityCombatEvents;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.registry.Registries;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Identifier;
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
        ServerEntityCombatEvents.AFTER_KILLED_OTHER_ENTITY.register((world, entity, killedEntity, damageSource) -> {
            if (!(entity instanceof ServerPlayerEntity player)) {
                return;
            }

            NoLifeTrackerService service = NoLifeTrackerService.get();
            if (service == null) {
                return;
            }

            PlayerStats stats = service.stats().get(player.getUuid());

            if (killedEntity instanceof ServerPlayerEntity victim) {
                stats.totalPlayerKills++;
                stats.killedPlayers.merge(victim.getName().getString(), 1, Integer::sum);
                service.tabList().markDirty();
                return;
            }

            if (!(killedEntity instanceof MobEntity)) {
                return;
            }

            Identifier mobId = Registries.ENTITY_TYPE.getId(killedEntity.getType());
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
                ChallengeRegistry.invalidate(player.getUuid());
                stats.lastKillTime = System.currentTimeMillis();

                announceFirstKill(service, player, killedEntity.getType(), key, stats);
                service.stats().saveAsync();
            }

            service.tabList().markDirty();
        });
    }

    private static void announceFirstKill(NoLifeTrackerService service, ServerPlayerEntity player,
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

        MutableText message;
        if (challengeMob) {
            int score = ChallengeRegistry.uniqueKills(player.getUuid(), stats);
            int target = ChallengeRegistry.total();

            message = Text.literal(subject).formatted(Formatting.GOLD)
                    .append(Text.literal(prettyName).formatted(Formatting.WHITE, Formatting.BOLD))
                    .append(Text.literal("! [" + score + "/" + target + "]").formatted(Formatting.GREEN));
        } else {
            message = Text.literal(subject).formatted(Formatting.GRAY)
                    .append(Text.literal(prettyName).formatted(Formatting.WHITE))
                    .append(Text.literal("! (not part of the challenge)").formatted(Formatting.DARK_GRAY));
        }

        if (global) {
            // Reaches every online player and the server log.
            service.server().getPlayerManager().broadcast(message, false);
        } else {
            player.sendMessage(message, false);
        }
    }
}

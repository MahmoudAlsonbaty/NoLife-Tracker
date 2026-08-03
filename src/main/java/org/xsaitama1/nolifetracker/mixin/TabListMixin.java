package org.xsaitama1.nolifetracker.mixin;

import net.minecraft.scoreboard.Team;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.xsaitama1.nolifetracker.NoLifeTrackerService;
import org.xsaitama1.nolifetracker.challenge.ChallengeRegistry;
import org.xsaitama1.nolifetracker.config.ConfigManager;
import org.xsaitama1.nolifetracker.config.PluginConfig;
import org.xsaitama1.nolifetracker.data.PlayerStats;
import org.xsaitama1.nolifetracker.util.TextUtil;

/**
 * Appends challenge progress to the tab list entry.
 *
 * <p>Vanilla calls this every time a player list packet entry is built, so it must stay
 * cheap: everything shown here is either a plain field read or a cached lookup. Play time
 * is deliberately absent -- it changed every second, which is what forced the constant
 * whole-list rebroadcasts that made the server stutter.
 *
 * <p>Priority stays high so this runs after cosmetic name-changing mods and can wrap
 * whatever they produced.
 */
@Mixin(value = ServerPlayerEntity.class, priority = 2000)
public class TabListMixin {

    @Inject(method = "getPlayerListName", at = @At("RETURN"), cancellable = true)
    private void nolifetracker$appendProgress(CallbackInfoReturnable<Text> cir) {
        NoLifeTrackerService service = NoLifeTrackerService.get();
        if (service == null) {
            return;
        }

        ServerPlayerEntity player = (ServerPlayerEntity) (Object) this;
        PlayerStats stats = service.stats().get(player.getUuid());
        PluginConfig config = ConfigManager.get();

        Text originalName = cir.getReturnValue();
        if (originalName == null) {
            originalName = player.getName();
        }

        MutableText name = Text.empty()
                .append(Text.empty().append(originalName).formatted(nolifetracker$teamColour(player)));

        if (!config.afkSuffix.isEmpty() && service.afk().isAfk(player.getUuid())) {
            name.append(TextUtil.legacy(config.afkSuffix));
        }

        if (!config.tabNameSuffix.isEmpty()) {
            name.append(TextUtil.legacy(nolifetracker$render(config.tabNameSuffix, player, stats)));
        }

        cir.setReturnValue(name);
    }

    /**
     * Substitutes the per-player placeholders. Every value is a field read or a cached
     * lookup, so this stays within the budget a per-packet-entry call has.
     */
    @Unique
    private static String nolifetracker$render(String template, ServerPlayerEntity player, PlayerStats stats) {
        return template
                .replace("%kills%", String.valueOf(ChallengeRegistry.uniqueKills(player.getUuid(), stats)))
                .replace("%total%", String.valueOf(ChallengeRegistry.total()))
                .replace("%deaths%", String.valueOf(stats.totalDeaths))
                .replace("%pvpkills%", String.valueOf(stats.totalPlayerKills))
                .replace("%mobkills%", String.valueOf(stats.totalMobKills));
    }

    /**
     * Unique, and private: a plain public helper in a mixin class is merged into
     * ServerPlayerEntity itself, where it can collide with another mod's mixin.
     */
    @Unique
    private static Formatting nolifetracker$teamColour(ServerPlayerEntity player) {
        Team team = (Team) player.getScoreboardTeam();
        if (team != null && team.getColor() != null) {
            return team.getColor();
        }
        return Formatting.WHITE;
    }
}

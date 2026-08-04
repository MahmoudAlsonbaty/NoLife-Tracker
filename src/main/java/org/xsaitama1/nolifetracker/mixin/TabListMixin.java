package org.xsaitama1.nolifetracker.mixin;

import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.TextColor;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.scores.PlayerTeam;
import net.minecraft.world.scores.TeamColor;
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
@Mixin(value = ServerPlayer.class, priority = 2000)
public class TabListMixin {

    @Inject(method = "getTabListDisplayName", at = @At("RETURN"), cancellable = true)
    private void nolifetracker$appendProgress(CallbackInfoReturnable<Component> cir) {
        NoLifeTrackerService service = NoLifeTrackerService.get();
        if (service == null) {
            return;
        }

        ServerPlayer player = (ServerPlayer) (Object) this;
        PlayerStats stats = service.stats().get(player.getUUID());
        PluginConfig config = ConfigManager.get();

        Component originalName = cir.getReturnValue();
        if (originalName == null) {
            originalName = player.getName();
        }

        TextColor colour = nolifetracker$teamColour(player);
        MutableComponent name = Component.empty()
                .append(Component.empty().append(originalName).withStyle(style -> style.withColor(colour)));

        if (!config.afkSuffix.isEmpty() && service.afk().isAfk(player.getUUID())) {
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
    private static String nolifetracker$render(String template, ServerPlayer player, PlayerStats stats) {
        return template
                .replace("%kills%", String.valueOf(ChallengeRegistry.uniqueKills(player.getUUID(), stats)))
                .replace("%total%", String.valueOf(ChallengeRegistry.total()))
                .replace("%deaths%", String.valueOf(stats.totalDeaths))
                .replace("%pvpkills%", String.valueOf(stats.totalPlayerKills))
                .replace("%mobkills%", String.valueOf(stats.totalMobKills));
    }

    /**
     * Unique, and private: a plain public helper in a mixin class is merged into
     * ServerPlayer itself, where it can collide with another mod's mixin.
     *
     * <p>A team's colour is an optional {@link TeamColor} rather than a {@code ChatFormatting}
     * as of 26.2, so this hands back the {@link TextColor} to apply to the style directly.
     */
    @Unique
    private static TextColor nolifetracker$teamColour(ServerPlayer player) {
        PlayerTeam team = player.getTeam();
        if (team == null) {
            return TextColor.WHITE;
        }
        return team.getColor().map(TeamColor::textColor).orElse(TextColor.WHITE);
    }
}

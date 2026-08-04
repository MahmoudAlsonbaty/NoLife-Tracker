package org.xsaitama1.nolifetracker.command;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import org.xsaitama1.nolifetracker.NoLifeTrackerService;
import org.xsaitama1.nolifetracker.data.PlayerStats;
import org.xsaitama1.nolifetracker.data.StatsRepository;

import java.util.ArrayList;
import java.util.List;

/** Builds the {@code /nolifetracker} command tree. Rendering lives in {@link StatsDisplay}, mutation in {@link AdminCommands}. */
public final class NoLifeCommandRegistrar {

    private static final SuggestionProvider<CommandSourceStack> PLAYER_SUGGESTER =
            (context, builder) -> SharedSuggestionProvider.suggest(knownPlayerNames(context), builder);

    private NoLifeCommandRegistrar() {
    }

    public static void register() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
                dispatcher.register(Commands.literal("nolifetracker")
                        .executes(StatsDisplay::showHelp)

                        .then(Commands.literal("leaderboard")
                                .executes(StatsDisplay::showProgressLeaderboard)
                                .then(Commands.literal("deaths")
                                        .executes(StatsDisplay::showDeathLeaderboard)))

                        .then(Commands.literal("missing")
                                .executes(NoLifeCommandRegistrar::selfMissing))

                        .then(Commands.literal("afk")
                                .executes(NoLifeCommandRegistrar::toggleAfk))

                        .then(AdminCommands.exclude())
                        .then(AdminCommands.editMob())
                        .then(AdminCommands.config())
                        .then(AdminCommands.reload())
                        .then(AdminCommands.audit())

                        // Kept last: Brigadier matches literals before this catch-all argument.
                        .then(Commands.argument("player", StringArgumentType.word())
                                .suggests(PLAYER_SUGGESTER)
                                // Previously absent, so "/nolifetracker Steve" on its own was an error.
                                .executes(context -> StatsDisplay.showSummary(context, player(context)))
                                .then(Commands.literal("deaths")
                                        .executes(context -> StatsDisplay.showPlayerDeaths(context, player(context))))
                                .then(Commands.literal("mobs")
                                        .executes(context -> StatsDisplay.showPlayerMobs(context, player(context))))
                                .then(Commands.literal("kills")
                                        .executes(context -> StatsDisplay.showPlayerPvpKills(context, player(context))))
                                .then(Commands.literal("missing")
                                        .executes(context -> StatsDisplay.showMissing(context, player(context)))))));
    }

    private static String player(CommandContext<CommandSourceStack> context) {
        return StringArgumentType.getString(context, "player");
    }

    private static int selfMissing(CommandContext<CommandSourceStack> context) {
        ServerPlayer player = context.getSource().getPlayer();
        if (player == null) {
            context.getSource().sendFailure(Component.literal("Only players can use this; name someone with /nolifetracker <player> missing."));
            return 0;
        }
        return StatsDisplay.showMissing(context, player.getName().getString());
    }

    private static int toggleAfk(CommandContext<CommandSourceStack> context) {
        ServerPlayer player = context.getSource().getPlayer();
        if (player == null) {
            context.getSource().sendFailure(Component.literal("Only players can go AFK."));
            return 0;
        }

        NoLifeTrackerService service = NoLifeTrackerService.get();
        if (service == null) {
            context.getSource().sendFailure(Component.literal("NoLife Tracker is not ready yet."));
            return 0;
        }

        boolean nowAfk = service.afk().toggleManual(player.getUUID());
        context.getSource().sendSuccess(
                () -> Component.literal(nowAfk ? "AFK enabled." : "AFK disabled.").withStyle(ChatFormatting.GRAY), false);
        return 1;
    }

    private static List<String> knownPlayerNames(CommandContext<CommandSourceStack> context) {
        NoLifeTrackerService service = NoLifeTrackerService.get();
        if (service == null) {
            return List.of();
        }

        StatsRepository repository = service.stats();
        List<String> names = new ArrayList<>();
        for (PlayerStats stats : repository.all().values()) {
            if (!"Unknown".equals(stats.lastKnownName)) {
                names.add(stats.lastKnownName);
            }
        }
        return names;
    }
}

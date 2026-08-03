package org.xsaitama1.nolifetracker.command;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.command.CommandSource;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import org.xsaitama1.nolifetracker.NoLifeTrackerService;
import org.xsaitama1.nolifetracker.data.PlayerStats;
import org.xsaitama1.nolifetracker.data.StatsRepository;

import java.util.ArrayList;
import java.util.List;

/** Builds the {@code /nolifetracker} command tree. Rendering lives in {@link StatsDisplay}, mutation in {@link AdminCommands}. */
public final class NoLifeCommandRegistrar {

    private static final SuggestionProvider<ServerCommandSource> PLAYER_SUGGESTER =
            (context, builder) -> CommandSource.suggestMatching(knownPlayerNames(context), builder);

    private NoLifeCommandRegistrar() {
    }

    public static void register() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
                dispatcher.register(CommandManager.literal("nolifetracker")
                        .executes(StatsDisplay::showHelp)

                        .then(CommandManager.literal("leaderboard")
                                .executes(StatsDisplay::showProgressLeaderboard)
                                .then(CommandManager.literal("deaths")
                                        .executes(StatsDisplay::showDeathLeaderboard)))

                        .then(CommandManager.literal("missing")
                                .executes(NoLifeCommandRegistrar::selfMissing))

                        .then(CommandManager.literal("afk")
                                .executes(NoLifeCommandRegistrar::toggleAfk))

                        .then(AdminCommands.exclude())
                        .then(AdminCommands.editMob())
                        .then(AdminCommands.config())
                        .then(AdminCommands.reload())
                        .then(AdminCommands.audit())

                        // Kept last: Brigadier matches literals before this catch-all argument.
                        .then(CommandManager.argument("player", StringArgumentType.word())
                                .suggests(PLAYER_SUGGESTER)
                                // Previously absent, so "/nolifetracker Steve" on its own was an error.
                                .executes(context -> StatsDisplay.showSummary(context, player(context)))
                                .then(CommandManager.literal("deaths")
                                        .executes(context -> StatsDisplay.showPlayerDeaths(context, player(context))))
                                .then(CommandManager.literal("mobs")
                                        .executes(context -> StatsDisplay.showPlayerMobs(context, player(context))))
                                .then(CommandManager.literal("kills")
                                        .executes(context -> StatsDisplay.showPlayerPvpKills(context, player(context))))
                                .then(CommandManager.literal("missing")
                                        .executes(context -> StatsDisplay.showMissing(context, player(context)))))));
    }

    private static String player(CommandContext<ServerCommandSource> context) {
        return StringArgumentType.getString(context, "player");
    }

    private static int selfMissing(CommandContext<ServerCommandSource> context) {
        ServerPlayerEntity player = context.getSource().getPlayer();
        if (player == null) {
            context.getSource().sendError(Text.literal("Only players can use this; name someone with /nolifetracker <player> missing."));
            return 0;
        }
        return StatsDisplay.showMissing(context, player.getName().getString());
    }

    private static int toggleAfk(CommandContext<ServerCommandSource> context) {
        ServerPlayerEntity player = context.getSource().getPlayer();
        if (player == null) {
            context.getSource().sendError(Text.literal("Only players can go AFK."));
            return 0;
        }

        NoLifeTrackerService service = NoLifeTrackerService.get();
        if (service == null) {
            context.getSource().sendError(Text.literal("NoLife Tracker is not ready yet."));
            return 0;
        }

        boolean nowAfk = service.afk().toggleManual(player.getUuid());
        context.getSource().sendFeedback(
                () -> Text.literal(nowAfk ? "AFK enabled." : "AFK disabled.").formatted(Formatting.GRAY), false);
        return 1;
    }

    private static List<String> knownPlayerNames(CommandContext<ServerCommandSource> context) {
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

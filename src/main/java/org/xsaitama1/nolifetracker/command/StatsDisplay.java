package org.xsaitama1.nolifetracker.command;

import com.mojang.brigadier.context.CommandContext;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.stats.Stats;
import net.minecraft.world.entity.EntityType;
import org.xsaitama1.nolifetracker.NoLifeTrackerService;
import org.xsaitama1.nolifetracker.challenge.ChallengeRegistry;
import org.xsaitama1.nolifetracker.challenge.DimensionResolver;
import org.xsaitama1.nolifetracker.config.ConfigManager;
import org.xsaitama1.nolifetracker.config.ExcludedMobsConfig;
import org.xsaitama1.nolifetracker.data.PlayerStats;
import org.xsaitama1.nolifetracker.data.StatsRepository;
import org.xsaitama1.nolifetracker.util.TextUtil;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;

/** Everything the player-facing {@code /nolifetracker} subcommands print. */
public final class StatsDisplay {

    /** The groups {@code /nolifetracker missing} splits mobs into, in display order. */
    private static final List<Group> GROUPS = List.of(
            new Group("Overworld", DimensionResolver.OVERWORLD, ChatFormatting.GREEN),
            new Group("Nether", DimensionResolver.NETHER, ChatFormatting.DARK_RED),
            new Group("End", DimensionResolver.END, ChatFormatting.LIGHT_PURPLE),
            // Mobs with no resolvable dimension used to be dropped from the challenge entirely.
            // They now count, and land here.
            new Group("Other", null, ChatFormatting.AQUA)
    );

    private StatsDisplay() {
    }

    public static int showHelp(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        source.sendSystemMessage(Component.literal("--- NoLife Tracker ---").withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD));

        help(source, "/nolifetracker <player>", "Summary of a player's progress");
        help(source, "/nolifetracker missing", "Which mobs you still need");
        help(source, "/nolifetracker afk", "Toggle your AFK flag");
        help(source, "/nolifetracker leaderboard", "Top " + ConfigManager.get().leaderboardSize + " closest to finishing");
        help(source, "/nolifetracker leaderboard deaths", "Top " + ConfigManager.get().leaderboardSize + " by deaths");
        help(source, "/nolifetracker <player> mobs", "Every mob a player has killed");
        help(source, "/nolifetracker <player> missing", "Mobs a player still needs");
        help(source, "/nolifetracker <player> deaths", "How a player has died");
        help(source, "/nolifetracker <player> kills", "Who a player has killed");

        return 1;
    }

    public static int showSummary(CommandContext<CommandSourceStack> context, String playerName) {
        CommandSourceStack source = context.getSource();
        StatsRepository repository = repository(source);
        if (repository == null) {
            return 0;
        }

        UUID uuid = repository.findUuidByName(playerName);
        PlayerStats stats = uuid == null ? null : repository.all().get(uuid);
        if (stats == null) {
            return notFound(source, playerName);
        }

        source.sendSystemMessage(Component.literal("--- " + stats.lastKnownName + " ---")
                .withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD));

        int unique = ChallengeRegistry.uniqueKills(uuid, stats);
        line(source, "Challenge", unique + " / " + ChallengeRegistry.total() + " mobs", ChatFormatting.GREEN);
        line(source, "Mob kills", String.valueOf(stats.totalMobKills), ChatFormatting.AQUA);
        line(source, "Player kills", String.valueOf(stats.totalPlayerKills), ChatFormatting.RED);
        line(source, "Deaths", String.valueOf(stats.totalDeaths), ChatFormatting.GRAY);

        // Play time comes from vanilla's own statistics, which are only loaded for online players.
        ServerPlayer online = source.getServer().getPlayerList().getPlayer(uuid);
        if (online != null) {
            int ticks = online.getStats().getValue(Stats.CUSTOM, Stats.PLAY_TIME);
            line(source, "Play time", TextUtil.formatPlayTime(ticks), ChatFormatting.YELLOW);
        }

        return 1;
    }

    public static int showProgressLeaderboard(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        StatsRepository repository = repository(source);
        if (repository == null) {
            return 0;
        }

        source.sendSystemMessage(Component.literal("--- Top Mob Hunters ---").withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD));

        List<Ranked> ranked = rankByProgress(repository);
        if (ranked.isEmpty()) {
            source.sendSystemMessage(Component.literal("Nobody has killed anything yet.").withStyle(ChatFormatting.GRAY));
            return 1;
        }

        int total = ChallengeRegistry.total();
        int rank = 1;
        for (Ranked entry : ranked) {
            if (rank > ConfigManager.get().leaderboardSize) {
                break;
            }
            source.sendSystemMessage(Component.literal(rank + ". " + entry.stats.lastKnownName
                    + " - " + entry.score + "/" + total + " mobs").withStyle(ChatFormatting.GREEN));
            rank++;
        }
        return 1;
    }

    public static int showDeathLeaderboard(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        StatsRepository repository = repository(source);
        if (repository == null) {
            return 0;
        }

        source.sendSystemMessage(Component.literal("--- Most Deaths ---").withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD));

        List<PlayerStats> sorted = new ArrayList<>(repository.all().values());
        sorted.sort(Comparator.comparingInt((PlayerStats stats) -> stats.totalDeaths).reversed());

        int rank = 1;
        for (PlayerStats stats : sorted) {
            if (rank > ConfigManager.get().leaderboardSize) {
                break;
            }
            source.sendSystemMessage(Component.literal(rank + ". " + stats.lastKnownName + " - " + stats.totalDeaths + " deaths")
                    .withStyle(ChatFormatting.RED));
            rank++;
        }
        return 1;
    }

    public static int showPlayerDeaths(CommandContext<CommandSourceStack> context, String playerName) {
        return showTally(context, playerName, "Deaths", ChatFormatting.GRAY,
                stats -> stats.deathReasons, "This player hasn't died yet.", false);
    }

    public static int showPlayerPvpKills(CommandContext<CommandSourceStack> context, String playerName) {
        return showTally(context, playerName, "PvP Kills", ChatFormatting.AQUA,
                stats -> stats.killedPlayers, "This player hasn't killed anyone yet.", false);
    }

    public static int showPlayerMobs(CommandContext<CommandSourceStack> context, String playerName) {
        return showTally(context, playerName, "Mob Kills", ChatFormatting.AQUA,
                stats -> stats.killedMobs, "This player hasn't killed any mobs yet.", true);
    }

    public static int showMissing(CommandContext<CommandSourceStack> context, String playerName) {
        CommandSourceStack source = context.getSource();
        StatsRepository repository = repository(source);
        if (repository == null) {
            return 0;
        }

        UUID uuid = repository.findUuidByName(playerName);
        PlayerStats stats = uuid == null ? null : repository.all().get(uuid);
        if (stats == null) {
            return notFound(source, playerName);
        }

        int unique = ChallengeRegistry.uniqueKills(uuid, stats);
        int total = ChallengeRegistry.total();

        source.sendSystemMessage(Component.literal("--- " + stats.lastKnownName + " [" + unique + "/" + total + "] ---")
                .withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD));

        if (unique >= total && total > 0) {
            source.sendSystemMessage(Component.literal("Every mob in the game has been defeated!")
                    .withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD));
            return 1;
        }

        Map<String, Bucket> buckets = bucketMobs(stats);

        boolean firstGroup = true;
        for (Group group : GROUPS) {
            Bucket bucket = buckets.get(group.name);
            if (bucket == null || bucket.isEmpty()) {
                continue;
            }

            if (!firstGroup) {
                source.sendSystemMessage(Component.literal(" "));
            }
            firstGroup = false;

            int done = bucket.killed.size();
            source.sendSystemMessage(Component.literal(group.name + " [" + done + "/"
                    + (done + bucket.remaining.size()) + "]").withStyle(group.colour));
            source.sendSystemMessage(bucket.render(group.colour));
        }

        return 1;
    }

    // ------------------------------------------------------------------
    // internals
    // ------------------------------------------------------------------

    private static int showTally(CommandContext<CommandSourceStack> context, String playerName,
                                 String heading, ChatFormatting colour,
                                 java.util.function.Function<PlayerStats, Map<String, Integer>> selector,
                                 String emptyMessage, boolean prettifyKeys) {
        CommandSourceStack source = context.getSource();
        StatsRepository repository = repository(source);
        if (repository == null) {
            return 0;
        }

        PlayerStats stats = repository.findByName(playerName);
        if (stats == null) {
            return notFound(source, playerName);
        }

        Map<String, Integer> tally = selector.apply(stats);
        String suffix = prettifyKeys
                ? " [" + ChallengeRegistry.uniqueKills(repository.findUuidByName(playerName), stats)
                + "/" + ChallengeRegistry.total() + "]"
                : "";

        source.sendSystemMessage(Component.literal("--- " + stats.lastKnownName + "'s " + heading + suffix + " ---")
                .withStyle(ChatFormatting.GOLD));

        if (tally.isEmpty()) {
            source.sendSystemMessage(Component.literal(emptyMessage).withStyle(ChatFormatting.GRAY));
            return 1;
        }

        List<Map.Entry<String, Integer>> sorted = new ArrayList<>(tally.entrySet());
        sorted.sort(Map.Entry.<String, Integer>comparingByValue().reversed());

        for (Map.Entry<String, Integer> entry : sorted) {
            String label = prettifyKeys ? TextUtil.prettify(entry.getKey()) : entry.getKey();
            source.sendSystemMessage(Component.literal("- " + label + ": " + entry.getValue()).withStyle(colour));
        }
        return 1;
    }

    private static Map<String, Bucket> bucketMobs(PlayerStats stats) {
        Map<String, Bucket> buckets = new TreeMap<>();

        for (String mobId : ChallengeRegistry.challengeIds()) {
            Bucket bucket = buckets.computeIfAbsent(
                    groupNameFor(ChallengeRegistry.dimensionOf(mobId)), key -> new Bucket());

            if (stats.killedMobs.containsKey(mobId)) {
                bucket.killed.add(TextUtil.prettify(mobId));
            } else {
                bucket.remaining.add(TextUtil.prettify(mobId));
            }
        }

        // Excluded mobs are shown struck through so it is obvious they are not being counted.
        for (String mobId : ExcludedMobsConfig.all()) {
            Identifier id = Identifier.tryParse(mobId);
            if (id == null) {
                continue;
            }
            Optional<EntityType<?>> type = BuiltInRegistries.ENTITY_TYPE.getOptional(id);
            if (type.isEmpty()) {
                continue;
            }

            buckets.computeIfAbsent(groupNameFor(DimensionResolver.resolve(type.get())), key -> new Bucket())
                    .excluded.add(TextUtil.prettify(mobId));
        }

        buckets.values().forEach(Bucket::sort);
        return buckets;
    }

    private static String groupNameFor(Identifier dimension) {
        if (dimension != null) {
            for (Group group : GROUPS) {
                if (dimension.equals(group.dimension)) {
                    return group.name;
                }
            }
        }
        return "Other";
    }

    private static List<Ranked> rankByProgress(StatsRepository repository) {
        List<Ranked> ranked = new ArrayList<>();
        for (Map.Entry<UUID, PlayerStats> entry : repository.all().entrySet()) {
            // Scored once per player rather than twice per comparison inside the sort.
            ranked.add(new Ranked(entry.getValue(),
                    ChallengeRegistry.uniqueKills(entry.getKey(), entry.getValue())));
        }

        ranked.sort(Comparator.comparingInt((Ranked r) -> r.score).reversed()
                .thenComparingLong(r -> r.stats.lastKillTime));
        return ranked;
    }

    static StatsRepository repository(CommandSourceStack source) {
        NoLifeTrackerService service = NoLifeTrackerService.get();
        if (service == null) {
            source.sendFailure(Component.literal("NoLife Tracker is not ready yet."));
            return null;
        }
        return service.stats();
    }

    private static int notFound(CommandSourceStack source, String playerName) {
        source.sendSystemMessage(Component.literal("No stats recorded for '" + playerName + "'.").withStyle(ChatFormatting.RED));
        return 0;
    }

    private static void help(CommandSourceStack source, String command, String description) {
        source.sendSystemMessage(Component.literal(command).withStyle(ChatFormatting.YELLOW)
                .append(Component.literal(" - " + description).withStyle(ChatFormatting.GRAY)));
    }

    private static void line(CommandSourceStack source, String label, String value, ChatFormatting colour) {
        source.sendSystemMessage(Component.literal(label + ": ").withStyle(ChatFormatting.GRAY)
                .append(Component.literal(value).withStyle(colour)));
    }

    private record Group(String name, Identifier dimension, ChatFormatting colour) {
    }

    private record Ranked(PlayerStats stats, int score) {
    }

    /** The three lists shown for one dimension: still needed, already done, and not counted. */
    private static final class Bucket {
        private final List<String> remaining = new ArrayList<>();
        private final List<String> killed = new ArrayList<>();
        private final List<String> excluded = new ArrayList<>();

        private boolean isEmpty() {
            return remaining.isEmpty() && killed.isEmpty() && excluded.isEmpty();
        }

        private void sort() {
            remaining.sort(String::compareToIgnoreCase);
            killed.sort(String::compareToIgnoreCase);
            excluded.sort(String::compareToIgnoreCase);
        }

        private MutableComponent render(ChatFormatting remainingColour) {
            MutableComponent line = Component.empty();
            boolean first = true;

            first = append(line, remaining, first, remainingColour);
            first = append(line, killed, first, ChatFormatting.GRAY);
            append(line, excluded, first, ChatFormatting.GRAY, ChatFormatting.STRIKETHROUGH);

            return line;
        }

        /** Returns whether the line is still empty, so separators are never emitted first. */
        private static boolean append(MutableComponent line, List<String> values, boolean first, ChatFormatting... formats) {
            if (values.isEmpty()) {
                return first;
            }
            if (!first) {
                line.append(Component.literal(", ").withStyle(ChatFormatting.GRAY));
            }
            line.append(Component.literal(String.join(", ", values)).withStyle(formats));
            return false;
        }
    }
}

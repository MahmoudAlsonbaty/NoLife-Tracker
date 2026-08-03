package org.xsaitama1.nolifetracker.command;

import com.mojang.brigadier.context.CommandContext;
import net.minecraft.registry.Registries;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.entity.EntityType;
import net.minecraft.stat.Stats;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Identifier;
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
            new Group("Overworld", DimensionResolver.OVERWORLD, Formatting.GREEN),
            new Group("Nether", DimensionResolver.NETHER, Formatting.DARK_RED),
            new Group("End", DimensionResolver.END, Formatting.LIGHT_PURPLE),
            // Mobs with no resolvable dimension used to be dropped from the challenge entirely.
            // They now count, and land here.
            new Group("Other", null, Formatting.AQUA)
    );

    private StatsDisplay() {
    }

    public static int showHelp(CommandContext<ServerCommandSource> context) {
        ServerCommandSource source = context.getSource();
        source.sendMessage(Text.literal("--- NoLife Tracker ---").formatted(Formatting.GOLD, Formatting.BOLD));

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

    public static int showSummary(CommandContext<ServerCommandSource> context, String playerName) {
        ServerCommandSource source = context.getSource();
        StatsRepository repository = repository(source);
        if (repository == null) {
            return 0;
        }

        UUID uuid = repository.findUuidByName(playerName);
        PlayerStats stats = uuid == null ? null : repository.all().get(uuid);
        if (stats == null) {
            return notFound(source, playerName);
        }

        source.sendMessage(Text.literal("--- " + stats.lastKnownName + " ---")
                .formatted(Formatting.GOLD, Formatting.BOLD));

        int unique = ChallengeRegistry.uniqueKills(uuid, stats);
        line(source, "Challenge", unique + " / " + ChallengeRegistry.total() + " mobs", Formatting.GREEN);
        line(source, "Mob kills", String.valueOf(stats.totalMobKills), Formatting.AQUA);
        line(source, "Player kills", String.valueOf(stats.totalPlayerKills), Formatting.RED);
        line(source, "Deaths", String.valueOf(stats.totalDeaths), Formatting.GRAY);

        // Play time comes from vanilla's own statistics, which are only loaded for online players.
        ServerPlayerEntity online = source.getServer().getPlayerManager().getPlayer(uuid);
        if (online != null) {
            int ticks = online.getStatHandler().getStat(Stats.CUSTOM, Stats.PLAY_TIME);
            line(source, "Play time", TextUtil.formatPlayTime(ticks), Formatting.YELLOW);
        }

        return 1;
    }

    public static int showProgressLeaderboard(CommandContext<ServerCommandSource> context) {
        ServerCommandSource source = context.getSource();
        StatsRepository repository = repository(source);
        if (repository == null) {
            return 0;
        }

        source.sendMessage(Text.literal("--- Top Mob Hunters ---").formatted(Formatting.GOLD, Formatting.BOLD));

        List<Ranked> ranked = rankByProgress(repository);
        if (ranked.isEmpty()) {
            source.sendMessage(Text.literal("Nobody has killed anything yet.").formatted(Formatting.GRAY));
            return 1;
        }

        int total = ChallengeRegistry.total();
        int rank = 1;
        for (Ranked entry : ranked) {
            if (rank > ConfigManager.get().leaderboardSize) {
                break;
            }
            source.sendMessage(Text.literal(rank + ". " + entry.stats.lastKnownName
                    + " - " + entry.score + "/" + total + " mobs").formatted(Formatting.GREEN));
            rank++;
        }
        return 1;
    }

    public static int showDeathLeaderboard(CommandContext<ServerCommandSource> context) {
        ServerCommandSource source = context.getSource();
        StatsRepository repository = repository(source);
        if (repository == null) {
            return 0;
        }

        source.sendMessage(Text.literal("--- Most Deaths ---").formatted(Formatting.GOLD, Formatting.BOLD));

        List<PlayerStats> sorted = new ArrayList<>(repository.all().values());
        sorted.sort(Comparator.comparingInt((PlayerStats stats) -> stats.totalDeaths).reversed());

        int rank = 1;
        for (PlayerStats stats : sorted) {
            if (rank > ConfigManager.get().leaderboardSize) {
                break;
            }
            source.sendMessage(Text.literal(rank + ". " + stats.lastKnownName + " - " + stats.totalDeaths + " deaths")
                    .formatted(Formatting.RED));
            rank++;
        }
        return 1;
    }

    public static int showPlayerDeaths(CommandContext<ServerCommandSource> context, String playerName) {
        return showTally(context, playerName, "Deaths", Formatting.GRAY,
                stats -> stats.deathReasons, "This player hasn't died yet.", false);
    }

    public static int showPlayerPvpKills(CommandContext<ServerCommandSource> context, String playerName) {
        return showTally(context, playerName, "PvP Kills", Formatting.AQUA,
                stats -> stats.killedPlayers, "This player hasn't killed anyone yet.", false);
    }

    public static int showPlayerMobs(CommandContext<ServerCommandSource> context, String playerName) {
        return showTally(context, playerName, "Mob Kills", Formatting.AQUA,
                stats -> stats.killedMobs, "This player hasn't killed any mobs yet.", true);
    }

    public static int showMissing(CommandContext<ServerCommandSource> context, String playerName) {
        ServerCommandSource source = context.getSource();
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

        source.sendMessage(Text.literal("--- " + stats.lastKnownName + " [" + unique + "/" + total + "] ---")
                .formatted(Formatting.GOLD, Formatting.BOLD));

        if (unique >= total && total > 0) {
            source.sendMessage(Text.literal("Every mob in the game has been defeated!")
                    .formatted(Formatting.GOLD, Formatting.BOLD));
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
                source.sendMessage(Text.literal(" "));
            }
            firstGroup = false;

            int done = bucket.killed.size();
            source.sendMessage(Text.literal(group.name + " [" + done + "/"
                    + (done + bucket.remaining.size()) + "]").formatted(group.colour));
            source.sendMessage(bucket.render(group.colour));
        }

        return 1;
    }

    // ------------------------------------------------------------------
    // internals
    // ------------------------------------------------------------------

    private static int showTally(CommandContext<ServerCommandSource> context, String playerName,
                                 String heading, Formatting colour,
                                 java.util.function.Function<PlayerStats, Map<String, Integer>> selector,
                                 String emptyMessage, boolean prettifyKeys) {
        ServerCommandSource source = context.getSource();
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

        source.sendMessage(Text.literal("--- " + stats.lastKnownName + "'s " + heading + suffix + " ---")
                .formatted(Formatting.GOLD));

        if (tally.isEmpty()) {
            source.sendMessage(Text.literal(emptyMessage).formatted(Formatting.GRAY));
            return 1;
        }

        List<Map.Entry<String, Integer>> sorted = new ArrayList<>(tally.entrySet());
        sorted.sort(Map.Entry.<String, Integer>comparingByValue().reversed());

        for (Map.Entry<String, Integer> entry : sorted) {
            String label = prettifyKeys ? TextUtil.prettify(entry.getKey()) : entry.getKey();
            source.sendMessage(Text.literal("- " + label + ": " + entry.getValue()).formatted(colour));
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
            Optional<EntityType<?>> type = Registries.ENTITY_TYPE.getOptionalValue(id);
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

    static StatsRepository repository(ServerCommandSource source) {
        NoLifeTrackerService service = NoLifeTrackerService.get();
        if (service == null) {
            source.sendError(Text.literal("NoLife Tracker is not ready yet."));
            return null;
        }
        return service.stats();
    }

    private static int notFound(ServerCommandSource source, String playerName) {
        source.sendMessage(Text.literal("No stats recorded for '" + playerName + "'.").formatted(Formatting.RED));
        return 0;
    }

    private static void help(ServerCommandSource source, String command, String description) {
        source.sendMessage(Text.literal(command).formatted(Formatting.YELLOW)
                .append(Text.literal(" - " + description).formatted(Formatting.GRAY)));
    }

    private static void line(ServerCommandSource source, String label, String value, Formatting colour) {
        source.sendMessage(Text.literal(label + ": ").formatted(Formatting.GRAY)
                .append(Text.literal(value).formatted(colour)));
    }

    private record Group(String name, Identifier dimension, Formatting colour) {
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

        private MutableText render(Formatting remainingColour) {
            MutableText line = Text.empty();
            boolean first = true;

            first = append(line, remaining, first, remainingColour);
            first = append(line, killed, first, Formatting.GRAY);
            append(line, excluded, first, Formatting.GRAY, Formatting.STRIKETHROUGH);

            return line;
        }

        /** Returns whether the line is still empty, so separators are never emitted first. */
        private static boolean append(MutableText line, List<String> values, boolean first, Formatting... formats) {
            if (values.isEmpty()) {
                return first;
            }
            if (!first) {
                line.append(Text.literal(", ").formatted(Formatting.GRAY));
            }
            line.append(Text.literal(String.join(", ", values)).formatted(formats));
            return false;
        }
    }
}

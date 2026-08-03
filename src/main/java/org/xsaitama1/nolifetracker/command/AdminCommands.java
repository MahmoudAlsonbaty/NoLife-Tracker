package org.xsaitama1.nolifetracker.command;

import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import net.minecraft.command.CommandSource;
import net.minecraft.command.argument.IdentifierArgumentType;
import net.minecraft.registry.Registries;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Identifier;
import org.xsaitama1.nolifetracker.NoLifeTrackerService;
import org.xsaitama1.nolifetracker.challenge.ChallengeRegistry;
import org.xsaitama1.nolifetracker.challenge.MobClassifier;
import org.xsaitama1.nolifetracker.config.ConfigManager;
import org.xsaitama1.nolifetracker.config.DimensionOverridesConfig;
import org.xsaitama1.nolifetracker.config.ExcludedMobsConfig;
import org.xsaitama1.nolifetracker.config.PluginConfig;
import org.xsaitama1.nolifetracker.util.TextUtil;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.BiConsumer;
import java.util.function.Function;

/**
 * Admin-only subcommands.
 *
 * <p>These rewrite the challenge rules for everyone, so they sit behind
 * {@code ADMINS_CHECK} (permission level 3). They previously used
 * {@code MODERATORS_CHECK}, which is level 1 and is routinely handed out to
 * trusted players on a normal server.
 */
public final class AdminCommands {

    private static List<String> cachedEntityIds;

    private static final SuggestionProvider<ServerCommandSource> ENTITY_SUGGESTER =
            (context, builder) -> CommandSource.suggestMatching(entityIds(), builder);

    private static final SuggestionProvider<ServerCommandSource> DIMENSION_SUGGESTER =
            (context, builder) -> CommandSource.suggestMatching(
                    List.of("minecraft:overworld", "minecraft:the_nether", "minecraft:the_end"), builder);

    private AdminCommands() {
    }

    public static LiteralArgumentBuilder<ServerCommandSource> exclude() {
        return CommandManager.literal("exclude")
                .requires(CommandManager.requirePermissionLevel(CommandManager.ADMINS_CHECK))
                .then(CommandManager.argument("mob", IdentifierArgumentType.identifier())
                        .suggests(ENTITY_SUGGESTER)
                        .executes(context -> {
                            context.getSource().sendFeedback(() -> Text.literal(
                                    "Specify true to stop counting this mob, or false to count it again.")
                                    .formatted(Formatting.RED), false);
                            return 0;
                        })
                        .then(CommandManager.argument("excluded", BoolArgumentType.bool())
                                .executes(AdminCommands::setExcluded)));
    }

    public static LiteralArgumentBuilder<ServerCommandSource> editMob() {
        return CommandManager.literal("editMob")
                .requires(CommandManager.requirePermissionLevel(CommandManager.ADMINS_CHECK))
                .then(CommandManager.argument("mob", IdentifierArgumentType.identifier())
                        .suggests(ENTITY_SUGGESTER)
                        // Literal first: Brigadier prefers it over the identifier argument, which
                        // is what the old "Remove" suggestion was trying and failing to be.
                        .then(CommandManager.literal("clear")
                                .executes(AdminCommands::clearDimension))
                        .then(CommandManager.argument("dimension", IdentifierArgumentType.identifier())
                                .suggests(DIMENSION_SUGGESTER)
                                .executes(AdminCommands::setDimension)));
    }

    /**
     * Every setting in {@code config.json}, editable in-game. Each change is written straight
     * back to the file, so the two can never drift apart.
     */
    public static LiteralArgumentBuilder<ServerCommandSource> config() {
        return CommandManager.literal("config")
                .requires(CommandManager.requirePermissionLevel(CommandManager.ADMINS_CHECK))
                .executes(AdminCommands::showConfig)

                .then(textSetting("tabHeader",
                        config -> config.tabHeader, (config, value) -> config.tabHeader = value))
                .then(textSetting("topPlayerLine",
                        config -> config.topPlayerLine, (config, value) -> config.topPlayerLine = value))
                .then(textSetting("tabFooter",
                        config -> config.tabFooter, (config, value) -> config.tabFooter = value))
                .then(booleanSetting("showTopPlayerInTabList",
                        (config, value) -> config.showTopPlayerInTabList = value))
                .then(booleanSetting("announceFirstKills",
                        (config, value) -> config.announceFirstKills = value))
                .then(booleanSetting("globalMobKillAnnouncement",
                        (config, value) -> config.globalMobKillAnnouncement = value))
                .then(booleanSetting("announceNonChallengeKills",
                        (config, value) -> config.announceNonChallengeKills = value))
                .then(booleanSetting("afkTrackingEnabled",
                        (config, value) -> config.afkTrackingEnabled = value))

                .then(numberSetting("tabUpdateSeconds",
                        PluginConfig.MIN_TAB_UPDATE_SECONDS, PluginConfig.MAX_TAB_UPDATE_SECONDS,
                        (config, value) -> config.tabUpdateSeconds = value))
                .then(numberSetting("afkThresholdSeconds",
                        PluginConfig.MIN_AFK_THRESHOLD_SECONDS, PluginConfig.MAX_AFK_THRESHOLD_SECONDS,
                        (config, value) -> config.afkThresholdSeconds = value))
                .then(numberSetting("leaderboardSize",
                        PluginConfig.MIN_LEADERBOARD_SIZE, PluginConfig.MAX_LEADERBOARD_SIZE,
                        (config, value) -> config.leaderboardSize = value))
                .then(numberSetting("autoSaveMinutes",
                        PluginConfig.MIN_AUTO_SAVE_MINUTES, PluginConfig.MAX_AUTO_SAVE_MINUTES,
                        (config, value) -> config.autoSaveMinutes = value));
    }

    public static LiteralArgumentBuilder<ServerCommandSource> reload() {
        return CommandManager.literal("reload")
                .requires(CommandManager.requirePermissionLevel(CommandManager.ADMINS_CHECK))
                .executes(context -> {
                    NoLifeTrackerService service = NoLifeTrackerService.get();
                    if (service == null) {
                        context.getSource().sendError(Text.literal("NoLife Tracker is not ready yet."));
                        return 0;
                    }

                    service.reload();
                    cachedEntityIds = null;
                    feedback(context, "Reloaded config. Challenge set is now "
                            + ChallengeRegistry.total() + " mobs.");
                    return 1;
                });
    }

    /**
     * Reports anything that could make the challenge set wrong: mobs the classifier could
     * not place, and config entries naming entities that do not exist.
     */
    public static LiteralArgumentBuilder<ServerCommandSource> audit() {
        return CommandManager.literal("audit")
                .requires(CommandManager.requirePermissionLevel(CommandManager.MODERATORS_CHECK))
                .executes(context -> {
                    ServerCommandSource source = context.getSource();
                    source.sendMessage(Text.literal("--- NoLife Tracker audit ---")
                            .formatted(Formatting.GOLD, Formatting.BOLD));

                    source.sendMessage(Text.literal("Tracked mobs: ").formatted(Formatting.GRAY)
                            .append(Text.literal(String.valueOf(ChallengeRegistry.total()))
                                    .formatted(Formatting.GREEN)));

                    // Sorted here rather than trusted from all(), which hands back a Set.copyOf
                    // whose iteration order is deliberately unspecified.
                    List<String> excluded = new ArrayList<>(new TreeSet<>(ExcludedMobsConfig.all()));
                    if (excluded.isEmpty()) {
                        source.sendMessage(Text.literal("Excluded: ").formatted(Formatting.GRAY)
                                .append(Text.literal("none").formatted(Formatting.GREEN)));
                    } else {
                        reportList(source, "Excluded (not counted)",
                                excluded, Formatting.YELLOW);
                        source.sendMessage(Text.literal("(restore with /nolifetracker exclude <mob> false)").formatted(Formatting.YELLOW));
                    }

                    Set<String> unclassified = MobClassifier.unclassifiedCandidates(
                            Set.copyOf(ConfigManager.get().forceIncludeMobs));
                    reportList(source, "Unclassified (add to forceIncludeMobs if any is a mob)",
                            List.copyOf(unclassified), Formatting.YELLOW);

                    reportList(source, "Dimension overrides naming unknown entities",
                            DimensionOverridesConfig.unknownEntityIds(), Formatting.RED);
                    reportList(source, "Exclusions naming unknown entities",
                            ExcludedMobsConfig.unknownEntityIds(), Formatting.RED);

                    return 1;
                });
    }

    // ------------------------------------------------------------------

    private static int setExcluded(CommandContext<ServerCommandSource> context) {
        Identifier mob = IdentifierArgumentType.getIdentifier(context, "mob");
        boolean excluded = BoolArgumentType.getBool(context, "excluded");

        if (Registries.ENTITY_TYPE.getOptionalValue(mob).isEmpty()) {
            context.getSource().sendError(Text.literal("No such entity: " + mob));
            return 0;
        }

        boolean changed = excluded
                ? ExcludedMobsConfig.add(mob.toString())
                : ExcludedMobsConfig.remove(mob.toString());

        if (!changed) {
            feedback(context, mob + " was already " + (excluded ? "excluded." : "counted."));
            return 0;
        }

        applyChallengeChange(context);
        feedback(context, mob + (excluded ? " is no longer counted." : " now counts.")
                + " Challenge set is " + ChallengeRegistry.total() + " mobs.");
        return 1;
    }

    private static int setDimension(CommandContext<ServerCommandSource> context) {
        Identifier mob = IdentifierArgumentType.getIdentifier(context, "mob");
        Identifier dimension = IdentifierArgumentType.getIdentifier(context, "dimension");

        if (Registries.ENTITY_TYPE.getOptionalValue(mob).isEmpty()) {
            context.getSource().sendError(Text.literal("No such entity: " + mob));
            return 0;
        }

        DimensionOverridesConfig.put(mob.toString(), dimension.toString());
        applyChallengeChange(context);
        feedback(context, mob + " is now grouped under " + dimension + ".");
        return 1;
    }

    private static int clearDimension(CommandContext<ServerCommandSource> context) {
        Identifier mob = IdentifierArgumentType.getIdentifier(context, "mob");

        if (!DimensionOverridesConfig.remove(mob.toString())) {
            feedback(context, "No dimension override was set for " + mob + ".");
            return 0;
        }

        applyChallengeChange(context);
        feedback(context, "Cleared the dimension override for " + mob + ".");
        return 1;
    }

    /** Prints every setting and its current value, so the file is not the only way to see them. */
    private static int showConfig(CommandContext<ServerCommandSource> context) {
        ServerCommandSource source = context.getSource();
        PluginConfig config = ConfigManager.get();

        source.sendMessage(Text.literal("--- NoLife Tracker config ---").formatted(Formatting.GOLD, Formatting.BOLD));
        source.sendMessage(Text.literal("config/nolifetracker/config.json - /nolifetracker config <setting> <value> to change")
                .formatted(Formatting.DARK_GRAY));

        heading(source, "Tab list");
        setting(source, "tabHeader", config.tabHeader);
        setting(source, "showTopPlayerInTabList", config.showTopPlayerInTabList);
        setting(source, "tabFooter", config.tabFooter);
        setting(source, "tabNameSuffix", config.tabNameSuffix);
        setting(source, "afkSuffix", config.afkSuffix);
        setting(source, "tabUpdateSeconds", config.tabUpdateSeconds);

        heading(source, "Announcements");
        setting(source, "announceFirstKills", config.announceFirstKills);
        setting(source, "globalMobKillAnnouncement", config.globalMobKillAnnouncement);
        setting(source, "announceNonChallengeKills", config.announceNonChallengeKills);

        heading(source, "AFK");
        setting(source, "afkTrackingEnabled", config.afkTrackingEnabled);
        setting(source, "afkThresholdSeconds", config.afkThresholdSeconds);

        heading(source, "Other");
        setting(source, "leaderboardSize", config.leaderboardSize);
        setting(source, "autoSaveMinutes", config.autoSaveMinutes);
        setting(source, "forceIncludeMobs", config.forceIncludeMobs.size() + " mob(s), edit in config.json");

        return 1;
    }

    private static LiteralArgumentBuilder<ServerCommandSource> booleanSetting(
            String name, BiConsumer<PluginConfig, Boolean> setter) {
        return CommandManager.literal(name)
                .executes(context -> describe(context, name))
                .then(CommandManager.argument("value", BoolArgumentType.bool())
                        .executes(context -> {
                            boolean value = BoolArgumentType.getBool(context, "value");
                            setter.accept(ConfigManager.get(), value);
                            return applied(context, name, String.valueOf(value));
                        }));
    }

    private static LiteralArgumentBuilder<ServerCommandSource> numberSetting(
            String name, int min, int max, BiConsumer<PluginConfig, Integer> setter) {
        return CommandManager.literal(name)
                .executes(context -> describe(context, name))
                .then(CommandManager.argument("value", IntegerArgumentType.integer(min, max))
                        .executes(context -> {
                            int value = IntegerArgumentType.getInteger(context, "value");
                            setter.accept(ConfigManager.get(), value);
                            return applied(context, name, String.valueOf(value));
                        }));
    }

    /**
     * A formatted-text setting.
     *
     * <p>Chat cannot carry a real line break, so {@code \n} typed as two characters becomes
     * one. The {@code clear} literal is declared before the greedy argument so Brigadier
     * prefers it; to set the text to the literal word, prefix it with a colour code.
     */
    private static LiteralArgumentBuilder<ServerCommandSource> textSetting(
            String name, Function<PluginConfig, String> getter, BiConsumer<PluginConfig, String> setter) {
        return CommandManager.literal(name)
                .executes(context -> {
                    String current = getter.apply(ConfigManager.get());
                    if (current.isEmpty()) {
                        feedback(context, Text.literal(name + " is empty.").formatted(Formatting.GREEN));
                        return 1;
                    }
                    context.getSource().sendMessage(Text.literal(name + ": ").formatted(Formatting.GRAY)
                            .append(Text.literal(current).formatted(Formatting.WHITE)));
                    context.getSource().sendMessage(Text.literal("shows as: ").formatted(Formatting.GRAY)
                            .append(TextUtil.legacy(current)));
                    return 1;
                })
                .then(CommandManager.literal("clear")
                        .executes(context -> {
                            setter.accept(ConfigManager.get(), "");
                            return applied(context, name, "");
                        }))
                .then(CommandManager.argument("text", StringArgumentType.greedyString())
                        .executes(context -> {
                            String value = StringArgumentType.getString(context, "text").replace("\\n", "\n");
                            setter.accept(ConfigManager.get(), value);
                            return applied(context, name, value);
                        }));
    }

    /** Persists a config change, pushes it to every client, and reports what it looks like. */
    private static int applied(CommandContext<ServerCommandSource> context, String name, String value) {
        ConfigManager.save();

        NoLifeTrackerService service = NoLifeTrackerService.get();
        if (service != null) {
            service.tabList().broadcast();
        }

        if (value.isEmpty()) {
            feedback(context, Text.literal(name + " is now empty.").formatted(Formatting.GREEN));
        } else {
            feedback(context, Text.literal(name + " is now ").formatted(Formatting.GREEN)
                    .append(TextUtil.legacy(value)));
        }
        return 1;
    }

    private static int describe(CommandContext<ServerCommandSource> context, String name) {
        context.getSource().sendError(Text.literal("Give " + name + " a value, e.g. /nolifetracker config " + name + " true"));
        return 0;
    }

    private static void heading(ServerCommandSource source, String text) {
        source.sendMessage(Text.literal(text).formatted(Formatting.YELLOW));
    }

    private static void setting(ServerCommandSource source, String name, Object value) {
        String rendered = String.valueOf(value);
        source.sendMessage(Text.literal("  " + name + ": ").formatted(Formatting.GRAY)
                .append(Text.literal(rendered.isEmpty() ? "(empty)" : rendered).formatted(Formatting.WHITE)));
    }

    private static void applyChallengeChange(CommandContext<ServerCommandSource> context) {
        ChallengeRegistry.refresh();
        ChallengeRegistry.invalidateAll();

        NoLifeTrackerService service = NoLifeTrackerService.get();
        if (service != null) {
            service.tabList().broadcast();
        }
    }

    private static void reportList(ServerCommandSource source, String heading,
                                   List<String> values, Formatting colour) {
        if (values.isEmpty()) {
            return;
        }
        source.sendMessage(Text.literal(heading + " (" + values.size() + "):").formatted(colour));
        source.sendMessage(Text.literal("  " + String.join(", ", values)).formatted(Formatting.GRAY));
    }

    private static void feedback(CommandContext<ServerCommandSource> context, String message) {
        feedback(context, Text.literal(message).formatted(Formatting.GREEN));
    }

    /** Broadcast to other admins, which is why the message is built before the supplier is called. */
    private static void feedback(CommandContext<ServerCommandSource> context, Text message) {
        context.getSource().sendFeedback(() -> message, true);
    }

    /** Cached: this used to walk the whole registry on every keystroke of a tab-completion. */
    private static List<String> entityIds() {
        if (cachedEntityIds == null) {
            cachedEntityIds = Registries.ENTITY_TYPE.getIds().stream()
                    .map(Identifier::toString)
                    .sorted()
                    .toList();
        }
        return cachedEntityIds;
    }
}

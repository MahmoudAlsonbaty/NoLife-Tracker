package org.xsaitama1.nolifetracker.command;

import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.IdentifierArgument;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
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

    private static final SuggestionProvider<CommandSourceStack> ENTITY_SUGGESTER =
            (context, builder) -> SharedSuggestionProvider.suggest(entityIds(), builder);

    private static final SuggestionProvider<CommandSourceStack> DIMENSION_SUGGESTER =
            (context, builder) -> SharedSuggestionProvider.suggest(
                    List.of("minecraft:overworld", "minecraft:the_nether", "minecraft:the_end"), builder);

    private AdminCommands() {
    }

    public static LiteralArgumentBuilder<CommandSourceStack> exclude() {
        return Commands.literal("exclude")
                .requires(Commands.hasPermission(Commands.LEVEL_ADMINS))
                .then(Commands.argument("mob", IdentifierArgument.id())
                        .suggests(ENTITY_SUGGESTER)
                        .executes(context -> {
                            context.getSource().sendSuccess(() -> Component.literal(
                                    "Specify true to stop counting this mob, or false to count it again.")
                                    .withStyle(ChatFormatting.RED), false);
                            return 0;
                        })
                        .then(Commands.argument("excluded", BoolArgumentType.bool())
                                .executes(AdminCommands::setExcluded)));
    }

    public static LiteralArgumentBuilder<CommandSourceStack> editMob() {
        return Commands.literal("editMob")
                .requires(Commands.hasPermission(Commands.LEVEL_ADMINS))
                .then(Commands.argument("mob", IdentifierArgument.id())
                        .suggests(ENTITY_SUGGESTER)
                        // Literal first: Brigadier prefers it over the identifier argument, which
                        // is what the old "Remove" suggestion was trying and failing to be.
                        .then(Commands.literal("clear")
                                .executes(AdminCommands::clearDimension))
                        .then(Commands.argument("dimension", IdentifierArgument.id())
                                .suggests(DIMENSION_SUGGESTER)
                                .executes(AdminCommands::setDimension)));
    }

    /**
     * Every setting in {@code config.json}, editable in-game. Each change is written straight
     * back to the file, so the two can never drift apart.
     */
    public static LiteralArgumentBuilder<CommandSourceStack> config() {
        return Commands.literal("config")
                .requires(Commands.hasPermission(Commands.LEVEL_ADMINS))
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

    public static LiteralArgumentBuilder<CommandSourceStack> reload() {
        return Commands.literal("reload")
                .requires(Commands.hasPermission(Commands.LEVEL_ADMINS))
                .executes(context -> {
                    NoLifeTrackerService service = NoLifeTrackerService.get();
                    if (service == null) {
                        context.getSource().sendFailure(Component.literal("NoLife Tracker is not ready yet."));
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
    public static LiteralArgumentBuilder<CommandSourceStack> audit() {
        return Commands.literal("audit")
                .requires(Commands.hasPermission(Commands.LEVEL_MODERATORS))
                .executes(context -> {
                    CommandSourceStack source = context.getSource();
                    source.sendSystemMessage(Component.literal("--- NoLife Tracker audit ---")
                            .withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD));

                    source.sendSystemMessage(Component.literal("Tracked mobs: ").withStyle(ChatFormatting.GRAY)
                            .append(Component.literal(String.valueOf(ChallengeRegistry.total()))
                                    .withStyle(ChatFormatting.GREEN)));

                    // Sorted here rather than trusted from all(), which hands back a Set.copyOf
                    // whose iteration order is deliberately unspecified.
                    List<String> excluded = new ArrayList<>(new TreeSet<>(ExcludedMobsConfig.all()));
                    if (excluded.isEmpty()) {
                        source.sendSystemMessage(Component.literal("Excluded: ").withStyle(ChatFormatting.GRAY)
                                .append(Component.literal("none").withStyle(ChatFormatting.GREEN)));
                    } else {
                        reportList(source, "Excluded (not counted)",
                                excluded, ChatFormatting.YELLOW);
                        source.sendSystemMessage(Component.literal("(restore with /nolifetracker exclude <mob> false)").withStyle(ChatFormatting.YELLOW));
                    }

                    Set<String> unclassified = MobClassifier.unclassifiedCandidates(
                            Set.copyOf(ConfigManager.get().forceIncludeMobs));
                    reportList(source, "Unclassified (add to forceIncludeMobs if any is a mob)",
                            List.copyOf(unclassified), ChatFormatting.YELLOW);

                    reportList(source, "Dimension overrides naming unknown entities",
                            DimensionOverridesConfig.unknownEntityIds(), ChatFormatting.RED);
                    reportList(source, "Exclusions naming unknown entities",
                            ExcludedMobsConfig.unknownEntityIds(), ChatFormatting.RED);

                    return 1;
                });
    }

    // ------------------------------------------------------------------

    private static int setExcluded(CommandContext<CommandSourceStack> context) {
        Identifier mob = IdentifierArgument.getId(context, "mob");
        boolean excluded = BoolArgumentType.getBool(context, "excluded");

        if (BuiltInRegistries.ENTITY_TYPE.getOptional(mob).isEmpty()) {
            context.getSource().sendFailure(Component.literal("No such entity: " + mob));
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

    private static int setDimension(CommandContext<CommandSourceStack> context) {
        Identifier mob = IdentifierArgument.getId(context, "mob");
        Identifier dimension = IdentifierArgument.getId(context, "dimension");

        if (BuiltInRegistries.ENTITY_TYPE.getOptional(mob).isEmpty()) {
            context.getSource().sendFailure(Component.literal("No such entity: " + mob));
            return 0;
        }

        DimensionOverridesConfig.put(mob.toString(), dimension.toString());
        applyChallengeChange(context);
        feedback(context, mob + " is now grouped under " + dimension + ".");
        return 1;
    }

    private static int clearDimension(CommandContext<CommandSourceStack> context) {
        Identifier mob = IdentifierArgument.getId(context, "mob");

        if (!DimensionOverridesConfig.remove(mob.toString())) {
            feedback(context, "No dimension override was set for " + mob + ".");
            return 0;
        }

        applyChallengeChange(context);
        feedback(context, "Cleared the dimension override for " + mob + ".");
        return 1;
    }

    /** Prints every setting and its current value, so the file is not the only way to see them. */
    private static int showConfig(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        PluginConfig config = ConfigManager.get();

        source.sendSystemMessage(Component.literal("--- NoLife Tracker config ---").withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD));
        source.sendSystemMessage(Component.literal("config/nolifetracker/config.json - /nolifetracker config <setting> <value> to change")
                .withStyle(ChatFormatting.DARK_GRAY));

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

    private static LiteralArgumentBuilder<CommandSourceStack> booleanSetting(
            String name, BiConsumer<PluginConfig, Boolean> setter) {
        return Commands.literal(name)
                .executes(context -> describe(context, name))
                .then(Commands.argument("value", BoolArgumentType.bool())
                        .executes(context -> {
                            boolean value = BoolArgumentType.getBool(context, "value");
                            setter.accept(ConfigManager.get(), value);
                            return applied(context, name, String.valueOf(value));
                        }));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> numberSetting(
            String name, int min, int max, BiConsumer<PluginConfig, Integer> setter) {
        return Commands.literal(name)
                .executes(context -> describe(context, name))
                .then(Commands.argument("value", IntegerArgumentType.integer(min, max))
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
    private static LiteralArgumentBuilder<CommandSourceStack> textSetting(
            String name, Function<PluginConfig, String> getter, BiConsumer<PluginConfig, String> setter) {
        return Commands.literal(name)
                .executes(context -> {
                    String current = getter.apply(ConfigManager.get());
                    if (current.isEmpty()) {
                        feedback(context, Component.literal(name + " is empty.").withStyle(ChatFormatting.GREEN));
                        return 1;
                    }
                    context.getSource().sendSystemMessage(Component.literal(name + ": ").withStyle(ChatFormatting.GRAY)
                            .append(Component.literal(current).withStyle(ChatFormatting.WHITE)));
                    context.getSource().sendSystemMessage(Component.literal("shows as: ").withStyle(ChatFormatting.GRAY)
                            .append(TextUtil.legacy(current)));
                    return 1;
                })
                .then(Commands.literal("clear")
                        .executes(context -> {
                            setter.accept(ConfigManager.get(), "");
                            return applied(context, name, "");
                        }))
                .then(Commands.argument("text", StringArgumentType.greedyString())
                        .executes(context -> {
                            String value = StringArgumentType.getString(context, "text").replace("\\n", "\n");
                            setter.accept(ConfigManager.get(), value);
                            return applied(context, name, value);
                        }));
    }

    /** Persists a config change, pushes it to every client, and reports what it looks like. */
    private static int applied(CommandContext<CommandSourceStack> context, String name, String value) {
        ConfigManager.save();

        NoLifeTrackerService service = NoLifeTrackerService.get();
        if (service != null) {
            service.tabList().broadcast();
        }

        if (value.isEmpty()) {
            feedback(context, Component.literal(name + " is now empty.").withStyle(ChatFormatting.GREEN));
        } else {
            feedback(context, Component.literal(name + " is now ").withStyle(ChatFormatting.GREEN)
                    .append(TextUtil.legacy(value)));
        }
        return 1;
    }

    private static int describe(CommandContext<CommandSourceStack> context, String name) {
        context.getSource().sendFailure(Component.literal("Give " + name + " a value, e.g. /nolifetracker config " + name + " true"));
        return 0;
    }

    private static void heading(CommandSourceStack source, String text) {
        source.sendSystemMessage(Component.literal(text).withStyle(ChatFormatting.YELLOW));
    }

    private static void setting(CommandSourceStack source, String name, Object value) {
        String rendered = String.valueOf(value);
        source.sendSystemMessage(Component.literal("  " + name + ": ").withStyle(ChatFormatting.GRAY)
                .append(Component.literal(rendered.isEmpty() ? "(empty)" : rendered).withStyle(ChatFormatting.WHITE)));
    }

    private static void applyChallengeChange(CommandContext<CommandSourceStack> context) {
        ChallengeRegistry.refresh();
        ChallengeRegistry.invalidateAll();

        NoLifeTrackerService service = NoLifeTrackerService.get();
        if (service != null) {
            service.tabList().broadcast();
        }
    }

    private static void reportList(CommandSourceStack source, String heading,
                                   List<String> values, ChatFormatting colour) {
        if (values.isEmpty()) {
            return;
        }
        source.sendSystemMessage(Component.literal(heading + " (" + values.size() + "):").withStyle(colour));
        source.sendSystemMessage(Component.literal("  " + String.join(", ", values)).withStyle(ChatFormatting.GRAY));
    }

    private static void feedback(CommandContext<CommandSourceStack> context, String message) {
        feedback(context, Component.literal(message).withStyle(ChatFormatting.GREEN));
    }

    /** Broadcast to other admins, which is why the message is built before the supplier is called. */
    private static void feedback(CommandContext<CommandSourceStack> context, Component message) {
        context.getSource().sendSuccess(() -> message, true);
    }

    /** Cached: this used to walk the whole registry on every keystroke of a tab-completion. */
    private static List<String> entityIds() {
        if (cachedEntityIds == null) {
            cachedEntityIds = BuiltInRegistries.ENTITY_TYPE.keySet().stream()
                    .map(Identifier::toString)
                    .sorted()
                    .toList();
        }
        return cachedEntityIds;
    }
}

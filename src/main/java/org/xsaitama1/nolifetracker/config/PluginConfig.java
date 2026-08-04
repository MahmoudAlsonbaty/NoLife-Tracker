package org.xsaitama1.nolifetracker.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.xsaitama1.nolifetracker.challenge.MobClassifier;

import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;

/**
 * The contents of {@code config/nolifetracker/config.json}.
 *
 * <p>Every setting the mod has lives here, so an admin can set the server up by editing one
 * file before the first start rather than discovering options one at a time. The same
 * settings are reachable in-game through {@code /nolifetracker config}; that writes back to this
 * file, so the two never disagree.
 *
 * <p>Field names are the on-disk keys -- renaming one silently resets that setting for
 * every existing server, so a rename needs a {@link #configVersion} bump and a migration.
 */
public class PluginConfig {

    private static final Logger LOGGER = LoggerFactory.getLogger("nolifetracker");

    /**
     * Bumped when a field changes meaning and old files have to be rewritten.
     *
     * <p>2: {@code tabHeader} was split into {@code tabHeader} + {@code topPlayerLine}, so
     * that {@code showTopPlayerInTabList} can hide the leader banner without also taking
     * the server name with it.
     */
    public static final int CURRENT_VERSION = 2;

    // Clamp bounds, public so /nolifetracker config validates against exactly what sanitised() enforces
    // rather than a second copy of the numbers that can drift away from these.
    public static final int MIN_TAB_UPDATE_SECONDS = 1;
    public static final int MAX_TAB_UPDATE_SECONDS = 3600;
    public static final int MIN_AFK_THRESHOLD_SECONDS = 10;
    public static final int MAX_AFK_THRESHOLD_SECONDS = 86_400;
    public static final int MIN_AUTO_SAVE_MINUTES = 1;
    public static final int MAX_AUTO_SAVE_MINUTES = 1440;
    public static final int MIN_LEADERBOARD_SIZE = 1;
    public static final int MAX_LEADERBOARD_SIZE = 100;

    /** Written into the file on every save, so the docs track the mod rather than the file's age. */
    private static final List<String> README = List.of(
            "NoLife Tracker configuration.",
            "Edit this file with the server stopped, or change any of it in-game with",
            "/nolifetracker config <setting> <value>. '/nolifetracker reload' re-reads the file without a restart.",
            "",
            "TEXT FORMATTING",
            "  &-codes set colour and style: &a green, &6 gold, &c red, &7 grey,",
            "  &l bold, &o italic, &n underline, &m strikethrough, &r reset.",
            "  A real line break in a JSON string is written \\n.",
            "",
            "PLACEHOLDERS in tabHeader, topPlayerLine and tabFooter",
            "  %player%     name of the player with the most unique mob kills",
            "  %kills%      that player's unique challenge kills",
            "  %total%      number of mobs in the challenge",
            "  %online%     players currently online",
            "  %max%        player slots on the server",
            "",
            "PLACEHOLDERS in tabNameSuffix (rendered per player, beside their own name)",
            "  %kills%      that player's unique challenge kills",
            "  %total%      number of mobs in the challenge",
            "  %deaths%     times they have died",
            "  %pvpkills%   players they have killed",
            "  %mobkills%   mobs they have killed in total, repeats included",
            "",
            "SETTINGS",
            "  tabHeader                  text above the tab list; your server name line.",
            "  showTopPlayerInTabList     add topPlayerLine underneath tabHeader.",
            "  topPlayerLine              the '#1 <player>' banner. Hidden when the setting",
            "                             above is false, and while nobody has killed anything.",
            "  tabFooter                  text below the tab list. Empty hides it.",
            "  tabNameSuffix              stats shown beside each player's name in the tab",
            "                             list. Empty shows names on their own.",
            "  afkSuffix                  marker added for a player who is AFK.",
            "  tabUpdateSeconds           smallest gap between tab list refreshes. Updates are",
            "                             event-driven; this only rate-limits them.",
            "  announceFirstKills         announce the first time a player kills each mob.",
            "  globalMobKillAnnouncement  true announces that to the whole server, false only",
            "                             to the player who got the kill. Needs the setting",
            "                             above to be true.",
            "  announceNonChallengeKills  also announce mobs that are not part of the",
            "                             challenge, such as excluded ones.",
            "  afkTrackingEnabled         flag players who stop moving as AFK. /nolifetracker afk keeps",
            "                             working either way.",
            "  afkThresholdSeconds        how long they have to stand still first.",
            "  leaderboardSize            rows shown by /nolifetracker leaderboard.",
            "  autoSaveMinutes            how often progress is flushed to disk.",
            "  forceIncludeMobs           mobs to count that Minecraft files as non-spawning.",
            "                             '/nolifetracker audit' lists candidates you may want here.",
            "",
            "OTHER FILES in this folder",
            "  excluded_mobs.json         mobs left out of the challenge (/nolifetracker exclude).",
            "  dimension_overrides.json   which group a mob is listed under (/nolifetracker editMob).",
            "  challenge_mobs.json        generated report of the resolved mob list.",
            "",
            "configVersion is maintained by the mod. Leave it alone."
    );

    /** Documentation, rewritten on every save. Editing it here has no effect. */
    public List<String> _readme = README;

    /** Zero in files written before versioning existed, which is what triggers the migration. */
    public int configVersion = 0;

    // ------------------------------------------------------------------
    // Tab list
    // ------------------------------------------------------------------

    /** Text above the tab list. */
    public String tabHeader = "&a&l! Minecraft SMP !";

    /** Whether {@link #topPlayerLine} is appended to {@link #tabHeader}. */
    public boolean showTopPlayerInTabList = true;

    /** The leader banner, kept apart from the header so it can be hidden on its own. */
    public String topPlayerLine = "&6&l#1 &e%player% &e[&f%kills%&e/&f%total%&e] Mobs Killed";

    /** Text below the tab list. Empty by default. */
    public String tabFooter = "";

    /** Appended to every player's tab list entry. Empty leaves names unadorned. */
    public String tabNameSuffix = " &6[%kills%/%total%] &7| &7☠ %deaths% &7| &c⚔ %pvpkills% &7| &a\uD83D\uDDE1 %mobkills%";

    /** Appended before {@link #tabNameSuffix} for a player who is AFK. */
    public String afkSuffix = " &7&o[AFK]";

    /**
     * Lower bound on how often the tab list may be rebuilt, in seconds. Updates are
     * event-driven -- this only rate-limits them, it does not schedule them.
     */
    public int tabUpdateSeconds = 5;

    // ------------------------------------------------------------------
    // Announcements
    // ------------------------------------------------------------------

    /** Master switch for the "hunted their first ..." message. */
    public boolean announceFirstKills = true;

    /** Announce a player's first kill of a mob to everyone, rather than only to them. */
    public boolean globalMobKillAnnouncement = false;

    /** Whether mobs outside the challenge are announced too. */
    public boolean announceNonChallengeKills = true;

    // ------------------------------------------------------------------
    // AFK
    // ------------------------------------------------------------------

    /** Automatic, movement-based AFK detection. {@code /nolifetracker afk} works regardless. */
    public boolean afkTrackingEnabled = true;

    /** Idle time before a player is flagged AFK. */
    public int afkThresholdSeconds = 120;

    // ------------------------------------------------------------------
    // Commands and storage
    // ------------------------------------------------------------------

    /** Rows shown by {@code /nolifetracker leaderboard}. */
    public int leaderboardSize = 10;

    /** How often progress is flushed to disk in the background. */
    public int autoSaveMinutes = 5;

    /**
     * Mobs to count even though vanilla files them under {@code MobCategory.MISC}.
     * Defaults cover the golems, bosses and summon-only mobs; add to it if a future
     * update introduces a mob that {@code /nolifetracker audit} reports as unclassified.
     */
    public List<String> forceIncludeMobs = new ArrayList<>(new TreeSet<>(MobClassifier.DEFAULT_FORCE_INCLUDE));

    /** Clamps hand-edited values into a workable range so a bad config cannot wedge the server. */
    public PluginConfig sanitised() {
        _readme = README;

        tabHeader = orEmpty(tabHeader);
        topPlayerLine = orEmpty(topPlayerLine);
        tabFooter = orEmpty(tabFooter);
        tabNameSuffix = orEmpty(tabNameSuffix);
        afkSuffix = orEmpty(afkSuffix);

        migrate();

        tabUpdateSeconds = clamp(tabUpdateSeconds, MIN_TAB_UPDATE_SECONDS, MAX_TAB_UPDATE_SECONDS);
        afkThresholdSeconds = clamp(afkThresholdSeconds, MIN_AFK_THRESHOLD_SECONDS, MAX_AFK_THRESHOLD_SECONDS);
        autoSaveMinutes = clamp(autoSaveMinutes, MIN_AUTO_SAVE_MINUTES, MAX_AUTO_SAVE_MINUTES);
        leaderboardSize = clamp(leaderboardSize, MIN_LEADERBOARD_SIZE, MAX_LEADERBOARD_SIZE);

        // Union rather than replace, so a mod update that identifies another MISC-filed mob
        // reaches servers that already have a config file. To stop counting one of these,
        // use /nolifetracker exclude -- removing it here just puts it back on the next start.
        TreeSet<String> merged = new TreeSet<>(MobClassifier.DEFAULT_FORCE_INCLUDE);
        if (forceIncludeMobs != null) {
            merged.addAll(forceIncludeMobs);
        }
        forceIncludeMobs = new ArrayList<>(merged);

        return this;
    }

    /**
     * Brings a file written by an older version up to {@link #CURRENT_VERSION}.
     *
     * <p>Before version 2 the header was one string holding both the server name and the
     * leader banner, which is why turning {@code showTopPlayerInTabList} off blanked the
     * whole header. Everything up to the first line break stays as the header; the rest
     * becomes {@link #topPlayerLine}, which is exactly how the old default -- and every
     * custom header that followed its shape -- was laid out.
     */
    private void migrate() {
        if (configVersion >= CURRENT_VERSION) {
            return;
        }

        if (containsLeaderPlaceholder(tabHeader)) {
            int lineBreak = tabHeader.indexOf('\n');
            if (lineBreak >= 0) {
                topPlayerLine = tabHeader.substring(lineBreak + 1);
                tabHeader = tabHeader.substring(0, lineBreak);
            } else {
                topPlayerLine = tabHeader;
                tabHeader = "";
            }
            LOGGER.info("Split the old combined tab header into tabHeader ({}) and topPlayerLine ({}).",
                    tabHeader, topPlayerLine);
        }

        configVersion = CURRENT_VERSION;
    }

    private static boolean containsLeaderPlaceholder(String text) {
        return text.contains("%player%") || text.contains("%kills%") || text.contains("%total%");
    }

    private static String orEmpty(String value) {
        return value == null ? "" : value;
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}

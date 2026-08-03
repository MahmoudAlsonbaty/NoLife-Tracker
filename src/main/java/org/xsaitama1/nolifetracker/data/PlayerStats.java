package org.xsaitama1.nolifetracker.data;

import java.util.HashMap;
import java.util.Map;

/**
 * One player's saved progress. Serialised directly to JSON, so field names are the
 * on-disk format -- renaming one is a data migration, not a refactor.
 *
 * <p>Play time deliberately lives nowhere in here. Vanilla already tracks it per player
 * under {@code Stats.CUSTOM / Stats.PLAY_TIME} and persists it in {@code world/stats/}, so
 * duplicating it only created state that could drift or be lost on an unclean shutdown.
 */
public class PlayerStats {

    public String lastKnownName = "Unknown";

    public int totalDeaths = 0;
    public int totalMobKills = 0;
    public int totalPlayerKills = 0;

    /** Epoch millis of the most recent first-of-its-kind kill; breaks leaderboard ties in favour of whoever got there first. */
    public long lastKillTime = 0L;

    public Map<String, Integer> deathReasons = new HashMap<>();

    /** Bare mob id path (e.g. {@code zombie_villager}) to number of kills. */
    public Map<String, Integer> killedMobs = new HashMap<>();

    public Map<String, Integer> killedPlayers = new HashMap<>();

    /**
     * Gson leaves any field absent from the JSON as null, including the maps. Hand-edited
     * or partially written files are therefore normalised here rather than at every use site.
     */
    public PlayerStats sanitised() {
        if (lastKnownName == null) {
            lastKnownName = "Unknown";
        }
        if (deathReasons == null) {
            deathReasons = new HashMap<>();
        }
        if (killedMobs == null) {
            killedMobs = new HashMap<>();
        }
        if (killedPlayers == null) {
            killedPlayers = new HashMap<>();
        }
        return this;
    }
}

package org.xsaitama1.nolifetracker.config;

/** Holds the live {@link PluginConfig} and mediates reads and writes of it. */
public final class ConfigManager {

    private static PluginConfig config = new PluginConfig();

    private ConfigManager() {
    }

    public static PluginConfig get() {
        return config;
    }

    public static void load() {
        config = JsonConfigs
                .load(NoLifeTrackerPaths.config(), PluginConfig.class, PluginConfig::new, "config")
                .sanitised();

        // Rewrite immediately so fields added by a mod update appear in the file with their
        // defaults, and so any clamped value is reflected on disk rather than silently differing.
        save();
    }

    public static void save() {
        JsonConfigs.save(NoLifeTrackerPaths.config(), config, "config");
    }
}

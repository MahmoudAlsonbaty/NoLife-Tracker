package org.xsaitama1.nolifetracker;

import net.fabricmc.api.ModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.xsaitama1.nolifetracker.command.NoLifeCommandRegistrar;
import org.xsaitama1.nolifetracker.config.ConfigManager;
import org.xsaitama1.nolifetracker.config.NoLifeTrackerPaths;
import org.xsaitama1.nolifetracker.listener.CombatListener;
import org.xsaitama1.nolifetracker.listener.LifecycleListener;

/**
 * Entry point. Registers listeners only -- everything with a server lifetime is built in
 * {@link NoLifeTrackerService} once a server actually starts.
 */
public class NoLifeTracker implements ModInitializer {

    public static final String MOD_ID = "nolifetracker";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    @Override
    public void onInitialize() {
        // Written here rather than only at server start so config.json exists -- fully
        // populated and documented -- after the first launch, whether or not a world ever
        // loaded. Only the plain config is safe this early; the mob files need the entity
        // registry, so they stay in NoLifeTrackerService.start().
        NoLifeTrackerPaths.migrateLegacyConfigs();
        ConfigManager.load();

        LifecycleListener.register();
        CombatListener.register();
        NoLifeCommandRegistrar.register();

        LOGGER.info("NoLife Tracker initialised.");
    }
}

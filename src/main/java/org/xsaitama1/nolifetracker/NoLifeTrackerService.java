package org.xsaitama1.nolifetracker;

import net.minecraft.server.MinecraftServer;
import org.xsaitama1.nolifetracker.challenge.ChallengeRegistry;
import org.xsaitama1.nolifetracker.config.ConfigManager;
import org.xsaitama1.nolifetracker.config.DimensionOverridesConfig;
import org.xsaitama1.nolifetracker.config.ExcludedMobsConfig;
import org.xsaitama1.nolifetracker.config.NoLifeTrackerPaths;
import org.xsaitama1.nolifetracker.data.StatsRepository;
import org.xsaitama1.nolifetracker.tab.AfkTracker;
import org.xsaitama1.nolifetracker.tab.TabListManager;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * Everything with a lifetime tied to a running server, in one place.
 *
 * <p>Replaces the previous set of static fields that were reassigned from inside the tick
 * loop and never cleared, which quietly kept a dead server object alive after leaving a
 * single-player world.
 *
 * <p>Held statically so mixins can reach it, but null between servers -- callers must check.
 */
public final class NoLifeTrackerService {

    private static volatile NoLifeTrackerService instance;

    private final MinecraftServer server;
    private final StatsRepository stats;
    private final TabListManager tabList;
    private final AfkTracker afk;

    private final List<DelayedTask> delayedTasks = new ArrayList<>();
    private int ticksSinceAutoSave = 0;

    private NoLifeTrackerService(MinecraftServer server) {
        this.server = server;
        this.stats = new StatsRepository(server);
        this.tabList = new TabListManager(server, stats);
        this.afk = new AfkTracker(this.tabList::markDirty);
    }

    /** Null when no server is running. */
    public static NoLifeTrackerService get() {
        return instance;
    }

    public static void start(MinecraftServer server) {
        NoLifeTrackerPaths.migrateLegacyConfigs();
        ConfigManager.load();
        DimensionOverridesConfig.load();
        ExcludedMobsConfig.load();

        NoLifeTrackerPaths.migrateLegacyStats(server);

        NoLifeTrackerService service = new NoLifeTrackerService(server);
        service.stats.load();
        ChallengeRegistry.rebuild(server);

        instance = service;
    }

    public static void stop() {
        NoLifeTrackerService current = instance;
        instance = null;
        if (current != null) {
            current.stats.shutdown();
        }
    }

    public MinecraftServer server() {
        return server;
    }

    public StatsRepository stats() {
        return stats;
    }

    public TabListManager tabList() {
        return tabList;
    }

    public AfkTracker afk() {
        return afk;
    }

    /** Re-reads config from disk and rebuilds the challenge set. */
    public void reload() {
        ConfigManager.load();
        DimensionOverridesConfig.load();
        ExcludedMobsConfig.load();
        ChallengeRegistry.rebuild(server);
        tabList.broadcast();
    }

    /**
     * Runs {@code task} on the server thread after a delay. Used instead of parking a
     * thread pool worker on {@link Thread#sleep}, which is what the join handler used to do.
     */
    public void runIn(int ticks, Runnable task) {
        delayedTasks.add(new DelayedTask(ticks, task));
    }

    public void tick() {
        runDelayedTasks();

        ticksSinceAutoSave++;
        if (ticksSinceAutoSave >= ConfigManager.get().autoSaveMinutes * 60 * 20) {
            stats.saveAsync();
            ticksSinceAutoSave = 0;
        }

        afk.tick(server);
        tabList.tick();
    }

    private void runDelayedTasks() {
        if (delayedTasks.isEmpty()) {
            return;
        }

        // Collect first, then run: a task is free to schedule another one without
        // invalidating the iterator.
        List<Runnable> due = new ArrayList<>();
        for (Iterator<DelayedTask> it = delayedTasks.iterator(); it.hasNext(); ) {
            DelayedTask task = it.next();
            if (--task.remainingTicks <= 0) {
                due.add(task.action);
                it.remove();
            }
        }
        due.forEach(Runnable::run);
    }

    private static final class DelayedTask {
        private int remainingTicks;
        private final Runnable action;

        private DelayedTask(int remainingTicks, Runnable action) {
            this.remainingTicks = remainingTicks;
            this.action = action;
        }
    }
}

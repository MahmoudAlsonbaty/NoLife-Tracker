package org.xsaitama1.nolifetracker.listener;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.server.level.ServerPlayer;
import org.xsaitama1.nolifetracker.NoLifeTrackerService;
import org.xsaitama1.nolifetracker.challenge.ChallengeRegistry;
import org.xsaitama1.nolifetracker.data.PlayerStats;

import java.util.UUID;

/** Server start/stop, the tick loop, and player join/leave. */
public final class LifecycleListener {

    /** Long enough for a joining client to have finished loading before it is sent a header. */
    private static final int JOIN_HEADER_DELAY_TICKS = 20;

    private LifecycleListener() {
    }

    public static void register() {
        ServerLifecycleEvents.SERVER_STARTED.register(NoLifeTrackerService::start);

        ServerLifecycleEvents.SERVER_STOPPING.register(server -> NoLifeTrackerService.stop());

        ServerTickEvents.END_SERVER_TICK.register(server -> {
            NoLifeTrackerService service = NoLifeTrackerService.get();
            if (service != null) {
                service.tick();
            }
        });

        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            NoLifeTrackerService service = NoLifeTrackerService.get();
            if (service == null) {
                return;
            }

            ServerPlayer player = handler.getPlayer();
            PlayerStats stats = service.stats().get(player.getUUID());
            stats.lastKnownName = player.getName().getString();

            ChallengeRegistry.invalidate(player.getUUID());
            service.runIn(JOIN_HEADER_DELAY_TICKS, service.tabList()::broadcast);
        });

        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            NoLifeTrackerService service = NoLifeTrackerService.get();
            if (service == null || handler.getPlayer() == null) {
                return;
            }

            UUID uuid = handler.getPlayer().getUUID();
            service.afk().forget(uuid);
            service.stats().saveAsync();
            service.tabList().markDirty();
        });
    }
}

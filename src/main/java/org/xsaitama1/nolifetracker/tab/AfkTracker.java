package org.xsaitama1.nolifetracker.tab;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.Vec3d;
import org.xsaitama1.nolifetracker.config.ConfigManager;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Flags players who have not moved for a while.
 *
 * <p>Detection is position-based, so a player who is mining or fishing without moving will
 * still be marked AFK. Toggling it by hand with {@code /nolifetracker afk} pins the state until the
 * player toggles it back.
 */
public final class AfkTracker {

    private static final int CHECK_INTERVAL_TICKS = 20;

    private final Set<UUID> afk = new HashSet<>();
    private final Set<UUID> manual = new HashSet<>();
    private final Map<UUID, Vec3d> lastPositions = new HashMap<>();
    private final Map<UUID, Long> lastMovedAt = new HashMap<>();

    private final Runnable onStateChanged;
    private int tickCounter = 0;

    public AfkTracker(Runnable onStateChanged) {
        this.onStateChanged = onStateChanged;
    }

    public boolean isAfk(UUID playerUuid) {
        return afk.contains(playerUuid);
    }

    /** Player-initiated toggle; stays put until they toggle it back. */
    public boolean toggleManual(UUID playerUuid) {
        boolean nowAfk;
        if (afk.remove(playerUuid)) {
            manual.remove(playerUuid);
            nowAfk = false;
        } else {
            afk.add(playerUuid);
            manual.add(playerUuid);
            nowAfk = true;
        }

        onStateChanged.run();
        return nowAfk;
    }

    public void forget(UUID playerUuid) {
        afk.remove(playerUuid);
        manual.remove(playerUuid);
        lastPositions.remove(playerUuid);
        lastMovedAt.remove(playerUuid);
    }

    public void tick(MinecraftServer server) {
        if (++tickCounter % CHECK_INTERVAL_TICKS != 0) {
            return;
        }

        if (!ConfigManager.get().afkTrackingEnabled) {
            // Drop flags this tracker set so switching detection off takes effect at once,
            // while leaving anyone who chose it with /nolifetracker afk exactly where they are.
            if (afk.retainAll(manual)) {
                onStateChanged.run();
            }
            return;
        }

        long now = System.currentTimeMillis();
        long thresholdMillis = ConfigManager.get().afkThresholdSeconds * 1000L;
        boolean changed = false;

        for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
            UUID uuid = player.getUuid();
            Vec3d position = new Vec3d(player.getX(), player.getY(), player.getZ());
            Vec3d previous = lastPositions.put(uuid, position);

            if (previous == null) {
                lastMovedAt.put(uuid, now);
                continue;
            }

            if (previous.squaredDistanceTo(position) > 0.0001) {
                lastMovedAt.put(uuid, now);

                // Movement clears automatic AFK, but not a state the player set themselves.
                if (afk.contains(uuid) && !manual.contains(uuid)) {
                    afk.remove(uuid);
                    player.sendMessage(Text.literal("You are no longer AFK.")
                            .formatted(Formatting.GRAY), false);
                    changed = true;
                }
                continue;
            }

            long idleSince = lastMovedAt.getOrDefault(uuid, now);
            if (now - idleSince >= thresholdMillis && !afk.contains(uuid)) {
                afk.add(uuid);
                player.sendMessage(Text.literal("You are now AFK.")
                        .formatted(Formatting.GRAY), false);
                changed = true;
            }
        }

        if (changed) {
            onStateChanged.run();
        }
    }
}

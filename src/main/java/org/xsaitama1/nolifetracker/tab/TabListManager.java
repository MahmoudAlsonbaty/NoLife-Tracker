package org.xsaitama1.nolifetracker.tab;

import net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundTabListPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.xsaitama1.nolifetracker.challenge.ChallengeRegistry;
import org.xsaitama1.nolifetracker.config.ConfigManager;
import org.xsaitama1.nolifetracker.config.PluginConfig;
import org.xsaitama1.nolifetracker.data.PlayerStats;
import org.xsaitama1.nolifetracker.data.StatsRepository;
import org.xsaitama1.nolifetracker.util.TextUtil;

import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Pushes tab list updates when something has actually changed.
 *
 * <p>The old design broadcast on a fixed timer whether or not anything was different,
 * which was only necessary because the tab name embedded a live-ticking play time. With
 * that gone, nothing in the display changes more than a few times an hour, so a dirty
 * flag plus a rate limit replaces the timer entirely.
 */
public final class TabListManager {

    private final MinecraftServer server;
    private final StatsRepository stats;

    private boolean dirty = false;
    private int ticksSinceBroadcast = 0;

    public TabListManager(MinecraftServer server, StatsRepository stats) {
        this.server = server;
        this.stats = stats;
    }

    /** Something changed that the tab list shows; it will go out at the next allowed moment. */
    public void markDirty() {
        dirty = true;
    }

    public void tick() {
        ticksSinceBroadcast++;
        if (!dirty) {
            return;
        }
        if (ticksSinceBroadcast < ConfigManager.get().tabUpdateSeconds * 20) {
            return;
        }

        broadcast();
        dirty = false;
        ticksSinceBroadcast = 0;
    }

    /** Sends immediately, bypassing the rate limit. Used on join and after an admin change. */
    public void broadcast() {
        List<ServerPlayer> players = server.getPlayerList().getPlayers();
        if (players.isEmpty()) {
            return;
        }

        server.getPlayerList().broadcastAll(new ClientboundPlayerInfoUpdatePacket(
                EnumSet.of(ClientboundPlayerInfoUpdatePacket.Action.UPDATE_DISPLAY_NAME), players));

        sendHeader(players);
    }

    private void sendHeader(List<ServerPlayer> players) {
        PluginConfig config = ConfigManager.get();

        // Null on a fresh server, where nobody has killed anything yet. The old code called
        // getFirst() unconditionally and threw NoSuchElementException there.
        Map.Entry<UUID, PlayerStats> leader = findLeader();

        String header = config.tabHeader;
        if (config.showTopPlayerInTabList && !config.topPlayerLine.isEmpty() && leader != null) {
            header = header.isEmpty() ? config.topPlayerLine : header + "\n" + config.topPlayerLine;
        }

        // Sent unconditionally, including when everything resolves to empty. A client keeps
        // displaying the last header it was handed until another one arrives, so turning
        // showTopPlayerInTabList off has to actively clear the banner -- simply declining to
        // send, which is what this used to do, left the old text on screen indefinitely.
        ClientboundTabListPacket packet = new ClientboundTabListPacket(
                TextUtil.legacy(render(header, leader, players.size())),
                TextUtil.legacy(render(config.tabFooter, leader, players.size())));

        for (ServerPlayer player : players) {
            player.connection.send(packet);
        }
    }

    /** Substitutes the header placeholders. A missing leader renders as a neutral placeholder. */
    private String render(String template, Map.Entry<UUID, PlayerStats> leader, int online) {
        if (template.isEmpty()) {
            return template;
        }

        return template
                .replace("%player%", leader == null ? "nobody" : leader.getValue().lastKnownName)
                .replace("%kills%", String.valueOf(
                        leader == null ? 0 : ChallengeRegistry.uniqueKills(leader.getKey(), leader.getValue())))
                .replace("%total%", String.valueOf(ChallengeRegistry.total()))
                .replace("%online%", String.valueOf(online))
                .replace("%max%", String.valueOf(server.getPlayerList().getMaxPlayers()));
    }

    /**
     * One pass to find the top player. This used to sort every player who had ever joined,
     * recomputing each side's kill count inside the comparator, just to read element zero.
     */
    private Map.Entry<UUID, PlayerStats> findLeader() {
        Map.Entry<UUID, PlayerStats> best = null;
        int bestKills = -1;

        for (Map.Entry<UUID, PlayerStats> entry : stats.all().entrySet()) {
            int kills = ChallengeRegistry.uniqueKills(entry.getKey(), entry.getValue());

            boolean better = kills > bestKills
                    || (kills == bestKills && best != null
                    && entry.getValue().lastKillTime < best.getValue().lastKillTime);

            if (better) {
                best = entry;
                bestKills = kills;
            }
        }

        return best;
    }
}

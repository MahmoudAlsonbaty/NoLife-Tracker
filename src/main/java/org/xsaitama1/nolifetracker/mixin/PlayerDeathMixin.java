package org.xsaitama1.nolifetracker.mixin;

import net.minecraft.entity.damage.DamageSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.xsaitama1.nolifetracker.NoLifeTrackerService;
import org.xsaitama1.nolifetracker.data.PlayerStats;

/** Records how a player died, grouped by vanilla's own death message. */
@Mixin(ServerPlayerEntity.class)
public class PlayerDeathMixin {

    /**
     * Death messages embed killer and item names, so the number of distinct reasons is
     * unbounded on a long-running server. Past this many, new phrasings are folded into
     * a single bucket rather than growing the save file forever.
     */
    @Unique
    private static final int NOLIFETRACKER$MAX_REASONS = 200;

    @Inject(method = "onDeath", at = @At("HEAD"))
    private void nolifetracker$recordDeath(DamageSource damageSource, CallbackInfo ci) {
        NoLifeTrackerService service = NoLifeTrackerService.get();
        if (service == null) {
            return;
        }

        ServerPlayerEntity player = (ServerPlayerEntity) (Object) this;
        PlayerStats stats = service.stats().get(player.getUuid());

        stats.totalDeaths++;
        stats.lastKnownName = player.getName().getString();

        String reason = nolifetracker$deathReason(player);
        if (stats.deathReasons.size() >= NOLIFETRACKER$MAX_REASONS && !stats.deathReasons.containsKey(reason)) {
            reason = "Other";
        }
        stats.deathReasons.merge(reason, 1, Integer::sum);

        // No save here on purpose. Deaths are frequent and unbounded, and every save
        // serialises the whole map; the periodic autosave, disconnect and shutdown
        // flushes cover this without putting that cost on the death path.
        service.tabList().markDirty();
    }

    /**
     * Vanilla's message starts with the player's name. Strip it so entries group by cause.
     * The message uses the display name, which a team prefix or a nickname mod can make
     * differ from the plain name, so both are tried.
     */
    @Unique
    private static String nolifetracker$deathReason(ServerPlayerEntity player) {
        String message = player.getDamageTracker().getDeathMessage().getString();

        Text displayName = player.getDisplayName();
        String[] prefixes = {
                displayName == null ? null : displayName.getString(),
                player.getName().getString()
        };

        for (String prefix : prefixes) {
            if (prefix != null && !prefix.isEmpty() && message.startsWith(prefix)) {
                message = message.substring(prefix.length()).trim();
                break;
            }
        }

        if (message.isEmpty()) {
            return "Unknown";
        }
        return Character.toUpperCase(message.charAt(0)) + message.substring(1);
    }
}

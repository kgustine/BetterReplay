package me.justindevb.replay.playback;

import me.justindevb.replay.Replay;
import me.justindevb.replay.config.ReplayConfigSetting;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class ReplayViewerStateManager implements Listener {

    private final Replay replay;
    private final Map<UUID, ReplayViewerState> pendingRestores = new ConcurrentHashMap<>();
    private final Set<UUID> activeVanishedViewers = ConcurrentHashMap.newKeySet();

    public ReplayViewerStateManager(Replay replay) {
        this.replay = replay;
    }

    public ReplayViewerState capture(Player viewer) {
        return new ReplayViewerState(
                viewer.getLocation().clone(),
                viewer.getGameMode(),
                viewer.getAllowFlight(),
                viewer.isFlying());
    }

    public ReplayViewerState applyReplaySafety(Player viewer, ReplayViewerState state) {
        if (viewer == null) {
            return state;
        }

        ReplayViewerState updatedState = state;

        if (ReplayConfigSetting.PLAYBACK_VANISH_VIEWER.getBoolean(replay.getConfig())) {
            vanishViewer(viewer);
            if (updatedState != null) {
                updatedState = updatedState.withReplayVanishApplied(true);
            }
        }

        if (resolveSafetyMode() == ReplayViewerSafetyMode.OFF) {
            return updatedState;
        }

        viewer.setGameMode(GameMode.CREATIVE);
        viewer.setAllowFlight(true);
        viewer.setFlying(false);
        viewer.setFallDistance(0.0F);
        return updatedState;
    }

    public void restoreViewerState(Player viewer, ReplayViewerState state) {
        if (viewer == null || state == null) {
            return;
        }

        if (ReplayConfigSetting.PLAYBACK_RESTORE_VIEWER_LOCATION_ON_STOP.getBoolean(replay.getConfig())) {
            Location returnLocation = state.returnLocation();
            if (returnLocation != null && returnLocation.getWorld() != null) {
                viewer.setFallDistance(0.0F);
                replay.getFoliaLib().getScheduler().teleportAsync(viewer, returnLocation.clone());
            }
        }

        if (ReplayConfigSetting.PLAYBACK_RESTORE_VIEWER_GAMEMODE_ON_STOP.getBoolean(replay.getConfig())) {
            viewer.setGameMode(state.originalGameMode());
        }

        if (ReplayConfigSetting.PLAYBACK_RESTORE_VIEWER_FLIGHT_ON_STOP.getBoolean(replay.getConfig())) {
            viewer.setAllowFlight(state.allowFlight());
            viewer.setFlying(state.flying());
        }

        if (state.replayVanishApplied()) {
            restoreViewerVisibility(viewer);
        }
        viewer.setFallDistance(0.0F);
    }

    public void queuePendingRestore(UUID viewerId, ReplayViewerState state) {
        if (viewerId == null || state == null) {
            return;
        }
        activeVanishedViewers.remove(viewerId);
        pendingRestores.put(viewerId, state);
    }

    public void clearPendingRestore(UUID viewerId) {
        if (viewerId == null) {
            return;
        }
        pendingRestores.remove(viewerId);
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        hideActiveReplayViewersFrom(player);

        ReplayViewerState state = pendingRestores.remove(player.getUniqueId());
        if (state == null) {
            return;
        }
        if (!ReplayConfigSetting.PLAYBACK_RESTORE_VIEWER_STATE_ON_REJOIN.getBoolean(replay.getConfig())) {
            return;
        }

        // Join events already run on the server thread, so the pending restore can be applied immediately.
        restoreViewerState(player, state);
    }

    private ReplayViewerSafetyMode resolveSafetyMode() {
        return ReplayViewerSafetyMode.fromConfiguredValue(
                ReplayConfigSetting.PLAYBACK_VIEWER_SAFETY_MODE.getString(replay.getConfig()));
    }

    private void vanishViewer(Player viewer) {
        activeVanishedViewers.add(viewer.getUniqueId());
        for (Player onlinePlayer : Bukkit.getOnlinePlayers()) {
            if (onlinePlayer.equals(viewer)) {
                continue;
            }
            onlinePlayer.hidePlayer(replay, viewer);
        }
    }

    private void restoreViewerVisibility(Player viewer) {
        activeVanishedViewers.remove(viewer.getUniqueId());

        for (Player onlinePlayer : Bukkit.getOnlinePlayers()) {
            if (onlinePlayer.equals(viewer)) {
                continue;
            }
            onlinePlayer.showPlayer(replay, viewer);
        }
    }

    private void hideActiveReplayViewersFrom(Player player) {
        for (UUID viewerId : activeVanishedViewers) {
            if (viewerId.equals(player.getUniqueId())) {
                continue;
            }

            Player vanishedViewer = Bukkit.getPlayer(viewerId);
            if (vanishedViewer != null && vanishedViewer.isOnline()) {
                player.hidePlayer(replay, vanishedViewer);
            }
        }
    }
}
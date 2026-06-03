package me.justindevb.replay.playback;

import me.justindevb.replay.Replay;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerJoinEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.MockedStatic;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ReplayViewerStateManagerTest {

    @Mock private Replay replay;
    @Mock private FileConfiguration config;
    @Mock private Player viewer;
    @Mock private Player otherPlayer;
    @Mock private Player lateJoiner;
    @Mock private PlayerJoinEvent joinEvent;
    @Mock private Location currentLocation;
    @Mock private Location currentLocationClone;
    @Mock private Location returnLocation;
    @Mock private Location returnLocationClone;
    @Mock private World world;

    private ReplayViewerStateManager manager;

    @BeforeEach
    void setUp() {
        manager = new ReplayViewerStateManager(replay);
    }

    @Test
    void capture_clonesLocationAndPreservesFlightState() {
        when(viewer.getLocation()).thenReturn(currentLocation);
        when(currentLocation.clone()).thenReturn(currentLocationClone);
        when(viewer.getGameMode()).thenReturn(GameMode.SURVIVAL);
        when(viewer.getAllowFlight()).thenReturn(true);
        when(viewer.isFlying()).thenReturn(false);

        ReplayViewerState state = manager.capture(viewer);

        assertSame(currentLocationClone, state.returnLocation());
        assertEquals(GameMode.SURVIVAL, state.originalGameMode());
        assertEquals(true, state.allowFlight());
        assertEquals(false, state.flying());
        assertEquals(false, state.replayVanishApplied());
    }

    @Test
    void applyReplaySafety_creativeModeConfigured_switchesViewerToCreative() {
        when(replay.getConfig()).thenReturn(config);
        when(config.getBoolean("Playback.Vanish-Viewer", true)).thenReturn(false);
        when(config.getString("Playback.Viewer-Safety-Mode", "creative")).thenReturn("creative");

        ReplayViewerState updatedState = manager.applyReplaySafety(
            viewer,
            new ReplayViewerState(returnLocation, GameMode.SURVIVAL, false, false));

        verify(viewer).setGameMode(GameMode.CREATIVE);
        verify(viewer).setAllowFlight(true);
        verify(viewer).setFlying(false);
        verify(viewer).setFallDistance(0.0F);
        assertEquals(false, updatedState.replayVanishApplied());
    }

    @Test
    void applyReplaySafety_offConfigured_leavesViewerUntouched() {
        when(replay.getConfig()).thenReturn(config);
        when(config.getBoolean("Playback.Vanish-Viewer", true)).thenReturn(false);
        when(config.getString("Playback.Viewer-Safety-Mode", "creative")).thenReturn("off");

        manager.applyReplaySafety(viewer, new ReplayViewerState(returnLocation, GameMode.SURVIVAL, false, false));

        verify(viewer, never()).setGameMode(GameMode.CREATIVE);
        verify(viewer, never()).setAllowFlight(true);
        verify(viewer, never()).setFlying(false);
    }

    @Test
    void applyReplaySafety_vanishEnabled_hidesViewerFromOtherOnlinePlayers() {
        when(replay.getConfig()).thenReturn(config);
        when(config.getBoolean("Playback.Vanish-Viewer", true)).thenReturn(true);
        when(config.getString("Playback.Viewer-Safety-Mode", "creative")).thenReturn("off");
        when(viewer.getUniqueId()).thenReturn(UUID.randomUUID());

        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(Bukkit::getOnlinePlayers).thenReturn(List.of(viewer, otherPlayer));

                ReplayViewerState updatedState = manager.applyReplaySafety(
                    viewer,
                    new ReplayViewerState(returnLocation, GameMode.SURVIVAL, false, false));

                assertEquals(true, updatedState.replayVanishApplied());
        }

        verify(otherPlayer).hidePlayer(replay, viewer);
    }

    @Test
    void restoreViewerState_restoresLocationModeAndFlight() {
        when(replay.getConfig()).thenReturn(config);
        when(config.getBoolean("Playback.Restore-Viewer-Location-On-Stop", true)).thenReturn(true);
        when(config.getBoolean("Playback.Restore-Viewer-GameMode-On-Stop", true)).thenReturn(true);
        when(config.getBoolean("Playback.Restore-Viewer-Flight-On-Stop", true)).thenReturn(true);
        when(returnLocation.getWorld()).thenReturn(world);
        when(returnLocation.clone()).thenReturn(returnLocationClone);

        ReplayViewerState state = new ReplayViewerState(returnLocation, GameMode.SURVIVAL, false, false);

        manager.restoreViewerState(viewer, state);

        verify(viewer).teleport(returnLocationClone);
        verify(viewer).setGameMode(GameMode.SURVIVAL);
        verify(viewer).setAllowFlight(false);
        verify(viewer).setFlying(false);
        verify(viewer, times(2)).setFallDistance(0.0F);
    }

    @Test
    void restoreViewerState_afterVanish_showsViewerToOtherOnlinePlayers() {
        UUID viewerId = UUID.randomUUID();
        when(replay.getConfig()).thenReturn(config);
        when(config.getBoolean("Playback.Vanish-Viewer", true)).thenReturn(true);
        when(config.getString("Playback.Viewer-Safety-Mode", "creative")).thenReturn("off");
        when(config.getBoolean("Playback.Restore-Viewer-Location-On-Stop", true)).thenReturn(false);
        when(config.getBoolean("Playback.Restore-Viewer-GameMode-On-Stop", true)).thenReturn(false);
        when(config.getBoolean("Playback.Restore-Viewer-Flight-On-Stop", true)).thenReturn(false);
        when(viewer.getUniqueId()).thenReturn(viewerId);

        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(Bukkit::getOnlinePlayers).thenReturn(List.of(viewer, otherPlayer));

                ReplayViewerState updatedState = manager.applyReplaySafety(
                    viewer,
                    new ReplayViewerState(returnLocation, GameMode.SURVIVAL, false, false));
                manager.restoreViewerState(viewer, updatedState);
        }

        verify(otherPlayer).hidePlayer(replay, viewer);
        verify(otherPlayer).showPlayer(replay, viewer);
    }

    @Test
    void onPlayerJoin_restoresQueuedViewerStateOnce() {
        UUID viewerId = UUID.randomUUID();
        when(replay.getConfig()).thenReturn(config);
        when(config.getBoolean("Playback.Restore-Viewer-State-On-Rejoin", true)).thenReturn(true);
        when(config.getBoolean("Playback.Restore-Viewer-Location-On-Stop", true)).thenReturn(true);
        when(config.getBoolean("Playback.Restore-Viewer-GameMode-On-Stop", true)).thenReturn(true);
        when(config.getBoolean("Playback.Restore-Viewer-Flight-On-Stop", true)).thenReturn(true);
        when(returnLocation.getWorld()).thenReturn(world);
        when(returnLocation.clone()).thenReturn(returnLocationClone);
        when(joinEvent.getPlayer()).thenReturn(viewer);
        when(viewer.getUniqueId()).thenReturn(viewerId);

        manager.queuePendingRestore(viewerId, new ReplayViewerState(returnLocation, GameMode.SURVIVAL, false, false));

        manager.onPlayerJoin(joinEvent);

        verify(viewer).teleport(returnLocationClone);
        verify(viewer).setGameMode(GameMode.SURVIVAL);

        manager.onPlayerJoin(joinEvent);

        verify(viewer).teleport(returnLocationClone);
    }

    @Test
    void onPlayerJoin_hidesCurrentlyVanishedReplayViewerFromJoiner() {
        UUID viewerId = UUID.randomUUID();
        UUID joinerId = UUID.randomUUID();
        when(replay.getConfig()).thenReturn(config);
        when(config.getBoolean("Playback.Vanish-Viewer", true)).thenReturn(true);
        when(config.getString("Playback.Viewer-Safety-Mode", "creative")).thenReturn("off");
        when(viewer.getUniqueId()).thenReturn(viewerId);
        when(viewer.isOnline()).thenReturn(true);
        when(joinEvent.getPlayer()).thenReturn(lateJoiner);
        when(lateJoiner.getUniqueId()).thenReturn(joinerId);

        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(Bukkit::getOnlinePlayers).thenReturn(List.of(viewer, otherPlayer));
            bukkit.when(() -> Bukkit.getPlayer(viewerId)).thenReturn(viewer);

            manager.applyReplaySafety(viewer, new ReplayViewerState(returnLocation, GameMode.SURVIVAL, false, false));
            manager.onPlayerJoin(joinEvent);
        }

        verify(lateJoiner).hidePlayer(replay, viewer);
    }
}
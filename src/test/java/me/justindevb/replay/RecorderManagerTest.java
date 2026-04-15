package me.justindevb.replay;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.PacketEventsAPI;
import com.github.retrooper.packetevents.event.PacketListenerCommon;
import com.github.retrooper.packetevents.event.EventManager;
import com.tcoded.folialib.FoliaLib;
import com.tcoded.folialib.impl.PlatformScheduler;
import com.tcoded.folialib.wrapper.task.WrappedTask;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.PluginManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.io.File;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class RecorderManagerTest {

    private Replay plugin;
    private FoliaLib foliaLib;
    private PlatformScheduler scheduler;
    private PluginManager pluginManager;
    private RecorderManager manager;

    private MockedStatic<Bukkit> bukkitStatic;
    private MockedStatic<Replay> replayStatic;
    private MockedStatic<PacketEvents> packetEventsStatic;

    @BeforeEach
    void setUp() {
        plugin = mock(Replay.class);
        foliaLib = mock(FoliaLib.class);
        scheduler = mock(PlatformScheduler.class);
        pluginManager = mock(PluginManager.class);

        when(plugin.getFoliaLib()).thenReturn(foliaLib);
        when(foliaLib.getScheduler()).thenReturn(scheduler);
        when(plugin.getDataFolder()).thenReturn(new File(System.getProperty("java.io.tmpdir"), "betterreplay-test"));

        bukkitStatic = mockStatic(Bukkit.class);
        bukkitStatic.when(Bukkit::getPluginManager).thenReturn(pluginManager);
        bukkitStatic.when(Bukkit::getLogger).thenReturn(Logger.getLogger("test"));

        replayStatic = mockStatic(Replay.class);
        replayStatic.when(Replay::getInstance).thenReturn(plugin);

        PacketEventsAPI<?> packetApi = mock(PacketEventsAPI.class);
        EventManager eventManager = mock(EventManager.class);
        when(packetApi.getEventManager()).thenReturn(eventManager);
        when(eventManager.registerListener(any(), any())).thenReturn(mock(PacketListenerCommon.class));
        packetEventsStatic = mockStatic(PacketEvents.class);
        packetEventsStatic.when(PacketEvents::getAPI).thenReturn(packetApi);

        manager = new RecorderManager(plugin);
    }

    @AfterEach
    void tearDown() {
        packetEventsStatic.close();
        replayStatic.close();
        bukkitStatic.close();
    }

    @Test
    void startSession_newName_returnsTrue() {
        Player p = mock(Player.class);
        when(p.getUniqueId()).thenReturn(UUID.randomUUID());

        WrappedTask mockTask = mock(WrappedTask.class);
        when(scheduler.runTimer(any(Runnable.class), anyLong(), anyLong())).thenReturn(mockTask);

        boolean result = manager.startSession("test", List.of(p), 30);
        assertTrue(result);
        assertTrue(manager.getActiveSessions().containsKey("test"));
    }

    @Test
    void startSession_duplicateName_returnsFalse() {
        Player p = mock(Player.class);
        when(p.getUniqueId()).thenReturn(UUID.randomUUID());

        WrappedTask mockTask = mock(WrappedTask.class);
        when(scheduler.runTimer(any(Runnable.class), anyLong(), anyLong())).thenReturn(mockTask);

        manager.startSession("dup", List.of(p), -1);
        boolean result = manager.startSession("dup", List.of(p), -1);
        assertFalse(result);
    }

    @Test
    void stopSession_existing_returnsTrue() {
        Player p = mock(Player.class);
        when(p.getUniqueId()).thenReturn(UUID.randomUUID());

        WrappedTask mockTask = mock(WrappedTask.class);
        when(scheduler.runTimer(any(Runnable.class), anyLong(), anyLong())).thenReturn(mockTask);
        when(scheduler.runNextTick(any(java.util.function.Consumer.class))).thenReturn(CompletableFuture.completedFuture(null));

        manager.startSession("stop-test", List.of(p), -1);
        boolean stopped = manager.stopSession("stop-test", false);
        assertTrue(stopped);
        assertFalse(manager.getActiveSessions().containsKey("stop-test"));
    }

    @Test
    void stopSession_nonExistent_returnsFalse() {
        boolean result = manager.stopSession("nope", false);
        assertFalse(result);
    }

    @Test
    void shutdown_stopsAllSessions() {
        Player p = mock(Player.class);
        when(p.getUniqueId()).thenReturn(UUID.randomUUID());

        WrappedTask mockTask = mock(WrappedTask.class);
        when(scheduler.runTimer(any(Runnable.class), anyLong(), anyLong())).thenReturn(mockTask);

        manager.startSession("s1", List.of(p), -1);
        manager.startSession("s2", List.of(p), -1);

        manager.shutdown();

        assertTrue(manager.getActiveSessions().isEmpty());
    }

    @Test
    void getActiveSessions_empty_initially() {
        assertTrue(manager.getActiveSessions().isEmpty());
    }
}

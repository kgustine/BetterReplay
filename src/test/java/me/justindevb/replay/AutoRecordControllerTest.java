package me.justindevb.replay;

import com.tcoded.folialib.FoliaLib;
import com.tcoded.folialib.impl.PlatformScheduler;
import com.tcoded.folialib.wrapper.task.WrappedTask;
import me.justindevb.replay.api.RecordingTarget;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.MockedStatic;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AutoRecordControllerTest {

    @TempDir
    Path tempDir;

    @Test
    void stop_persistsDisabledState() {
        Replay plugin = pluginWithConfig(config(false));
        RecorderManager recorderManager = mock(RecorderManager.class);
        AutoRecordController controller = new AutoRecordController(plugin, recorderManager);

        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(Bukkit::getOnlinePlayers).thenReturn(List.of());

            assertTrue(controller.start(new RecordingTarget.AllPlayers(), 30 * 60, "auto", true));
            assertTrue(controller.stop(false, true));
        }

        YamlConfiguration state = YamlConfiguration.loadConfiguration(tempDir.resolve("auto-record-state.yml").toFile());
        assertFalse(state.getBoolean("enabled", true));
    }

    @Test
    void restorePersistedDisabledState_suppressesConfigStartup() throws Exception {
        YamlConfiguration state = new YamlConfiguration();
        state.set("version", 1);
        state.set("enabled", false);
        state.save(tempDir.resolve("auto-record-state.yml").toFile());

        Replay plugin = pluginWithConfig(config(true));
        RecorderManager recorderManager = mock(RecorderManager.class);
        AutoRecordController controller = new AutoRecordController(plugin, recorderManager);

        controller.restorePersistedOrConfiguredStartup();

        verifyNoInteractions(recorderManager);
        assertTrue(controller.status().isEmpty());
    }

    @Test
    void start_persistsPlayerTargets() {
        Replay plugin = pluginWithConfig(config(false));
        RecorderManager recorderManager = mock(RecorderManager.class);
        AutoRecordController controller = new AutoRecordController(plugin, recorderManager);
        UUID uuid = UUID.randomUUID();

        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(Bukkit::getOnlinePlayers).thenReturn(List.of());

            assertTrue(controller.start(new RecordingTarget.Players(Set.of(uuid)), 20 * 60, "suspects", true));
        }

        YamlConfiguration state = YamlConfiguration.loadConfiguration(tempDir.resolve("auto-record-state.yml").toFile());
        assertTrue(state.getBoolean("enabled", false));
        assertEquals("players", state.getString("target.type"));
        assertEquals(List.of(uuid.toString()), state.getStringList("target.players"));
        assertEquals(20, state.getInt("segmentDurationMinutes"));
        assertEquals("suspects", state.getString("namePrefix"));
    }

    private Replay pluginWithConfig(YamlConfiguration config) {
        Replay plugin = mock(Replay.class);
        FoliaLib foliaLib = mock(FoliaLib.class);
        PlatformScheduler scheduler = mock(PlatformScheduler.class);
        WrappedTask task = mock(WrappedTask.class);
        when(plugin.getDataFolder()).thenReturn(tempDir.toFile());
        when(plugin.getConfig()).thenReturn(config);
        when(plugin.getLogger()).thenReturn(Logger.getLogger("AutoRecordControllerTest"));
        when(plugin.getFoliaLib()).thenReturn(foliaLib);
        when(foliaLib.getScheduler()).thenReturn(scheduler);
        when(scheduler.runTimer(any(Runnable.class), anyLong(), anyLong())).thenReturn(task);
        return plugin;
    }

    private YamlConfiguration config(boolean recordOnStartup) {
        YamlConfiguration config = new YamlConfiguration();
        config.set("Recording.Auto-Record.Record-On-Startup", recordOnStartup);
        config.set("Recording.Auto-Record.Startup-Target", "all");
        config.set("Recording.Auto-Record.Segment-Duration-Minutes", 30);
        config.set("Recording.Auto-Record.Name-Prefix", "auto");
        config.set("Recording.Auto-Record.Save-Active-Segment-On-Shutdown", true);
        config.set("Recording.Auto-Record.Name-Timezone", "UTC");
        return config;
    }
}

package me.justindevb.replay.storage;

import me.justindevb.replay.Replay;
import me.justindevb.replay.recording.TimelineEvent;
import org.bukkit.configuration.file.FileConfiguration;
import io.papermc.paper.plugin.configuration.PluginMeta;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.List;
import java.util.concurrent.ExecutionException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class FileReplayStorageTest {

    @Mock private Replay plugin;
    @Mock private FileConfiguration config;
    @Mock private PluginMeta pluginMeta;

    @TempDir
    File tempDir;

    private FileReplayStorage storage;

    @BeforeEach
    void setUp() {
        when(plugin.getDataFolder()).thenReturn(tempDir);
        when(plugin.getConfig()).thenReturn(config);
        when(plugin.getPluginMeta()).thenReturn(pluginMeta);
        when(pluginMeta.getVersion()).thenReturn("1.4.0");
        storage = new FileReplayStorage(plugin);
    }

    private List<TimelineEvent> sampleTimeline() {
        return List.of(
                new TimelineEvent.PlayerMove(0, "uuid-1", "Steve", "world", 1, 64, 3, 0, 0, "STANDING"),
                new TimelineEvent.BlockBreak(5, "uuid-1", "world", 10, 64, 20, "minecraft:stone"),
                new TimelineEvent.PlayerQuit(10, "uuid-1")
        );
    }

    // ── Compressed roundtrip ──────────────────────────────────

    @Nested
    class CompressedMode {
        @BeforeEach
        void enableCompression() {
            when(config.getBoolean("General.Compress-Replays", true)).thenReturn(true);
        }

        @Test
        void saveAndLoad_roundtrip() throws ExecutionException, InterruptedException {
            List<TimelineEvent> timeline = sampleTimeline();
            storage.saveReplay("test", timeline).get();

            List<TimelineEvent> loaded = storage.loadReplay("test").get();

            assertNotNull(loaded);
            assertEquals(timeline.size(), loaded.size());
            assertInstanceOf(TimelineEvent.PlayerMove.class, loaded.get(0));
            assertInstanceOf(TimelineEvent.BlockBreak.class, loaded.get(1));
            assertInstanceOf(TimelineEvent.PlayerQuit.class, loaded.get(2));
        }

        @Test
        void savedFile_hasGzExtension() throws ExecutionException, InterruptedException {
            storage.saveReplay("compressed", sampleTimeline()).get();

            File replayDir = new File(tempDir, "replays");
            File compressed = new File(replayDir, "compressed.json.gz");
            assertTrue(compressed.exists());

            File plain = new File(replayDir, "compressed.json");
            assertFalse(plain.exists());
        }

        @Test
        void save_removesLegacyUncompressed() throws ExecutionException, InterruptedException, IOException {
            File replayDir = new File(tempDir, "replays");
            replayDir.mkdirs();
            File legacy = new File(replayDir, "migration.json");
            Files.writeString(legacy.toPath(), "[]");
            assertTrue(legacy.exists());

            storage.saveReplay("migration", sampleTimeline()).get();

            assertFalse(legacy.exists());
            assertTrue(new File(replayDir, "migration.json.gz").exists());
        }
    }

    // ── Uncompressed roundtrip ────────────────────────────────

    @Nested
    class UncompressedMode {
        @BeforeEach
        void disableCompression() {
            when(config.getBoolean("General.Compress-Replays", true)).thenReturn(false);
        }

        @Test
        void saveAndLoad_roundtrip() throws ExecutionException, InterruptedException {
            List<TimelineEvent> timeline = sampleTimeline();
            storage.saveReplay("plain", timeline).get();

            List<TimelineEvent> loaded = storage.loadReplay("plain").get();
            assertNotNull(loaded);
            assertEquals(timeline.size(), loaded.size());
        }

        @Test
        void savedFile_hasJsonExtension() throws ExecutionException, InterruptedException {
            storage.saveReplay("plainfile", sampleTimeline()).get();

            File replayDir = new File(tempDir, "replays");
            assertTrue(new File(replayDir, "plainfile.json").exists());
            assertFalse(new File(replayDir, "plainfile.json.gz").exists());
        }
    }

    // ── listReplays ───────────────────────────────────────────

    @Test
    void listReplays_bothExtensions() throws ExecutionException, InterruptedException, IOException {
        when(config.getBoolean("General.Compress-Replays", true)).thenReturn(true);
        storage.saveReplay("compressed1", sampleTimeline()).get();

        when(config.getBoolean("General.Compress-Replays", true)).thenReturn(false);
        storage.saveReplay("uncompressed1", sampleTimeline()).get();

        List<String> names = storage.listReplays().get();
        assertTrue(names.contains("compressed1"));
        assertTrue(names.contains("uncompressed1"));
        assertEquals(2, names.size());
    }

    @Test
    void listReplays_empty() throws ExecutionException, InterruptedException {
        assertTrue(storage.listReplays().get().isEmpty());
    }

    // ── deleteReplay ──────────────────────────────────────────

    @Test
    void deleteReplay_existing_returnsTrue() throws ExecutionException, InterruptedException {
        when(config.getBoolean("General.Compress-Replays", true)).thenReturn(true);
        storage.saveReplay("todelete", sampleTimeline()).get();

        assertTrue(storage.deleteReplay("todelete").get());

        assertNull(storage.loadReplay("todelete").get());
    }

    @Test
    void deleteReplay_nonExistent_returnsFalse() throws ExecutionException, InterruptedException {
        assertFalse(storage.deleteReplay("nope").get());
    }

    // ── replayExists ──────────────────────────────────────────

    @Test
    void replayExists_true() throws ExecutionException, InterruptedException {
        when(config.getBoolean("General.Compress-Replays", true)).thenReturn(true);
        storage.saveReplay("exists", sampleTimeline()).get();

        assertTrue(storage.replayExists("exists").get());
    }

    @Test
    void replayExists_false() throws ExecutionException, InterruptedException {
        assertFalse(storage.replayExists("nope").get());
    }

    // ── getReplayFile ─────────────────────────────────────────

    @Test
    void getReplayFile_existing() throws ExecutionException, InterruptedException {
        when(config.getBoolean("General.Compress-Replays", true)).thenReturn(true);
        storage.saveReplay("filereq", sampleTimeline()).get();

        File file = storage.getReplayFile("filereq").get();
        assertNotNull(file);
        assertTrue(file.exists());
        assertTrue(file.getName().contains("filereq"));
    }

    @Test
    void getReplayFile_nonExistent_returnsNull() throws ExecutionException, InterruptedException {
        assertNull(storage.getReplayFile("nope").get());
    }

    // ── loadReplay ────────────────────────────────────────────

    @Test
    void loadReplay_nonExistent_returnsNull() throws ExecutionException, InterruptedException {
        assertNull(storage.loadReplay("does-not-exist").get());
    }

    // ── Cross-format loading ──────────────────────────────────

    @Test
    void canLoadCompressedAfterSavingCompressed() throws ExecutionException, InterruptedException {
        when(config.getBoolean("General.Compress-Replays", true)).thenReturn(true);
        storage.saveReplay("crossformat", sampleTimeline()).get();

        // Even with compression toggled off, should still load compressed files
        when(config.getBoolean("General.Compress-Replays", true)).thenReturn(false);
        List<TimelineEvent> loaded = storage.loadReplay("crossformat").get();
        assertNotNull(loaded);
        assertEquals(3, loaded.size());
    }
}

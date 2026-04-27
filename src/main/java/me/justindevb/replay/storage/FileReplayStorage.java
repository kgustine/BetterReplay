package me.justindevb.replay.storage;

import me.justindevb.replay.Replay;
import me.justindevb.replay.config.ReplayConfigSetting;
import me.justindevb.replay.recording.TimelineEvent;
import me.justindevb.replay.util.io.ReplayCompressor;

import java.io.*;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

public class FileReplayStorage implements ReplayStorage {

    private final File replayFolder;
    private final Replay replay;
    private final ReplayStorageCodec saveCodec;
    private final ReplayFormatDetector formatDetector;

    public FileReplayStorage(Replay replay) {
        this(replay, new JsonReplayStorageCodec(), new DefaultReplayFormatDetector(List.of(new JsonReplayStorageCodec())));
    }

    FileReplayStorage(Replay replay, ReplayStorageCodec saveCodec, ReplayFormatDetector formatDetector) {
        this.replay = replay;
        this.saveCodec = saveCodec;
        this.formatDetector = formatDetector;
        this.replayFolder = new File(replay.getDataFolder(), "replays");
        if (!replayFolder.exists())
            replayFolder.mkdirs();
    }

    /** Returns true when the plugin config has compression enabled (default: true). */
    private boolean isCompressionEnabled() {
        return saveCodec.supportsCompression() && ReplayConfigSetting.COMPRESS_REPLAYS.getBoolean(replay.getConfig());
    }

    /**
     * Resolve the existing file for a replay name, checking the compressed
     * extension first, then the legacy uncompressed extension.
     * Returns null when neither file exists.
     */
    private File resolveExisting(String name) {
        File compressed = new File(replayFolder, name + JsonReplayStorageCodec.EXT_COMPRESSED);
        if (compressed.exists()) return compressed;
        File plain = new File(replayFolder, name + JsonReplayStorageCodec.EXT_UNCOMPRESSED);
        if (plain.exists()) return plain;
        File preferred = new File(replayFolder, name + saveCodec.fileExtension(isCompressionEnabled()));
        if (preferred.exists()) return preferred;
        return null;
    }

    private byte[] encodeForStorage(List<TimelineEvent> timeline) throws IOException {
        byte[] payload = saveCodec.encodeTimeline(timeline, replay.getPluginMeta().getVersion());
        return isCompressionEnabled() ? ReplayCompressor.compress(new String(payload, java.nio.charset.StandardCharsets.UTF_8)) : payload;
    }

    private void removeLegacyJsonVariants(String name, String retainedExtension) {
        for (String extension : List.of(JsonReplayStorageCodec.EXT_COMPRESSED, JsonReplayStorageCodec.EXT_UNCOMPRESSED)) {
            if (!extension.equals(retainedExtension)) {
                File legacy = new File(replayFolder, name + extension);
                if (legacy.exists()) legacy.delete();
            }
        }
    }

    @Override
    public CompletableFuture<Void> saveReplay(String name, List<TimelineEvent> timeline) {
        return CompletableFuture.runAsync(() -> {
            try {
                boolean compressionEnabled = isCompressionEnabled();
                String extension = saveCodec.fileExtension(compressionEnabled);
                File file = new File(replayFolder, name + extension);
                Files.write(file.toPath(), encodeForStorage(timeline));
                removeLegacyJsonVariants(name, extension);
            } catch (IOException e) {
                throw new RuntimeException("Failed to save replay " + name, e);
            }
        });
    }

    @Override
    public CompletableFuture<List<TimelineEvent>> loadReplay(String name) {
        return CompletableFuture.supplyAsync(() -> {
            File file = resolveExisting(name);
            if (file == null) return null;

            try {
                byte[] bytes = Files.readAllBytes(file.toPath());
                ReplayStorageCodec codec = formatDetector.detectCodec(file.getName(), bytes);
                return codec.decodeTimeline(bytes, replay.getPluginMeta().getVersion());
            } catch (IOException e) {
                throw new RuntimeException("Failed to load replay " + name, e);
            }
        });
    }

    @Override
    public CompletableFuture<Boolean> deleteReplay(String name) {
        return CompletableFuture.supplyAsync(() -> {
            File file = resolveExisting(name);
            return file != null && file.delete();
        });
    }

    @Override
    public CompletableFuture<List<String>> listReplays() {
        return CompletableFuture.supplyAsync(() -> {
            File[] files = replayFolder.listFiles(
                    (dir, n) -> n.endsWith(JsonReplayStorageCodec.EXT_COMPRESSED)
                            || n.endsWith(JsonReplayStorageCodec.EXT_UNCOMPRESSED)
                            || n.endsWith(saveCodec.fileExtension(false))
                            || n.endsWith(saveCodec.fileExtension(true)));
            List<String> names = new ArrayList<>();
            if (files != null) {
                for (File f : files) {
                    String n = f.getName();
                    String detectedExtension = detectExtension(n);
                    if (detectedExtension != null) {
                        names.add(n.substring(0, n.length() - detectedExtension.length()));
                    }
                }
            }
            return names;
        });
    }

    private String detectExtension(String fileName) {
        for (String extension : List.of(
                JsonReplayStorageCodec.EXT_COMPRESSED,
                JsonReplayStorageCodec.EXT_UNCOMPRESSED,
                saveCodec.fileExtension(true),
                saveCodec.fileExtension(false))) {
            if (fileName.endsWith(extension)) {
                return extension;
            }
        }
        return null;
    }

    @Override
    public CompletableFuture<Boolean> replayExists(String name) {
        return CompletableFuture.supplyAsync(() -> resolveExisting(name) != null);
    }

    @Override
    public CompletableFuture<File> getReplayFile(String name) {
        return CompletableFuture.supplyAsync(() -> {
            File file = resolveExisting(name);
            if (file == null || !file.isFile()) {
                return null;
            }

            try {
                byte[] bytes = Files.readAllBytes(file.toPath());
                ReplayStorageCodec codec = formatDetector.detectCodec(file.getName(), bytes);
                return codec.writeReplayFile(name, bytes, replay.getPluginMeta().getVersion());
            } catch (IOException e) {
                throw new RuntimeException("Failed to get replay file " + name, e);
            }
        });
    }
}

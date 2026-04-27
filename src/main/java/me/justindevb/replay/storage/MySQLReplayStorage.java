package me.justindevb.replay.storage;

import me.justindevb.replay.Replay;
import me.justindevb.replay.config.ReplayConfigSetting;
import me.justindevb.replay.recording.TimelineEvent;
import me.justindevb.replay.util.io.ReplayCompressor;

import javax.sql.DataSource;
import java.io.*;
import java.sql.*;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

public class MySQLReplayStorage implements ReplayStorage {

    private final DataSource dataSource;
    private final Replay replay;
    private final ReplayStorageCodec saveCodec;
    private final ReplayFormatDetector formatDetector;

    public MySQLReplayStorage(DataSource dataSource, Replay replay) {
        this(dataSource, replay, new JsonReplayStorageCodec(), new DefaultReplayFormatDetector(List.of(new JsonReplayStorageCodec())));
    }

    MySQLReplayStorage(DataSource dataSource, Replay replay, ReplayStorageCodec saveCodec, ReplayFormatDetector formatDetector) {
        this.dataSource = dataSource;
        this.replay = replay;
        this.saveCodec = saveCodec;
        this.formatDetector = formatDetector;
        init();
    }

    /** Returns true when the plugin config has compression enabled (default: true). */
    private boolean isCompressionEnabled() {
        return saveCodec.supportsCompression() && ReplayConfigSetting.COMPRESS_REPLAYS.getBoolean(replay.getConfig());
    }

    private byte[] encodeForStorage(List<TimelineEvent> timeline) throws IOException {
        byte[] payload = saveCodec.encodeTimeline(timeline, replay.getPluginMeta().getVersion());
        return isCompressionEnabled() ? ReplayCompressor.compress(new String(payload, java.nio.charset.StandardCharsets.UTF_8)) : payload;
    }

    private void init() {
        replay.getFoliaLib().getScheduler().runAsync(task -> {
            try (Connection conn = dataSource.getConnection();
                 Statement stmt = conn.createStatement()) {

                stmt.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS replays (
                        name VARCHAR(64) PRIMARY KEY,
                        created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                        data MEDIUMBLOB NOT NULL
                    )
                """);

            } catch (SQLException e) {
                replay.getLogger().log(java.util.logging.Level.SEVERE, "Failed to init replay table", e);
            }
        });
    }


    @Override
    public CompletableFuture<Void> saveReplay(String name, List<TimelineEvent> timeline) {
        return CompletableFuture.runAsync(() -> {
            try (Connection conn = dataSource.getConnection();
                 PreparedStatement ps = conn.prepareStatement("""
                 INSERT INTO replays (name, data)
                 VALUES (?, ?)
                 ON DUPLICATE KEY UPDATE data = VALUES(data)
             """)) {

                byte[] data = encodeForStorage(timeline);

                ps.setString(1, name);
                ps.setBytes(2, data);
                ps.executeUpdate();

            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
    }


    @Override
    public CompletableFuture<List<TimelineEvent>> loadReplay(String name) {
        return CompletableFuture.supplyAsync(() -> {
            try (Connection conn = dataSource.getConnection();
                 PreparedStatement ps = conn.prepareStatement(
                         "SELECT data FROM replays WHERE name=?"
                 )) {

                ps.setString(1, name);

                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) return null;
                    byte[] data = rs.getBytes("data");
                    ReplayStorageCodec codec = formatDetector.detectCodec(name, data);
                    return codec.decodeTimeline(data, replay.getPluginMeta().getVersion());
                }

            } catch (Exception e) {
                throw new RuntimeException("Failed to load replay: " + name, e);
            }
        });
    }


    @Override
    public CompletableFuture<Boolean> replayExists(String name) {
        return CompletableFuture.supplyAsync(() -> {
            try (Connection conn = dataSource.getConnection();
                 PreparedStatement ps = conn.prepareStatement(
                         "SELECT 1 FROM replays WHERE name=? LIMIT 1"
                 )) {

                ps.setString(1, name);

                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next();
                }

            } catch (SQLException e) {
                replay.getLogger().log(java.util.logging.Level.SEVERE, "Failed to check replay existence: " + name, e);
                return false;
            }
        });
    }


    @Override
    public CompletableFuture<Boolean> deleteReplay(String name) {
        return CompletableFuture.supplyAsync(() -> {
            try (Connection conn = dataSource.getConnection();
                 PreparedStatement ps = conn.prepareStatement(
                         "DELETE FROM replays WHERE name=?"
                 )) {

                ps.setString(1, name);
                int affected = ps.executeUpdate();
                return affected > 0;

            } catch (Exception e) {
                throw new RuntimeException("Failed to delete replay: " + name, e);
            }
        });
    }


    @Override
    public CompletableFuture<List<String>> listReplays() {
        return CompletableFuture.supplyAsync(() -> {
            List<String> names = new ArrayList<>();
            try (Connection conn = dataSource.getConnection();
                 PreparedStatement ps = conn.prepareStatement(
                         "SELECT name FROM replays ORDER BY created_at DESC"
                 );
                 ResultSet rs = ps.executeQuery()) {

                while (rs.next()) {
                    names.add(rs.getString("name"));
                }
                return names;

            } catch (Exception e) {
                throw new RuntimeException("Failed to list replays", e);
            }
        });
    }

    @Override
    public CompletableFuture<File> getReplayFile(String name) {
        return CompletableFuture.supplyAsync(() -> {
            try (Connection conn = dataSource.getConnection();
                PreparedStatement ps = conn.prepareStatement("SELECT data FROM replays WHERE name=?")) {

                ps.setString(1, name);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next())
                        return null;

                    byte[] data = rs.getBytes("data");
                    ReplayStorageCodec codec = formatDetector.detectCodec(name, data);
                    return codec.writeReplayFile(name, data, replay.getPluginMeta().getVersion());
                }
            } catch (Exception e) {
                replay.getLogger().log(java.util.logging.Level.SEVERE, "Failed to get replay file: " + name, e);
                return null;
            }
        });
    }
}

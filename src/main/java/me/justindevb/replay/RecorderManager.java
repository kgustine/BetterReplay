package me.justindevb.replay;

import me.justindevb.replay.chunk.ChunkRecordingArtifacts;
import com.tcoded.folialib.wrapper.task.WrappedTask;
import me.justindevb.replay.api.events.RecordingStartEvent;
import me.justindevb.replay.api.events.RecordingStopEvent;
import me.justindevb.replay.api.RecordingEnrollmentPolicy;
import me.justindevb.replay.api.RecordingPlayerAddResult;
import me.justindevb.replay.api.RecordingSessionOptions;
import me.justindevb.replay.api.RecordingTarget;
import me.justindevb.replay.recording.inventory.SharedEquipmentCaptureCache;
import me.justindevb.replay.recording.inventory.SharedStorageCaptureCache;
import me.justindevb.replay.storage.ReplaySaveRequest;
import me.justindevb.replay.storage.binary.BinaryReplayAppendLogReader;
import me.justindevb.replay.storage.binary.BinaryReplayAppendLogRecovery;
import me.justindevb.replay.util.ReplayMessages;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.stream.Stream;

public class RecorderManager implements Listener {
    private static final String APPEND_LOG_EXTENSION = ".appendlog";

    private final Replay replay;
    private final ConcurrentHashMap<String, RecordingSession> activeSessions = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, PendingAllRecording> pendingAllRecordings = new ConcurrentHashMap<>();
    private final BinaryReplayAppendLogReader appendLogReader = new BinaryReplayAppendLogReader();
    private final SharedEquipmentCaptureCache sharedEquipmentCaptureCache = new SharedEquipmentCaptureCache();
    private final SharedStorageCaptureCache sharedStorageCaptureCache = new SharedStorageCaptureCache();
    private AutoRecordController autoRecordController;
    private WrappedTask tickTask;

    public RecorderManager(Replay replay) {
        this.replay = replay;
    }

    public void setAutoRecordController(AutoRecordController autoRecordController) {
        this.autoRecordController = autoRecordController;
    }

    public boolean startSession(String name, Collection<Player> players, int durationSeconds) {
        RecordingSessionOptions options = new RecordingSessionOptions(
                new RecordingTarget.Players(players.stream().map(Player::getUniqueId).collect(java.util.stream.Collectors.toSet())),
                RecordingEnrollmentPolicy.TARGET_PLAYERS_ON_JOIN,
                durationSeconds,
                false);
        return startSession(name, players, options);
    }

    public boolean startSession(String name, Collection<Player> players, RecordingSessionOptions options) {
        if (activeSessions.containsKey(name) || pendingAllRecordings.containsKey(name)) {
            return false;
        }

        RecordingSession session = new RecordingSession(name, replay.getDataFolder(), players, options, sharedStorageCaptureCache);
        session.start();

        Bukkit.getPluginManager().callEvent(new RecordingStartEvent(name, players, session, options.durationSeconds()));
        activeSessions.put(name, session);

        if (tickTask == null) {
            tickTask = replay.getFoliaLib().getScheduler().runTimer(this::tickAll, 1L, 1L);
        }
        return true;
    }

    public boolean startAllPlayersSession(String name, int durationSeconds) {
        if (activeSessions.containsKey(name) || pendingAllRecordings.containsKey(name)) {
            return false;
        }

        List<Player> onlinePlayers = new ArrayList<>(Bukkit.getOnlinePlayers());
        if (onlinePlayers.isEmpty()) {
            pendingAllRecordings.put(name, new PendingAllRecording(name, durationSeconds));
            return true;
        }

        return startSession(name, onlinePlayers, new RecordingSessionOptions(
                new RecordingTarget.AllPlayers(),
                RecordingEnrollmentPolicy.ALL_PLAYERS_ON_JOIN,
                durationSeconds,
                false));
    }


    public boolean stopSession(String name, boolean save) {
        if (pendingAllRecordings.remove(name) != null) {
            return true;
        }

        RecordingSession session = activeSessions.remove(name);
        if (session == null)
            return false;

        session.stop(save);

        replay.getFoliaLib().getScheduler().runNextTick(task -> {
            Bukkit.getPluginManager().callEvent(new RecordingStopEvent(session));
        });

        if (activeSessions.isEmpty() && tickTask != null) {
            tickTask.cancel();
            tickTask = null;
        }
        return true;
    }

    private void tickAll() {
        sharedEquipmentCaptureCache.beginTick();

        Iterator<Map.Entry<String, RecordingSession>> it = activeSessions.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, RecordingSession> entry = it.next();
            RecordingSession session = entry.getValue();
            session.tick(sharedEquipmentCaptureCache);
            if (session.isStopped()) {
                it.remove();
            }
        }

        if (activeSessions.isEmpty() && tickTask != null) {
            tickTask.cancel();
            tickTask = null;
        }
    }

    public Map<String, RecordingSession> getActiveSessions() {
        return activeSessions;
    }

    public Set<String> getPendingRecordingNames() {
        return pendingAllRecordings.keySet();
    }

    public RecordingPlayerAddResult addPlayerToSession(String recordingName, Player player) {
        RecordingSession session = activeSessions.get(recordingName);
        if (session == null) {
            return RecordingPlayerAddResult.SESSION_NOT_FOUND;
        }
        return session.addTrackedPlayer(player);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        startPendingAllRecordings(player);
        enrollJoiningPlayer(player);
        if (autoRecordController != null) {
            autoRecordController.handlePlayerJoin(player);
        }
    }

    public void enrollJoiningPlayer(Player player) {
        for (RecordingSession session : activeSessions.values()) {
            if (session.acceptsJoin(player)) {
                session.addTrackedPlayer(player, false);
            }
        }
    }

    private void startPendingAllRecordings(Player joiningPlayer) {
        for (PendingAllRecording pending : List.copyOf(pendingAllRecordings.values())) {
            if (!pendingAllRecordings.remove(pending.name(), pending)) {
                continue;
            }
            startSession(pending.name(), List.of(joiningPlayer), new RecordingSessionOptions(
                    new RecordingTarget.AllPlayers(),
                    RecordingEnrollmentPolicy.ALL_PLAYERS_ON_JOIN,
                    pending.durationSeconds(),
                    false));
        }
    }

    public CompletableFuture<Void> recoverPendingAppendLogs() {
        File tempFolder = new File(replay.getDataFolder(), "replays/.tmp");
        File[] appendLogs = tempFolder.listFiles((dir, name) -> name.endsWith(APPEND_LOG_EXTENSION));
        if (appendLogs == null || appendLogs.length == 0) {
            return CompletableFuture.completedFuture(null);
        }

        replay.getLogger().info("Found " + appendLogs.length + " pending replay temp log(s) to recover.");
        List<CompletableFuture<Void>> recoveries = new ArrayList<>();
        for (File appendLog : appendLogs) {
            recoveries.add(recoverAppendLog(appendLog));
        }
        return CompletableFuture.allOf(recoveries.toArray(CompletableFuture[]::new));
    }

    private CompletableFuture<Void> recoverAppendLog(File appendLogFile) {
        String replayName = appendLogFile.getName().substring(0, appendLogFile.getName().length() - APPEND_LOG_EXTENSION.length());

        BinaryReplayAppendLogRecovery recovery;
        try {
            recovery = appendLogReader.recover(appendLogFile.toPath());
        } catch (IOException e) {
            replay.getLogger().log(Level.SEVERE, "Failed to recover recording temp log: " + appendLogFile.getName(), e);
            return CompletableFuture.completedFuture(null);
        }

        if (recovery.timeline().isEmpty()) {
            replay.getLogger().warning("Skipping recovery for " + replayName + ": no valid events found (" + recovery.stopReason() + ")");
            return CompletableFuture.completedFuture(null);
        }

        if (recovery.discardedTail()) {
            replay.getLogger().warning("Recovered replay " + replayName + " with truncated tail: " + recovery.stopReason());
        }

        long recoveredStart = recovery.header().recordingStartedAtEpochMillis() > 0
                ? recovery.header().recordingStartedAtEpochMillis()
                : System.currentTimeMillis();
        File chunkTempDirectory = new File(replay.getDataFolder(), "replays/.tmp/chunks/" + replayName);
        ChunkRecordingArtifacts chunkArtifacts = chunkTempDirectory.isDirectory()
                ? new ChunkRecordingArtifacts(chunkTempDirectory.toPath(), 0, 0)
                : ChunkRecordingArtifacts.NONE;

        return replay.getReplayStorage().saveReplay(replayName, new ReplaySaveRequest(recovery.timeline(), recoveredStart, chunkArtifacts))
                .thenCompose(v -> refreshReplayCache().thenApply(ignored -> v))
                .thenAccept(v -> {
                    if (appendLogFile.exists() && !appendLogFile.delete()) {
                        replay.getLogger().warning("Recovered replay " + replayName + " but failed to delete temp log " + appendLogFile.getName());
                        return;
                    }
                    deleteChunkTempDirectory(chunkTempDirectory.toPath());
                    replay.getLogger().info("Recovered replay from temp log: " + replayName);
                })
                .exceptionally(ex -> {
                    replay.getLogger().log(Level.SEVERE, "Failed to save recovered replay: " + replayName, ex);
                    return null;
                });
    }

    private CompletableFuture<Void> refreshReplayCache() {
        return replay.getReplayStorage().listReplays().thenAccept(replays -> replay.getReplayCache().setReplays(replays));
    }

    private void deleteChunkTempDirectory(Path chunkTempDirectory) {
        if (!Files.exists(chunkTempDirectory)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(chunkTempDirectory)) {
            walk.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException e) {
                    replay.getLogger().log(Level.WARNING, "Failed to delete recovered chunk temp path: " + path, e);
                }
            });
        } catch (IOException e) {
            replay.getLogger().log(Level.WARNING, "Failed to enumerate recovered chunk temp directory: " + chunkTempDirectory, e);
        }
    }

    @Deprecated
    public void replaySession(String name, Player viewer) {
        replay.getReplayStorage().loadReplay(name)
                .thenAccept(timeline -> {
                   // Bukkit.getScheduler().runTask(replay, () -> {
                    replay.getFoliaLib().getScheduler().runNextTick(task -> {
                        new ReplaySession(timeline, viewer, replay).start();
                    });
                })
                .exceptionally(ex -> {
                    replay.getLogger().log(java.util.logging.Level.SEVERE, "Failed to load replay: " + name, ex);
                    ReplayMessages.send(viewer, "§cFailed to load replay: " + name);
                    return null;
                });
    }


    public void shutdown() {
        for (RecordingSession s : activeSessions.values())
            s.stopForRecovery();

        activeSessions.clear();
        if (tickTask != null) {
            tickTask.cancel();
            tickTask = null;
        }
        pendingAllRecordings.clear();
    }

    private record PendingAllRecording(String name, int durationSeconds) {}
}

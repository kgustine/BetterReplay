package me.justindevb.replay;

import com.tcoded.folialib.wrapper.task.WrappedTask;
import me.justindevb.replay.api.AutoRecordingStatus;
import me.justindevb.replay.api.RecordingEnrollmentPolicy;
import me.justindevb.replay.api.RecordingSessionOptions;
import me.justindevb.replay.api.RecordingTarget;
import me.justindevb.replay.config.ReplayConfigSetting;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Level;

public class AutoRecordController {
    private static final String STATE_FILE_NAME = "auto-record-state.yml";
    private static final DateTimeFormatter NAME_TIME_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd-HH-mm-ss");

    private final Replay replay;
    private final RecorderManager recorderManager;
    private final File stateFile;

    private WrappedTask rolloverTask;
    private boolean enabled;
    private RecordingTarget target;
    private int segmentDurationSeconds;
    private String namePrefix;
    private ZoneId nameTimezone;
    private boolean saveActiveSegmentOnShutdown;
    private String activeSegmentName;
    private long currentSegmentStartedAtEpochMillis;

    public AutoRecordController(Replay replay, RecorderManager recorderManager) {
        this.replay = replay;
        this.recorderManager = recorderManager;
        this.stateFile = new File(replay.getDataFolder(), STATE_FILE_NAME);
    }

    public boolean start(RecordingTarget target, int segmentDurationSeconds, String namePrefix, boolean persist) {
        return start(target, segmentDurationSeconds, namePrefix, configuredZoneId(),
                ReplayConfigSetting.AUTO_RECORD_SAVE_ACTIVE_SEGMENT_ON_SHUTDOWN.getBoolean(replay.getConfig()), persist);
    }

    private boolean start(RecordingTarget target,
                          int segmentDurationSeconds,
                          String namePrefix,
                          ZoneId nameTimezone,
                          boolean saveActiveSegmentOnShutdown,
                          boolean persist) {
        if (enabled) {
            return false;
        }
        if (segmentDurationSeconds <= 0 || target == null || namePrefix == null || namePrefix.isBlank()) {
            return false;
        }

        this.enabled = true;
        this.target = target;
        this.segmentDurationSeconds = segmentDurationSeconds;
        this.namePrefix = namePrefix.trim();
        this.nameTimezone = nameTimezone;
        this.saveActiveSegmentOnShutdown = saveActiveSegmentOnShutdown;

        ensureTask();
        startSegmentIfTargetsOnline();
        if (persist) {
            persistEnabledState();
        }
        return true;
    }

    public boolean stop(boolean saveActiveSegment, boolean persistDisabled) {
        if (!enabled) {
            if (persistDisabled) {
                persistDisabledState();
            }
            return false;
        }

        enabled = false;
        cancelTask();
        if (activeSegmentName != null) {
            recorderManager.stopSession(activeSegmentName, saveActiveSegment);
            activeSegmentName = null;
            currentSegmentStartedAtEpochMillis = 0L;
        }
        if (persistDisabled) {
            persistDisabledState();
        }
        return true;
    }

    public void shutdown() {
        if (!enabled) {
            return;
        }
        persistEnabledState();
        stop(saveActiveSegmentOnShutdown, false);
    }

    public void handlePlayerJoin(Player player) {
        if (!enabled || activeSegmentName != null || !targetMatches(player)) {
            return;
        }
        startSegmentIfTargetsOnline();
    }

    public Optional<AutoRecordingStatus> status() {
        if (!enabled) {
            return Optional.empty();
        }
        long nextRollover = activeSegmentName == null
                ? 0L
                : currentSegmentStartedAtEpochMillis + (segmentDurationSeconds * 1000L);
        return Optional.of(new AutoRecordingStatus(
                true,
                activeSegmentName == null,
                describeTarget(target),
                activeSegmentName,
                segmentDurationSeconds,
                namePrefix,
                currentSegmentStartedAtEpochMillis,
                nextRollover));
    }

    public void restorePersistedOrConfiguredStartup() {
        PersistedState persisted = loadState();
        if (persisted != null) {
            if (persisted.enabled()) {
                start(persisted.target(), persisted.segmentDurationSeconds(), persisted.namePrefix(),
                        persisted.nameTimezone(), persisted.saveActiveSegmentOnShutdown(), false);
            }
            return;
        }

        if (!ReplayConfigSetting.AUTO_RECORD_ON_STARTUP.getBoolean(replay.getConfig())) {
            return;
        }
        RecordingTarget startupTarget = resolveStartupTarget();
        int minutes = Math.max(1, ReplayConfigSetting.AUTO_RECORD_SEGMENT_DURATION_MINUTES.getInt(replay.getConfig()));
        start(startupTarget, minutes * 60, ReplayConfigSetting.AUTO_RECORD_NAME_PREFIX.getString(replay.getConfig()), false);
    }

    private void ensureTask() {
        if (rolloverTask == null) {
            rolloverTask = replay.getFoliaLib().getScheduler().runTimer(this::tick, 20L, 20L);
        }
    }

    private void cancelTask() {
        if (rolloverTask != null) {
            rolloverTask.cancel();
            rolloverTask = null;
        }
    }

    private void tick() {
        if (!enabled) {
            return;
        }
        if (activeSegmentName == null) {
            startSegmentIfTargetsOnline();
            return;
        }
        if (System.currentTimeMillis() - currentSegmentStartedAtEpochMillis >= segmentDurationSeconds * 1000L) {
            recorderManager.stopSession(activeSegmentName, true);
            activeSegmentName = null;
            currentSegmentStartedAtEpochMillis = 0L;
            startSegmentIfTargetsOnline();
        }
    }

    private void startSegmentIfTargetsOnline() {
        List<Player> players = resolveOnlineTargets();
        if (players.isEmpty()) {
            return;
        }

        for (int suffix = 0; suffix < 100; suffix++) {
            String name = generatedSegmentName(suffix);
            boolean started = recorderManager.startSession(name, players, new RecordingSessionOptions(
                    target,
                    target instanceof RecordingTarget.AllPlayers
                            ? RecordingEnrollmentPolicy.ALL_PLAYERS_ON_JOIN
                            : RecordingEnrollmentPolicy.TARGET_PLAYERS_ON_JOIN,
                    -1,
                    true));
            if (started) {
                activeSegmentName = name;
                currentSegmentStartedAtEpochMillis = System.currentTimeMillis();
                return;
            }
        }
        replay.getLogger().warning("Failed to start auto-record segment because all generated names collided.");
    }

    private List<Player> resolveOnlineTargets() {
        if (target instanceof RecordingTarget.AllPlayers) {
            return new ArrayList<>(Bukkit.getOnlinePlayers());
        }
        if (!(target instanceof RecordingTarget.Players playersTarget)) {
            return List.of();
        }
        List<Player> players = new ArrayList<>();
        for (UUID uuid : playersTarget.playerUuids()) {
            Player player = Bukkit.getPlayer(uuid);
            if (player != null && player.isOnline()) {
                players.add(player);
            }
        }
        return players;
    }

    private boolean targetMatches(Player player) {
        if (target instanceof RecordingTarget.AllPlayers) {
            return true;
        }
        return target instanceof RecordingTarget.Players playersTarget
                && playersTarget.playerUuids().contains(player.getUniqueId());
    }

    private String generatedSegmentName(int suffix) {
        String base = namePrefix + "-" + NAME_TIME_FORMAT.format(Instant.now().atZone(nameTimezone));
        return suffix == 0 ? base : base + "-" + suffix;
    }

    private ZoneId configuredZoneId() {
        String configured = ReplayConfigSetting.AUTO_RECORD_NAME_TIMEZONE.getString(replay.getConfig());
        try {
            return ZoneId.of(configured);
        } catch (RuntimeException e) {
            replay.getLogger().warning("Invalid auto-record timezone '" + configured + "'; using UTC.");
            return ZoneId.of("UTC");
        }
    }

    private RecordingTarget resolveStartupTarget() {
        String configured = ReplayConfigSetting.AUTO_RECORD_STARTUP_TARGET.getString(replay.getConfig());
        if (configured == null || configured.isBlank() || configured.equalsIgnoreCase("all")) {
            return new RecordingTarget.AllPlayers();
        }
        OfflinePlayer offlinePlayer = Bukkit.getOfflinePlayer(configured.trim());
        return new RecordingTarget.Players(Set.of(offlinePlayer.getUniqueId()));
    }

    private String describeTarget(RecordingTarget target) {
        if (target instanceof RecordingTarget.AllPlayers) {
            return "all";
        }
        if (target instanceof RecordingTarget.Players players) {
            return players.playerUuids().stream()
                    .map(UUID::toString)
                    .collect(java.util.stream.Collectors.joining(", "));
        }
        return "unknown";
    }

    private void persistEnabledState() {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("version", 1);
        yaml.set("enabled", true);
        yaml.set("target.type", target instanceof RecordingTarget.AllPlayers ? "all" : "players");
        if (target instanceof RecordingTarget.Players players) {
            yaml.set("target.players", players.playerUuids().stream().map(UUID::toString).toList());
        }
        yaml.set("segmentDurationMinutes", Math.max(1, segmentDurationSeconds / 60));
        yaml.set("namePrefix", namePrefix);
        yaml.set("nameTimezone", nameTimezone.getId());
        yaml.set("saveActiveSegmentOnShutdown", saveActiveSegmentOnShutdown);
        yaml.set("updatedAt", Instant.now().toString());
        saveState(yaml);
    }

    private void persistDisabledState() {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("version", 1);
        yaml.set("enabled", false);
        yaml.set("updatedAt", Instant.now().toString());
        saveState(yaml);
    }

    private void saveState(YamlConfiguration yaml) {
        try {
            if (!stateFile.getParentFile().exists() && !stateFile.getParentFile().mkdirs()) {
                replay.getLogger().warning("Failed to create auto-record state directory: " + stateFile.getParent());
                return;
            }
            File tempFile = new File(stateFile.getParentFile(), STATE_FILE_NAME + ".tmp");
            yaml.save(tempFile);
            Files.move(tempFile.toPath(), stateFile.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException | RuntimeException e) {
            replay.getLogger().log(Level.WARNING, "Failed to save auto-record state.", e);
        }
    }

    private PersistedState loadState() {
        if (!stateFile.isFile()) {
            return null;
        }
        try {
            YamlConfiguration yaml = YamlConfiguration.loadConfiguration(stateFile);
            boolean persistedEnabled = yaml.getBoolean("enabled", false);
            if (!persistedEnabled) {
            return new PersistedState(false, new RecordingTarget.AllPlayers(), 60, "auto", ZoneId.of("UTC"), true);
            }
            RecordingTarget loadedTarget;
            if ("players".equalsIgnoreCase(yaml.getString("target.type", "all"))) {
                Set<UUID> uuids = yaml.getStringList("target.players").stream()
                        .map(value -> {
                            try {
                                return UUID.fromString(value);
                            } catch (IllegalArgumentException ignored) {
                                return null;
                            }
                        })
                        .filter(Objects::nonNull)
                        .collect(java.util.stream.Collectors.toSet());
                loadedTarget = uuids.isEmpty() ? new RecordingTarget.AllPlayers() : new RecordingTarget.Players(uuids);
            } else {
                loadedTarget = new RecordingTarget.AllPlayers();
            }
            int minutes = Math.max(1, yaml.getInt("segmentDurationMinutes", ReplayConfigSetting.AUTO_RECORD_SEGMENT_DURATION_MINUTES.getInt(replay.getConfig())));
            String prefix = yaml.getString("namePrefix", ReplayConfigSetting.AUTO_RECORD_NAME_PREFIX.getString(replay.getConfig()));
            ZoneId zoneId;
            try {
                zoneId = ZoneId.of(yaml.getString("nameTimezone", "UTC"));
            } catch (RuntimeException e) {
                zoneId = ZoneId.of("UTC");
            }
            boolean saveOnShutdown = yaml.getBoolean("saveActiveSegmentOnShutdown", true);
            return new PersistedState(true, loadedTarget, minutes * 60, prefix, zoneId, saveOnShutdown);
        } catch (RuntimeException e) {
            replay.getLogger().log(Level.WARNING, "Failed to load auto-record state; falling back to config.", e);
            return null;
        }
    }

    private record PersistedState(boolean enabled, RecordingTarget target, int segmentDurationSeconds, String namePrefix,
                                  ZoneId nameTimezone, boolean saveActiveSegmentOnShutdown) {}
}

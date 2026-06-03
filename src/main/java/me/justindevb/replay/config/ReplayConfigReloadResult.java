package me.justindevb.replay.config;

import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumMap;
import java.util.List;

public record ReplayConfigReloadResult(
        List<ReplayConfigSetting> immediateChanges,
        List<ReplayConfigSetting> retentionRestartChanges,
        List<ReplayConfigSetting> newSessionChanges,
        List<ReplayConfigSetting> restartRequiredChanges,
        List<ReplayConfigSetting> futureChanges,
        boolean retentionServiceRestarted
) {

    public ReplayConfigReloadResult {
        immediateChanges = List.copyOf(immediateChanges);
        retentionRestartChanges = List.copyOf(retentionRestartChanges);
        newSessionChanges = List.copyOf(newSessionChanges);
        restartRequiredChanges = List.copyOf(restartRequiredChanges);
        futureChanges = List.copyOf(futureChanges);
    }

    public static ReplayConfigReloadResult fromChangedSettings(Collection<ReplayConfigSetting> changedSettings,
                                                               boolean retentionServiceRestarted) {
        EnumMap<ReplayConfigReloadScope, List<ReplayConfigSetting>> byScope =
                new EnumMap<>(ReplayConfigReloadScope.class);
        for (ReplayConfigReloadScope scope : ReplayConfigReloadScope.values()) {
            byScope.put(scope, new ArrayList<>());
        }

        for (ReplayConfigSetting setting : changedSettings) {
            byScope.get(setting.getReloadScope()).add(setting);
        }

        return new ReplayConfigReloadResult(
                byScope.get(ReplayConfigReloadScope.IMMEDIATE),
                byScope.get(ReplayConfigReloadScope.RETENTION_RESTART),
                byScope.get(ReplayConfigReloadScope.NEW_SESSIONS_ONLY),
                byScope.get(ReplayConfigReloadScope.RESTART_REQUIRED),
                byScope.get(ReplayConfigReloadScope.FUTURE_ONLY),
                retentionServiceRestarted);
    }

    public boolean hasVisibleChanges() {
        return !immediateChanges.isEmpty()
                || !retentionRestartChanges.isEmpty()
                || !newSessionChanges.isEmpty()
                || !restartRequiredChanges.isEmpty()
                || !futureChanges.isEmpty();
    }
}
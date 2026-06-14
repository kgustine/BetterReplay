package me.justindevb.replay.api;

public record AutoRecordingStatus(
        boolean enabled,
        boolean waiting,
        String targetDescription,
        String activeSegmentName,
        int segmentDurationSeconds,
        String namePrefix,
        long currentSegmentStartedAtEpochMillis,
        long nextRolloverAtEpochMillis
) {}

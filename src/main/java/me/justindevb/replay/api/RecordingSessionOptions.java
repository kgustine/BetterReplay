package me.justindevb.replay.api;

public record RecordingSessionOptions(
        RecordingTarget target,
        RecordingEnrollmentPolicy enrollmentPolicy,
        int durationSeconds,
        boolean autoRecordSegment
) {
    public static RecordingSessionOptions targeted(RecordingTarget target, int durationSeconds) {
        return new RecordingSessionOptions(
                target,
                RecordingEnrollmentPolicy.TARGET_PLAYERS_ON_JOIN,
                durationSeconds,
                false);
    }
}

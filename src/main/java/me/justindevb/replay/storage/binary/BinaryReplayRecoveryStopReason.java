package me.justindevb.replay.storage.binary;

/**
 * Explains why append-log recovery stopped scanning a temp file.
 */
public enum BinaryReplayRecoveryStopReason {
    CLEAN_EOF,
    TRUNCATED_RECORD_LENGTH,
    TRUNCATED_RECORD,
    CHECKSUM_MISMATCH,
    MALFORMED_RECORD
}
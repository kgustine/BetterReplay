package me.justindevb.replay.storage.binary;

import me.justindevb.replay.recording.TimelineEvent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BinaryReplayAppendLogRecoveryTest {

    private static final long RECORDING_STARTED_AT = 1_700_000_000_000L;

    @TempDir
    Path tempDir;

    @Test
    void stopsAtTruncatedTailAndKeepsValidPrefix() throws Exception {
        Path path = tempDir.resolve("truncated.appendlog");
        BinaryReplayAppendLogReader reader = new BinaryReplayAppendLogReader();

        try (BinaryReplayAppendLogWriter writer = new BinaryReplayAppendLogWriter(path, RECORDING_STARTED_AT)) {
            writer.append(new TimelineEvent.PlayerQuit(0, "uuid-1"));
            writer.append(new TimelineEvent.PlayerQuit(1, "uuid-2"));
            writer.flush();
        }

        byte[] bytes = Files.readAllBytes(path);
        Files.write(path, java.util.Arrays.copyOf(bytes, bytes.length - 2));

        BinaryReplayAppendLogRecovery recovery = reader.recover(path);

        assertEquals(BinaryReplayRecoveryStopReason.TRUNCATED_RECORD, recovery.stopReason());
        assertEquals(RECORDING_STARTED_AT, recovery.header().recordingStartedAtEpochMillis());
        assertEquals(List.of(new TimelineEvent.PlayerQuit(0, "uuid-1")), recovery.timeline());
        assertTrue(recovery.discardedTail());
    }

    @Test
    void stopsAtChecksumMismatchAndKeepsValidPrefix() throws Exception {
        Path path = tempDir.resolve("corrupt.appendlog");
        BinaryReplayAppendLogReader reader = new BinaryReplayAppendLogReader();

        try (BinaryReplayAppendLogWriter writer = new BinaryReplayAppendLogWriter(path, RECORDING_STARTED_AT)) {
            writer.append(new TimelineEvent.PlayerQuit(0, "uuid-1"));
            writer.append(new TimelineEvent.PlayerQuit(1, "uuid-2"));
            writer.flush();
        }

        byte[] bytes = Files.readAllBytes(path);
        bytes[bytes.length - 1] ^= 0x01;
        Files.write(path, bytes);

        BinaryReplayAppendLogRecovery recovery = reader.recover(path);

        assertEquals(BinaryReplayRecoveryStopReason.CHECKSUM_MISMATCH, recovery.stopReason());
        assertEquals(RECORDING_STARTED_AT, recovery.header().recordingStartedAtEpochMillis());
        assertEquals(List.of(new TimelineEvent.PlayerQuit(0, "uuid-1")), recovery.timeline());
        assertFalse(recovery.records().isEmpty());
    }

    @Test
    void failsRecoveryWhenHeaderIsTruncated() throws Exception {
        Path path = tempDir.resolve("header-truncated.appendlog");
        try (BinaryReplayAppendLogWriter writer = new BinaryReplayAppendLogWriter(path, RECORDING_STARTED_AT)) {
            writer.flush();
        }

        byte[] bytes = Files.readAllBytes(path);
        Files.write(path, java.util.Arrays.copyOf(bytes, BinaryReplayFormat.APPEND_LOG_HEADER_SIZE - 1));

        BinaryReplayAppendLogRecovery recovery = new BinaryReplayAppendLogReader().recover(path);

        assertEquals(BinaryReplayRecoveryStopReason.TRUNCATED_HEADER, recovery.stopReason());
        assertEquals(List.of(), recovery.timeline());
    }

    @Test
    void treatsInvalidRecordLengthVarIntAsTruncatedLengthWithoutAllocating() throws Exception {
        Path path = tempDir.resolve("invalid-record-length.appendlog");
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(BinaryReplayFormat.APPEND_LOG_MAGIC);
        out.write(BinaryReplayFormat.APPEND_LOG_HEADER_VERSION);
        out.write(BinaryReplayFormat.APPEND_LOG_HEADER_FLAGS_NONE);
        out.write(0);
        out.write(0);
        out.writeBytes(ByteBuffer.allocate(Long.BYTES)
                .order(BinaryReplayFormat.PRIMITIVE_BYTE_ORDER)
                .putLong(RECORDING_STARTED_AT)
                .array());
        out.writeBytes(new byte[] {(byte) 0x80, (byte) 0x80, (byte) 0x80, (byte) 0x80, 0x10});
        Files.write(path, out.toByteArray());

        BinaryReplayAppendLogRecovery recovery = new BinaryReplayAppendLogReader().recover(path);

        assertEquals(BinaryReplayRecoveryStopReason.TRUNCATED_RECORD_LENGTH, recovery.stopReason());
        assertEquals(BinaryReplayFormat.APPEND_LOG_HEADER_SIZE, recovery.consumedBytes());
    }

    @Test
    void treatsOversizedRecordAsMalformedBeforeAllocation() throws Exception {
        Path path = tempDir.resolve("oversized-record.appendlog");
        ByteArrayOutputStream out = appendLogHeader();
        out.writeBytes(BinaryEncoding.encodeVarInt(BinaryReplayReadLimits.MAX_APPEND_LOG_RECORD_BYTES + 1));
        Files.write(path, out.toByteArray());

        BinaryReplayAppendLogRecovery recovery = new BinaryReplayAppendLogReader().recover(path);

        assertEquals(BinaryReplayRecoveryStopReason.MALFORMED_RECORD, recovery.stopReason());
        assertEquals(BinaryReplayFormat.APPEND_LOG_HEADER_SIZE, recovery.consumedBytes());
    }

    @Test
    void acceptsSyntheticRecordLengthAboveChunkLimitWithoutAllocatingItsPayload() throws Exception {
        Path path = tempDir.resolve("large-accepted-record.appendlog");
        ByteArrayOutputStream out = appendLogHeader();
        out.writeBytes(BinaryEncoding.encodeVarInt(BinaryReplayReadLimits.MAX_DECODED_CHUNK_BYTES + 1));
        Files.write(path, out.toByteArray());

        BinaryReplayAppendLogRecovery recovery = new BinaryReplayAppendLogReader().recover(path);

        assertEquals(BinaryReplayRecoveryStopReason.TRUNCATED_RECORD, recovery.stopReason());
        assertEquals(BinaryReplayFormat.APPEND_LOG_HEADER_SIZE, recovery.consumedBytes());
    }

    private static ByteArrayOutputStream appendLogHeader() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(BinaryReplayFormat.APPEND_LOG_MAGIC);
        out.write(BinaryReplayFormat.APPEND_LOG_HEADER_VERSION);
        out.write(BinaryReplayFormat.APPEND_LOG_HEADER_FLAGS_NONE);
        out.write(0);
        out.write(0);
        out.writeBytes(ByteBuffer.allocate(Long.BYTES)
                .order(BinaryReplayFormat.PRIMITIVE_BYTE_ORDER)
                .putLong(RECORDING_STARTED_AT)
                .array());
        return out;
    }
}

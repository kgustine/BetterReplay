package me.justindevb.replay.storage.binary;

import me.justindevb.replay.recording.TimelineEvent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BinaryReplayAppendLogRecoveryTest {

    @TempDir
    Path tempDir;

    @Test
    void stopsAtTruncatedTailAndKeepsValidPrefix() throws Exception {
        Path path = tempDir.resolve("truncated.appendlog");
        BinaryReplayAppendLogReader reader = new BinaryReplayAppendLogReader();

        try (BinaryReplayAppendLogWriter writer = new BinaryReplayAppendLogWriter(path)) {
            writer.append(new TimelineEvent.PlayerQuit(0, "uuid-1"));
            writer.append(new TimelineEvent.PlayerQuit(1, "uuid-2"));
            writer.flush();
        }

        byte[] bytes = Files.readAllBytes(path);
        Files.write(path, java.util.Arrays.copyOf(bytes, bytes.length - 2));

        BinaryReplayAppendLogRecovery recovery = reader.recover(path);

        assertEquals(BinaryReplayRecoveryStopReason.TRUNCATED_RECORD, recovery.stopReason());
        assertEquals(List.of(new TimelineEvent.PlayerQuit(0, "uuid-1")), recovery.timeline());
        assertTrue(recovery.discardedTail());
    }

    @Test
    void stopsAtChecksumMismatchAndKeepsValidPrefix() throws Exception {
        Path path = tempDir.resolve("corrupt.appendlog");
        BinaryReplayAppendLogReader reader = new BinaryReplayAppendLogReader();

        try (BinaryReplayAppendLogWriter writer = new BinaryReplayAppendLogWriter(path)) {
            writer.append(new TimelineEvent.PlayerQuit(0, "uuid-1"));
            writer.append(new TimelineEvent.PlayerQuit(1, "uuid-2"));
            writer.flush();
        }

        byte[] bytes = Files.readAllBytes(path);
        bytes[bytes.length - 1] ^= 0x01;
        Files.write(path, bytes);

        BinaryReplayAppendLogRecovery recovery = reader.recover(path);

        assertEquals(BinaryReplayRecoveryStopReason.CHECKSUM_MISMATCH, recovery.stopReason());
        assertEquals(List.of(new TimelineEvent.PlayerQuit(0, "uuid-1")), recovery.timeline());
        assertFalse(recovery.records().isEmpty());
    }
}
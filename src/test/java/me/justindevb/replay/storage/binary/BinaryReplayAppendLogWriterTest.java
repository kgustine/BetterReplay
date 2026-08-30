package me.justindevb.replay.storage.binary;

import me.justindevb.replay.recording.TimelineEvent;
import me.justindevb.replay.util.io.SerializedItemData;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.io.IOException;
import java.util.List;
import java.util.zip.CRC32C;

import static org.junit.jupiter.api.Assertions.*;

class BinaryReplayAppendLogWriterTest {

    private static final long RECORDING_STARTED_AT = 1_700_000_000_000L;

    @TempDir
    Path tempDir;

    @Test
    void recordLengthPolicyAllowsRecordsAboveChunkLimitButRejectsAppendLogOverflow() {
        assertDoesNotThrow(() -> BinaryReplayAppendLogWriter.validateRecordLength(
                BinaryReplayReadLimits.MAX_DECODED_CHUNK_BYTES + 1L));
        assertDoesNotThrow(() -> BinaryReplayAppendLogWriter.validateRecordLength(
                BinaryReplayReadLimits.MAX_APPEND_LOG_RECORD_BYTES));
        assertThrows(IOException.class, () -> BinaryReplayAppendLogWriter.validateRecordLength(
                BinaryReplayReadLimits.MAX_APPEND_LOG_RECORD_BYTES + 1L));
    }

    @Test
    void appendsMultipleEventTypesAndReadsThemBack() throws Exception {
        Path path = tempDir.resolve("multi.appendlog");
        BinaryReplayAppendLogReader reader = new BinaryReplayAppendLogReader();

        try (BinaryReplayAppendLogWriter writer = new BinaryReplayAppendLogWriter(path, RECORDING_STARTED_AT)) {
            writer.append(new TimelineEvent.PlayerQuit(0, "uuid-1"));
            writer.append(new TimelineEvent.SprintToggle(5, "uuid-1", true));
            writer.append(new TimelineEvent.BlockBreakStage(8, null, "world", 1, 2, 3, 4));
            writer.flush();
        }

        List<TimelineEvent> timeline = reader.readTimeline(path);

        assertEquals(3, timeline.size());
        assertEquals(new TimelineEvent.PlayerQuit(0, "uuid-1"), timeline.get(0));
        assertEquals(new TimelineEvent.SprintToggle(5, "uuid-1", true), timeline.get(1));
        assertEquals(new TimelineEvent.BlockBreakStage(8, null, "world", 1, 2, 3, 4), timeline.get(2));
    }

    @Test
    void reusesStringDefinitionsAfterFirstUse() throws Exception {
        Path path = tempDir.resolve("reuse.appendlog");
        BinaryReplayAppendLogReader reader = new BinaryReplayAppendLogReader();

        try (BinaryReplayAppendLogWriter writer = new BinaryReplayAppendLogWriter(path, RECORDING_STARTED_AT)) {
            writer.append(new TimelineEvent.PlayerQuit(0, "shared-uuid"));
            writer.append(new TimelineEvent.PlayerQuit(1, "shared-uuid"));
            writer.flush();
        }

        List<BinaryReplayAppendLogReader.DecodedRecord> records = reader.readRecords(path);

        assertEquals(3, records.size());
        assertEquals(BinaryRecordType.DEFINE_STRING, records.get(0).type());
        assertEquals(BinaryRecordType.PLAYER_QUIT, records.get(1).type());
        assertEquals(BinaryRecordType.PLAYER_QUIT, records.get(2).type());
    }

    @Test
    void storesCrc32cForEachRecordContent() throws Exception {
        Path path = tempDir.resolve("crc.appendlog");
        BinaryReplayAppendLogReader reader = new BinaryReplayAppendLogReader();

        try (BinaryReplayAppendLogWriter writer = new BinaryReplayAppendLogWriter(path, RECORDING_STARTED_AT)) {
            writer.append(new TimelineEvent.PlayerQuit(0, "uuid-1"));
            writer.flush();
        }

        List<BinaryReplayAppendLogReader.DecodedRecord> records = reader.readRecords(path);
        assertFalse(records.isEmpty());

        for (BinaryReplayAppendLogReader.DecodedRecord record : records) {
            CRC32C crc32c = new CRC32C();
            crc32c.update(record.checksummedBytes(), 0, record.checksummedBytes().length);
            assertEquals((int) crc32c.getValue(), record.storedChecksum());
        }
    }

    @Test
    void keepsSimultaneousWritersIsolated() throws Exception {
        Path firstPath = tempDir.resolve("first.appendlog");
        Path secondPath = tempDir.resolve("second.appendlog");
        BinaryReplayAppendLogReader reader = new BinaryReplayAppendLogReader();

           try (BinaryReplayAppendLogWriter first = new BinaryReplayAppendLogWriter(firstPath, RECORDING_STARTED_AT);
               BinaryReplayAppendLogWriter second = new BinaryReplayAppendLogWriter(secondPath, RECORDING_STARTED_AT + 1)) {
            first.append(new TimelineEvent.PlayerQuit(0, "first"));
            second.append(new TimelineEvent.PlayerQuit(0, "second"));
            first.flush();
            second.flush();
        }

        List<TimelineEvent> firstTimeline = reader.readTimeline(firstPath);
        List<TimelineEvent> secondTimeline = reader.readTimeline(secondPath);

        assertEquals(List.of(new TimelineEvent.PlayerQuit(0, "first")), firstTimeline);
        assertEquals(List.of(new TimelineEvent.PlayerQuit(0, "second")), secondTimeline);
    }

    @Test
    void persistsHeaderMetadataAheadOfRecords() throws Exception {
        Path path = tempDir.resolve("header.appendlog");
        BinaryReplayAppendLogReader reader = new BinaryReplayAppendLogReader();

        try (BinaryReplayAppendLogWriter writer = new BinaryReplayAppendLogWriter(path, RECORDING_STARTED_AT)) {
            writer.append(new TimelineEvent.PlayerQuit(0, "uuid-1"));
            writer.flush();
        }

        BinaryReplayAppendLogRecovery recovery = reader.recover(path);

        assertEquals(RECORDING_STARTED_AT, recovery.header().recordingStartedAtEpochMillis());
        assertEquals(List.of(new TimelineEvent.PlayerQuit(0, "uuid-1")), recovery.timeline());
    }

    @Test
    void roundTripsSplitInventoryEventsWithRawItemPayloads() throws Exception {
        Path path = tempDir.resolve("inventory.appendlog");
        BinaryReplayAppendLogReader reader = new BinaryReplayAppendLogReader();

        TimelineEvent.EquipmentStateUpdate equipment = new TimelineEvent.EquipmentStateUpdate(
                2,
                "uuid-1",
                4,
                SerializedItemData.fromBytes(new byte[] {1, 2, 3}),
                SerializedItemData.fromBytes(new byte[] {4, 5, 6}),
                List.of(
                        SerializedItemData.fromBytes(new byte[] {7}),
                        SerializedItemData.fromBytes(new byte[] {8}),
                        SerializedItemData.fromBytes(new byte[] {9}),
                        SerializedItemData.empty()
                ));
        TimelineEvent.InventoryStorageUpdate storage = new TimelineEvent.InventoryStorageUpdate(
                2,
                "uuid-1",
                List.of(
                        SerializedItemData.fromBytes(new byte[] {10, 11}),
                        SerializedItemData.empty(),
                        SerializedItemData.fromBytes(new byte[] {12, 13, 14})
                ));

        try (BinaryReplayAppendLogWriter writer = new BinaryReplayAppendLogWriter(path, RECORDING_STARTED_AT)) {
            writer.append(equipment);
            writer.append(storage);
            writer.flush();
        }

        List<TimelineEvent> timeline = reader.readTimeline(path);

        assertEquals(List.of(equipment, storage), timeline);
    }
}

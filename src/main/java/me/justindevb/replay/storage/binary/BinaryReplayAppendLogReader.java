package me.justindevb.replay.storage.binary;

import me.justindevb.replay.recording.TimelineEvent;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.CRC32C;

/**
 * Reads append-log temp files written by {@link BinaryReplayAppendLogWriter}.
 */
public final class BinaryReplayAppendLogReader {

    public List<TimelineEvent> readTimeline(Path path) throws IOException {
        BinaryReplayAppendLogRecovery recovery = recover(path);
        if (!recovery.isComplete()) {
            throw new IOException("Append-log ended with " + recovery.stopReason());
        }
        return recovery.timeline();
    }

    public List<DecodedRecord> readRecords(Path path) throws IOException {
        BinaryReplayAppendLogRecovery recovery = recover(path);
        if (!recovery.isComplete()) {
            throw new IOException("Append-log ended with " + recovery.stopReason());
        }
        return recovery.records();
    }

    public BinaryReplayAppendLogRecovery recover(Path path) throws IOException {
        if (!Files.exists(path)) {
            return new BinaryReplayAppendLogRecovery(
                    BinaryReplayAppendLogHeader.empty(),
                    List.of(),
                    List.of(),
                    List.of(),
                    BinaryReplayRecoveryStopReason.CLEAN_EOF,
                    0);
        }

        long fileSize = Files.size(path);
        if (fileSize > BinaryReplayReadLimits.MAX_DECODED_TIMELINE_BYTES) {
            throw new IOException("Append-log exceeds the permitted size");
        }
        byte[] bytes;
        try (InputStream input = Files.newInputStream(path)) {
            bytes = BinaryReplayReadLimits.readAllBytes(
                    input,
                    BinaryReplayReadLimits.MAX_DECODED_TIMELINE_BYTES,
                    "Append-log");
        }
        if (bytes.length < BinaryReplayFormat.APPEND_LOG_HEADER_SIZE) {
            return new BinaryReplayAppendLogRecovery(
                    BinaryReplayAppendLogHeader.empty(),
                    List.of(),
                    List.of(),
                    List.of(),
                    BinaryReplayRecoveryStopReason.TRUNCATED_HEADER,
                    0);
        }

        ParsedHeader parsedHeader = parseHeader(bytes);
        if (parsedHeader == null) {
            return new BinaryReplayAppendLogRecovery(
                    BinaryReplayAppendLogHeader.empty(),
                    List.of(),
                    List.of(),
                    List.of(),
                    BinaryReplayRecoveryStopReason.MALFORMED_HEADER,
                    0);
        }

        BinaryReplayAppendLogHeader header = parsedHeader.header();
        List<String> stringTable = new ArrayList<>();
        List<TimelineEvent> timeline = new ArrayList<>();
        List<DecodedRecord> records = new ArrayList<>();
        int offset = parsedHeader.nextOffset();

        while (offset < bytes.length) {
            VarIntRead recordLengthRead = tryReadVarInt(bytes, offset);
            if (recordLengthRead == null) {
                return new BinaryReplayAppendLogRecovery(header, records, timeline, stringTable,
                        BinaryReplayRecoveryStopReason.TRUNCATED_RECORD_LENGTH, offset);
            }

            int recordLength = recordLengthRead.value();
            int recordContentOffset = recordLengthRead.nextOffset();
            if (recordLength > BinaryReplayReadLimits.MAX_APPEND_LOG_RECORD_BYTES) {
                return new BinaryReplayAppendLogRecovery(header, records, timeline, stringTable,
                        BinaryReplayRecoveryStopReason.MALFORMED_RECORD, offset);
            }
            int remaining = bytes.length - recordContentOffset;
            if (recordLength < 0 || remaining < BinaryReplayFormat.APPEND_LOG_CRC_BYTES
                    || recordLength > remaining - BinaryReplayFormat.APPEND_LOG_CRC_BYTES) {
                return new BinaryReplayAppendLogRecovery(header, records, timeline, stringTable,
                        BinaryReplayRecoveryStopReason.TRUNCATED_RECORD, offset);
            }
            int recordEnd = recordContentOffset + recordLength + BinaryReplayFormat.APPEND_LOG_CRC_BYTES;

            byte[] recordContent = slice(bytes, recordContentOffset, recordLength);
            int storedChecksum = readLittleEndianInt(bytes, recordContentOffset + recordLength);
            int computedChecksum = calculateCrc32c(recordContent);
            if (storedChecksum != computedChecksum) {
                return new BinaryReplayAppendLogRecovery(header, records, timeline, stringTable,
                        BinaryReplayRecoveryStopReason.CHECKSUM_MISMATCH, offset);
            }

            try {
                DecodedRecord record = decodeRecord(recordContent, storedChecksum);
                if (record.type() == BinaryRecordType.DEFINE_STRING) {
                    BinaryReplayAppendLogCodec.DefinedString definedString = BinaryReplayAppendLogCodec.decodeDefineString(record.payload());
                    if (definedString.index() != stringTable.size()) {
                        return new BinaryReplayAppendLogRecovery(header, records, timeline, stringTable,
                                BinaryReplayRecoveryStopReason.MALFORMED_RECORD, offset);
                    }
                    BinaryReplayStorageCodec.validateStringTableCount(stringTable.size() + 1);
                    stringTable.add(definedString.value());
                } else {
                    BinaryReplayStorageCodec.validateTimelineEventCount(timeline.size() + 1);
                    timeline.add(BinaryReplayAppendLogCodec.decodeEvent(record.type(), record.payload(), stringTable));
                }
                records.add(record);
            } catch (IllegalArgumentException | IOException ex) {
                return new BinaryReplayAppendLogRecovery(header, records, timeline, stringTable,
                        BinaryReplayRecoveryStopReason.MALFORMED_RECORD, offset);
            }

            offset = recordEnd;
        }

        return new BinaryReplayAppendLogRecovery(header, records, timeline, stringTable,
                BinaryReplayRecoveryStopReason.CLEAN_EOF, offset);
    }

    private static ParsedHeader parseHeader(byte[] bytes) {
        ByteBuffer headerBuffer = ByteBuffer.wrap(bytes, 0, BinaryReplayFormat.APPEND_LOG_HEADER_SIZE)
                .order(BinaryReplayFormat.PRIMITIVE_BYTE_ORDER);

        byte[] magic = new byte[BinaryReplayFormat.APPEND_LOG_MAGIC.length];
        headerBuffer.get(magic);
        if (!java.util.Arrays.equals(magic, BinaryReplayFormat.APPEND_LOG_MAGIC)) {
            return null;
        }

        int headerVersion = headerBuffer.get() & 0xFF;
        int flags = headerBuffer.get() & 0xFF;
        short reserved = headerBuffer.getShort();
        long recordingStartedAtEpochMillis = headerBuffer.getLong();

        if (headerVersion != BinaryReplayFormat.APPEND_LOG_HEADER_VERSION
                || flags != BinaryReplayFormat.APPEND_LOG_HEADER_FLAGS_NONE
                || reserved != 0
                || recordingStartedAtEpochMillis < 0) {
            return null;
        }

        return new ParsedHeader(new BinaryReplayAppendLogHeader(recordingStartedAtEpochMillis), BinaryReplayFormat.APPEND_LOG_HEADER_SIZE);
    }

    private static int calculateCrc32c(byte[] recordContent) {
        CRC32C crc32c = new CRC32C();
        crc32c.update(recordContent, 0, recordContent.length);
        return (int) crc32c.getValue();
    }

    private static DecodedRecord decodeRecord(byte[] recordContent, int storedChecksum) throws IOException {
        BinaryReplayAppendLogCodec.Cursor recordCursor = new BinaryReplayAppendLogCodec.Cursor(recordContent);
        int recordTypeTag = recordCursor.readVarInt();
        BinaryRecordType recordType = BinaryRecordType.fromTag(recordTypeTag)
                .orElseThrow(() -> new IOException("Unknown append-log record tag: " + recordTypeTag));
        return new DecodedRecord(recordType, recordCursor.remainingBytes(), storedChecksum, recordContent);
    }

    private static VarIntRead tryReadVarInt(byte[] bytes, int offset) {
        int value = 0;
        int shift = 0;
        int currentOffset = offset;
        while (currentOffset < bytes.length) {
            int current = bytes[currentOffset++] & 0xFF;
            if (shift == 28 && (current & 0xF8) != 0) {
                return null;
            }
            value |= (current & 0x7F) << shift;
            if ((current & 0x80) == 0) {
                return new VarIntRead(value, currentOffset);
            }
            shift += 7;
            if (shift > 28) {
                return null;
            }
        }
        return null;
    }

    private static int readLittleEndianInt(byte[] bytes, int offset) {
        return (bytes[offset] & 0xFF)
                | ((bytes[offset + 1] & 0xFF) << 8)
                | ((bytes[offset + 2] & 0xFF) << 16)
                | ((bytes[offset + 3] & 0xFF) << 24);
    }

    private static byte[] slice(byte[] bytes, int offset, int length) {
        byte[] copy = new byte[length];
        System.arraycopy(bytes, offset, copy, 0, length);
        return copy;
    }

    private record VarIntRead(int value, int nextOffset) {
    }

    private record ParsedHeader(BinaryReplayAppendLogHeader header, int nextOffset) {
    }

    public record DecodedRecord(BinaryRecordType type, byte[] payload, int storedChecksum, byte[] checksummedBytes) {
    }
}

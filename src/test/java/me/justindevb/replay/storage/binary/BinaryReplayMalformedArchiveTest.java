package me.justindevb.replay.storage.binary;

import com.google.gson.Gson;
import net.jpountz.lz4.LZ4FrameInputStream;
import net.jpountz.lz4.LZ4FrameOutputStream;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.zip.CRC32;
import java.util.zip.CRC32C;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BinaryReplayMalformedArchiveTest {

    private static final Gson GSON = new Gson();
    private final BinaryReplayStorageCodec codec = new BinaryReplayStorageCodec();

    @Test
    void failsWhenManifestEntryIsMissing() throws Exception {
        Map<String, byte[]> entries = readArchiveEntries(readFixture("goldens/minimal-v2.br"));
        entries.remove(BinaryReplayFormat.MANIFEST_ENTRY_NAME);

        IOException ex = assertThrows(IOException.class, () -> codec.decodeTimeline(writeArchive(entries), "1.4.0"));

        assertTrue(ex.getMessage().contains("missing required entries"));
    }

    @Test
    void failsWhenReplayEntryIsMissing() throws Exception {
        Map<String, byte[]> entries = readArchiveEntries(readFixture("goldens/minimal-v2.br"));
        entries.remove(BinaryReplayFormat.REPLAY_ENTRY_NAME);

        IOException ex = assertThrows(IOException.class, () -> codec.decodeTimeline(writeArchive(entries), "1.4.0"));

        assertTrue(ex.getMessage().contains("missing required entries"));
    }

    @Test
    void failsWhenManifestChecksumDoesNotMatchReplayBytes() throws Exception {
        Map<String, byte[]> entries = readArchiveEntries(readFixture("goldens/minimal-v2.br"));
        byte[] replayBytes = Arrays.copyOf(entries.get(BinaryReplayFormat.REPLAY_ENTRY_NAME), entries.get(BinaryReplayFormat.REPLAY_ENTRY_NAME).length);
        replayBytes[replayBytes.length - 1] ^= 0x01;
        entries.put(BinaryReplayFormat.REPLAY_ENTRY_NAME, replayBytes);

        assertThrows(IOException.class, () -> codec.decodeTimeline(writeArchive(entries), "1.4.0"));
    }

    @Test
    void failsWhenIndexSectionIsPresentButTruncated() throws Exception {
        Map<String, byte[]> entries = readArchiveEntries(readFixture("goldens/minimal-v2.br"));
        byte[] payload = decompress(entries.get(BinaryReplayFormat.REPLAY_ENTRY_NAME));
        int footerOffset = payload.length - BinaryReplayFormat.INDEX_SECTION_FOOTER_BYTES;
        long indexOffset = ByteBuffer.wrap(payload, footerOffset, BinaryReplayFormat.INDEX_SECTION_FOOTER_BYTES)
                .order(BinaryReplayFormat.PRIMITIVE_BYTE_ORDER)
                .getLong();

        byte[] truncatedPayload = Arrays.copyOf(payload, footerOffset - 1);
        ByteBuffer.wrap(truncatedPayload,
                        truncatedPayload.length - BinaryReplayFormat.INDEX_SECTION_FOOTER_BYTES,
                        BinaryReplayFormat.INDEX_SECTION_FOOTER_BYTES)
                .order(BinaryReplayFormat.PRIMITIVE_BYTE_ORDER)
                .putLong(indexOffset);

        entries.put(BinaryReplayFormat.REPLAY_ENTRY_NAME, compress(truncatedPayload));
        updateManifestChecksum(entries);

        assertThrows(IOException.class, () -> codec.decodeTimeline(writeArchive(entries), "1.4.0"));
    }

    @Test
    void rejectsDeflatedArchiveEntries() throws Exception {
        Map<String, byte[]> entries = readArchiveEntries(readFixture("goldens/minimal-v2.br"));
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(out)) {
            ZipEntry manifest = new ZipEntry(BinaryReplayFormat.MANIFEST_ENTRY_NAME);
            zip.putNextEntry(manifest);
            zip.write(entries.get(BinaryReplayFormat.MANIFEST_ENTRY_NAME));
            zip.closeEntry();
            writeStoredEntry(zip, BinaryReplayFormat.REPLAY_ENTRY_NAME, entries.get(BinaryReplayFormat.REPLAY_ENTRY_NAME));
        }

        IOException ex = assertThrows(IOException.class, () -> codec.decodeTimeline(out.toByteArray(), "1.4.0"));

        assertTrue(ex.getMessage().contains("STORED"));
    }

    @Test
    void rejectsUnknownArchiveEntries() throws Exception {
        Map<String, byte[]> entries = readArchiveEntries(readFixture("goldens/minimal-v2.br"));
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(out)) {
            writeStoredEntry(zip, "unknown.bin", new byte[] {1});
            writeStoredEntry(zip, BinaryReplayFormat.MANIFEST_ENTRY_NAME, entries.get(BinaryReplayFormat.MANIFEST_ENTRY_NAME));
            writeStoredEntry(zip, BinaryReplayFormat.REPLAY_ENTRY_NAME, entries.get(BinaryReplayFormat.REPLAY_ENTRY_NAME));
        }

        assertThrows(IOException.class, () -> codec.decodeTimeline(out.toByteArray(), "1.4.0"));
    }

    @Test
    void rejectsDuplicateArchiveEntries() throws Exception {
        Map<String, byte[]> entries = readArchiveEntries(readFixture("goldens/minimal-v2.br"));
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(out)) {
            writeStoredEntry(zip, BinaryReplayFormat.MANIFEST_ENTRY_NAME, entries.get(BinaryReplayFormat.MANIFEST_ENTRY_NAME));
            writeStoredEntry(zip, "manifest.jsox", entries.get(BinaryReplayFormat.MANIFEST_ENTRY_NAME));
            writeStoredEntry(zip, BinaryReplayFormat.REPLAY_ENTRY_NAME, entries.get(BinaryReplayFormat.REPLAY_ENTRY_NAME));
        }
        byte[] duplicateArchive = replaceAscii(out.toByteArray(), "manifest.jsox", BinaryReplayFormat.MANIFEST_ENTRY_NAME);

        assertThrows(IOException.class, () -> codec.decodeTimeline(duplicateArchive, "1.4.0"));
    }

    @Test
    void canDecodeOnlyChecksBoundedZipSignature() {
        assertTrue(codec.canDecode("signature", new byte[] {'P', 'K', 3, 4}));
        assertFalse(codec.canDecode("not-zip", new byte[] {'B', 'R', 'P', 'L'}));
    }

    @Test
    void rejectsFinalizedIndexWhoseFirstCheckpointIsNotZero() throws Exception {
        Map<String, byte[]> entries = mutableArchive();
        byte[] payload = decompress(entries.get(BinaryReplayFormat.REPLAY_ENTRY_NAME));
        int firstTickOffset = firstTickIndexOffset(payload);
        ByteBuffer.wrap(payload, firstTickOffset, Integer.BYTES)
                .order(BinaryReplayFormat.PRIMITIVE_BYTE_ORDER)
                .putInt(BinaryReplayFormat.TICK_INDEX_INTERVAL);

        assertMalformedPayload(entries, payload);
    }

    @Test
    void rejectsFinalizedIndexOffsetThatIsNotAnEventRecord() throws Exception {
        Map<String, byte[]> entries = mutableArchive();
        byte[] payload = decompress(entries.get(BinaryReplayFormat.REPLAY_ENTRY_NAME));
        int firstTickOffset = firstTickIndexOffset(payload);
        ByteBuffer.wrap(payload, firstTickOffset + Integer.BYTES, Long.BYTES)
                .order(BinaryReplayFormat.PRIMITIVE_BYTE_ORDER)
                .putLong(BinaryReplayFormat.PAYLOAD_HEADER_SIZE);

        assertMalformedPayload(entries, payload);
    }

    @Test
    void rejectsFinalizedIndexThatSeeksPastItsCheckpoint() throws Exception {
        Map<String, byte[]> entries = mutableArchive();
        byte[] payload = decompress(entries.get(BinaryReplayFormat.REPLAY_ENTRY_NAME));
        int firstTickOffset = firstTickIndexOffset(payload);
        long laterEventOffset = secondEventRecordOffset(payload);
        ByteBuffer.wrap(payload, firstTickOffset + Integer.BYTES, Long.BYTES)
                .order(BinaryReplayFormat.PRIMITIVE_BYTE_ORDER)
                .putLong(laterEventOffset);

        assertMalformedPayload(entries, payload);
    }

    @Test
    void rejectsOversizedLogicalStringTableCountBeforeAllocation() throws Exception {
        Map<String, byte[]> entries = mutableArchive();
        byte[] payload = decompress(entries.get(BinaryReplayFormat.REPLAY_ENTRY_NAME));
        int countOffset = indexSectionOffset(payload) + BinaryReplayFormat.INDEX_SECTION_MAGIC.length;
        byte[] oversizedCount = BinaryEncoding.encodeVarInt(BinaryReplayReadLimits.MAX_STRING_TABLE_ENTRIES + 1);
        System.arraycopy(oversizedCount, 0, payload, countOffset, oversizedCount.length);

        assertMalformedPayload(entries, payload);
    }

    @Test
    void rejectsOversizedLogicalTickIndexCountBeforeAllocation() throws Exception {
        Map<String, byte[]> entries = mutableArchive();
        byte[] payload = decompress(entries.get(BinaryReplayFormat.REPLAY_ENTRY_NAME));
        int tickCountOffset = firstTickIndexOffset(payload) - 1;
        byte[] oversizedCount = BinaryEncoding.encodeVarInt(BinaryReplayReadLimits.MAX_TICK_INDEX_ENTRIES + 1);
        System.arraycopy(oversizedCount, 0, payload, tickCountOffset, oversizedCount.length);

        assertMalformedPayload(entries, payload);
    }

    @Test
    void rejectsFinalizedIndexWithNonIncreasingCheckpoints() throws Exception {
        Map<String, byte[]> entries = mutableArchive();
        byte[] payload = decompress(entries.get(BinaryReplayFormat.REPLAY_ENTRY_NAME));
        int firstTickOffset = firstTickIndexOffset(payload);
        int oldFooterOffset = payload.length - BinaryReplayFormat.INDEX_SECTION_FOOTER_BYTES;
        byte[] expanded = new byte[payload.length + BinaryReplayFormat.TICK_INDEX_ENTRY_BYTES];
        System.arraycopy(payload, 0, expanded, 0, oldFooterOffset);
        expanded[firstTickOffset - 1] = 2;
        System.arraycopy(
                payload,
                firstTickOffset,
                expanded,
                oldFooterOffset,
                BinaryReplayFormat.TICK_INDEX_ENTRY_BYTES);
        ByteBuffer.wrap(
                        expanded,
                        expanded.length - BinaryReplayFormat.INDEX_SECTION_FOOTER_BYTES,
                        BinaryReplayFormat.INDEX_SECTION_FOOTER_BYTES)
                .order(BinaryReplayFormat.PRIMITIVE_BYTE_ORDER)
                .putLong(indexSectionOffset(payload));

        assertMalformedPayload(entries, expanded);
    }

    private static Map<String, byte[]> mutableArchive() throws IOException {
        return readArchiveEntries(readFixture("goldens/minimal-v2.br"));
    }

    private void assertMalformedPayload(Map<String, byte[]> entries, byte[] payload) throws Exception {
        entries.put(BinaryReplayFormat.REPLAY_ENTRY_NAME, compress(payload));
        updateManifestChecksum(entries);
        assertThrows(IOException.class, () -> codec.decodeTimeline(writeArchive(entries), "1.4.0"));
    }

    private static int firstTickIndexOffset(byte[] payload) throws IOException {
        int offset = indexSectionOffset(payload) + BinaryReplayFormat.INDEX_SECTION_MAGIC.length;
        VarIntRead stringCount = readVarInt(payload, offset);
        offset = stringCount.nextOffset();
        for (int index = 0; index < stringCount.value(); index++) {
            VarIntRead length = readVarInt(payload, offset);
            offset = length.nextOffset() + length.value();
        }
        return readVarInt(payload, offset).nextOffset();
    }

    private static long secondEventRecordOffset(byte[] payload) throws IOException {
        int offset = BinaryReplayFormat.PAYLOAD_HEADER_SIZE;
        int eventCount = 0;
        while (offset < indexSectionOffset(payload)) {
            int recordOffset = offset;
            VarIntRead length = readVarInt(payload, offset);
            VarIntRead type = readVarInt(payload, length.nextOffset());
            if (type.value() != BinaryRecordType.DEFINE_STRING.tag() && ++eventCount == 2) {
                return recordOffset;
            }
            offset = length.nextOffset() + length.value();
        }
        throw new IOException("Golden archive has fewer than two events");
    }

    private static int indexSectionOffset(byte[] payload) {
        return Math.toIntExact(ByteBuffer.wrap(
                        payload,
                        payload.length - BinaryReplayFormat.INDEX_SECTION_FOOTER_BYTES,
                        BinaryReplayFormat.INDEX_SECTION_FOOTER_BYTES)
                .order(BinaryReplayFormat.PRIMITIVE_BYTE_ORDER)
                .getLong());
    }

    private static VarIntRead readVarInt(byte[] bytes, int offset) throws IOException {
        int value = 0;
        for (int shift = 0; shift <= 28 && offset < bytes.length; shift += 7) {
            int current = bytes[offset++] & 0xFF;
            value |= (current & 0x7F) << shift;
            if ((current & 0x80) == 0) {
                return new VarIntRead(value, offset);
            }
        }
        throw new IOException("Malformed test VarInt");
    }

    private record VarIntRead(int value, int nextOffset) {
    }

    private static byte[] readFixture(String resourcePath) throws IOException {
        try (InputStream in = BinaryReplayMalformedArchiveTest.class.getClassLoader().getResourceAsStream(resourcePath)) {
            if (in == null) {
                throw new IOException("Missing fixture resource: " + resourcePath);
            }
            return in.readAllBytes();
        }
    }

    private static Map<String, byte[]> readArchiveEntries(byte[] archiveBytes) throws IOException {
        Map<String, byte[]> entries = new HashMap<>();
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(archiveBytes))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                entries.put(entry.getName(), zip.readAllBytes());
                zip.closeEntry();
            }
        }
        return entries;
    }

    private static byte[] writeArchive(Map<String, byte[]> entries) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(out)) {
            for (String name : Arrays.asList(BinaryReplayFormat.MANIFEST_ENTRY_NAME, BinaryReplayFormat.REPLAY_ENTRY_NAME)) {
                if (entries.containsKey(name)) {
                    writeStoredEntry(zip, name, entries.get(name));
                }
            }
        }
        return out.toByteArray();
    }

    private static void writeStoredEntry(ZipOutputStream zip, String name, byte[] contents) throws IOException {
        ZipEntry entry = new ZipEntry(name);
        entry.setMethod(ZipEntry.STORED);
        entry.setSize(contents.length);
        entry.setCompressedSize(contents.length);
        CRC32 crc32 = new CRC32();
        crc32.update(contents, 0, contents.length);
        entry.setCrc(crc32.getValue());
        zip.putNextEntry(entry);
        zip.write(contents);
        zip.closeEntry();
    }

    private static byte[] decompress(byte[] replayBytes) throws IOException {
        try (LZ4FrameInputStream lz4 = new LZ4FrameInputStream(new ByteArrayInputStream(replayBytes))) {
            return lz4.readAllBytes();
        }
    }

    private static byte[] compress(byte[] payload) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (LZ4FrameOutputStream lz4 = new LZ4FrameOutputStream(out)) {
            lz4.write(payload);
        }
        return out.toByteArray();
    }

    private static void updateManifestChecksum(Map<String, byte[]> entries) {
        BinaryReplayManifest manifest = GSON.fromJson(
                new String(entries.get(BinaryReplayFormat.MANIFEST_ENTRY_NAME), StandardCharsets.UTF_8),
                BinaryReplayManifest.class);
        CRC32C crc32c = new CRC32C();
        byte[] replayBytes = entries.get(BinaryReplayFormat.REPLAY_ENTRY_NAME);
        crc32c.update(replayBytes, 0, replayBytes.length);
        BinaryReplayManifest updated = new BinaryReplayManifest(
                manifest.formatVersion(),
                manifest.recordedWithVersion(),
                manifest.minimumViewerVersion(),
                manifest.recordingStartedAtEpochMillis(),
                "%08x".formatted(crc32c.getValue()),
                manifest.payloadChecksumAlgorithm(),
                manifest.hasChunkData(),
                manifest.chunkRegionEntryCount(),
                manifest.chunkEntryCount(),
                manifest.chunkCoordinateHash(),
                manifest.chunkPayloadFormat(),
                manifest.chunkPayloadVersion());
        entries.put(BinaryReplayFormat.MANIFEST_ENTRY_NAME, GSON.toJson(updated).getBytes(StandardCharsets.UTF_8));
    }

    private static byte[] replaceAscii(byte[] bytes, String from, String to) {
        byte[] source = from.getBytes(StandardCharsets.US_ASCII);
        byte[] replacement = to.getBytes(StandardCharsets.US_ASCII);
        if (source.length != replacement.length) {
            throw new IllegalArgumentException("Replacement values must have equal lengths");
        }
        byte[] result = bytes.clone();
        for (int offset = 0; offset <= result.length - source.length; offset++) {
            if (Arrays.equals(Arrays.copyOfRange(result, offset, offset + source.length), source)) {
                System.arraycopy(replacement, 0, result, offset, replacement.length);
            }
        }
        return result;
    }
}

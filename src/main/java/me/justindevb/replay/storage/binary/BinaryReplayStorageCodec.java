package me.justindevb.replay.storage.binary;

import com.google.gson.Gson;
import me.justindevb.replay.chunk.ChunkRecordingArtifacts;
import me.justindevb.replay.chunk.ReplayChunkData;
import me.justindevb.replay.recording.TimelineEvent;
import me.justindevb.replay.storage.ReplayFormat;
import me.justindevb.replay.storage.ReplayInspection;
import me.justindevb.replay.storage.ReplayInspectionBuilder;
import me.justindevb.replay.storage.ReplayIndexedTimeline;
import me.justindevb.replay.storage.ReplayPlaybackData;
import me.justindevb.replay.storage.ReplaySaveRequest;
import me.justindevb.replay.storage.ReplayStorageCodec;
import me.justindevb.replay.util.VersionUtil;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.util.AbstractList;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.CRC32C;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Loads finalized BetterReplay .br archives for playback.
 */
public final class BinaryReplayStorageCodec implements ReplayStorageCodec {

    private static final Comparator<String> CHUNK_ENTRY_ORDER = Comparator.naturalOrder();
    private static final byte[] ZIP_LOCAL_FILE_HEADER = new byte[] {'P', 'K', 3, 4};
    private static final Pattern CHUNK_ENTRY_NAME = Pattern.compile(
            "chunks/((?:[A-Za-z0-9._-]|%[0-9A-F]{2})+)/r\\.(-?\\d+)\\.(-?\\d+)\\.brregion");

    private final Gson gson;
    private final BinaryReplayArchiveFinalizer finalizer;
    private final BinaryChunkTempArchiveFinalizer chunkArchiveFinalizer;
    private final BinaryChunkRegionCodec chunkRegionCodec;

    public BinaryReplayStorageCodec() {
        this(new Gson(), new BinaryReplayArchiveFinalizer(), new BinaryChunkTempArchiveFinalizer(), new BinaryChunkRegionCodec());
    }

    BinaryReplayStorageCodec(
            Gson gson,
            BinaryReplayArchiveFinalizer finalizer,
            BinaryChunkTempArchiveFinalizer chunkArchiveFinalizer,
            BinaryChunkRegionCodec chunkRegionCodec
    ) {
        this.gson = Objects.requireNonNull(gson, "gson");
        this.finalizer = Objects.requireNonNull(finalizer, "finalizer");
        this.chunkArchiveFinalizer = Objects.requireNonNull(chunkArchiveFinalizer, "chunkArchiveFinalizer");
        this.chunkRegionCodec = Objects.requireNonNull(chunkRegionCodec, "chunkRegionCodec");
    }

    @Override
    public ReplayFormat format() {
        return ReplayFormat.BINARY_ARCHIVE;
    }

    @Override
    public boolean canDecode(String replayName, byte[] storedBytes) {
        return storedBytes != null
                && storedBytes.length <= BinaryReplayReadLimits.MAX_STORED_ARCHIVE_BYTES
                && startsWith(storedBytes, ZIP_LOCAL_FILE_HEADER);
    }

    @Override
    public String fileExtension(boolean compressionEnabled) {
        return BinaryReplayFormat.FILE_EXTENSION;
    }

    @Override
    public boolean supportsCompression() {
        return false;
    }

    @Override
    public byte[] encodeTimeline(List<TimelineEvent> timeline, String pluginVersion) throws IOException {
        return finalizer.finalizeReplay("replay", timeline, pluginVersion);
    }

    @Override
    public byte[] finalizeReplay(String replayName, List<TimelineEvent> timeline, String pluginVersion) throws IOException {
        return finalizer.finalizeReplay(replayName, timeline, pluginVersion);
    }

    @Override
    public byte[] finalizeReplay(
            String replayName,
            List<TimelineEvent> timeline,
            String pluginVersion,
            Long recordingStartedAtEpochMillis
    ) throws IOException {
        if (recordingStartedAtEpochMillis == null) {
            return finalizer.finalizeReplay(replayName, timeline, pluginVersion);
        }
        return finalizer.finalizeReplay(replayName, timeline, pluginVersion, recordingStartedAtEpochMillis);
    }

    @Override
    public byte[] finalizeReplay(String replayName, ReplaySaveRequest request, String pluginVersion) throws IOException {
        ReplayChunkData chunkData = resolveChunkData(request);
        long recordingStartedAtEpochMillis = request.recordingStartedAtEpochMillis() != null
                ? request.recordingStartedAtEpochMillis()
                : System.currentTimeMillis();
        return finalizer.finalizeReplay(replayName, request.timeline(), pluginVersion, recordingStartedAtEpochMillis, chunkData);
    }

    @Override
    public List<TimelineEvent> decodeTimeline(byte[] storedBytes, String runningVersion) throws IOException {
        return openReplay(storedBytes, runningVersion).timeline();
    }

    @Override
    public ReplayPlaybackData decodeReplayData(byte[] storedBytes, String runningVersion) throws IOException {
        ParsedBinaryReplay replay = openReplay(storedBytes, runningVersion);
        return new ReplayPlaybackData(replay.timeline(), replay.chunkData());
    }

    @Override
    public ReplayInspection inspectReplay(String replayName, byte[] storedBytes, String runningVersion) throws IOException {
        ArchiveEntries archiveEntries = readArchiveEntries(storedBytes);
        BinaryReplayManifest manifest = parseManifest(archiveEntries.manifestBytes());
        try {
            validateManifest(manifest, archiveEntries.replayBytes(), runningVersion);
        } catch (VersionUtil.ReplayVersionMismatchException ex) {
            // Still surface manifest metadata so operators can inspect incompatible replays.
            InspectedChunkData inspectedChunkData = inspectChunkData(manifest, archiveEntries.chunkEntries());
            return new ReplayInspection(
                    replayName,
                    format(),
                    0,
                    0,
                    0,
                    0,
                    0,
                    storedBytes.length,
                    archiveEntries.replayBytes().length,
                    0,
                    inspectedChunkData.chunkRegionEntryCount(),
                    inspectedChunkData.chunkEntryCount(),
                    inspectedChunkData.compressedChunkPayloadBytes(),
                    inspectedChunkData.decompressedChunkPayloadBytes(),
                    manifest.recordingStartedAtEpochMillis(),
                    manifest.recordedWithVersion(),
                    manifest.minimumViewerVersion(),
                    0,
                    0,
                    false,
                    0);
        }

        byte[] payload = decompress(manifest, archiveEntries.replayBytes());
        validatePayloadHeader(payload);
        ParsedPayload parsedPayload = parsePayload(payload);
        LazyTimeline timeline = new LazyTimeline(payload, parsedPayload.events(), parsedPayload.stringTable(), parsedPayload.tickIndex());
        InspectedChunkData inspectedChunkData = inspectChunkData(manifest, archiveEntries.chunkEntries());

        return ReplayInspectionBuilder.build(
                replayName,
                format(),
                storedBytes.length,
                archiveEntries.replayBytes().length,
                payload.length,
            inspectedChunkData.chunkRegionEntryCount(),
            inspectedChunkData.chunkEntryCount(),
            inspectedChunkData.compressedChunkPayloadBytes(),
            inspectedChunkData.decompressedChunkPayloadBytes(),
                manifest.recordingStartedAtEpochMillis(),
                manifest.recordedWithVersion(),
                manifest.minimumViewerVersion(),
                parsedPayload.indexLoaded(),
                parsedPayload.tickIndex().size(),
                timeline);
    }

    @Override
    public File writeReplayFile(String replayName, byte[] storedBytes, String runningVersion) throws IOException {
        File tempFile = File.createTempFile("replay_" + replayName + "_", BinaryReplayFormat.FILE_EXTENSION);
        tempFile.deleteOnExit();
        Files.write(tempFile.toPath(), storedBytes);
        return tempFile;
    }

    ParsedBinaryReplay openReplay(byte[] storedBytes, String runningVersion) throws IOException {
        ArchiveEntries archiveEntries = readArchiveEntries(storedBytes);
        BinaryReplayManifest manifest = parseManifest(archiveEntries.manifestBytes());
        validateManifest(manifest, archiveEntries.replayBytes(), runningVersion);

        byte[] payload = decompress(manifest, archiveEntries.replayBytes());
        validatePayloadHeader(payload);

        ParsedPayload parsedPayload = parsePayload(payload);
        LazyTimeline timeline = new LazyTimeline(payload, parsedPayload.events(), parsedPayload.stringTable(), parsedPayload.tickIndex());
        return new ParsedBinaryReplay(
            manifest,
            timeline,
            parsedPayload.tickIndex(),
            parsedPayload.stringTable(),
            parsedPayload.indexLoaded(),
            loadChunkData(manifest, archiveEntries.chunkEntries()));
    }

    private ReplayChunkData resolveChunkData(ReplaySaveRequest request) throws IOException {
        ReplayChunkData chunkData = request.chunkData();
        if (chunkData != null && chunkData.hasChunkData()) {
            return chunkData;
        }

        ChunkRecordingArtifacts chunkArtifacts = request.chunkArtifacts();
        if (chunkArtifacts == null || !chunkArtifacts.isPresent()) {
            return ReplayChunkData.NONE;
        }
        return chunkArchiveFinalizer.finalizeArtifacts(chunkArtifacts);
    }

    private ArchiveEntries readArchiveEntries(byte[] storedBytes) throws IOException {
        Objects.requireNonNull(storedBytes, "storedBytes");
        if (storedBytes.length > BinaryReplayReadLimits.MAX_STORED_ARCHIVE_BYTES) {
            throw new IOException("Binary replay archive exceeds the permitted stored size");
        }
        byte[] manifestBytes = null;
        byte[] replayBytes = null;
        Map<String, byte[]> chunkEntries = new HashMap<>();
        Set<String> entryNames = new HashSet<>();
        int entryCount = 0;
        long retainedBytes = 0;

        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(storedBytes))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (++entryCount > BinaryReplayReadLimits.MAX_ZIP_ENTRY_COUNT) {
                    throw new IOException("Binary replay archive contains too many ZIP entries");
                }
                String entryName = entry.getName();
                if (entryName.getBytes(BinaryReplayFormat.STRING_CHARSET).length
                        > BinaryReplayReadLimits.MAX_ZIP_ENTRY_NAME_BYTES) {
                    throw new IOException("Binary replay ZIP entry name exceeds the permitted size");
                }
                if (!entryNames.add(entryName)) {
                    throw new IOException("Binary replay archive contains duplicate entry: " + entryName);
                }
                if (entry.isDirectory() || !isKnownArchiveEntryName(entryName)) {
                    throw new IOException("Binary replay archive contains an invalid or unknown entry: " + entryName);
                }
                if (entry.getMethod() != ZipEntry.STORED) {
                    throw new IOException("Binary replay archive entries must use the STORED ZIP method");
                }

                int entryLimit = entryLimit(entryName);
                long declaredSize = entry.getSize();
                long declaredCompressedSize = entry.getCompressedSize();
                if (declaredSize < 0 || declaredCompressedSize < 0
                        || declaredSize != declaredCompressedSize
                        || declaredSize > entryLimit) {
                    throw new IOException("Binary replay ZIP entry has an invalid declared size: " + entryName);
                }
                retainedBytes += declaredSize;
                if (retainedBytes > BinaryReplayReadLimits.MAX_TOTAL_RETAINED_ZIP_ENTRY_BYTES) {
                    throw new IOException("Binary replay archive retained entries exceed the aggregate limit");
                }

                byte[] bytes = BinaryReplayReadLimits.readAllBytes(zip, entryLimit, "Binary replay ZIP entry " + entryName);
                if (bytes.length != declaredSize) {
                    throw new IOException("Binary replay ZIP entry size does not match its declaration: " + entryName);
                }
                if (BinaryReplayFormat.MANIFEST_ENTRY_NAME.equals(entryName)) {
                    manifestBytes = bytes;
                } else if (BinaryReplayFormat.REPLAY_ENTRY_NAME.equals(entryName)) {
                    replayBytes = bytes;
                } else {
                    chunkEntries.put(entryName, bytes);
                }
                zip.closeEntry();
            }
        } catch (IllegalArgumentException ex) {
            throw new IOException("Binary replay archive contains malformed ZIP metadata", ex);
        }

        if (manifestBytes == null || replayBytes == null) {
            throw new IOException("Binary replay archive is missing required entries");
        }
        return new ArchiveEntries(manifestBytes, replayBytes, Map.copyOf(chunkEntries));
    }

    private static boolean isKnownArchiveEntryName(String entryName) {
        if (BinaryReplayFormat.MANIFEST_ENTRY_NAME.equals(entryName)
                || BinaryReplayFormat.REPLAY_ENTRY_NAME.equals(entryName)) {
            return true;
        }
        Matcher matcher = CHUNK_ENTRY_NAME.matcher(entryName);
        if (!matcher.matches()) {
            return false;
        }
        try {
            Integer.parseInt(matcher.group(2));
            Integer.parseInt(matcher.group(3));
            return true;
        } catch (NumberFormatException ex) {
            return false;
        }
    }

    private static int entryLimit(String entryName) {
        if (BinaryReplayFormat.MANIFEST_ENTRY_NAME.equals(entryName)) {
            return BinaryReplayReadLimits.MAX_MANIFEST_BYTES;
        }
        if (BinaryReplayFormat.REPLAY_ENTRY_NAME.equals(entryName)) {
            return BinaryReplayReadLimits.MAX_COMPRESSED_REPLAY_BYTES;
        }
        return BinaryReplayReadLimits.MAX_CHUNK_REGION_BYTES;
    }

    private ReplayChunkData loadChunkData(BinaryReplayManifest manifest, Map<String, byte[]> chunkEntries) {
        try {
            return inspectChunkData(manifest, chunkEntries).hasChunkData()
                    ? new ReplayChunkData(manifest.chunkMetadata(), chunkEntries)
                    : ReplayChunkData.NONE;
        } catch (IOException | RuntimeException ex) {
            return ReplayChunkData.NONE;
        }
    }

    private InspectedChunkData inspectChunkData(BinaryReplayManifest manifest, Map<String, byte[]> chunkEntries) throws IOException {
        if (!manifest.hasChunkData()) {
            return InspectedChunkData.NONE;
        }
        if (chunkEntries.size() != manifest.chunkRegionEntryCount()) {
            return InspectedChunkData.NONE;
        }

        int chunkEntryCount = 0;
        long compressedChunkPayloadBytes = 0;
        long decompressedChunkPayloadBytes = 0;
        List<String> coordinateDigests = new ArrayList<>();
        for (String entryName : chunkEntries.keySet().stream().sorted(CHUNK_ENTRY_ORDER).toList()) {
            if (!entryName.endsWith(BinaryReplayFormat.CHUNK_REGION_FILE_EXTENSION)) {
                return InspectedChunkData.NONE;
            }

            BinaryChunkRegionCodec.DecodedBinaryChunkRegion decodedRegion = chunkRegionCodec.decode(chunkEntries.get(entryName));
            chunkEntryCount += decodedRegion.entries().size();
            for (BinaryChunkRegionIndexEntry indexEntry : decodedRegion.indexEntries()) {
                compressedChunkPayloadBytes += indexEntry.compressedLength();
                decompressedChunkPayloadBytes += indexEntry.uncompressedLength();
                coordinateDigests.add(entryName + ':' + indexEntry.localChunkX() + ':' + indexEntry.localChunkZ());
            }
        }

        if (chunkEntryCount != manifest.chunkEntryCount()) {
            return InspectedChunkData.NONE;
        }
        String coordinateHash = crc32cHex(coordinateDigests);
        if (manifest.chunkCoordinateHash() != null && !manifest.chunkCoordinateHash().equals(coordinateHash)) {
            return InspectedChunkData.NONE;
        }

        return new InspectedChunkData(
                true,
                manifest.chunkRegionEntryCount(),
                chunkEntryCount,
                compressedChunkPayloadBytes,
                decompressedChunkPayloadBytes);
    }

    private BinaryReplayManifest parseManifest(byte[] manifestBytes) throws IOException {
        try {
            BinaryReplayManifest manifest = gson.fromJson(
                    new String(manifestBytes, BinaryReplayFormat.STRING_CHARSET),
                    BinaryReplayManifest.class);
            if (manifest == null) {
                throw new IOException("Binary replay manifest is empty");
            }
            return manifest;
        } catch (RuntimeException ex) {
            throw new IOException("Failed to parse binary replay manifest", ex);
        }
    }

    private void validateManifest(BinaryReplayManifest manifest, byte[] replayBytes, String runningVersion) throws IOException {
        if (manifest.formatVersion() != BinaryReplayFormat.FORMAT_VERSION) {
            throw new IOException("Unsupported binary replay format version: " + manifest.formatVersion());
        }
        if (!BinaryReplayFormat.PAYLOAD_CHECKSUM_ALGORITHM.equals(manifest.payloadChecksumAlgorithm())) {
            throw new IOException("Unsupported payload checksum algorithm: " + manifest.payloadChecksumAlgorithm());
        }
        if (!VersionUtil.isAtLeast(runningVersion, manifest.minimumViewerVersion())) {
            throw new VersionUtil.ReplayVersionMismatchException(manifest.minimumViewerVersion(), runningVersion);
        }
        String actualChecksum = crc32cHex(replayBytes);
        if (!actualChecksum.equals(manifest.payloadChecksum())) {
            throw new IOException("Binary replay payload checksum mismatch");
        }
    }

    private static byte[] decompress(BinaryReplayManifest manifest, byte[] replayBytes) throws IOException {
        BinaryReplayPayloadCompression compression = manifest.payloadCompression() != null
                ? BinaryReplayPayloadCompression.fromManifestValue(manifest.payloadCompression())
                : BinaryReplayPayloadCompression.detect(replayBytes);
        return compression.decompress(replayBytes);
    }

    private static void validatePayloadHeader(byte[] payload) throws IOException {
        if (payload.length < BinaryReplayFormat.PAYLOAD_HEADER_SIZE) {
            throw new IOException("Binary replay payload is too short");
        }
        if (!Arrays.equals(Arrays.copyOfRange(payload, 0, BinaryReplayFormat.PAYLOAD_MAGIC.length), BinaryReplayFormat.PAYLOAD_MAGIC)) {
            throw new IOException("Invalid binary replay payload magic");
        }
        if ((payload[4] & 0xFF) != BinaryReplayFormat.FORMAT_VERSION) {
            throw new IOException("Unsupported binary replay payload version: " + (payload[4] & 0xFF));
        }
        if ((payload[5] & 0xFF) != BinaryReplayFormat.PAYLOAD_FLAGS_NONE) {
            throw new IOException("Unsupported binary replay payload flags: " + (payload[5] & 0xFF));
        }
        if (payload[6] != 0 || payload[7] != 0) {
            throw new IOException("Binary replay payload reserved header bytes must be zero");
        }
    }

    private static ParsedPayload parsePayload(byte[] payload) throws IOException {
        IndexSection indexSection = tryParseIndexSection(payload);
        int eventSectionEnd = indexSection != null ? indexSection.indexSectionOffset() : payload.length;
        ScannedEventStream scanned = scanEventStream(payload, BinaryReplayFormat.PAYLOAD_HEADER_SIZE, eventSectionEnd);

        List<String> stringTable = indexSection != null ? indexSection.stringTable() : scanned.stringTable();
        List<BinaryTickIndexEntry> tickIndex = indexSection != null ? indexSection.tickIndex() : rebuildTickIndex(scanned.events());

        if (indexSection != null && !scanned.stringTable().equals(stringTable)) {
            throw new IOException("Binary replay string table does not match finalized index section");
        }
        if (indexSection != null) {
            validateFinalizedTickIndex(tickIndex, scanned.events());
        }

        return new ParsedPayload(scanned.events(), stringTable, tickIndex, indexSection != null);
    }

    private static IndexSection tryParseIndexSection(byte[] payload) throws IOException {
        if (payload.length < BinaryReplayFormat.PAYLOAD_HEADER_SIZE + BinaryReplayFormat.INDEX_SECTION_FOOTER_BYTES) {
            return null;
        }

        int footerOffset = payload.length - BinaryReplayFormat.INDEX_SECTION_FOOTER_BYTES;
        long indexSectionOffsetLong = ByteBuffer.wrap(payload, footerOffset, BinaryReplayFormat.INDEX_SECTION_FOOTER_BYTES)
                .order(BinaryReplayFormat.PRIMITIVE_BYTE_ORDER)
                .getLong();
        if (indexSectionOffsetLong < BinaryReplayFormat.PAYLOAD_HEADER_SIZE || indexSectionOffsetLong >= footerOffset) {
            return null;
        }

        int indexSectionOffset = Math.toIntExact(indexSectionOffsetLong);
        if (indexSectionOffset + BinaryReplayFormat.INDEX_SECTION_MAGIC.length > footerOffset) {
            return null;
        }
        if (!Arrays.equals(
                Arrays.copyOfRange(payload, indexSectionOffset, indexSectionOffset + BinaryReplayFormat.INDEX_SECTION_MAGIC.length),
                BinaryReplayFormat.indexSectionMagicBytes())) {
            return null;
        }

        try {
            int cursorOffset = indexSectionOffset + BinaryReplayFormat.INDEX_SECTION_MAGIC.length;
            VarIntRead stringCountRead = readVarInt(payload, cursorOffset, footerOffset);
            int stringCount = stringCountRead.value();
            cursorOffset = stringCountRead.nextOffset();
            validateStringTableCount(stringCount);
            if (stringCount > footerOffset - cursorOffset) {
                throw new IOException("Invalid binary replay string-table count");
            }
            List<String> stringTable = new ArrayList<>(stringCount);
            for (int index = 0; index < stringCount; index++) {
                VarIntRead stringLengthRead = readVarInt(payload, cursorOffset, footerOffset);
                int stringLength = stringLengthRead.value();
                long stringEnd = (long) stringLengthRead.nextOffset() + stringLength;
                if (stringLength < 0 || stringLength > BinaryReplayReadLimits.MAX_STRING_BYTES
                        || stringEnd > footerOffset) {
                    throw new IOException("Invalid or oversized string in binary replay index");
                }
                stringTable.add(new String(
                        payload,
                        stringLengthRead.nextOffset(),
                        stringLength,
                        BinaryReplayFormat.STRING_CHARSET));
                cursorOffset = (int) stringEnd;
            }

            VarIntRead tickCountRead = readVarInt(payload, cursorOffset, footerOffset);
            int tickIndexCount = tickCountRead.value();
            cursorOffset = tickCountRead.nextOffset();
            validateTickIndexCount(tickIndexCount);
            if (tickIndexCount > (footerOffset - cursorOffset) / BinaryReplayFormat.TICK_INDEX_ENTRY_BYTES
                    || (long) cursorOffset + (long) tickIndexCount * BinaryReplayFormat.TICK_INDEX_ENTRY_BYTES != footerOffset) {
                throw new IOException("Invalid binary replay tick-index count");
            }
            List<BinaryTickIndexEntry> tickIndex = new ArrayList<>(tickIndexCount);
            for (int index = 0; index < tickIndexCount; index++) {
                ByteBuffer row = ByteBuffer.wrap(payload, cursorOffset, BinaryReplayFormat.TICK_INDEX_ENTRY_BYTES)
                        .order(BinaryReplayFormat.PRIMITIVE_BYTE_ORDER);
                tickIndex.add(new BinaryTickIndexEntry(row.getInt(), row.getLong()));
                cursorOffset += BinaryReplayFormat.TICK_INDEX_ENTRY_BYTES;
            }
            return new IndexSection(indexSectionOffset, stringTable, tickIndex);
        } catch (IllegalArgumentException | IndexOutOfBoundsException ex) {
            throw new IOException("Failed to parse binary replay index section", ex);
        }
    }

    private static ScannedEventStream scanEventStream(byte[] payload, int startOffset, int endOffset) throws IOException {
        int offset = startOffset;
        List<EventSlice> events = new ArrayList<>();
        List<String> stringTable = new ArrayList<>();

        while (offset < endOffset) {
            VarIntRead recordLengthRead = readVarInt(payload, offset, endOffset);
            int recordContentOffset = recordLengthRead.nextOffset();
            long recordContentEndLong = (long) recordContentOffset + recordLengthRead.value();
            if (recordLengthRead.value() < 0 || recordContentEndLong > endOffset) {
                throw new IOException("Malformed finalized replay record length");
            }
            int recordContentEnd = (int) recordContentEndLong;

            VarIntRead recordTypeRead = readVarInt(payload, recordContentOffset, recordContentEnd);
            BinaryRecordType recordType = BinaryRecordType.fromTag(recordTypeRead.value())
                    .orElseThrow(() -> new IOException("Unknown binary replay record tag: " + recordTypeRead.value()));

            int eventPayloadOffset = recordTypeRead.nextOffset();
            int eventPayloadLength = recordContentEnd - eventPayloadOffset;

            if (recordType == BinaryRecordType.DEFINE_STRING) {
                byte[] eventPayload = Arrays.copyOfRange(payload, eventPayloadOffset, recordContentEnd);
                validateDefinedStringLength(eventPayload);
                BinaryReplayAppendLogCodec.DefinedString definedString = BinaryReplayAppendLogCodec.decodeDefineString(eventPayload);
                if (definedString.index() != stringTable.size()) {
                    throw new IOException("Invalid string-table index in finalized replay: " + definedString.index());
                }
                validateStringTableCount(stringTable.size() + 1);
                stringTable.add(definedString.value());
            } else {
                if (eventPayloadLength < Integer.BYTES) {
                    throw new IOException("Finalized replay event payload is too short to contain a tick");
                }
                int tick = readLittleEndianInt(payload, eventPayloadOffset);
                validateTimelineEventCount(events.size() + 1);
                events.add(new EventSlice(recordType, offset, eventPayloadOffset, eventPayloadLength, tick));
            }

            offset = recordContentEnd;
        }

        return new ScannedEventStream(events, stringTable);
    }

    static void validateStringTableCount(int count) throws IOException {
        validateCount(count, BinaryReplayReadLimits.MAX_STRING_TABLE_ENTRIES, "string-table");
    }

    static void validateTickIndexCount(int count) throws IOException {
        validateCount(count, BinaryReplayReadLimits.MAX_TICK_INDEX_ENTRIES, "tick-index");
    }

    static void validateTimelineEventCount(int count) throws IOException {
        validateCount(count, BinaryReplayReadLimits.MAX_TIMELINE_EVENT_COUNT, "timeline event");
    }

    private static void validateCount(int count, int maximum, String description) throws IOException {
        if (count < 0 || count > maximum) {
            throw new IOException("Invalid or excessive binary replay " + description + " count: " + count);
        }
    }

    private static void validateFinalizedTickIndex(
            List<BinaryTickIndexEntry> tickIndex,
            List<EventSlice> events
    ) throws IOException {
        validateTickIndexCount(tickIndex.size());
        if (events.isEmpty()) {
            if (!tickIndex.isEmpty()) {
                throw new IOException("Empty binary replay has a nonempty tick index");
            }
            return;
        }
        if (tickIndex.isEmpty()) {
            if (events.getFirst().tick() <= 0) {
                throw new IOException("Binary replay is missing its initial tick-index checkpoint");
            }
            return;
        }
        if (tickIndex.getFirst().tick() != 0 || tickIndex.size() > events.size()) {
            throw new IOException("Binary replay tick index has invalid nonempty semantics");
        }

        Map<Long, Integer> eventIndexesByOffset = new HashMap<>();
        for (int eventIndex = 0; eventIndex < events.size(); eventIndex++) {
            eventIndexesByOffset.put(events.get(eventIndex).recordOffset(), eventIndex);
        }

        int previousTick = -1;
        int previousEventIndex = -1;
        for (BinaryTickIndexEntry checkpoint : tickIndex) {
            if (checkpoint.tick() <= previousTick) {
                throw new IOException("Binary replay tick-index checkpoints are not strictly increasing");
            }
            Integer eventIndex = eventIndexesByOffset.get(checkpoint.byteOffset());
            if (eventIndex == null || eventIndex <= previousEventIndex) {
                throw new IOException("Binary replay tick-index offset does not identify a forward event record");
            }
            EventSlice referencedEvent = events.get(eventIndex);
            if (referencedEvent.tick() > checkpoint.tick()) {
                throw new IOException("Binary replay tick index seeks past its checkpoint");
            }
            if (eventIndex + 1 < events.size() && checkpoint.tick() > events.get(eventIndex + 1).tick()) {
                throw new IOException("Binary replay tick index checkpoint is not useful for seeking");
            }
            previousTick = checkpoint.tick();
            previousEventIndex = eventIndex;
        }
    }

    private static void validateDefinedStringLength(byte[] eventPayload) throws IOException {
        VarIntRead index = readVarInt(eventPayload, 0, eventPayload.length);
        VarIntRead length = readVarInt(eventPayload, index.nextOffset(), eventPayload.length);
        if (length.value() < 0 || length.value() > BinaryReplayReadLimits.MAX_STRING_BYTES
                || (long) length.nextOffset() + length.value() != eventPayload.length) {
            throw new IOException("Invalid or oversized string in finalized replay");
        }
    }

    private static List<BinaryTickIndexEntry> rebuildTickIndex(List<EventSlice> events) {
        if (events.isEmpty()) {
            return List.of();
        }

        List<BinaryTickIndexEntry> tickIndex = new ArrayList<>();
        long lastEventOffset = -1;
        long nextCheckpointTick = 0;
        boolean indexStarted = false;

        for (EventSlice event : events) {
            if (!indexStarted && event.tick() <= 0) {
                tickIndex.add(new BinaryTickIndexEntry(0, event.recordOffset()));
                nextCheckpointTick = BinaryReplayFormat.TICK_INDEX_INTERVAL;
                indexStarted = true;
            } else if (indexStarted) {
                if (nextCheckpointTick <= event.tick()) {
                    int checkpointTick = event.tick() - event.tick() % BinaryReplayFormat.TICK_INDEX_INTERVAL;
                    tickIndex.add(new BinaryTickIndexEntry(checkpointTick, lastEventOffset));
                    nextCheckpointTick = (long) checkpointTick + BinaryReplayFormat.TICK_INDEX_INTERVAL;
                }
            }
            lastEventOffset = event.recordOffset();
        }

        return tickIndex;
    }

    private static VarIntRead readVarInt(byte[] bytes, int startOffset, int endOffset) throws IOException {
        int value = 0;
        int shift = 0;
        int offset = startOffset;
        while (offset < endOffset) {
            int current = bytes[offset++] & 0xFF;
            value |= (current & 0x7F) << shift;
            if ((current & 0x80) == 0) {
                return new VarIntRead(value, offset);
            }
            shift += 7;
            if (shift > 28) {
                throw new IOException("VarInt is too large in binary replay payload");
            }
        }
        throw new IOException("Unexpected end of payload while reading VarInt");
    }

    private static int readLittleEndianInt(byte[] bytes, int offset) {
        return ByteBuffer.wrap(bytes, offset, Integer.BYTES)
                .order(BinaryReplayFormat.PRIMITIVE_BYTE_ORDER)
                .getInt();
    }

    private static String crc32cHex(byte[] bytes) {
        CRC32C crc32c = new CRC32C();
        crc32c.update(bytes, 0, bytes.length);
        return "%08x".formatted(crc32c.getValue());
    }

    private static boolean startsWith(byte[] bytes, byte[] prefix) {
        return bytes.length >= prefix.length && Arrays.equals(Arrays.copyOf(bytes, prefix.length), prefix);
    }

    private static String crc32cHex(List<String> coordinateDigests) {
        CRC32C crc32c = new CRC32C();
        for (String digest : coordinateDigests) {
            byte[] bytes = digest.getBytes(BinaryReplayFormat.STRING_CHARSET);
            crc32c.update(bytes, 0, bytes.length);
            crc32c.update('\n');
        }
        return "%08x".formatted(crc32c.getValue());
    }

    record ParsedBinaryReplay(
            BinaryReplayManifest manifest,
            LazyTimeline timeline,
            List<BinaryTickIndexEntry> tickIndex,
            List<String> stringTable,
            boolean indexLoaded,
            ReplayChunkData chunkData
    ) {
    }

    static final class LazyTimeline extends AbstractList<TimelineEvent> implements ReplayIndexedTimeline {

        private final byte[] payload;
        private final List<EventSlice> events;
        private final List<String> stringTable;
        private final List<BinaryTickIndexEntry> tickIndex;
        private final Map<Long, Integer> eventIndexByOffset;

        LazyTimeline(byte[] payload, List<EventSlice> events, List<String> stringTable, List<BinaryTickIndexEntry> tickIndex) {
            this.payload = payload;
            this.events = List.copyOf(events);
            this.stringTable = List.copyOf(stringTable);
            this.tickIndex = List.copyOf(tickIndex);
            Map<Long, Integer> offsetIndex = new HashMap<>();
            for (int index = 0; index < events.size(); index++) {
                offsetIndex.put(events.get(index).recordOffset(), index);
            }
            this.eventIndexByOffset = Map.copyOf(offsetIndex);
        }

        @Override
        public TimelineEvent get(int index) {
            EventSlice slice = events.get(index);
            byte[] eventPayload = Arrays.copyOfRange(payload, slice.payloadOffset(), slice.payloadOffset() + slice.payloadLength());
            return BinaryReplayAppendLogCodec.decodeEvent(slice.recordType(), eventPayload, stringTable);
        }

        @Override
        public int size() {
            return events.size();
        }

        @Override
        public int findEventIndexAtOrAfterTick(int targetTick) {
            int candidateIndex = 0;
            if (!tickIndex.isEmpty()) {
                BinaryTickIndexEntry checkpoint = tickIndex.get(findCheckpointIndex(targetTick));
                candidateIndex = eventIndexByOffset.getOrDefault(checkpoint.byteOffset(), 0);
            }
            while (candidateIndex < events.size() && events.get(candidateIndex).tick() < targetTick) {
                candidateIndex++;
            }
            return candidateIndex;
        }

        private int findCheckpointIndex(int targetTick) {
            int low = 0;
            int high = tickIndex.size() - 1;
            int best = 0;
            while (low <= high) {
                int mid = (low + high) >>> 1;
                int checkpointTick = tickIndex.get(mid).tick();
                if (checkpointTick <= targetTick) {
                    best = mid;
                    low = mid + 1;
                } else {
                    high = mid - 1;
                }
            }
            return best;
        }
    }

    private record ArchiveEntries(byte[] manifestBytes, byte[] replayBytes, Map<String, byte[]> chunkEntries) {
    }

    private record InspectedChunkData(
            boolean hasChunkData,
            int chunkRegionEntryCount,
            int chunkEntryCount,
            long compressedChunkPayloadBytes,
            long decompressedChunkPayloadBytes
    ) {
        private static final InspectedChunkData NONE = new InspectedChunkData(false, 0, 0, 0, 0);
    }

    private record ParsedPayload(List<EventSlice> events, List<String> stringTable, List<BinaryTickIndexEntry> tickIndex, boolean indexLoaded) {
    }

    private record IndexSection(int indexSectionOffset, List<String> stringTable, List<BinaryTickIndexEntry> tickIndex) {
    }

    private record ScannedEventStream(List<EventSlice> events, List<String> stringTable) {
    }

    private record EventSlice(BinaryRecordType recordType, long recordOffset, int payloadOffset, int payloadLength, int tick) {
    }

    private record VarIntRead(int value, int nextOffset) {
    }
}

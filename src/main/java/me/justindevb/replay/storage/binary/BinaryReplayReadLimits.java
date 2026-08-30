package me.justindevb.replay.storage.binary;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Objects;

/**
 * Fixed allocation and retention limits for untrusted binary replay data.
 */
public final class BinaryReplayReadLimits {

    private static final int MEBIBYTE = 1024 * 1024;

    public static final int MAX_STORED_ARCHIVE_BYTES = 128 * MEBIBYTE;
    public static final int MAX_ZIP_ENTRY_COUNT = 65_536;
    public static final int MAX_ZIP_ENTRY_NAME_BYTES = 1024;
    public static final int MAX_MANIFEST_BYTES = MEBIBYTE;
    public static final int MAX_COMPRESSED_REPLAY_BYTES = 128 * MEBIBYTE;
    public static final int MAX_DECODED_TIMELINE_BYTES = 256 * MEBIBYTE;
    public static final int MAX_CHUNK_REGION_BYTES = 64 * MEBIBYTE;
    public static final int MAX_TOTAL_RETAINED_ZIP_ENTRY_BYTES = 128 * MEBIBYTE;
    public static final int MAX_DECODED_CHUNK_BYTES = 8 * MEBIBYTE;
    public static final int MAX_APPEND_LOG_RECORD_BYTES = 128 * MEBIBYTE;
    public static final int MAX_TEMP_REGION_BYTES = MAX_STORED_ARCHIVE_BYTES;
    public static final int MAX_STRING_BYTES = MEBIBYTE;
    public static final int MAX_ITEM_NBT_BYTES = 2 * MEBIBYTE;
    public static final int MAX_REGION_CHUNKS = 1024;
    public static final int MAX_TIMELINE_EVENT_COUNT = 2_000_000;
    public static final int MAX_STRING_TABLE_ENTRIES = 1_000_000;
    public static final int MAX_TICK_INDEX_ENTRIES = 2_000_000;

    private BinaryReplayReadLimits() {
    }

    public static byte[] readAllBytes(InputStream input, int maximumBytes, String description) throws IOException {
        Objects.requireNonNull(input, "input");
        if (maximumBytes < 0) {
            throw new IllegalArgumentException("maximumBytes must not be negative");
        }

        ByteArrayOutputStream output = new ByteArrayOutputStream(Math.min(maximumBytes, 8192));
        byte[] buffer = new byte[8192];
        int total = 0;
        while (true) {
            int remaining = maximumBytes - total;
            int requested = remaining < buffer.length ? remaining + 1 : buffer.length;
            int read = input.read(buffer, 0, requested);
            if (read < 0) {
                return output.toByteArray();
            }
            if (read == 0) {
                continue;
            }
            if (read > maximumBytes - total) {
                throw new IOException(description + " exceeds the limit of " + maximumBytes + " bytes");
            }
            output.write(buffer, 0, read);
            total += read;
        }
    }
}

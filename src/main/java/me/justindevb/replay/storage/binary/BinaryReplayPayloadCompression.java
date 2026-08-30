package me.justindevb.replay.storage.binary;

import com.github.luben.zstd.ZstdInputStream;
import com.github.luben.zstd.ZstdOutputStream;
import net.jpountz.lz4.LZ4FrameInputStream;
import net.jpountz.lz4.LZ4FrameOutputStream;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.Locale;
import java.util.Objects;

/**
 * Frozen compression codecs for finalized binary replay payload bytes.
 */
public enum BinaryReplayPayloadCompression {
    LZ4_FRAME("lz4_frame", new byte[] {0x04, 0x22, 0x4D, 0x18}),
    ZSTD("zstd", new byte[] {0x28, (byte) 0xB5, 0x2F, (byte) 0xFD});

    public static final BinaryReplayPayloadCompression DEFAULT = ZSTD;
    public static final int ZSTD_LEVEL = 1;
    private static final int MIN_COMPATIBLE_ZSTD_WINDOW_LOG = 19;

    private final String manifestValue;
    private final byte[] magicBytes;

    BinaryReplayPayloadCompression(String manifestValue, byte[] magicBytes) {
        this.manifestValue = manifestValue;
        this.magicBytes = magicBytes;
    }

    public String manifestValue() {
        return manifestValue;
    }

    public byte[] compress(byte[] payload) throws IOException {
        Objects.requireNonNull(payload, "payload");
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        switch (this) {
            case LZ4_FRAME -> {
                try (LZ4FrameOutputStream lz4 = new LZ4FrameOutputStream(out)) {
                    lz4.write(payload);
                }
            }
            case ZSTD -> {
                try (ZstdOutputStream zstd = new ZstdOutputStream(out, ZSTD_LEVEL)) {
                    zstd.write(payload);
                }
            }
        }
        return out.toByteArray();
    }

    public byte[] decompress(byte[] compressedPayload) throws IOException {
        return decompressBounded(compressedPayload, BinaryReplayReadLimits.MAX_DECODED_TIMELINE_BYTES);
    }

    public byte[] decompress(byte[] compressedPayload, int expectedLength) throws IOException {
        if (expectedLength <= 0 || expectedLength > BinaryReplayReadLimits.MAX_DECODED_CHUNK_BYTES) {
            throw new IOException("Decoded chunk length is outside the permitted range: " + expectedLength);
        }
        byte[] payload = decompressBounded(compressedPayload, expectedLength);
        if (payload.length != expectedLength) {
            throw new IOException("Decoded payload length mismatch: expected " + expectedLength + " but got " + payload.length);
        }
        return payload;
    }

    private byte[] decompressBounded(byte[] compressedPayload, int maximumDecodedBytes) throws IOException {
        Objects.requireNonNull(compressedPayload, "compressedPayload");
        if (compressedPayload.length > BinaryReplayReadLimits.MAX_COMPRESSED_REPLAY_BYTES) {
            throw new IOException("Compressed payload exceeds the permitted size");
        }
        if (!startsWith(compressedPayload, magicBytes)) {
            throw new IOException("Compressed payload magic does not match codec " + manifestValue);
        }
        try {
            return switch (this) {
                case LZ4_FRAME -> {
                    try (LZ4FrameInputStream lz4 = new LZ4FrameInputStream(new ByteArrayInputStream(compressedPayload))) {
                        yield BinaryReplayReadLimits.readAllBytes(lz4, maximumDecodedBytes, "Decoded LZ4 payload");
                    }
                }
                case ZSTD -> {
                    try (ZstdInputStream zstd = new ZstdInputStream(new ByteArrayInputStream(compressedPayload))) {
                        zstd.setLongMax(zstdWindowLogForMaximumDecodedBytes(maximumDecodedBytes));
                        yield BinaryReplayReadLimits.readAllBytes(zstd, maximumDecodedBytes, "Decoded Zstandard payload");
                    }
                }
            };
        } catch (RuntimeException ex) {
            throw new IOException("Failed to decompress " + manifestValue + " payload", ex);
        }
    }

    static int zstdWindowLogForMaximumDecodedBytes(int maximumDecodedBytes) {
        if (maximumDecodedBytes <= 0) {
            throw new IllegalArgumentException("maximumDecodedBytes must be positive");
        }
        int ceilLog2 = Integer.SIZE - Integer.numberOfLeadingZeros(maximumDecodedBytes - 1);
        return Math.max(MIN_COMPATIBLE_ZSTD_WINDOW_LOG, ceilLog2);
    }

    public static BinaryReplayPayloadCompression fromManifestValue(String value) throws IOException {
        if (value == null || value.isBlank()) {
            throw new IOException("Binary replay payload compression is missing");
        }
        String normalized = value.toLowerCase(Locale.ROOT);
        for (BinaryReplayPayloadCompression compression : values()) {
            if (compression.manifestValue.equals(normalized)) {
                return compression;
            }
        }
        throw new IOException("Unsupported binary replay payload compression: " + value);
    }

    public static BinaryReplayPayloadCompression detect(byte[] compressedPayload) throws IOException {
        Objects.requireNonNull(compressedPayload, "compressedPayload");
        for (BinaryReplayPayloadCompression compression : values()) {
            if (startsWith(compressedPayload, compression.magicBytes)) {
                return compression;
            }
        }
        throw new IOException("Unsupported binary replay payload compression magic");
    }

    private static boolean startsWith(byte[] bytes, byte[] prefix) {
        return bytes.length >= prefix.length && Arrays.equals(Arrays.copyOf(bytes, prefix.length), prefix);
    }
}

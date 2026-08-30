package me.justindevb.replay.storage.binary;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.io.IOException;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class BinaryReplayPayloadCompressionTest {

    @org.junit.jupiter.api.Test
    void zstdWindowLogRoundsDecodedPolicyUpWithValidMinimum() {
        assertEquals(19, BinaryReplayPayloadCompression.zstdWindowLogForMaximumDecodedBytes(1));
        assertEquals(19, BinaryReplayPayloadCompression.zstdWindowLogForMaximumDecodedBytes(1024));
        assertEquals(19, BinaryReplayPayloadCompression.zstdWindowLogForMaximumDecodedBytes(1025));
        assertEquals(28, BinaryReplayPayloadCompression.zstdWindowLogForMaximumDecodedBytes(
                BinaryReplayReadLimits.MAX_DECODED_TIMELINE_BYTES));
    }

    @ParameterizedTest
    @EnumSource(BinaryReplayPayloadCompression.class)
    void exactLengthDecompressionPreservesValidPayload(BinaryReplayPayloadCompression compression) throws Exception {
        byte[] payload = "bounded replay payload".getBytes(BinaryReplayFormat.STRING_CHARSET);

        assertArrayEquals(payload, compression.decompress(compression.compress(payload), payload.length));
    }

    @ParameterizedTest
    @EnumSource(BinaryReplayPayloadCompression.class)
    void exactLengthDecompressionRejectsLengthMismatch(BinaryReplayPayloadCompression compression) throws Exception {
        byte[] compressed = compression.compress(new byte[] {1, 2, 3});

        assertThrows(IOException.class, () -> compression.decompress(compressed, 2));
    }

    @ParameterizedTest
    @EnumSource(BinaryReplayPayloadCompression.class)
    void exactLengthDecompressionRejectsZeroLength(BinaryReplayPayloadCompression compression) throws Exception {
        byte[] compressed = compression.compress(new byte[] {1});

        assertThrows(IOException.class, () -> compression.decompress(compressed, 0));
    }

    @ParameterizedTest
    @EnumSource(BinaryReplayPayloadCompression.class)
    void decompressionRejectsMismatchedFrameMagic(BinaryReplayPayloadCompression compression) throws Exception {
        BinaryReplayPayloadCompression other = compression == BinaryReplayPayloadCompression.LZ4_FRAME
                ? BinaryReplayPayloadCompression.ZSTD
                : BinaryReplayPayloadCompression.LZ4_FRAME;

        assertThrows(IOException.class, () -> compression.decompress(other.compress(new byte[] {1})));
    }

    @ParameterizedTest
    @EnumSource(BinaryReplayPayloadCompression.class)
    void decompressionRejectsTruncatedFrames(BinaryReplayPayloadCompression compression) throws Exception {
        byte[] compressed = compression.compress(new byte[] {1, 2, 3, 4});

        assertThrows(IOException.class, () -> compression.decompress(Arrays.copyOf(compressed, compressed.length - 1)));
    }
}

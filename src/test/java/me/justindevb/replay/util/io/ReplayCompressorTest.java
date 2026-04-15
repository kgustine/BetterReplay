package me.justindevb.replay.util.io;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

class ReplayCompressorTest {

    @Test
    void compressDecompressRoundtrip() throws IOException {
        String original = "{\"type\":\"player_move\",\"tick\":42}";
        byte[] compressed = ReplayCompressor.compress(original);
        String decompressed = ReplayCompressor.decompress(compressed);
        assertEquals(original, decompressed);
    }

    @Test
    void isGzipCompressed_trueForCompressed() throws IOException {
        byte[] compressed = ReplayCompressor.compress("test");
        assertTrue(ReplayCompressor.isGzipCompressed(compressed));
    }

    @Test
    void isGzipCompressed_falseForPlainText() {
        byte[] plain = "hello world".getBytes(StandardCharsets.UTF_8);
        assertFalse(ReplayCompressor.isGzipCompressed(plain));
    }

    @Test
    void isGzipCompressed_falseForNull() {
        assertFalse(ReplayCompressor.isGzipCompressed(null));
    }

    @Test
    void isGzipCompressed_falseForEmptyArray() {
        assertFalse(ReplayCompressor.isGzipCompressed(new byte[0]));
    }

    @Test
    void isGzipCompressed_falseForSingleByte() {
        assertFalse(ReplayCompressor.isGzipCompressed(new byte[]{0x1f}));
    }

    @Test
    void decompressIfNeeded_handlesCompressed() throws IOException {
        String original = "compressed content";
        byte[] compressed = ReplayCompressor.compress(original);
        assertEquals(original, ReplayCompressor.decompressIfNeeded(compressed));
    }

    @Test
    void decompressIfNeeded_handlesUncompressed() throws IOException {
        String original = "plain text content";
        byte[] plain = original.getBytes(StandardCharsets.UTF_8);
        assertEquals(original, ReplayCompressor.decompressIfNeeded(plain));
    }

    @Test
    void compressDecompress_emptyString() throws IOException {
        byte[] compressed = ReplayCompressor.compress("");
        assertEquals("", ReplayCompressor.decompress(compressed));
    }

    @Test
    void compressDecompress_unicodeContent() throws IOException {
        String original = "Ünïcödé → 日本語 ← emoji 🎮";
        byte[] compressed = ReplayCompressor.compress(original);
        assertEquals(original, ReplayCompressor.decompress(compressed));
    }

    @Test
    void compressDecompress_largePayload() throws IOException {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 10_000; i++) {
            sb.append("{\"tick\":").append(i).append(",\"type\":\"player_move\"},\n");
        }
        String original = sb.toString();
        byte[] compressed = ReplayCompressor.compress(original);

        // Compression should reduce size significantly for repetitive data
        assertTrue(compressed.length < original.getBytes(StandardCharsets.UTF_8).length);
        assertEquals(original, ReplayCompressor.decompress(compressed));
    }
}

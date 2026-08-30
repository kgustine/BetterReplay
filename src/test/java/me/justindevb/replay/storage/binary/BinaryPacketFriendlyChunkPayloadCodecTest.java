package me.justindevb.replay.storage.binary;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class BinaryPacketFriendlyChunkPayloadCodecTest {

    private final BinaryPacketFriendlyChunkPayloadCodec codec = new BinaryPacketFriendlyChunkPayloadCodec();

    @Test
    void roundTripsPacketFriendlyChunkPayload() throws Exception {
        long[] blockWords = new long[128];
        blockWords[0] = 0x0123_4567_89AB_CDEFL;
        blockWords[127] = 0x0FED_CBA9_8765_4321L;
        long[] biomeWords = new long[] {0x0000_0000_0000_00AAL};

        BinaryPacketFriendlyChunkPayloadCodec.PacketFriendlyChunkPayload payload =
                new BinaryPacketFriendlyChunkPayloadCodec.PacketFriendlyChunkPayload(
                        -4,
                        List.of(
                                new BinaryPacketFriendlyChunkPayloadCodec.SectionPayload(
                                        List.of("minecraft:air", "minecraft:stone", "minecraft:dirt", "minecraft:grass_block"),
                                        2,
                                        blockWords,
                                        List.of("minecraft:plains", "minecraft:savanna"),
                                        1,
                                        biomeWords),
                                new BinaryPacketFriendlyChunkPayloadCodec.SectionPayload(
                                        List.of("minecraft:bedrock"),
                                        0,
                                        new long[0],
                                        List.of("minecraft:plains"),
                                        0,
                                        new long[0])
                        ),
                        List.of(new BinaryPacketFriendlyChunkPayloadCodec.BlockEntityPayload(
                                3,
                                20,
                                7,
                                "minecraft:chest",
                                new byte[] {0x0A, 0x00, 0x00})));

        byte[] encoded = codec.encode(payload);
        BinaryPacketFriendlyChunkPayloadCodec.PacketFriendlyChunkPayload decoded = codec.decode(encoded);

        assertEquals(-4, decoded.minSectionY());
        assertEquals(2, decoded.sections().size());
        assertEquals(List.of("minecraft:air", "minecraft:stone", "minecraft:dirt", "minecraft:grass_block"),
                decoded.sections().get(0).blockPalette());
        assertEquals(2, decoded.sections().get(0).blockBitsPerEntry());
        assertArrayEquals(blockWords, decoded.sections().get(0).blockWords());
        assertEquals(List.of("minecraft:plains", "minecraft:savanna"), decoded.sections().get(0).biomePalette());
        assertEquals(1, decoded.sections().get(0).biomeBitsPerEntry());
        assertArrayEquals(biomeWords, decoded.sections().get(0).biomeWords());
        assertEquals(List.of("minecraft:bedrock"), decoded.sections().get(1).blockPalette());
        assertEquals(0, decoded.sections().get(1).blockBitsPerEntry());
        assertEquals(1, decoded.blockEntities().size());
        assertEquals(3, decoded.blockEntities().get(0).localX());
        assertEquals(20, decoded.blockEntities().get(0).yOffset());
        assertEquals(7, decoded.blockEntities().get(0).localZ());
        assertEquals("minecraft:chest", decoded.blockEntities().get(0).typeKey());
        assertArrayEquals(new byte[] {0x0A, 0x00, 0x00}, decoded.blockEntities().get(0).nbtBytes());
    }

    @Test
    void rejectsUnsupportedPayloadFlags() throws Exception {
        byte[] encoded = codec.encode(new BinaryPacketFriendlyChunkPayloadCodec.PacketFriendlyChunkPayload(
                0,
                List.of(new BinaryPacketFriendlyChunkPayloadCodec.SectionPayload(
                        List.of("minecraft:air"),
                        0,
                        new long[0],
                        List.of("minecraft:plains"),
                        0,
                        new long[0])),
                List.of()));

        encoded[5] = (byte) (encoded[5] | 0x04);

        assertThrows(IOException.class, () -> codec.decode(encoded));
    }

    @Test
    void rejectsInvalidPackedWordCount() {
        assertThrows(IllegalArgumentException.class, () -> codec.encode(
                new BinaryPacketFriendlyChunkPayloadCodec.PacketFriendlyChunkPayload(
                        0,
                        List.of(new BinaryPacketFriendlyChunkPayloadCodec.SectionPayload(
                                List.of("minecraft:air", "minecraft:stone"),
                                1,
                                new long[63],
                                List.of("minecraft:plains"),
                                0,
                                new long[0])),
                        List.of())));
    }

    @Test
    void rejectsSectionCountThatCannotFitInPayload() {
        byte[] encoded = encodedEmptyChunk();
        ByteBuffer.wrap(encoded, 12, Integer.BYTES)
                .order(BinaryReplayFormat.PRIMITIVE_BYTE_ORDER)
                .putInt(10_000);

        assertThrows(IOException.class, () -> codec.decode(encoded));
    }

    @Test
    void rejectsPaletteLargerThanSectionCellCount() {
        byte[] encoded = encodedEmptyChunk();
        byte[] paletteSize = BinaryEncoding.encodeVarInt(4_097);
        byte[] malicious = new byte[16 + 1 + paletteSize.length];
        System.arraycopy(encoded, 0, malicious, 0, 17);
        System.arraycopy(paletteSize, 0, malicious, 17, paletteSize.length);

        assertThrows(IOException.class, () -> codec.decode(malicious));
    }

    @Test
    void rejectsPackedWordCountBeforeReadingUnexpectedWords() {
        byte[] encoded = encodedEmptyChunk();
        // block palette size, value length/value, bits-per-entry, then an invalid word count
        byte[] malicious = java.util.Arrays.copyOf(encoded, 16 + 1 + 1 + 1 + 1 + 1 + Long.BYTES);
        int offset = 16;
        malicious[offset++] = 0;
        malicious[offset++] = 1;
        malicious[offset++] = 1;
        malicious[offset++] = 'a';
        malicious[offset++] = 0;
        malicious[offset++] = 1;

        assertThrows(IOException.class, () -> codec.decode(malicious));
    }

    @Test
    void rejectsBlockEntityOutsideChunkHeight() {
        BinaryPacketFriendlyChunkPayloadCodec.PacketFriendlyChunkPayload payload =
                new BinaryPacketFriendlyChunkPayloadCodec.PacketFriendlyChunkPayload(
                        0,
                        List.of(new BinaryPacketFriendlyChunkPayloadCodec.SectionPayload(
                                List.of("minecraft:air"), 0, new long[0],
                                List.of("minecraft:plains"), 0, new long[0])),
                        List.of(new BinaryPacketFriendlyChunkPayloadCodec.BlockEntityPayload(
                                0, 16, 0, "minecraft:chest", new byte[0])));

        assertThrows(IOException.class, () -> codec.decode(codec.encode(payload)));
    }

    @Test
    void rejectsVarIntWithInvalidTerminalByte() {
        byte[] encoded = encodedEmptyChunk();
        byte[] malicious = java.util.Arrays.copyOf(encoded, 21);
        java.util.Arrays.fill(malicious, 16, 20, (byte) 0x80);
        malicious[20] = 0x10;

        assertThrows(IOException.class, () -> codec.decode(malicious));
    }

    @Test
    void rejectsOversizedPaletteStringBeforeDecodingIt() {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        body.write(0);
        body.write(1);
        body.writeBytes(BinaryEncoding.encodeVarInt(BinaryReplayReadLimits.MAX_STRING_BYTES + 1));
        body.writeBytes(new byte[8]);

        assertThrows(IOException.class, () -> codec.decode(packetPayload(0x01, 1, body.toByteArray())));
    }

    @Test
    void rejectsOversizedBlockEntityNbtBeforeAllocation() {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        body.write(1);
        writeSingletonPalette(body, 'a');
        writeSingletonPalette(body, 'b');
        body.write(0);
        body.write(0);
        body.write(1);
        body.write('x');
        body.writeBytes(BinaryEncoding.encodeVarInt(BinaryReplayReadLimits.MAX_ITEM_NBT_BYTES + 1));

        assertThrows(IOException.class, () -> codec.decode(packetPayload(0x03, 1, body.toByteArray())));
    }

    private byte[] encodedEmptyChunk() {
        return codec.encode(new BinaryPacketFriendlyChunkPayloadCodec.PacketFriendlyChunkPayload(
                0,
                List.of(new BinaryPacketFriendlyChunkPayloadCodec.SectionPayload(
                        List.of("minecraft:air"), 0, new long[0],
                        List.of("minecraft:plains"), 0, new long[0])),
                List.of()));
    }

    private static byte[] packetPayload(int flags, int sectionCount, byte[] body) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(new byte[] {'B', 'R', 'C', 'P', 1, (byte) flags, 0, 0});
        out.writeBytes(ByteBuffer.allocate(8)
                .order(BinaryReplayFormat.PRIMITIVE_BYTE_ORDER)
                .putInt(0)
                .putInt(sectionCount)
                .array());
        out.writeBytes(body);
        return out.toByteArray();
    }

    private static void writeSingletonPalette(ByteArrayOutputStream out, int value) {
        out.write(1);
        out.write(1);
        out.write(value);
        out.write(0);
        out.write(0);
    }
}

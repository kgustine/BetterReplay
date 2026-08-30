package me.justindevb.replay.storage.binary;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class BinaryChunkPayloadCodecTest {

    private final BinaryChunkPayloadCodec codec = new BinaryChunkPayloadCodec();

    @Test
    void roundTripsChunkPayload() throws Exception {
        short[] indexes = new short[16 * 16];
        indexes[indexes.length - 1] = 1;

        byte[] encoded = codec.encode(0, 1, List.of("minecraft:air", "minecraft:stone"), indexes);
        BinaryChunkPayloadCodec.DecodedChunkPayload decoded = codec.decode(encoded);

        assertEquals(1, decoded.height());
        assertEquals(List.of("minecraft:air", "minecraft:stone"), decoded.palette());
        assertArrayEquals(indexes, decoded.stateIndexes());
    }

    @Test
    void rejectsHeightWhoseBlockCountOverflows() {
        assertThrows(IOException.class, () -> codec.decode(payload(Integer.MAX_VALUE, new byte[] {1, 0})));
    }

    @Test
    void rejectsPaletteCountLargerThanShortIndexSpace() {
        assertThrows(IOException.class, () -> codec.decode(payload(1, BinaryEncoding.encodeVarInt(65_537))));
    }

    @Test
    void rejectsVarIntWithInvalidTerminalByte() {
        assertThrows(IOException.class, () -> codec.decode(payload(1,
                new byte[] {(byte) 0x80, (byte) 0x80, (byte) 0x80, (byte) 0x80, 0x10})));
    }

    @Test
    void rejectsOversizedPaletteStringBeforeDecodingIt() {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        body.write(1);
        body.writeBytes(BinaryEncoding.encodeVarInt(BinaryReplayReadLimits.MAX_STRING_BYTES + 1));
        body.writeBytes(new byte[16 * 16 * Short.BYTES]);

        assertThrows(IOException.class, () -> codec.decode(payload(1, body.toByteArray())));
    }

    private static byte[] payload(int height, byte[] body) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(new byte[] {'B', 'R', 'C', 'S', 1, 0, 0, 0});
        out.writeBytes(ByteBuffer.allocate(8)
                .order(BinaryReplayFormat.PRIMITIVE_BYTE_ORDER)
                .putInt(0)
                .putInt(height)
                .array());
        out.writeBytes(body);
        return out.toByteArray();
    }
}

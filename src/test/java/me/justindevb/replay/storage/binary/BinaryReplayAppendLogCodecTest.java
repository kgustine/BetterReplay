package me.justindevb.replay.storage.binary;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BinaryReplayAppendLogCodecTest {

    @Test
    void cursorRejectsVarIntWithInvalidTerminalByte() {
        BinaryReplayAppendLogCodec.Cursor cursor = new BinaryReplayAppendLogCodec.Cursor(
                new byte[] {(byte) 0x80, (byte) 0x80, (byte) 0x80, (byte) 0x80, 0x10});

        assertThrows(IllegalArgumentException.class, cursor::readVarInt);
    }

    @Test
    void cursorRejectsItemListCountThatExceedsRemainingBytes() {
        BinaryReplayAppendLogCodec.Cursor cursor = new BinaryReplayAppendLogCodec.Cursor(
                BinaryEncoding.encodeVarInt(10_000));

        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class, cursor::readSerializedItemList);
        assertTrue(exception.getMessage().contains("count exceeds"));
    }

    @Test
    void cursorRejectsNegativeReadLengthWithoutAllocation() {
        BinaryReplayAppendLogCodec.Cursor cursor = new BinaryReplayAppendLogCodec.Cursor(new byte[0]);

        assertThrows(IllegalArgumentException.class, () -> cursor.readBytes(-1));
    }

    @Test
    void cursorRejectsOversizedSerializedItemBeforeAllocation() {
        byte[] length = BinaryEncoding.encodeVarInt(BinaryReplayReadLimits.MAX_ITEM_NBT_BYTES + 1);
        byte[] payload = new byte[length.length + 1];
        payload[0] = 1;
        System.arraycopy(length, 0, payload, 1, length.length);
        BinaryReplayAppendLogCodec.Cursor cursor = new BinaryReplayAppendLogCodec.Cursor(payload);

        assertThrows(IllegalArgumentException.class, cursor::readSerializedItem);
    }

    @Test
    void cursorRejectsOversizedStringBeforeAllocation() {
        byte[] length = BinaryEncoding.encodeVarInt(BinaryReplayReadLimits.MAX_STRING_BYTES + 1);
        BinaryReplayAppendLogCodec.Cursor cursor = new BinaryReplayAppendLogCodec.Cursor(length);

        assertThrows(IllegalArgumentException.class, cursor::readLengthPrefixedString);
    }
}
